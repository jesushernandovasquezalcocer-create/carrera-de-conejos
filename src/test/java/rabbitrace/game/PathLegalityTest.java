package rabbitrace.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import rabbitrace.automation.Snapshot;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No path ever traverses the carrot, and a
 * path costing more than the remaining budget is never offered or accepted.
 *
 * <p>Both halves are read from the surfaces a scripted session sees. The offer
 * set is {@link Match#legalTargets()} and the {@code state} dump's
 * {@code legalTargets}; the cost of a target is the number of steps the committed
 * move returns. An independent BFS that never expands the carrot says which
 * tiles are within the remaining budget, and every turn of 200 seeded matches is
 * compared against it, so an offer is judged by distance, not by the engine's own
 * search describing itself.
 *
 * <p>The carrot is on the top row with a clear 3×3 clearing (
 * ), so the tile horizontally beyond it is the traversal trap: two steps if a
 * path may cross the carrot, four when it may not. Standing beside the carrot
 * with two or three pips left therefore distinguishes a path finder that treats
 * the carrot as terminal from one that walks through it. Every committed path is
 * also inspected step by step: the carrot may only ever be a final destination.
 *
 * <p>Over-budget refusal is checked at three budgets: a whole roll on the first
 * turn (the carrot is 13–20 steps away), the remaining budget after a step,
 * and the boundary rings around the active rabbit, where the tile at
 * {@code remaining + 1} must be absent from the offers and must throw without
 * moving the rabbit or spending a pip.
 */
class PathLegalityTest {

    private static final int MATCHES = 200;
    private static final int PLY_LIMIT = 200;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private static final int CARROT_COL = BoardGenerator.CARROT_COL;
    private static final int CARROT_ROW = BoardGenerator.CARROT_ROW;
    /** The top-row tile the carrot must never be traversed to reach. */
    private static final int[] BEYOND_CARROT = {CARROT_COL + 1, CARROT_ROW};
    /** The top-row tile on the other side of the carrot, used as the staging goal. */
    private static final int[] BESIDE_CARROT = {CARROT_COL - 1, CARROT_ROW};

    @Test
    void noPathTraversesTheCarrotAndNoOverBudgetPathIsOfferedOrAccepted() {
        assertPathsNeverTraverseTheCarrot();
        assertOverBudgetTargetsAreNeitherOfferedNorAccepted();
        assertTheCarrotBeyondTheBudgetIsNeitherOfferedNorAccepted();
    }

    /**
     * The seeded walk. Each turn reads the offer set, commits a whole path, and
     * inspects the steps: the path must be the shortest avoiding route, may never
     * pass through the carrot, and may only end on the carrot as its last step.
     *
     * <p>To make the traversal law observable rather than vacuously true, every
     * match first routes beside the carrot and waits for a two- or three-pip roll.
     * There, the tile beyond the carrot is two steps away if the carrot may be
     * crossed and four if it may not; the offer set is compared to the four-step
     * avoiding distance, and the tile is asserted absent. A path finder that
     * expanded the carrot would offer it at two or three and fail that turn.
     */
    private static void assertPathsNeverTraverseTheCarrot() {
        int targetsCommitted = 0;
        int multiStepPaths = 0;
        int besideTheCarrotProbes = 0;
        int steps = 0;

        for (int i = 0; i < MATCHES; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            assertEquals(Match.BLUE, match.throwHands(1, 3),
                    "the scripted opening throw must be decisive for seed " + seed);

            boolean probed = false;
            int plies = 0;
            while (!Match.MATCH_WON.equals(match.phase())) {
                assertTrue(plies++ < PLY_LIMIT, "seed " + seed + " did not finish within "
                        + PLY_LIMIT + " turns, so the seeded walk never reached the carrot");

                match.rollDie();
                assertEquals(Match.AWAIT_MOVE, match.phase(),
                        "seed " + seed + " is not in AwaitMove after its roll");
                assertOffersMatchTheIndependentReach(match, seed, "after rolling");

                Board board = match.board();
                int[] from = match.position(match.active());
                int budget = match.budget();
                if (sameCell(from, BESIDE_CARROT) && budget >= 2 && budget <= 3
                        && board.passable(BEYOND_CARROT[0], BEYOND_CARROT[1])) {
                    assertTraversalTrapIsClosed(match, seed);
                    besideTheCarrotProbes++;
                    probed = true;
                }

                int[][] toGoal = probed
                        ? distancesAvoidingCarrot(board, new int[]{CARROT_COL, CARROT_ROW})
                        : distancesAvoidingCarrot(board, BESIDE_CARROT);
                int[] goal = probed ? new int[]{CARROT_COL, CARROT_ROW} : BESIDE_CARROT;
                int[] target = targetToward(match, toGoal, goal, seed);
                int[][] fromHere = distancesAvoidingCarrot(board, from);
                int expectedSteps = fromHere[target[1]][target[0]];

                List<int[]> path = match.moveTo(target[0], target[1]);
                targetsCommitted++;
                if (path.size() >= 2) {
                    multiStepPaths++;
                }
                steps += path.size();
                assertPathIsLegal(path, from, target, expectedSteps, budget, seed);
                assertEquals(budget - path.size(), match.budget().intValue(),
                        "seed " + seed + " committed " + path.size() + " steps of a " + budget
                                + "-pip budget but the budget is now " + match.budget());
                assertEquals(budget - path.size(), reportedBudget(match),
                        "state reports a budget the committed path did not spend");

                if (!Match.MATCH_WON.equals(match.phase())) {
                    if (match.budget() == 0) {
                        match.endTurnIfSpent();
                    } else {
                        match.endTurn();
                    }
                    assertEquals(Match.AWAIT_ROLL, match.phase(),
                            "seed " + seed + " did not hand off to AwaitRoll");
                }
            }
        }

        assertTrue(targetsCommitted >= MATCHES * 2,
                "only " + targetsCommitted + " committed targets across " + MATCHES
                        + " matches, so the seeded walk is too short to judge paths");
        assertTrue(multiStepPaths >= MATCHES,
                "only " + multiStepPaths + " committed paths cost more than one step, so the"
                        + " multi-step path rule is barely exercised");
        assertTrue(steps >= MATCHES * 13,
                "only " + steps + " steps across " + MATCHES + " matches; the shortest route"
                        + " is 13 steps, so the walk did not reach the carrot");
        assertTrue(besideTheCarrotProbes >= 10,
                "only " + besideTheCarrotProbes + " turns stood beside the carrot with a"
                        + " partial budget, so the no-traversal law was rarely confronted");
    }

    /**
     * The traversal trap, stated directly. {@code BEYOND_CARROT} is one tile to the
     * right of the carrot; its only two-step route is across the carrot, while the
     * route that treats the carrot as terminal costs four. With a budget of two or
     * three the tile must be absent from the offers, and the state dump must agree.
     * An implementation that expanded the carrot would offer it at two or three.
     */
    private static void assertTraversalTrapIsClosed(Match match, String seed) {
        Board board = match.board();
        int[] from = match.position(match.active());
        int avoiding = distancesAvoidingCarrot(board, from)[BEYOND_CARROT[1]][BEYOND_CARROT[0]];
        int crossing = distancesAllowingCarrot(board, from)[BEYOND_CARROT[1]][BEYOND_CARROT[0]];
        assertEquals(4, avoiding,
                "seed " + seed + " at (" + from[0] + "," + from[1] + ") says the route beside"
                        + " the carrot to (" + BEYOND_CARROT[0] + "," + BEYOND_CARROT[1] + ") costs "
                        + avoiding + "not the four steps clearing fixes");
        assertEquals(2, crossing,
                "seed " + seed + " at (" + from[0] + "," + from[1] + ") says crossing the"
                        + " carrot reaches (" + BEYOND_CARROT[0] + "," + BEYOND_CARROT[1] + ") in "
                        + crossing + " steps, not the two the trap needs");
        assertFalse(offered(match, BEYOND_CARROT[0], BEYOND_CARROT[1]),
                "seed " + seed + " offers (" + BEYOND_CARROT[0] + "," + BEYOND_CARROT[1]
                        + ") with " + match.budget() + " pips left, although only the two-step route"
                        + " across the carrot fits");
        assertFalse(reportedOffers(match).contains(key(BEYOND_CARROT[0], BEYOND_CARROT[1])),
                "seed " + seed + " hides (" + BEYOND_CARROT[0] + "," + BEYOND_CARROT[1]
                        + ") from the engine but state offers it");
        assertThrows(IllegalArgumentException.class,
                () -> match.moveTo(BEYOND_CARROT[0], BEYOND_CARROT[1]),
                "seed " + seed + " accepted a path across the carrot to ("
                        + BEYOND_CARROT[0] + "," + BEYOND_CARROT[1] + ")");
    }

    /**
     * The remaining-budget boundary after a step has been taken. The first turn of
     * several seeds is rolled, one step is spent, and the tile exactly
     * {@code remaining + 1} avoiding steps away is asserted absent from the offers
     * and refused without moving the rabbit, spending a pip or changing the phase.
     * A target at exactly {@code remaining} steps is also found, so the boundary is
     * known to be tight rather than beyond the board's reach.
     */
    private static void assertOverBudgetTargetsAreNeitherOfferedNorAccepted() {
        int checked = 0;
        for (int i = 0; i < 1000 && checked < 10; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            match.throwHands(1, 3);
            if (match.rollDie() < 3) {
                continue;
            }
            int[] step = adjacentNonCarrotStep(match);
            if (step == null) {
                continue;
            }
            match.moveTo(step[0], step[1]);
            int remaining = match.budget();
            assertTrue(remaining >= 2, "seed " + seed + " has " + remaining
                    + " pips after one step of a roll of at least three");

            Board board = match.board();
            int[] from = match.position(match.active());
            int[][] distance = distancesAvoidingCarrot(board, from);
            int[] overBudget = tileAtDistance(match, distance, remaining + 1);
            int[] exact = tileAtDistance(match, distance, remaining);
            if (overBudget == null || exact == null) {
                continue;
            }

            assertOffersMatchTheIndependentReach(match, seed, "after spending a pip");
            assertFalse(offered(match, overBudget[0], overBudget[1]),
                    "seed " + seed + " offers (" + overBudget[0] + "," + overBudget[1] + ")"
                            + " at " + (remaining + 1) + " steps with " + remaining + " pips left");
            assertFalse(reportedOffers(match).contains(key(overBudget[0], overBudget[1])),
                    "seed " + seed + " hides (" + overBudget[0] + "," + overBudget[1]
                            + ") from the engine but state offers it");

            int[] before = match.position(match.active());
            int stepsBefore = match.stepsThisTurn();
            String phaseBefore = match.phase();
            assertThrows(IllegalArgumentException.class,
                    () -> match.moveTo(overBudget[0], overBudget[1]),
                    "seed " + seed + " accepted a path of " + (remaining + 1)
                            + " steps with " + remaining + " pips left");
            assertArrayEquals(before, match.position(match.active()),
                    "seed " + seed + " moved the rabbit while refusing an over-budget path");
            assertEquals(remaining, match.budget().intValue(),
                    "seed " + seed + " spent budget on a refused over-budget path");
            assertEquals(stepsBefore, match.stepsThisTurn(),
                    "seed " + seed + " counted a step for a refused over-budget path");
            assertEquals(phaseBefore, match.phase(),
                    "seed " + seed + " changed the phase on a refused over-budget path");

            assertTrue(offered(match, exact[0], exact[1]),
                    "seed " + seed + " withholds (" + exact[0] + "," + exact[1] + "), which"
                            + " costs exactly the " + remaining + " remaining pips");
            checked++;
        }
        assertTrue(checked >= 5,
                "only " + checked + " matches offered a tight remaining-budget boundary, so"
                        + " the refusal was barely exercised");
    }

    /**
     * The winning case at the worst budget: on a first turn the carrot is 13–20
     * steps away and the roll is at most 6, so its path always costs more than
     * the remaining budget. It must not appear in the offers, and committing it must
     * throw rather than win: no phase change, no winner, no movement, no pip spent.
     * This is the half of the winning-versus-traversal rule that a careless "click the carrot" shortcut
     * would break.
     */
    private static void assertTheCarrotBeyondTheBudgetIsNeitherOfferedNorAccepted() {
        for (int i = 0; i < 50; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            match.throwHands(1, 3);
            int roll = match.rollDie();

            int[] from = match.position(match.active());
            int[][] distance = distancesAvoidingCarrot(match.board(), from);
            int carrotCost = distance[CARROT_ROW][CARROT_COL];
            assertTrue(carrotCost > roll, "seed " + seed + " puts the carrot " + carrotCost
                    + " steps away on a " + roll + "-pip first turn; says 13-20 against a 6-pip"
                    + " ceiling, so this budget check would be vacuous");

            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "seed " + seed + " is not in AwaitMove after its first roll");
            assertFalse(offered(match, CARROT_COL, CARROT_ROW),
                    "seed " + seed + " offers the carrot at " + carrotCost + " steps with a "
                            + roll + "-pip budget");
            assertFalse(reportedOffers(match).contains(key(CARROT_COL, CARROT_ROW)),
                    "seed " + seed + " hides the carrot from the engine but state offers it");

            int[] before = match.position(match.active());
            int stepsBefore = match.stepsThisTurn();
            assertThrows(IllegalArgumentException.class,
                    () -> match.moveTo(CARROT_COL, CARROT_ROW),
                    "seed " + seed + " accepted the " + carrotCost
                            + "-step carrot path on a " + roll + "-pip budget");
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "seed " + seed + " changed the phase on a refused carrot path");
            assertNull(match.winner(),
                    "seed " + seed + " has a winner after a refused carrot path");
            assertArrayEquals(before, match.position(match.active()),
                    "seed " + seed + " moved the rabbit on a refused carrot path");
            assertEquals(roll, match.budget().intValue(),
                    "seed " + seed + " spent a pip on a refused carrot path");
            assertEquals(stepsBefore, match.stepsThisTurn(),
                    "seed " + seed + " counted a step on a refused carrot path");
        }
    }

    // ------------------------------------------------------------------
    // The offer set.
    // ------------------------------------------------------------------

    /**
     * Compares both offer surfaces with the tiles an independent BFS says are
     * within the remaining budget. The independent BFS never expands the carrot
     * and never lands on the other rabbit, so a tile offered beyond the
     * budget, a tile offered through the carrot, or a legal tile withheld all fail
     * here with the seed that produced it.
     */
    private static void assertOffersMatchTheIndependentReach(Match match, String seed, String when) {
        Board board = match.board();
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        int budget = match.budget();
        assertNotNull(budget, "seed " + seed + " has no budget " + when);

        int[][] distance = distancesAvoidingCarrot(board, from);
        Set<String> expected = new HashSet<>();
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (sameCell(new int[]{col, row}, from)
                        || sameCell(new int[]{col, row}, other)
                        || !board.passable(col, row)) {
                    continue;
                }
                if (distance[row][col] >= 1 && distance[row][col] <= budget) {
                    expected.add(key(col, row));
                }
            }
        }

        Set<String> actual = new HashSet<>();
        for (int[] target : match.legalTargets()) {
            actual.add(key(target[0], target[1]));
        }
        assertEquals(expected, actual, "seed " + seed + " " + when + " offers a different set"
                + " of targets than the tiles within its " + budget + " remaining pips");

        Set<String> reported = reportedOffers(match);
        assertEquals(actual, reported, "seed " + seed + " " + when + " reports a different"
                + " offer set in state than the engine offers");
        assertEquals(budget, reportedBudget(match),
                "seed " + seed + " " + when + " reports a different budget in state");
    }

    /**
     * The committed path: contiguous orthogonal steps from the rabbit's tile to the
     * target, exactly the independently measured avoiding cost, never through the
     * carrot, and ending on the carrot only when the carrot is the target.
     */
    private static void assertPathIsLegal(List<int[]> path, int[] from, int[] target,
                                          int expectedSteps, int budget, String seed) {
        assertFalse(path.isEmpty(), "seed " + seed + " committed an empty path");
        assertEquals(expectedSteps, path.size(), "seed " + seed + " committed a path of "
                + path.size() + " steps where the avoiding route to (" + target[0] + ","
                + target[1] + ") costs " + expectedSteps);
        assertTrue(path.size() <= budget, "seed " + seed + " committed " + path.size()
                + " steps on a " + budget + "-pip budget");

        int[] previous = from;
        for (int i = 0; i < path.size(); i++) {
            int[] step = path.get(i);
            assertArrayEquals(previous, new int[]{step[0], step[1]},
                    "seed " + seed + " recorded step " + (i + 1) + " starting at ("
                            + step[0] + "," + step[1] + "), not where the previous step ended");
            assertEquals(1, Math.abs(step[0] - step[2]) + Math.abs(step[1] - step[3]),
                    "seed " + seed + " recorded a step of " + Math.abs(step[0] - step[2]) + "+"
                            + Math.abs(step[1] - step[3]) + " tiles");
            if (i < path.size() - 1) {
                assertFalse(step[2] == CARROT_COL && step[3] == CARROT_ROW,
                        "seed " + seed + " traverses the carrot at step " + (i + 1) + " of "
                                + path.size());
            }
            previous = new int[]{step[2], step[3]};
        }
        assertArrayEquals(target, previous,
                "seed " + seed + " committed a path to (" + target[0] + "," + target[1]
                        + ") that ends at (" + previous[0] + "," + previous[1] + ")");

        boolean carrotTarget = target[0] == CARROT_COL && target[1] == CARROT_ROW;
        for (int i = 0; i < path.size(); i++) {
            int[] step = path.get(i);
            boolean onCarrot = step[2] == CARROT_COL && step[3] == CARROT_ROW;
            if (carrotTarget) {
                assertEquals(i == path.size() - 1, onCarrot,
                        "seed " + seed + " enters the carrot at step " + (i + 1) + " of "
                                + path.size() + ", not on the final step");
            } else {
                assertFalse(onCarrot, "seed " + seed + " traverses the carrot on the way to ("
                        + target[0] + "," + target[1] + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    // Movement and observation helpers.
    // ------------------------------------------------------------------

    /**
     * The offered target closest to {@code goal} by an independent distance map,
     * first wins a tie. The carrot is never a candidate unless it is the goal, so
     * routing beside it cannot accidentally win the match.
     */
    private static int[] targetToward(Match match, int[][] toGoal, int[] goal, String seed) {
        List<int[]> targets = match.legalTargets();
        assertFalse(targets.isEmpty(),
                "seed " + seed + " rolled with no legal target in AwaitMove");
        boolean goalIsCarrot = goal[0] == CARROT_COL && goal[1] == CARROT_ROW;
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] target : targets) {
            if (!goalIsCarrot && target[0] == CARROT_COL && target[1] == CARROT_ROW) {
                continue;
            }
            int distance = toGoal[target[1]][target[0]];
            if (distance >= 0 && distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        if (best == null) {
            best = targets.get(0);
        }
        return best;
    }

    /** A 4-way BFS that never expands the carrot: distances to every tile on a path that avoids it. */
    private static int[][] distancesAvoidingCarrot(Board board, int[] from) {
        int[][] distance = emptyDistances(board);
        Deque<int[]> queue = new ArrayDeque<>();
        distance[from[1]][from[0]] = 0;
        queue.add(from);
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : STEPS) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!board.passable(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                if (col == CARROT_COL && row == CARROT_ROW) {
                    // Terminal only: remember the cost, never walk through it.
                    continue;
                }
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }

    /** A plain 4-way BFS over passable tiles, used only to state the through-carrot cost. */
    private static int[][] distancesAllowingCarrot(Board board, int[] from) {
        int[][] distance = emptyDistances(board);
        Deque<int[]> queue = new ArrayDeque<>();
        distance[from[1]][from[0]] = 0;
        queue.add(from);
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : STEPS) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (board.passable(col, row) && distance[row][col] < 0) {
                    distance[row][col] = distance[cell[1]][cell[0]] + 1;
                    queue.addLast(new int[]{col, row});
                }
            }
        }
        return distance;
    }

    private static int[][] emptyDistances(Board board) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        return distance;
    }

    /** A passable tile at exactly {@code steps} avoiding distance, never the carrot or the other rabbit. */
    private static int[] tileAtDistance(Match match, int[][] distance, int steps) {
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        for (int row = 0; row < match.board().rows(); row++) {
            for (int col = 0; col < match.board().cols(); col++) {
                if (distance[row][col] != steps) {
                    continue;
                }
                if (col == CARROT_COL && row == CARROT_ROW) {
                    continue;
                }
                if (sameCell(new int[]{col, row}, from) || sameCell(new int[]{col, row}, other)) {
                    continue;
                }
                return new int[]{col, row};
            }
        }
        return null;
    }

    /** A legal adjacent step that is not the carrot and not the other rabbit's tile. */
    private static int[] adjacentNonCarrotStep(Match match) {
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        for (int[] delta : STEPS) {
            int col = from[0] + delta[0];
            int row = from[1] + delta[1];
            if (!match.board().passable(col, row)) {
                continue;
            }
            if ((col == CARROT_COL && row == CARROT_ROW) || sameCell(new int[]{col, row}, other)) {
                continue;
            }
            return new int[]{col, row};
        }
        return null;
    }

    private static boolean offered(Match match, int col, int row) {
        for (int[] target : match.legalTargets()) {
            if (target[0] == col && target[1] == row) {
                return true;
            }
        }
        return false;
    }

    /** The {@code legalTargets} cells of the state dump, as coordinate keys. */
    private static Set<String> reportedOffers(Match match) {
        JsonObject state = JsonParser.parseString(Snapshot.of(match)).getAsJsonObject();
        JsonArray targets = state.getAsJsonArray("legalTargets");
        Set<String> cells = new HashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            JsonArray cell = targets.get(i).getAsJsonArray();
            cells.add(key(cell.get(0).getAsInt(), cell.get(1).getAsInt()));
        }
        return cells;
    }

    private static int reportedBudget(Match match) {
        JsonObject state = JsonParser.parseString(Snapshot.of(match)).getAsJsonObject();
        return state.get("budget").getAsInt();
    }

    private static String key(int col, int row) {
        return col + "," + row;
    }

    private static boolean sameCell(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }
}
