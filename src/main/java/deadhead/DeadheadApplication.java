package deadhead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Deadhead — Spring Boot entry point.
 *
 *   mvn spring-boot:run     ->  http://localhost:8787
 */
@SpringBootApplication
@EnableScheduling
public class DeadheadApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeadheadApplication.class, args);
    }

    /** OurAirports database (85k airports) — loaded once, shared by every account. */
    @Bean
    AirportDb airportDb() throws IOException {
        return new AirportDb(Path.of("data/airports.csv"));
    }

    @EventListener
    void announce(ApplicationReadyEvent ready) {
        var env = ready.getApplicationContext().getEnvironment();
        System.out.println(System.getenv("DATABASE_URL") == null
            ? "Storage: SQLite at data/deadhead.db"
            : "Storage: Postgres via DATABASE_URL");
        if (System.getenv("DYNO") != null) return;   // running on a dyno — nobody to tell
        System.out.println("Deadhead running at http://localhost:" + env.getProperty("server.port", "8787"));
    }
}
