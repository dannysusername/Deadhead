package deadhead.store;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Creates the tables, and moves the single-user schema out of the way the
 * first time a multi-user build starts against an old database.
 *
 * Everything is keyed by user_id. There is no "the schedule" any more — there
 * is one schedule per account, and no query in the app is allowed to run
 * without a user_id in its WHERE clause.
 */
@Service
public class Schema {

    private final Db db;

    public Schema(Db db) {
        this.db = db;
    }

    @PostConstruct
    void create() {
        try (Connection c = db.connect()) {
            retireSingleUserTables(c);

            Db.exec(c, """
                CREATE TABLE IF NOT EXISTS users (
                  %s,
                  email         TEXT NOT NULL UNIQUE,
                  password_hash TEXT NOT NULL,
                  created_at    TEXT NOT NULL)""".formatted(db.autoIdColumn()));

            Db.exec(c, """
                CREATE TABLE IF NOT EXISTS user_settings (
                  user_id BIGINT NOT NULL,
                  name    TEXT   NOT NULL,
                  value   TEXT   NOT NULL,
                  PRIMARY KEY (user_id, name))""");

            // Timestamps are ISO-8601 text: it sorts correctly, it round-trips
            // through both engines unchanged, and it is exactly what the rest
            // of the app already passes around.
            Db.exec(c, """
                CREATE TABLE IF NOT EXISTS flights (
                  user_id   BIGINT  NOT NULL,
                  hash      TEXT    NOT NULL,
                  seq       INTEGER NOT NULL,
                  start_ts  TEXT    NOT NULL,
                  text      TEXT    NOT NULL,
                  origin    TEXT,
                  dest      TEXT,
                  departure TEXT,
                  PRIMARY KEY (user_id, hash))""");

            Db.exec(c, "CREATE INDEX IF NOT EXISTS flights_user_departure_idx "
                     + "ON flights (user_id, departure)");
        } catch (SQLException e) {
            throw new IllegalStateException("database setup failed: " + e.getMessage(), e);
        }
    }

    /**
     * The old single-user build stored one global `flights` table with no
     * user_id, plus a `blobs` table holding one shared costs document. Those
     * rows belong to whoever ran that install — not to the next person who
     * signs up — so they are renamed aside rather than migrated or dropped.
     * Nothing is deleted; recovering them is a rename away.
     */
    private void retireSingleUserTables(Connection c) throws SQLException {
        if (db.tableExists(c, "flights") && !db.columnExists(c, "flights", "user_id")) {
            Db.exec(c, "ALTER TABLE flights RENAME TO flights_singleuser_backup");
            System.out.println(
                "migrated: pre-accounts `flights` table renamed to `flights_singleuser_backup`");
        }
        if (db.tableExists(c, "blobs")) {
            Db.exec(c, "ALTER TABLE blobs RENAME TO blobs_singleuser_backup");
            System.out.println(
                "migrated: pre-accounts `blobs` table renamed to `blobs_singleuser_backup`");
        }
    }
}
