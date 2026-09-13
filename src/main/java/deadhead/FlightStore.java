package deadhead;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One account's saved flights, read straight from {@link FlightRepo}.
 *
 * Every calendar event is parsed exactly once and cached by a hash of its
 * content — re-importing the same calendar costs nothing, and new events are
 * the only ones parsed.
 *
 * Parsing strategy per event: try the "X04 to TMB" / "VDF->07FA" pattern
 * first (free), fall back to Claude only for titles the pattern can't read.
 *
 * Nothing here is cached across requests on purpose: with many accounts, a
 * process-wide cache is a cross-tenant leak waiting to happen, and one
 * indexed query per request is cheaper than being careful.
 */
@Service
public class FlightStore {

    /** One calendar event, parsed. departure == null means "not a flight". */
    public record Entry(String hash, String start, String text, String from, String to, String departure) {}

    public record ImportStats(int events, int newEvents, int newFlights, int aiParsed) {}

    private static final int AI_BATCH = 80;

    private final AirportDb db;
    private final FlightRepo repo;
    private final CalendarParser claude;

    /** One import at a time per account: reading the seen-hashes then writing is not atomic. */
    private final Map<Long, Lock> importLocks = new ConcurrentHashMap<>();

    public FlightStore(AirportDb db, FlightRepo repo, CalendarParser claude) {
        this.db = db;
        this.repo = repo;
        this.claude = claude;
    }

    // ── import ───────────────────────────────────────────────────

    /**
     * @param apiKey the pilot's own Anthropic key, or null. Without one, titles
     *               the airport-code pattern can't read are cached as
     *               not-flights — the import still succeeds, it just sees less.
     */
    public ImportStats importIcs(long userId, String ics, Airport home, String apiKey) {
        List<IcsParser.Event> events = IcsParser.parse(ics);
        if (events.isEmpty()) throw new IllegalArgumentException("No events found in that file.");

        Lock lock = importLocks.computeIfAbsent(userId, id -> new ReentrantLock());
        lock.lock();
        try {
            var seen = new java.util.HashSet<String>();
            for (Entry e : repo.load(userId)) seen.add(e.hash());

            List<IcsParser.Event> fresh = events.stream()
                .filter(e -> !seen.contains(hash(e)))
                .toList();

            Map<String, Entry> added = new LinkedHashMap<>();
            int flights = 0, aiParsed = 0;
            List<IcsParser.Event> forClaude = new ArrayList<>();

            for (IcsParser.Event e : fresh) {
                String[] codes = codePattern(e.text());
                if (codes != null) {
                    added.put(hash(e), flight(e, codes[0], codes[1]));
                    flights++;
                } else {
                    forClaude.add(e);
                }
            }

            if (!forClaude.isEmpty() && CalendarParser.looksLikeKey(apiKey)) {
                for (int i = 0; i < forClaude.size(); i += AI_BATCH) {
                    List<IcsParser.Event> batch =
                        forClaude.subList(i, Math.min(i + AI_BATCH, forClaude.size()));
                    flights += parseWithClaude(apiKey, batch, home, added);
                    aiParsed += batch.size();
                }
            } else {
                for (IcsParser.Event e : forClaude) added.put(hash(e), notFlight(e));
            }

            repo.add(userId, added.values());
            return new ImportStats(events.size(), fresh.size(), flights, aiParsed);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the number of flights found. Events Claude skips are cached as not-flights. */
    private int parseWithClaude(String apiKey, List<IcsParser.Event> batch,
                                Airport home, Map<String, Entry> into) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            lines.add(i + " | " + batch.get(i).start() + " | " + batch.get(i).text());
        }
        CalendarParser.ParsedFlights parsed = claude.parse(apiKey, lines, home);

        boolean[] isFlight = new boolean[batch.size()];
        int flights = 0;
        for (CalendarParser.ParsedFlight f : parsed.flights()) {
            if (f.event() < 0 || f.event() >= batch.size()) continue;
            try {
                IcsParser.Event e = batch.get(f.event());
                into.put(hash(e), flight(e, db.resolve(f.from()).ident(), db.resolve(f.to()).ident()));
                isFlight[f.event()] = true;
                flights++;
            } catch (IllegalArgumentException ignored) { }   // model invented a code: skip
        }
        for (int i = 0; i < batch.size(); i++) {
            if (!isFlight[i]) into.put(hash(batch.get(i)), notFlight(batch.get(i)));
        }
        return flights;
    }

