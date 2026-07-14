package deadhead;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The saved-flights database (data/flights.json). Every calendar event is
 * parsed exactly once and cached by a hash of its content — re-importing the
 * same calendar costs nothing, and new events are the only ones parsed.
 *
 * Parsing strategy per event: try the "X04 to TMB" / "VDF->07FA" pattern
 * first (free), fall back to Claude only for titles the pattern can't read.
 */
@Service
public class FlightStore {

    /** One calendar event, parsed. departure == null means "not a flight". */
    record Entry(String hash, String start, String text, String from, String to, String departure) {}

    public record ImportStats(int events, int newEvents, int newFlights, int aiParsed) {}

    private static final Path FILE = Path.of("data/flights.json");
    private static final int AI_BATCH = 80;

    private final AirportDb db;
    private final ObjectMapper json;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FlightStore(AirportDb db, ObjectMapper json) throws IOException {
        this.db = db;
        this.json = json;
        if (Files.exists(FILE)) {
            for (Entry e : json.readValue(Files.readAllBytes(FILE), Entry[].class)) {
                entries.put(e.hash(), e);
            }
        }
    }

    // ── import ───────────────────────────────────────────────────

    public synchronized ImportStats importIcs(String ics, Airport home) throws IOException {
        List<IcsParser.Event> events = IcsParser.parse(ics);
        if (events.isEmpty()) throw new IllegalArgumentException("No events found in that file.");

        List<IcsParser.Event> fresh = events.stream()
            .filter(e -> !entries.containsKey(hash(e)))
            .toList();

        int flights = 0, aiParsed = 0;
        List<IcsParser.Event> forClaude = new ArrayList<>();

        for (IcsParser.Event e : fresh) {
            String[] codes = codePattern(e.text());
            if (codes != null) {
                put(e, codes[0], codes[1]);
                flights++;
            } else {
                forClaude.add(e);
            }
        }

        if (!forClaude.isEmpty() && System.getenv("ANTHROPIC_API_KEY") != null) {
            for (int i = 0; i < forClaude.size(); i += AI_BATCH) {
                List<IcsParser.Event> batch = forClaude.subList(i, Math.min(i + AI_BATCH, forClaude.size()));
                int[] counts = parseWithClaude(batch, home);
                flights += counts[0];
                aiParsed += counts[1];
            }
        } else {
            forClaude.forEach(e -> putNotFlight(e));   // no key: assume not flights
        }

        save();
        return new ImportStats(events.size(), fresh.size(), flights, aiParsed);
    }

    /** Returns {flightsAdded, eventsSentToClaude}. Events Claude skips are cached as not-flights. */
    private int[] parseWithClaude(List<IcsParser.Event> batch, Airport home) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            lines.add(i + " | " + batch.get(i).start() + " | " + batch.get(i).text());
        }
        CalendarParser.ParsedFlights parsed = CalendarParser.parse(lines, home);

        boolean[] isFlight = new boolean[batch.size()];
        int flights = 0;
        for (CalendarParser.ParsedFlight f : parsed.flights()) {
            if (f.event() < 0 || f.event() >= batch.size()) continue;
            try {
                put(batch.get(f.event()), db.resolve(f.from()).ident(), db.resolve(f.to()).ident());
                isFlight[f.event()] = true;
                flights++;
            } catch (IllegalArgumentException ignored) { }   // model invented a code: skip
        }
        for (int i = 0; i < batch.size(); i++) {
            if (!isFlight[i]) putNotFlight(batch.get(i));
        }
        return new int[]{flights, batch.size()};
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

    private void put(IcsParser.Event e, String from, String to) {
        entries.put(hash(e), new Entry(hash(e), e.start().toString(), e.text(), from, to, e.start().toString()));
    }

    private void putNotFlight(IcsParser.Event e) {
        entries.put(hash(e), new Entry(hash(e), e.start().toString(), e.text(), null, null, null));
    }

    private void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        Files.write(FILE, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries.values()));
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

    public synchronized List<FlightView> flightsBetween(LocalDate from, LocalDate to) {
        List<Entry> inRange = flightEntries().stream()
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
    public synchronized List<Trip> tripsForIds(List<String> ids) {
        List<Trip> trips = new ArrayList<>();
        for (String id : ids) {
            Entry e = entries.get(id);
            if (e == null || e.departure() == null) continue;
            trips.add(new Trip(id, new Airport(e.from()), new Airport(e.to()),
                LocalDateTime.parse(e.departure())));
        }
        trips.sort(Comparator.comparing(Trip::departure));
        return trips;
    }

    /** Human label for error messages ("TMB->EYW | Client"). */
    public synchronized String label(String id) {
        Entry e = entries.get(id);
        return e == null ? id : e.text();
    }

    /** Where the schedule says the plane is: the latest arrival before `now`. */
    public synchronized java.util.Optional<String> lastArrivalBefore(LocalDateTime now) {
        return flightEntries().stream()
            .filter(e -> LocalDateTime.parse(e.departure()).isBefore(now))
            .max(Comparator.comparing(Entry::departure))
            .map(Entry::to);
    }

    public synchronized java.util.Optional<LocalDateTime> lastDepartureBefore(LocalDateTime now) {
        return flightEntries().stream()
            .map(e -> LocalDateTime.parse(e.departure()))
            .filter(d -> d.isBefore(now))
            .max(Comparator.naturalOrder());
    }

    /** Mondays of every week that has at least one flight, sorted. */
    public synchronized List<LocalDate> weeksWithFlights() {
        return flightEntries().stream()
            .map(e -> LocalDateTime.parse(e.departure()).toLocalDate().with(DayOfWeek.MONDAY))
            .distinct().sorted().toList();
    }

    public synchronized int flightCount() {
        return flightEntries().size();
    }

    private List<Entry> flightEntries() {
        return entries.values().stream().filter(e -> e.departure() != null).toList();
    }
}
