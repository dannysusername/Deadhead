package deadhead;

import java.time.Duration;

/**
 * Prices and durations for every kind of move. This is an interface on purpose:
 * the planner only *asks* for costs, it never computes them. Start with flat
 * napkin-math rates; swap in a smarter (even learned) model later without
 * touching the search at all.
 */
public interface CostModel {
    double flightCost(Airport from, Airport to);   // fuel + engine reserve + the pilot's time
    double groundCost(Airport from, Airport to);   // uber/rental + the pilot's time
    double overnightCost(Airport at);              // hotel, or 0 at home

    Duration flightTime(Airport from, Airport to);
    Duration groundTime(Airport from, Airport to);
}
