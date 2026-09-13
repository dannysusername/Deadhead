package deadhead.user;

import deadhead.store.Db;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Each account's own copy of the knobs — fuel, hotel, time value, home base,
 * tail number, calendar link.
 *
 * data/costs.defaults.properties supplies the starting values and nothing
 * else: it is checked into the repo, so it holds no home base, no tail number
 * and no calendar link. Anything a person types lands in user_settings, keyed
 * by their id, and is read back only for them.
 */
@Service
public class UserSettings {

    /** Everything the Settings panel may write. Anything else is ignored. */
    public static final List<String> KEYS = List.of(
        "home", "tail", "hex", "calendarUrl",
        "cruiseKts", "flightOverheadHours", "fuelPerHour", "enginePerHour",
        "timeValuePerHour", "roadFactor", "avgRoadMph",
        "uberBase", "uberPerMile", "hotelPerNight");

    private static final Set<String> TEXT_KEYS = Set.of("home", "tail", "hex", "calendarUrl");

    /** A tail number is short; a calendar URL is not. Keeps a stray paste out of the database. */
    private static final int MAX_VALUE = 2000;

    /**
     * The pilot's own Anthropic key, stored encrypted. Deliberately NOT in
     * KEYS: it must never come back out through all(), which is what the
     * Settings panel renders. Only {@link #apiKey} reads it, and only the
     * server calls that.
     */
    private static final String API_KEY = "anthropicApiKeyEnc";

    private static final Path DEFAULTS = Path.of("data/costs.defaults.properties");

    private final Db db;
    private final Secrets secrets;
    private final Properties defaults = new Properties();

    public UserSettings(Db db, Secrets secrets) {
        this.db = db;
        this.secrets = secrets;
        if (Files.exists(DEFAULTS)) {
            try (Reader r = Files.newBufferedReader(DEFAULTS)) {
                defaults.load(r);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The defaults overlaid with this account's saved values — what the planner reads. */
    public Properties forUser(long userId) {
        Properties p = new Properties();
        for (String k : KEYS) {
            String v = defaults.getProperty(k, "");
            if (!v.isBlank()) p.setProperty(k, v.trim());
        }
        // Non-editable defaults (nothing today, but the file may grow) come along too.
        for (String k : defaults.stringPropertyNames()) {
            if (!KEYS.contains(k) && !defaults.getProperty(k, "").isBlank()) {
                p.setProperty(k, defaults.getProperty(k).trim());
            }
        }
        saved(userId).forEach((k, v) -> {
            if (API_KEY.equals(k)) return;          // never leaves this class
            if (v.isBlank()) p.remove(k);
            else p.setProperty(k, v);
        });
        return p;
    }

    /** What the Settings panel shows: every key, blank where nothing is set. */
    public Map<String, String> all(long userId) {
        Properties merged = forUser(userId);
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : KEYS) out.put(k, merged.getProperty(k, ""));
        return out;
    }

    // ── the pilot's own API key ──────────────────────────────────

    /**
     * Store, replace, or (with a blank value) forget this account's key.
     * The plaintext is never written anywhere; only the ciphertext is.
     */
    public void setApiKey(long userId, String plaintext) {
        String key = plaintext == null ? "" : plaintext.trim();
        write(userId, Map.of(API_KEY, key.isEmpty() ? "" : secrets.encrypt(key)));
    }

    /** The decrypted key for server-side use, or empty if there isn't a usable one. */
    public Optional<String> apiKey(long userId) {
        String stored = saved(userId).get(API_KEY);
        if (stored == null || stored.isBlank()) return Optional.empty();
        return Optional.ofNullable(secrets.decrypt(stored)).filter(k -> !k.isBlank());
    }

    /** What the Settings panel may show: the last four characters, or "". */
    public String apiKeyHint(long userId) {
        return apiKey(userId)
            .map(k -> k.length() <= 4 ? "\u2026" : "\u2026" + k.substring(k.length() - 4))
            .orElse("");
    }

    public Map<String, String> update(long userId, Map<String, String> changes) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String key = e.getKey();
            if (!KEYS.contains(key)) continue;                     // unknown key: ignore, don't fail
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.length() > MAX_VALUE) {
                throw new IllegalArgumentException(key + " is too long.");
            }
            if (!TEXT_KEYS.contains(key) && !value.isEmpty()) {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException(key + " must be a number.");
                }
            }
            if (key.equals("calendarUrl") && !value.isEmpty()) {
                String lower = value.toLowerCase();
                if (!lower.startsWith("http://") && !lower.startsWith("https://")
                        && !lower.startsWith("webcal://")) {
                    throw new IllegalArgumentException(
                        "The calendar link must start with webcal:// or https://.");
                }
            }
            clean.put(key, value);
        }
        write(userId, clean);
        return all(userId);
    }

    // ── persistence ──────────────────────────────────────────────

    private Map<String, String> saved(long userId) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement(
                 "SELECT name, value FROM user_settings WHERE user_id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("settings read failed: " + e.getMessage(), e);
        }
        return out;
    }

    private void write(long userId, Map<String, String> values) {
        if (values.isEmpty()) return;
        try (Connection c = db.connect()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement up = c.prepareStatement(
                     "INSERT INTO user_settings (user_id, name, value) VALUES (?, ?, ?) "
                   + "ON CONFLICT (user_id, name) DO UPDATE SET value = EXCLUDED.value")) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    up.setLong(1, userId);
                    up.setString(2, e.getKey());
                    up.setString(3, e.getValue());
                    up.addBatch();
                }
                up.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("settings write failed: " + e.getMessage(), e);
        }
    }
}
