package deadhead;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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

    /** Dad's numbers: fuel/hr, hotel, uber rates, time value, default home base. */
    @Bean
    Properties costs(BlobStore blobs) throws IOException {
        Properties cfg = new Properties();
        try (Reader r = new java.io.StringReader(blobs.get(BlobStore.COSTS))) {
            cfg.load(r);
        }
        return cfg;
    }

    /** OurAirports database (85k airports) — loaded once at startup. */
    @Bean
    AirportDb airportDb() throws IOException {
        return new AirportDb(Path.of("data/airports.csv"));
    }

    @EventListener(ApplicationReadyEvent.class)
    void openBrowser() {
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            System.out.println("No ANTHROPIC_API_KEY — calendar events must contain airport codes.");
        }
        if (System.getenv("DYNO") != null) return;   // running on Heroku — no browser to open
        System.out.println("Deadhead running at http://localhost:8787");
        try {
            new ProcessBuilder("open", "http://localhost:8787").start();
        } catch (Exception ignored) { }
    }
}
