package deadhead;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Durable storage for the app's two mutable documents: the parsed-flights
 * database and the cost settings.
 *
 * Heroku's dyno disk is wiped on every restart, so file writes don't survive
 * there. When DATABASE_URL is set (Heroku Postgres) this stores each document
 * as a row in a key-value table; locally it stays plain files. First boot on
 * a fresh database is seeded from the committed files, so deploys migrate
 * themselves.
 */
@Service
public class BlobStore {

    public static final String FLIGHTS = "flights";
    public static final String COSTS = "costs";

    private static final Map<String, Path> FILES = Map.of(
        FLIGHTS, Path.of("data/flights.json"),
        COSTS, Path.of("data/costs.properties"));

    private final String jdbcUrl;   // null = file mode
    private final String user;
    private final String pass;

    public BlobStore() {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            jdbcUrl = null;
            user = pass = null;
            return;
        }
        // Heroku gives postgres://user:pass@host:port/db; JDBC wants its own scheme
        URI u = URI.create(databaseUrl);
        String[] auth = u.getUserInfo().split(":", 2);
        user = auth[0];
        pass = auth.length > 1 ? auth[1] : "";
        jdbcUrl = "jdbc:postgresql://" + u.getHost() + ":" + u.getPort() + u.getPath() + "?sslmode=require";

        try (Connection c = connect()) {
            c.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS blobs (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            seedFromFiles(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres setup failed: " + e.getMessage(), e);
        }
    }

    /** Fresh database + committed data files -> migrate them in once. */
    private void seedFromFiles(Connection c) throws SQLException {
        for (Map.Entry<String, Path> e : FILES.entrySet()) {
            if (!dbGet(c, e.getKey()).isEmpty() || !Files.exists(e.getValue())) continue;
            try {
                dbPut(c, e.getKey(), Files.readString(e.getValue()));
                System.out.println("seeded '" + e.getKey() + "' into Postgres from " + e.getValue());
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
        }
    }

    public synchronized String get(String key) {
        if (jdbcUrl == null) {
            try {
                Path p = FILES.get(key);
                return Files.exists(p) ? Files.readString(p) : "";
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        try (Connection c = connect()) {
            return dbGet(c, key);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres read failed: " + e.getMessage(), e);
        }
    }

    public synchronized void put(String key, String value) {
        if (jdbcUrl == null) {
            try {
                Files.createDirectories(FILES.get(key).getParent());
                Files.writeString(FILES.get(key), value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }
        try (Connection c = connect()) {
            dbPut(c, key, value);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres write failed: " + e.getMessage(), e);
        }
    }

    /** Cold dynos and fresh databases occasionally fail the first attempt — retry briefly. */
    private Connection connect() throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return DriverManager.getConnection(jdbcUrl, user, pass);
            } catch (SQLException e) {
                last = e;
                try { Thread.sleep(1000L * (attempt + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last;
    }

    private static String dbGet(Connection c, String key) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT value FROM blobs WHERE key = ?")) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private static void dbPut(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
            "INSERT INTO blobs (key, value) VALUES (?, ?) " +
            "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value")) {
            st.setString(1, key);
            st.setString(2, value);
            st.executeUpdate();
        }
    }
}
