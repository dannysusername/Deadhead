package deadhead;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Properties;

/**
 * "Find my plane" — two sources, most reliable first:
 *   1. live ADS-B (adsb.lol, airplanes.live) — works while the plane is flying
 *   2. the imported schedule — where the last flight before now arrived
 * (A parked plane doesn't broadcast, so "last landing" would need an OpenSky
 * account; the schedule answers the same question for free.)
 */
@Service
public class PlaneLocator {

    public record Location(String airport, String name, String source) {}

    private final Properties cfg;
    private final AirportDb db;
    private final FlightStore store;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).build();

    public PlaneLocator(Properties costs, AirportDb db, FlightStore store) {
        this.cfg = costs;
        this.db = db;
        this.store = store;
    }

    public Location locate() {
        String tail = cfg.getProperty("tail", "").trim();
        String hex = cfg.getProperty("hex", "").trim().toLowerCase();

        if (!tail.isEmpty()) {
            Optional<Location> live = liveAdsb(tail, hex);
            if (live.isPresent()) return live.get();
        }
        Optional<Location> scheduled = fromSchedule();
        if (scheduled.isPresent()) return scheduled.get();

        throw new IllegalArgumentException(
            "Couldn't locate " + (tail.isEmpty() ? "the plane" : tail) + " — enter the airport manually.");
    }

    /** Live position from free community ADS-B feeds (no API keys). */
    private Optional<Location> liveAdsb(String tail, String hex) {
        String[] urls = {
            "https://api.adsb.lol/v2/reg/" + tail,
            hex.isEmpty() ? null : "https://api.airplanes.live/v2/hex/" + hex,
        };
        for (String url : urls) {
            if (url == null) continue;
            try {
                JsonNode ac = get(url).path("ac");
                if (ac.isArray() && !ac.isEmpty()) {
                    double lat = ac.get(0).path("lat").asDouble(Double.NaN);
                    double lon = ac.get(0).path("lon").asDouble(Double.NaN);
                    if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                        AirportDb.Info a = db.nearest(lat, lon);
                        return Optional.of(new Location(a.ident(), a.name(), "flying now, near " + a.ident()));
                    }
                }
            } catch (Exception tryNextSource) { }
        }
        return Optional.empty();
    }

    /**
     * The calendar only logs CLIENT flights, never deadheads home — so a
     * scheduled arrival is only trustworthy while it's fresh. After that,
     * the plane has almost certainly been repositioned: assume home base.
     */
    private Optional<Location> fromSchedule() {
        Optional<String> last = store.lastArrivalBefore(LocalDateTime.now());
        Optional<LocalDateTime> when = store.lastDepartureBefore(LocalDateTime.now());

        if (last.isPresent() && when.isPresent()
                && when.get().isAfter(LocalDateTime.now().minusHours(36))) {
            AirportDb.Info a = db.resolve(last.get());
            return Optional.of(new Location(a.ident(), a.name(), "landed there recently, per the schedule"));
        }
        String home = cfg.getProperty("home", "").trim();
        if (home.isEmpty()) return Optional.empty();
        AirportDb.Info a = db.resolve(home);
        return Optional.of(new Location(a.ident(), a.name(),
            "assumed home — not flying now, and no flights logged in the last 36h"));
    }

    private JsonNode get(String url) throws Exception {
        HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        return json.readTree(res.body());
    }
}
