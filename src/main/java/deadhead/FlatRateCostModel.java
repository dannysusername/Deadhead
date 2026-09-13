package deadhead;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Napkin-math cost model: a table of leg times plus flat hourly rates.
 * Superseded by GeoCostModel; kept as the simplest possible CostModel.
 */
public class FlatRateCostModel implements CostModel {

    /** Flight hours and drive hours between a pair of airports (symmetric). */
    public record Leg(double flightHours, double driveHours) {}

    private final Map<String, Leg> legs = new HashMap<>();
    private final Airport home;

    // The knobs. "timeValue" = what an hour of the pilot's life is worth to them.
    private final double fuelPerHour = 200;
    private final double engineReservePerHour = 80;   // wear, maintenance fund
    private final double timeValuePerHour = 50;
    private final double uberBase = 30;
    private final double uberPerHour = 70;
    private final double hotelPerNight = 140;

    public FlatRateCostModel(Airport home) {
        this.home = home;
    }

    public void addLeg(Airport a, Airport b, double flightHours, double driveHours) {
        legs.put(key(a, b), new Leg(flightHours, driveHours));
    }

    private String key(Airport a, Airport b) {
        // store each pair once, in alphabetical order, so ADS->DAL == DAL->ADS
        return a.code().compareTo(b.code()) < 0
            ? a.code() + "-" + b.code()
            : b.code() + "-" + a.code();
    }

    private Leg leg(Airport a, Airport b) {
        Leg l = legs.get(key(a, b));
        if (l == null) throw new IllegalArgumentException("no leg data for " + a + " <-> " + b);
        return l;
    }

    @Override
    public double flightCost(Airport from, Airport to) {
        double h = leg(from, to).flightHours();
        return h * (fuelPerHour + engineReservePerHour + timeValuePerHour);
    }

    @Override
    public double groundCost(Airport from, Airport to) {
        double h = leg(from, to).driveHours();
        return uberBase + h * (uberPerHour + timeValuePerHour);
    }

    @Override
    public double overnightCost(Airport at) {
        return at.equals(home) ? 0 : hotelPerNight;
    }

    @Override
    public Duration flightTime(Airport from, Airport to) {
        return Duration.ofMinutes(Math.round(leg(from, to).flightHours() * 60));
    }

    @Override
    public Duration groundTime(Airport from, Airport to) {
        return Duration.ofMinutes(Math.round(leg(from, to).driveHours() * 60));
    }
}
