package deadhead;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The page-editable knobs. Values live in the shared Properties bean (so the
 * next plan uses them immediately) and are persisted back into
 * data/costs.properties without destroying its comments.
 */
@Service
public class SettingsService {

    private static final Path FILE = Path.of("data/costs.properties");

    private static final List<String> KEYS = List.of(
        "home", "tail", "hex", "calendarUrl",
        "cruiseKts", "flightOverheadHours", "fuelPerHour", "enginePerHour",
        "timeValuePerHour", "roadFactor", "avgRoadMph",
        "uberBase", "uberPerMile", "hotelPerNight");

    private static final Set<String> TEXT_KEYS = Set.of("home", "tail", "hex", "calendarUrl");

    private final Properties cfg;

    public SettingsService(Properties costs) {
        this.cfg = costs;
    }

    public synchronized Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : KEYS) out.put(k, cfg.getProperty(k, ""));
        return out;
    }

    public synchronized Map<String, String> update(Map<String, String> changes) throws IOException {
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (!KEYS.contains(key)) continue;
            if (!TEXT_KEYS.contains(key) && !value.isEmpty()) {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException(key + " must be a number.");
                }
            }
            cfg.setProperty(key, value);
        }
        persist();
        return all();
    }

    /** Rewrite only the `key=value` lines, keeping every comment in the file. */
    private void persist() throws IOException {
        List<String> lines = Files.exists(FILE) ? Files.readAllLines(FILE) : new ArrayList<>();
        List<String> out = new ArrayList<>();
        var written = new java.util.HashSet<String>();

        for (String line : lines) {
            String trimmed = line.strip();
            String matched = null;
            if (!trimmed.startsWith("#") && trimmed.contains("=")) {
                String k = trimmed.substring(0, trimmed.indexOf('=')).strip();
                if (KEYS.contains(k)) matched = k;
            }
            if (matched != null) {
                out.add(matched + "=" + cfg.getProperty(matched, ""));
                written.add(matched);
            } else {
                out.add(line);
            }
        }
        for (String k : KEYS) {
            if (!written.contains(k) && !cfg.getProperty(k, "").isEmpty()) {
                out.add(k + "=" + cfg.getProperty(k));
            }
        }
        Files.write(FILE, out);
    }
}
