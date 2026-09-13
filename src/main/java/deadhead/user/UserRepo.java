package deadhead.user;

import deadhead.store.Db;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

/** The users table. Email is the login and is stored already-normalized. */
@Service
public class UserRepo {

    private final Db db;

    public UserRepo(Db db) {
        this.db = db;
    }

    /** Lower-cased and trimmed, so "Dan@Example.com " and "dan@example.com" are one account. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    public Optional<UserAccount> findByEmail(String email) {
        String key = normalize(email);
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement(
                 "SELECT id, email, password_hash, created_at FROM users WHERE email = ?")) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("user lookup failed: " + e.getMessage(), e);
        }
    }

    public Optional<UserAccount> findById(long id) {
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement(
                 "SELECT id, email, password_hash, created_at FROM users WHERE id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("user lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Insert, relying on the UNIQUE index rather than a check-then-insert —
     * two people signing up with the same address at the same moment must not
     * both succeed.
     */
    public UserAccount create(String email, String passwordHash) {
        String key = normalize(email);
        String now = Instant.now().toString();
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, ?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, key);
            st.setString(2, passwordHash);
            st.setString(3, now);
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                if (keys.next()) return new UserAccount(keys.getLong(1), key, passwordHash, now);
            }
            // Some drivers don't hand back the key; the unique email finds it either way.
            return findByEmail(key).orElseThrow(
                () -> new IllegalStateException("account created but could not be read back"));
        } catch (SQLException e) {
            if (isDuplicate(e)) throw new DuplicateEmailException(key);
            throw new IllegalStateException("could not create the account: " + e.getMessage(), e);
        }
    }

    public int count() {
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement("SELECT count(*) FROM users");
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("user count failed: " + e.getMessage(), e);
        }
    }

    /** Every account with a calendar link, for the hourly background sync. */
    public java.util.List<Long> allIds() {
        try (Connection c = db.connect();
             PreparedStatement st = c.prepareStatement("SELECT id FROM users ORDER BY id");
             ResultSet rs = st.executeQuery()) {
            var out = new java.util.ArrayList<Long>();
            while (rs.next()) out.add(rs.getLong(1));
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("user list failed: " + e.getMessage(), e);
        }
    }

    private static UserAccount read(ResultSet rs) throws SQLException {
        return new UserAccount(
            rs.getLong("id"), rs.getString("email"),
            rs.getString("password_hash"), rs.getString("created_at"));
    }

    /** Postgres 23505, SQLite 19 — both mean "that email is taken". */
    private static boolean isDuplicate(SQLException e) {
        return "23505".equals(e.getSQLState())
            || e.getErrorCode() == 19
            || String.valueOf(e.getMessage()).toUpperCase().contains("UNIQUE");
    }

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email) {
            super("There's already an account for " + email + ".");
        }
    }
}
