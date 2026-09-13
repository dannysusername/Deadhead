package deadhead.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The one place that knows how to open a database connection, and the only
 * place that cares which engine is on the other end.
 *
 * DATABASE_URL set  -> Postgres (Heroku, Neon, anything that speaks it).
 * DATABASE_URL unset -> SQLite at data/deadhead.db.
 *
 * Both are real multi-user databases, so "works on my laptop" and "works on
 * the dyno" are the same code path — the only differences are the two dialect
 * strings below, used once each when the schema is created.
 */
@Service
public class Db {

    private final String jdbcUrl;
    private final String user;
    private final String pass;
    private final boolean postgres;

    public Db(@Value("${deadhead.db.url:}") String override) {
        if (override != null && !override.isBlank()) {
            // Set only by the test profile, which points every run at a
            // throwaway file instead of the developer's own database.
            jdbcUrl = override;
            postgres = override.startsWith("jdbc:postgresql");
            user = pass = null;
            return;
        }
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            postgres = false;
            user = pass = null;
            try {
                Files.createDirectories(Path.of("data"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not create data/ for the local database", e);
            }
            jdbcUrl = "jdbc:sqlite:data/deadhead.db";
        } else {
            postgres = true;
            // Heroku gives postgres://user:pass@host:port/db; JDBC wants its own scheme.
            // Managed providers (Neon) leave the port off, and getPort() answers -1 for that.
            URI u = URI.create(databaseUrl);
            String[] auth = u.getUserInfo().split(":", 2);
            user = auth[0];
            pass = auth.length > 1 ? auth[1] : "";
            int port = u.getPort() == -1 ? 5432 : u.getPort();
            jdbcUrl = "jdbc:postgresql://" + u.getHost() + ":" + port + u.getPath() + "?sslmode=require";
        }
    }

    public boolean isPostgres() {
        return postgres;
    }

    /** Cold dynos and fresh databases occasionally fail the first attempt — retry briefly. */
    public Connection connect() throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return user == null
                    ? DriverManager.getConnection(jdbcUrl)
                    : DriverManager.getConnection(jdbcUrl, user, pass);
            } catch (SQLException e) {
                last = e;
                if (!postgres) throw e;   // a local file doesn't get better on retry
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    // ── dialect ──────────────────────────────────────────────────

    /** An auto-incrementing primary key, spelled the way this engine spells it. */
    String autoIdColumn() {
        return postgres ? "id BIGSERIAL PRIMARY KEY" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    boolean tableExists(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, table, null)) {
            if (rs.next()) return true;
        }
        // Postgres folds unquoted identifiers to lower case; the metadata call is case-sensitive.
        try (ResultSet rs = c.getMetaData().getTables(null, null, table.toLowerCase(), null)) {
            return rs.next();
        }
    }

    boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            }
        }
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table.toLowerCase(), null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
