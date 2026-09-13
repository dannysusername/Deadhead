package deadhead;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules of the game: which moves are legal from a state, and what happens
 * (new state + cost) when you make one. Knows nothing about searching —
 * that's RoutePlanner's job.
 */
public class Planner {

    private final List<Trip> trips;
    private final Airport home;
    private final Set<Airport> airports;
    private final CostModel costs;
    private final LocalDateTime horizon;   // don't plan past this — keeps the search finite
    private static final LocalTime MORNING = LocalTime.of(8, 0);

    public Planner(List<Trip> trips, Airport home, Set<Airport> airports, CostModel costs) {
        this.trips = trips;
        this.home = home;
        this.airports = airports;
        this.costs = costs;
        LocalDateTime lastDeparture = trips.stream()
            .map(Trip::departure)
            .max(LocalDateTime::compareTo)
            .orElseThrow();
        this.horizon = lastDeparture.plusDays(1);
    }

    public Trip tripById(String id) {
        return trips.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    /** Done = every trip flown, and both the pilot and the plane are back home. */
    public boolean isGoal(State s) {
        return s.tripsRemaining().isEmpty()
            && s.planeAt().equals(home)
            && s.pilotAt().equals(home);
    }

    /**
     * A state is dead if some remaining trip's departure has already passed —
     * no sequence of moves can ever complete the week from here.
     */
    public boolean isFeasible(State s) {
        for (String id : s.tripsRemaining()) {
            if (tripById(id).departure().isBefore(s.time())) return false;
        }
        return true;
    }

    public List<Action> legalActions(State s) {
        List<Action> out = new ArrayList<>();

        // Fly a scheduled trip: pilot + plane at the origin, before departure time.
        // Waiting within the same day is free (he sits at the airport); waiting
        // across a night must go through Overnight so the hotel gets charged.
        for (String id : s.tripsRemaining()) {
            Trip t = tripById(id);
            if (s.planeAt().equals(t.from())
                    && s.pilotAt().equals(t.from())
                    && !s.time().isAfter(t.departure())
                    && s.time().toLocalDate().equals(t.departure().toLocalDate())) {
                out.add(new Action.FlyTrip(t));
            }
        }

        // Deadhead the empty plane somewhere (the pilot has to be with the plane to fly it).
        if (s.planeAt().equals(s.pilotAt())) {
            for (Airport a : airports) {
                if (!a.equals(s.planeAt())) out.add(new Action.RepositionPlane(a));
            }
        }

        // Uber/drive: the pilot moves, plane stays. THE "leave it there" move.
        for (Airport a : airports) {
            if (!a.equals(s.pilotAt())) out.add(new Action.GroundTravel(a));
        }

        // Call it a day.
        if (s.time().isBefore(horizon)) out.add(new Action.Overnight());

        return out;
    }

    public record Result(State next, double cost) {}

    public Result apply(State s, Action a) {
        return switch (a) {
            case Action.FlyTrip(Trip t) -> {
                // wait (free) until departure, then fly
                LocalDateTime arrive = t.departure().plus(costs.flightTime(t.from(), t.to()));
                Set<String> remaining = new HashSet<>(s.tripsRemaining());
                remaining.remove(t.id());
                yield new Result(
                    new State(t.to(), t.to(), arrive, Set.copyOf(remaining)),
                    costs.flightCost(t.from(), t.to()));
            }
            case Action.RepositionPlane(Airport to) -> new Result(
                new State(to, to, s.time().plus(costs.flightTime(s.planeAt(), to)), s.tripsRemaining()),
                costs.flightCost(s.planeAt(), to));

            case Action.GroundTravel(Airport to) -> new Result(
                new State(s.planeAt(), to, s.time().plus(costs.groundTime(s.pilotAt(), to)), s.tripsRemaining()),
                costs.groundCost(s.pilotAt(), to));

            case Action.Overnight() -> {
                // Wake at 08:00 — or earlier if tomorrow has an early departure.
                // (Without this, a 07:00 flight is unreachable after sleeping, and the
                // solver "stays up all night" burning money on pointless legs instead.)
                var nextDay = s.time().toLocalDate().plusDays(1);
                var wake = MORNING;
                for (String id : s.tripsRemaining()) {
                    Trip t = tripById(id);
                    if (t.departure().toLocalDate().equals(nextDay)
                            && t.departure().toLocalTime().isBefore(wake)) {
                        wake = t.departure().toLocalTime();
                    }
                }
                yield new Result(
                    new State(s.planeAt(), s.pilotAt(), nextDay.atTime(wake), s.tripsRemaining()),
                    costs.overnightCost(s.pilotAt()));
            }
        };
    }

    /** Human-readable description of a move, for printing plans. */
    public String describe(Action a, State before) {
        return switch (a) {
            case Action.FlyTrip(Trip t) ->
                "Fly " + t.from() + " -> " + t.to() + " (clients aboard)";
            case Action.RepositionPlane(Airport to) ->
                "Deadhead the empty plane " + before.planeAt() + " -> " + to;
            case Action.GroundTravel(Airport to) ->
                "Uber " + before.pilotAt() + " -> " + to + "  (plane stays at " + before.planeAt() + ")";
            case Action.Overnight() ->
                before.pilotAt().equals(home) ? "Sleep at home" : "Hotel overnight in " + before.pilotAt();
        };
    }
}
