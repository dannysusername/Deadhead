package deadhead;

/**
 * A move the pilot can make from some state. Sealed = the compiler knows these four
 * are the ONLY kinds of action, so switch expressions over an Action are
 * checked for completeness.
 */
public sealed interface Action {

    /** Fly a scheduled trip. Pilot and plane must both be at trip.from(). */
    record FlyTrip(Trip trip) implements Action {}

    /** Fly the EMPTY plane somewhere (a "deadhead"). Moves plane AND pilot. */
    record RepositionPlane(Airport to) implements Action {}

    /** Uber/drive. Moves ONLY the pilot — the plane stays put. This is the "leave it there" move. */
    record GroundTravel(Airport to) implements Action {}

    /** Stay put; the clock jumps to 8:00 the next morning. Hotel cost if not home. */
    record Overnight() implements Action {}
}
