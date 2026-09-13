package deadhead;

import deadhead.user.UserRepo;
import deadhead.user.UserSettings;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls each pilot's published calendar (webcal link) and re-imports it.
 * The hash cache in {@link FlightStore} makes this cheap: already-seen events
 * cost nothing; only genuinely new bookings get parsed.
 *
 * The hourly job walks every account that has a link configured, and one
 * account's broken link never stops the next account's sync.
 */
@Service
public class CalendarSyncService {

    public record SyncResult(String when, int events, int newFlights, String error) {}

    /** A published calendar is fetched by URL — cap it so one huge file can't sink the dyno. */
    private static final int MAX_CALENDAR_BYTES = 10 * 1024 * 1024;

    private final FlightStore store;
    private final AirportDb db;
    private final UserSettings settings;
    private final UserRepo users;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15)).build();

    private final Map<Long, SyncResult> last = new ConcurrentHashMap<>();

    public CalendarSyncService(FlightStore store, AirportDb db,
                               UserSettings settings, UserRepo users) {
        this.store = store;
        this.db = db;
        this.settings = settings;
        this.users = users;
    }

    public SyncResult lastSync(long userId) {
        return last.get(userId);
    }

    public SyncResult syncNow(long userId) {
        Properties cfg = settings.forUser(userId);
        String url = cfg.getProperty("calendarUrl", "").trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("No calendar link set — add it in Settings.");
        }
        String homeCode = cfg.getProperty("home", "").trim();
        if (homeCode.isEmpty()) {
            throw new IllegalArgumentException("Set your home base in Settings first.");
        }
        try {
            String https = url.replaceFirst("^(?i)webcal://", "https://");
            HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(https)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || !res.body().contains("BEGIN:VCALENDAR")) {
                throw new IllegalArgumentException(
                    "That link didn't return a calendar (HTTP " + res.statusCode() + ").");
            }
            if (res.body().length() > MAX_CALENDAR_BYTES) {
                throw new IllegalArgumentException("That calendar is too large to import.");
            }
            Airport home = new Airport(db.resolve(homeCode).ident());
            FlightStore.ImportStats stats = store.importIcs(
                userId, res.body(), home, settings.apiKey(userId).orElse(null));
            SyncResult ok = new SyncResult(
                LocalDateTime.now().toString(), stats.events(), stats.newFlights(), null);
            last.put(userId, ok);
            return ok;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            last.put(userId, new SyncResult(LocalDateTime.now().toString(), 0, 0, e.getMessage()));
            throw new IllegalArgumentException("Sync failed: " + e.getMessage());
        }
    }

    /** Hourly, for every account with a link. Failures are recorded, not fatal. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    void autoSync() {
        for (long userId : users.allIds()) {
            try {
                if (settings.forUser(userId).getProperty("calendarUrl", "").isBlank()) continue;
                syncNow(userId);
            } catch (Exception logged) {
                System.out.println("calendar auto-sync failed for user " + userId
                                 + ": " + logged.getMessage());
            }
        }
    }
}
