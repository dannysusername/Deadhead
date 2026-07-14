package deadhead;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Trips -> optimal week plan. */
@Service
public class PlanService {

    private final Properties cfg;
    private final AirportDb db;

    public PlanService(Properties costs, AirportDb airportDb) {
        this.cfg = costs;
        this.db = airportDb;
    }

    // What the browser receives
    public record Step(String when, String icon, String text, long cost) {}
    public record PlanView(long total, List<Step> steps) {}
    public record Result(List<String> airports, long savings, PlanView optimal, PlanView habit) {}

    public String defaultHome() {
        return cfg.getProperty("home", "KTMB").toUpperCase().trim();
    }

    /** Normalize any code (TMB / KTMB / IATA) to one canonical airport. */
    public Airport airport(String code) {
        return new Airport(db.resolve(code).ident());
    }

    /**
     * Catch physically impossible selections BEFORE the solver runs, and name
     * the clash — "no feasible plan" explains nothing; this does.
     */
    public void precheck(List<Trip> trips, java.util.function.Function<String, String> label, Airport home) {
        GeoCostModel costs = new GeoCostModel(db, home, cfg);
        for (int i = 0; i + 1 < trips.size(); i++) {
            Trip a = trips.get(i), b = trips.get(i + 1);
            if (a.departure().equals(b.departure()) && a.from().equals(b.from()) && a.to().equals(b.to())) {
                throw new IllegalArgumentException(
                    "Duplicate flight: \"" + label.apply(b.id()) + "\" — uncheck one.");
            }
            LocalDateTime arrive = a.departure().plus(costs.flightTime(a.from(), a.to()));
            LocalDateTime ready = a.to().equals(b.from())
                ? arrive
                : arrive.plus(costs.flightTime(a.to(), b.from()));
            if (ready.isAfter(b.departure())) {
                throw new IllegalArgumentException(
                    "\"" + label.apply(a.id()) + "\" and \"" + label.apply(b.id())
                    + "\" overlap — one plane can't fly both. Uncheck one.");
            }
        }
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEE HH:mm");

    public Result plan(List<Trip> trips, Airport home, Airport planeAt) {
        Set<Airport> airports = new HashSet<>();
        airports.add(home);
        airports.add(planeAt);   // the plane may start stranded somewhere from last week
        for (Trip t : trips) { airports.add(t.from()); airports.add(t.to()); }

        GeoCostModel costs = new GeoCostModel(db, home, cfg);
        Planner planner = new Planner(trips, home, airports, costs);
        RoutePlanner router = new RoutePlanner(planner);

        // Start a day early: if the plane isn't where the first trip departs,
        // dad needs time to go fetch it.
        LocalDateTime weekStart = trips.get(0).departure().minusDays(1).toLocalDate().atTime(8, 0);
        State start = new State(planeAt, home, weekStart,
            trips.stream().map(Trip::id).collect(Collectors.toUnmodifiableSet()));

        RoutePlanner.Plan optimal = router.solve(start);
        RoutePlanner.Plan habit = router.priceFixedPlan(start,
            HabitPlan.alwaysFlyHome(planner, start, trips, home));

        List<String> airportLines = airports.stream()
            .map(a -> a.code() + " — " + db.resolve(a.code()).name())
            .sorted().toList();

        return new Result(
            airportLines,
            Math.round(habit.totalCost() - optimal.totalCost()),
            toView(planner, optimal),
            toView(planner, habit));
    }

    private PlanView toView(Planner planner, RoutePlanner.Plan plan) {
        List<Step> steps = new ArrayList<>();
        for (RoutePlanner.Step s : plan.steps()) {
            var when = s.action() instanceof Action.FlyTrip f ? f.trip().departure() : s.before().time();
            String icon = switch (s.action()) {
                case Action.FlyTrip t -> "✈️";
                case Action.RepositionPlane r -> "🛩";
                case Action.GroundTravel g -> "🚗";
                case Action.Overnight o -> "🛏";
            };
            steps.add(new Step(when.format(FMT), icon,
                planner.describe(s.action(), s.before()), Math.round(s.cost())));
        }
        return new PlanView(Math.round(plan.totalCost()), steps);
    }
}
