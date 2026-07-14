package deadhead;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * One snapshot of "how things stand" — everything that matters for deciding
 * what to do next, and nothing else.
 *
 * Records give us equals()/hashCode() for free, which is what lets the search
 * recognize "I've been in this exact situation before" and skip re-exploring it.
 */
public record State(
    Airport planeAt,
    Airport dadAt,
    LocalDateTime time,
    Set<String> tripsRemaining   // ids of trips not yet flown
) {}
