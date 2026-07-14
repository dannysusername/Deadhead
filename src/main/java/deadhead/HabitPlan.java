package deadhead;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the plan a "plane always sleeps at home" pilot would fly, for any
 * week: before each trip, deadhead to its origin if needed; after each trip,
 * deadhead home. This is the baseline the optimizer gets compared against.
 */
public class HabitPlan {

    public static List<Action> alwaysFlyHome(Planner planner, State start, List<Trip> trips, Airport home) {
        List<Action> actions = new ArrayList<>();
        State s = start;
        // Plane stranded somewhere from last week? The habit pilot fetches it home first.
        if (!s.planeAt().equals(home)) {
            if (!s.dadAt().equals(s.planeAt())) {
                s = add(actions, planner, s, new Action.GroundTravel(s.planeAt()));
            }
            s = add(actions, planner, s, new Action.RepositionPlane(home));
        }
        for (Trip t : trips) {   // trips arrive sorted by departure
            while (s.time().toLocalDate().isBefore(t.departure().toLocalDate())) {
                s = add(actions, planner, s, new Action.Overnight());
            }
            if (!s.planeAt().equals(t.from())) {
                s = add(actions, planner, s, new Action.RepositionPlane(t.from()));
            }
            s = add(actions, planner, s, new Action.FlyTrip(t));
            if (!s.planeAt().equals(home)) {
                s = add(actions, planner, s, new Action.RepositionPlane(home));
            }
        }
        return actions;
    }

    private static State add(List<Action> actions, Planner planner, State s, Action a) {
        actions.add(a);
        return planner.apply(s, a).next();
    }
}
