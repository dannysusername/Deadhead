package deadhead;

import deadhead.store.Db;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Where parsed calendar entries live — one row per event, per account.
 *
 * A null departure means "already looked at, not a flight". That cache is what
 * stops a re-import from paying Claude twice for the same events, so those rows
 * are load-bearing and are stored just like real flights.
 *
 * Every statement here is keyed by user_id. There is deliberately no method to
 * read or write a flight without one.
 */
@Service
public class FlightRepo {

    private static final String COLUMNS = "user_id, hash, seq, start_ts, text, origin, dest, departure";

    private final Db db;

    public FlightRepo(Db db) {
        this.db = db;
    }

    public List<FlightStore.Entry> load(long userId) {
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement(
                 "SELECT hash, start_ts, text, origin, dest, departure "
               + "FROM flights WHERE user_id = ? ORDER BY seq")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                List<FlightStore.Entry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new FlightStore.Entry(
                        rs.getString("hash"),
                        rs.getString("start_ts"),
                        rs.getString("text"),
                        rs.getString("origin"),
                        rs.getString("dest"),
                        rs.getString("departure")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("flights read failed: " + e.getMessage(), e);
        }
    }

    /**
     * Add the entries this import produced. Existing rows are left alone
     * rather than rewritten wholesale — an import only ever learns about
     * events it hasn't seen, so there is nothing in the old rows to update,
     * and not touching them keeps one account's import off everyone else's
     * rows even under a concurrent write.
     */
    public void add(long userId, Collection<FlightStore.Entry> entries) {
        if (entries.isEmpty()) return;
        try (Connection c = db.connect()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int seq = nextSeq(c, userId);
                try (PreparedStatement st = c.prepareStatement(
                    "INSERT INTO flights (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                  + "ON CONFLICT (user_id, hash) DO NOTHING")) {
                    for (FlightStore.Entry e : entries) {
                        st.setLong(1, userId);
                        st.setString(2, e.hash());
                        st.setInt(3, seq++);
                        st.setString(4, e.start());
                        st.setString(5, e.text());
                        st.setString(6, e.from());
                        st.setString(7, e.to());
                        st.setString(8, e.departure());
                        st.addBatch();
                    }
                    st.executeBatch();
                }
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

    /** Wipe one account's schedule — the "start over" button, and how tests reset. */
    public void deleteAll(long userId) {
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement("DELETE FROM flights WHERE user_id = ?")) {
            st.setLong(1, userId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("flights delete failed: " + e.getMessage(), e);
        }
    }

    private static int nextSeq(Connection c, long userId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
            "SELECT coalesce(max(seq), 0) FROM flights WHERE user_id = ?")) {
            st.setLong(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : 1;
            }
        }
    }
}
