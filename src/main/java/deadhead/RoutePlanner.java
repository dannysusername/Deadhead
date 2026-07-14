package deadhead;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Dijkstra's algorithm over the state graph. This is the exact same algorithm
 * as LeetCode "Network Delay Time" — the only difference is that neighbors
 * come from planner.legalActions(state) instead of an adjacency list.
 * Dijkstra doesn't care where the graph comes from.
 */
public class RoutePlanner {

    private final Planner planner;

    public RoutePlanner(Planner planner) {
        this.planner = planner;
    }

    /** One move in the final plan: the action taken, the state it led to, its cost. */
    public record Step(Action action, State before, State after, double cost) {}

    public record Plan(List<Step> steps, double totalCost, int statesExplored) {}

    /** A node on the frontier: a state, the cheapest known cost to reach it, and how we got there. */
    private record Node(State state, double costSoFar, Node parent, Action action) {}

    public Plan solve(State start) {
        PriorityQueue<Node> frontier =
            new PriorityQueue<>((a, b) -> Double.compare(a.costSoFar(), b.costSoFar()));
        Map<State, Double> bestCost = new HashMap<>();

        frontier.add(new Node(start, 0, null, null));
        bestCost.put(start, 0.0);
        int explored = 0;

        while (!frontier.isEmpty()) {
            Node node = frontier.poll();

            // stale entry: we already found a cheaper way to this state
            if (node.costSoFar() > bestCost.getOrDefault(node.state(), Double.MAX_VALUE)) continue;
            explored++;

            if (planner.isGoal(node.state())) {
                return new Plan(reconstruct(node), node.costSoFar(), explored);
            }
            if (!planner.isFeasible(node.state())) continue;   // a trip already missed — dead branch

            for (Action a : planner.legalActions(node.state())) {
                Planner.Result r = planner.apply(node.state(), a);
                double newCost = node.costSoFar() + r.cost();
                if (newCost < bestCost.getOrDefault(r.next(), Double.MAX_VALUE)) {
                    bestCost.put(r.next(), newCost);
                    frontier.add(new Node(r.next(), newCost, node, a));
                }
            }
        }
        throw new IllegalStateException("Couldn't fit these flights together — try unchecking one.");
    }

    /** Walk parent links back to the start, then flip the list around. */
    private List<Step> reconstruct(Node goal) {
        List<Step> steps = new ArrayList<>();
        for (Node n = goal; n.parent() != null; n = n.parent()) {
            steps.add(new Step(n.action(), n.parent().state(), n.state(),
                               n.costSoFar() - n.parent().costSoFar()));
        }
        Collections.reverse(steps);
        return steps;
    }

    /** Price out a hand-written plan (for comparing against the optimizer). */
    public Plan priceFixedPlan(State start, List<Action> actions) {
        List<Step> steps = new ArrayList<>();
        State s = start;
        double total = 0;
        for (Action a : actions) {
            Planner.Result r = planner.apply(s, a);
            steps.add(new Step(a, s, r.next(), r.cost()));
            total += r.cost();
            s = r.next();
        }
        if (!planner.isGoal(s)) throw new IllegalStateException("fixed plan doesn't reach the goal");
        return new Plan(steps, total, 0);
    }
}
