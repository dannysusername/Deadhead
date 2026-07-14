package deadhead;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses the week's trips from CSV lines:
 *
 *   # from,to,date,departure
 *   ADS,DAL,2026-07-15,09:00
 *   DAL,AUS,2026-07-17,10:00
 */
public class WeekFile {

    public static List<Trip> load(Path csv) throws IOException {
        return parse(Files.readAllLines(csv));
    }

    public static List<Trip> parse(List<String> lines) {
        List<Trip> trips = new ArrayList<>();
        int n = 0;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",");
            if (f.length < 4) {
                throw new IllegalArgumentException(
                    "Bad line: \"" + line + "\" (need from,to,date,departure)");
            }
            n++;
            trips.add(new Trip(
                "T" + n,
                new Airport(f[0].strip().toUpperCase()),
                new Airport(f[1].strip().toUpperCase()),
                LocalDateTime.of(LocalDate.parse(f[2].strip()), LocalTime.parse(f[3].strip()))));
        }
        trips.sort(Comparator.comparing(Trip::departure));
        return trips;
    }
}
