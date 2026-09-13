package deadhead;

import java.time.LocalDateTime;

/**
 * A job the pilot has committed to: fly someone from A to B, departing at a
 * specific time. The plane (and pilot) must be at {@code from} by {@code departure}.
 */
public record Trip(String id, Airport from, Airport to, LocalDateTime departure) {}
