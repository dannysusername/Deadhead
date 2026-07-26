package deadhead;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Where parsed calendar entries live. On Postgres each entry is its own row in
 * `flights`, so the schedule can be queried with ordinary SQL; with no
 * DATABASE_URL it stays the committed data/flights.json file, as before.
 *
 * A null departure means "already looked at, not a flight". That cache is what
 * stops a re-import from paying Claude twice for the same events, so those rows
 * are load-bearing and are stored just like real flights.
 */
@Service
public class FlightRepo {

    private static final Path FILE = Path.of("data/flights.json");

    private static final String COLUMNS = "seq, hash, start_ts, text, origin, dest, departure";

    private final BlobStore blobs;
    private final ObjectMapper json;

    FlightRepo(BlobStore blobs, ObjectMapper json) throws IOException {
        this.blobs = blobs;
        this.json = json;
        if (!blobs.usingDb()) return;

        try (Connection c = blobs.connect(); Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS flights (
                  seq       INTEGER NOT NULL,
                  hash      TEXT PRIMARY KEY,
                  start_ts  TIMESTAMP NOT NULL,
                  text      TEXT NOT NULL,
                  origin    TEXT,
                  dest      TEXT,
                  departure TIMESTAMP)""");
            st.execute("CREATE INDEX IF NOT EXISTS flights_departure_idx ON flights (departure)");
            seedFromFile(c);
        } catch (SQLException e) {
            throw new IllegalStateException("flights table setup failed: " + e.getMessage(), e);
        }
    }

    /** Fresh database + committed data file -> migrate it in once, like BlobStore does for costs. */
    private void seedFromFile(Connection c) throws SQLException, IOException {
        if (!Files.exists(FILE)) return;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM flights")) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }
        List<FlightStore.Entry> seed = readFile();
        if (seed.isEmpty()) return;
        write(c, seed);
        System.out.println("seeded " + seed.size() + " flights into Postgres from " + FILE);
    }

    // ── reading ──────────────────────────────────────────────────

    synchronized List<FlightStore.Entry> load() throws IOException {
        if (!blobs.usingDb()) return readFile();
        try (Connection c = blobs.connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + COLUMNS + " FROM flights ORDER BY seq")) {
            List<FlightStore.Entry> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new FlightStore.Entry(
                    rs.getString("hash"),
                    text(rs, "start_ts"),
                    rs.getString("text"),
                    rs.getString("origin"),
                    rs.getString("dest"),
                    text(rs, "departure")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("flights read failed: " + e.getMessage(), e);
        }
    }

    /** Timestamps travel as the ISO strings the rest of the app already parses. */
    private static String text(ResultSet rs, String column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? null : t.toString();
    }

    private List<FlightStore.Entry> readFile() throws IOException {
        if (!Files.exists(FILE)) return List.of();
        String stored = Files.readString(FILE);
        if (stored.isBlank()) return List.of();
        return List.of(json.readValue(stored, FlightStore.Entry[].class));
    }

    // ── writing ──────────────────────────────────────────────────

    synchronized void save(Collection<FlightStore.Entry> entries) throws IOException {
        if (!blobs.usingDb()) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, json.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
            return;
        }
        try (Connection c = blobs.connect()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                write(c, entries);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("flights write failed: " + e.getMessage(), e);
        }
    }

    /**
     * The in-memory map is the whole truth, so the table is replaced wholesale —
     * same semantics as rewriting the document, minus the blob.
     */
    private static void write(Connection c, Collection<FlightStore.Entry> entries) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DELETE FROM flights");
        }
        try (PreparedStatement st = c.prepareStatement(
            "INSERT INTO flights (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int seq = 0;
            for (FlightStore.Entry e : entries) {
                st.setInt(1, ++seq);
                st.setString(2, e.hash());
                st.setObject(3, LocalDateTime.parse(e.start()));
                st.setString(4, e.text());
                st.setString(5, e.from());
                st.setString(6, e.to());
                if (e.departure() == null) st.setNull(7, Types.TIMESTAMP);
                else st.setObject(7, LocalDateTime.parse(e.departure()));
                st.addBatch();
            }
            st.executeBatch();
        }
    }
}
