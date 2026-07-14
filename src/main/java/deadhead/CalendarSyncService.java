package deadhead;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * Pulls dad's published iCloud calendar (webcal link) and re-imports it.
 * The FlightStore's hash cache makes this cheap: already-seen events cost
 * nothing; only genuinely new bookings get parsed.
 */
@Service
public class CalendarSyncService {

    public record SyncResult(String when, int events, int newFlights, String error) {}

    private final Properties cfg;
    private final FlightStore store;
    private final AirportDb db;
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15)).build();

    private volatile SyncResult last;

    public CalendarSyncService(Properties costs, FlightStore store, AirportDb db) {
        this.cfg = costs;
        this.store = store;
        this.db = db;
    }

    public SyncResult lastSync() {
        return last;
    }

    public SyncResult syncNow() {
        String url = cfg.getProperty("calendarUrl", "").trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("No calendar link set — add it in Settings.");
        }
        try {
            String https = url.replaceFirst("^webcal://", "https://");
            HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(https)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || !res.body().contains("BEGIN:VCALENDAR")) {
                throw new IllegalArgumentException(
                    "That link didn't return a calendar (HTTP " + res.statusCode() + ").");
            }
            Airport home = new Airport(db.resolve(cfg.getProperty("home", "KTMB")).ident());
            FlightStore.ImportStats stats = store.importIcs(res.body(), home);
            last = new SyncResult(LocalDateTime.now().toString(), stats.events(), stats.newFlights(), null);
            return last;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            last = new SyncResult(LocalDateTime.now().toString(), 0, 0, e.getMessage());
            throw new IllegalArgumentException("Sync failed: " + e.getMessage());
        }
    }

    /** Hourly, once a link is configured. Failures are recorded, not fatal. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    void autoSync() {
        if (cfg.getProperty("calendarUrl", "").isBlank()) return;
        try {
            syncNow();
        } catch (Exception logged) {
            System.out.println("calendar auto-sync failed: " + logged.getMessage());
        }
    }
}