    /** "X04 to TMB | Client" / "VDF->07FA | Client" -> {KX04-ident, KTMB-ident}, else null. */
    private static final Pattern ROUTE = Pattern.compile(
        "\\b([A-Z0-9]{3,4})\\s*(?:->|→|\\bto\\b)\\s*([A-Z0-9]{3,4})\\b", Pattern.CASE_INSENSITIVE);

    private String[] codePattern(String text) {
        Matcher m = ROUTE.matcher(text);
        while (m.find()) {
            String a = m.group(1).toUpperCase(), b = m.group(2).toUpperCase();
            if (!a.matches(".*[A-Z].*") || !b.matches(".*[A-Z].*")) continue;   // "9 to 5" isn't a route
            try {
                return new String[]{db.resolve(a).ident(), db.resolve(b).ident()};
            } catch (IllegalArgumentException notAirports) { }   // "back to TMB" etc: try next match
        }
        return null;
    }

    private static Entry flight(IcsParser.Event e, String from, String to) {
        return new Entry(hash(e), e.start().toString(), e.text(), from, to, e.start().toString());
    }

    private static Entry notFlight(IcsParser.Event e) {
        return new Entry(hash(e), e.start().toString(), e.text(), null, null, null);
    }

    private static String hash(IcsParser.Event e) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((e.start() + "|" + e.text()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ── reading ──────────────────────────────────────────────────

    /** One flight as the picker sees it. conflict = pre-unchecked in the UI. */
    public record FlightView(String id, String departure, String from, String to,
                             String text, boolean conflict) {}

    public List<FlightView> flightsBetween(long userId, LocalDate from, LocalDate to) {
        List<Entry> inRange = flightEntries(userId).stream()
            .filter(e -> {
                LocalDate d = LocalDateTime.parse(e.departure()).toLocalDate();
                return !d.isBefore(from) && !d.isAfter(to);
            })
            .sorted(Comparator.comparing(Entry::departure))
            .toList();

        // Flag duplicates and same-instant departures — one plane can't fly both.
        List<FlightView> out = new ArrayList<>();
        var seenExact = new java.util.HashSet<String>();
        var seenInstant = new java.util.HashSet<String>();
        for (Entry e : inRange) {
            boolean dup = !seenExact.add(e.from() + ">" + e.to() + "@" + e.departure());
            boolean clash = !seenInstant.add(e.departure());
            out.add(new FlightView(e.hash(), e.departure(), e.from(), e.to(), e.text(), dup || clash));
        }
        return out;
    }

    /** The picker's checked flights, as trips for the optimizer. */
    public List<Trip> tripsForIds(long userId, List<String> ids) {
        Map<String, Entry> byId = byId(userId);
        List<Trip> trips = new ArrayList<>();
        for (String id : ids) {
            Entry e = byId.get(id);
            if (e == null || e.departure() == null) continue;
            trips.add(new Trip(id, new Airport(e.from()), new Airport(e.to()),
                LocalDateTime.parse(e.departure())));
        }
        trips.sort(Comparator.comparing(Trip::departure));
        return trips;
    }

    /** Human labels for error messages ("TMB->EYW | Scott L."), fetched in one pass. */
    public Map<String, String> labels(long userId) {
        Map<String, String> out = new LinkedHashMap<>();
        byId(userId).forEach((id, e) -> out.put(id, e.text()));
        return out;
    }

    /** Where the schedule says the plane is: the latest arrival before `now`. */
    public java.util.Optional<String> lastArrivalBefore(long userId, LocalDateTime now) {
        return flightEntries(userId).stream()
            .filter(e -> LocalDateTime.parse(e.departure()).isBefore(now))
            .max(Comparator.comparing(Entry::departure))
            .map(Entry::to);
    }

    public java.util.Optional<LocalDateTime> lastDepartureBefore(long userId, LocalDateTime now) {
        return flightEntries(userId).stream()
            .map(e -> LocalDateTime.parse(e.departure()))
            .filter(d -> d.isBefore(now))
            .max(Comparator.naturalOrder());
    }

    /** Mondays of every week that has at least one flight, sorted. */
    public List<LocalDate> weeksWithFlights(long userId) {
        return flightEntries(userId).stream()
            .map(e -> LocalDateTime.parse(e.departure()).toLocalDate().with(DayOfWeek.MONDAY))
            .distinct().sorted().toList();
    }

    public int flightCount(long userId) {
        return flightEntries(userId).size();
    }

    /** Forget everything imported for this account. */
    public void clear(long userId) {
        repo.deleteAll(userId);
    }

    private Map<String, Entry> byId(long userId) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : repo.load(userId)) out.put(e.hash(), e);
        return out;
    }

    private List<Entry> flightEntries(long userId) {
        return repo.load(userId).stream().filter(e -> e.departure() != null).toList();
    }
}
