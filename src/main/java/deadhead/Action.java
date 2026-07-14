package deadhead;

/**
 * A move dad can make from some state. Sealed = the compiler knows these four
 * are the ONLY kinds of action, so switch expressions over an Action are
 * checked for completeness.
 */
public sealed interface Action {

    /** Fly a scheduled trip. Dad and plane must both be at trip.from(). */
    record FlyTrip(Trip trip) implements Action {}

    /** Fly the EMPTY plane somewhere (a "deadhead"). Moves plane AND dad. */
    record RepositionPlane(Airport to) implements Action {}

    /** Uber/drive. Moves ONLY dad — the plane stays put. This is the "leave it there" move. */
    record GroundTravel(Airport to) implements Action {}

    /** Stay put; the clock jumps to 8:00 the next morning. Hotel cost if not home. */
    record Overnight() implements Action {}
}
