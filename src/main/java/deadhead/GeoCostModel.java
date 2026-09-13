package deadhead;

import java.time.Duration;
import java.util.Properties;

/**
 * Cost model computed from geography + this pilot's own saved numbers.
 * No hand-entered leg table: flight time comes from great-circle distance at
 * cruise speed, drive time from an estimated road distance. Every knob lives
 * in their settings, so tuning never means recompiling.
 */
public class GeoCostModel implements CostModel {

    private final AirportDb db;
    private final Airport home;

    private final double cruiseKts;
    private final double flightOverheadHours;   // preflight, taxi, climb
    private final double fuelPerHour;
    private final double enginePerHour;         // wear + maintenance reserve
    private final double timeValuePerHour;
    private final double roadFactor;            // road miles ≈ straight-line miles × this
    private final double avgRoadMph;
    private final double uberBase;
    private final double uberPerMile;
    private final double hotelPerNight;

    public GeoCostModel(AirportDb db, Airport home, Properties p) {
        this.db = db;
        this.home = home;
        this.cruiseKts = num(p, "cruiseKts", 172);
        this.flightOverheadHours = num(p, "flightOverheadHours", 0.25);
        this.fuelPerHour = num(p, "fuelPerHour", 115);
        this.enginePerHour = num(p, "enginePerHour", 85);
        this.timeValuePerHour = num(p, "timeValuePerHour", 50);
        this.roadFactor = num(p, "roadFactor", 1.3);
        this.avgRoadMph = num(p, "avgRoadMph", 55);
        this.uberBase = num(p, "uberBase", 5);
        this.uberPerMile = num(p, "uberPerMile", 2.0);
        this.hotelPerNight = num(p, "hotelPerNight", 140);
    }

    private static double num(Properties p, String key, double fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Double.parseDouble(v.trim());
    }

    /** Great-circle distance in nautical miles (haversine). */
    double distanceNm(Airport a, Airport b) {
        AirportDb.Info ia = db.resolve(a.code()), ib = db.resolve(b.code());
        double lat1 = Math.toRadians(ia.lat()), lat2 = Math.toRadians(ib.lat());
        double dLat = lat2 - lat1, dLon = Math.toRadians(ib.lon() - ia.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                 + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 3440.065 * 2 * Math.asin(Math.sqrt(h));   // earth radius in nm
    }

    double flightHours(Airport from, Airport to) {
        return distanceNm(from, to) / cruiseKts + flightOverheadHours;
    }

    double driveMiles(Airport from, Airport to) {
        return distanceNm(from, to) * 1.15078 * roadFactor;   // nm -> statute, then road factor
    }

    double driveHours(Airport from, Airport to) {
        return driveMiles(from, to) / avgRoadMph + 0.15;      // + waiting for the car
    }

    @Override
    public double flightCost(Airport from, Airport to) {
        return flightHours(from, to) * (fuelPerHour + enginePerHour + timeValuePerHour);
    }

    @Override
    public double groundCost(Airport from, Airport to) {
        return uberBase + driveMiles(from, to) * uberPerMile
             + driveHours(from, to) * timeValuePerHour;
    }

    @Override
    public double overnightCost(Airport at) {
        return at.equals(home) ? 0 : hotelPerNight;
    }

    @Override
    public Duration flightTime(Airport from, Airport to) {
        return Duration.ofMinutes(Math.round(flightHours(from, to) * 60));
    }

    @Override
    public Duration groundTime(Airport from, Airport to) {
        return Duration.ofMinutes(Math.round(driveHours(from, to) * 60));
    }
}
