package deadhead;

import deadhead.user.UserAccount;
import deadhead.user.UserRepo;
import deadhead.user.UserService;
import deadhead.user.UserSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The property this whole refactor exists to hold: two accounts on one server
 * never see each other's flights or each other's numbers.
 */
@SpringBootTest
class AccountIsolationTest {

    @Autowired UserService users;
    @Autowired UserRepo repo;
    @Autowired UserSettings settings;
    @Autowired FlightStore flights;
    @Autowired AirportDb airports;
    @Autowired deadhead.store.Db db;

    private static final String ICS_A = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART:20260715T090000
        SUMMARY:TMB to EYW
        END:VEVENT
        END:VCALENDAR
        """;

    private static final String ICS_B = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART:20260716T110000
        SUMMARY:ADS to AUS
        END:VEVENT
        END:VCALENDAR
        """;

    /** Straight at the row, to prove what is actually written to the database. */
    private String rawSetting(long userId, String name) {
        try (var c = db.connect();
             var st = c.prepareStatement(
                 "SELECT value FROM user_settings WHERE user_id = ? AND name = ?")) {
            st.setLong(1, userId);
            st.setString(2, name);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UserAccount fresh(String tag) {
        return users.register(tag + "-" + System.nanoTime() + "@example.com", "correct-horse-battery");
    }

    @Test
    void one_accounts_import_is_invisible_to_another() {
        UserAccount a = fresh("a");
        UserAccount b = fresh("b");

        flights.importIcs(a.id(), ICS_A, new Airport("KTMB"), null);
        flights.importIcs(b.id(), ICS_B, new Airport("KADS"), null);

        assertThat(flights.flightCount(a.id())).isEqualTo(1);
        assertThat(flights.flightCount(b.id())).isEqualTo(1);

        String aRoute = flights.labels(a.id()).values().iterator().next();
        String bRoute = flights.labels(b.id()).values().iterator().next();
        assertThat(aRoute).isEqualTo("TMB to EYW");
        assertThat(bRoute).isEqualTo("ADS to AUS");

        // and B's ids are not resolvable as A's trips
        String bId = flights.labels(b.id()).keySet().iterator().next();
        assertThat(flights.tripsForIds(a.id(), java.util.List.of(bId))).isEmpty();
    }

    @Test
    void settings_are_per_account_and_default_to_the_committed_file() {
        UserAccount a = fresh("s1");
        UserAccount b = fresh("s2");

        // Nothing personal ships in the defaults.
        assertThat(settings.all(a.id()).get("home")).isEmpty();
        assertThat(settings.all(a.id()).get("tail")).isEmpty();
        assertThat(settings.all(a.id()).get("hotelPerNight")).isEqualTo("140");

        settings.update(a.id(), Map.of("home", "KTMB", "hotelPerNight", "300"));
        settings.update(b.id(), Map.of("home", "KADS"));

        assertThat(settings.all(a.id()).get("home")).isEqualTo("KTMB");
        assertThat(settings.all(a.id()).get("hotelPerNight")).isEqualTo("300");
        assertThat(settings.all(b.id()).get("home")).isEqualTo("KADS");
        assertThat(settings.all(b.id()).get("hotelPerNight")).isEqualTo("140");   // untouched

        Properties pa = settings.forUser(a.id());
        assertThat(pa.getProperty("hotelPerNight")).isEqualTo("300");
    }

    @Test
    void clearing_one_schedule_leaves_the_other_alone() {
        UserAccount a = fresh("c1");
        UserAccount b = fresh("c2");
        flights.importIcs(a.id(), ICS_A, new Airport("KTMB"), null);
        flights.importIcs(b.id(), ICS_B, new Airport("KADS"), null);

        flights.clear(a.id());

        assertThat(flights.flightCount(a.id())).isZero();
        assertThat(flights.flightCount(b.id())).isEqualTo(1);
    }

    @Test
    void reimporting_the_same_calendar_adds_nothing() {
        UserAccount a = fresh("dup");
        FlightStore.ImportStats first = flights.importIcs(a.id(), ICS_A, new Airport("KTMB"), null);
        FlightStore.ImportStats again = flights.importIcs(a.id(), ICS_A, new Airport("KTMB"), null);

        assertThat(first.newFlights()).isEqualTo(1);
        assertThat(again.newEvents()).isZero();
        assertThat(flights.flightCount(a.id())).isEqualTo(1);
    }

    @Test
    void emails_are_unique_and_case_insensitive() {
        String email = "Mixed-" + System.nanoTime() + "@Example.com";
        users.register(email, "correct-horse-battery");

        assertThatThrownBy(() -> users.register(email.toLowerCase(), "another-long-password"))
            .isInstanceOf(UserRepo.DuplicateEmailException.class);
    }

    @Test
    void weak_and_malformed_signups_are_refused() {
        assertThatThrownBy(() -> users.register("nope@example.com", "short"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10 characters");

        assertThatThrownBy(() -> users.register("not-an-email", "correct-horse-battery"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("email address");
    }

    @Test
    void passwords_are_hashed_not_stored() {
        UserAccount a = fresh("hash");
        String stored = repo.findByEmail(a.email()).orElseThrow().passwordHash();
        assertThat(stored).doesNotContain("correct-horse-battery").startsWith("$2");
    }

    @Test
    void an_api_key_is_encrypted_at_rest_and_never_readable_through_settings() {
        UserAccount a = fresh("key");
        String plaintext = "sk-ant-api03-notarealkey-0000000000000000";

        settings.setApiKey(a.id(), plaintext);

        // Readable by the server...
        assertThat(settings.apiKey(a.id())).contains(plaintext);
        // ...as a hint by the page...
        assertThat(settings.apiKeyHint(a.id())).isEqualTo("\u20260000");
        // ...but never as a value the Settings panel renders.
        assertThat(settings.all(a.id()).values()).doesNotContain(plaintext);
        assertThat(settings.all(a.id())).doesNotContainKey("anthropicApiKeyEnc");
        // ...and not through the properties the planner reads either.
        assertThat(settings.forUser(a.id()).stringPropertyNames())
            .doesNotContain("anthropicApiKeyEnc");
    }

    @Test
    void the_stored_bytes_are_not_the_key() {
        UserAccount a = fresh("enc");
        String plaintext = "sk-ant-api03-notarealkey-1111111111111111";
        settings.setApiKey(a.id(), plaintext);

        String onDisk = rawSetting(a.id(), "anthropicApiKeyEnc");
        assertThat(onDisk).isNotNull().isNotEqualTo(plaintext).doesNotContain("sk-ant");
    }

    @Test
    void one_pilots_api_key_is_invisible_to_another() {
        UserAccount a = fresh("k1");
        UserAccount b = fresh("k2");
        settings.setApiKey(a.id(), "sk-ant-api03-notarealkey-2222222222222222");

        assertThat(settings.apiKey(b.id())).isEmpty();
        assertThat(settings.apiKeyHint(b.id())).isEmpty();
    }

    @Test
    void removing_a_key_leaves_nothing_behind() {
        UserAccount a = fresh("kdel");
        settings.setApiKey(a.id(), "sk-ant-api03-notarealkey-3333333333333333");
        settings.setApiKey(a.id(), null);

        assertThat(settings.apiKey(a.id())).isEmpty();
        assertThat(settings.apiKeyHint(a.id())).isEmpty();
    }

    @Test
    void importing_without_a_key_still_reads_airport_codes() {
        UserAccount a = fresh("nokey");
        FlightStore.ImportStats stats = flights.importIcs(a.id(), ICS_A, new Airport("KTMB"), null);

        assertThat(stats.newFlights()).isEqualTo(1);
        assertThat(stats.aiParsed()).isZero();     // nothing was billed to anyone
    }

    @Test
    void settings_reject_nonsense_and_ignore_unknown_keys() {
        UserAccount a = fresh("val");

        assertThatThrownBy(() -> settings.update(a.id(), Map.of("hotelPerNight", "lots")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> settings.update(a.id(), Map.of("calendarUrl", "ftp://nope")))
            .isInstanceOf(IllegalArgumentException.class);

        // An unknown key is dropped rather than persisted or blowing up.
        settings.update(a.id(), Map.of("password_hash", "hunter2"));
        assertThat(settings.all(a.id())).doesNotContainKey("password_hash");
    }
}
