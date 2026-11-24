package rabbitrace.game;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import rabbitrace.automation.Snapshot;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match enters {@code MatchWon} exactly on
 * the step that enters the carrot, and at no other moment.
 *
 * <p>Both halves of the sentence are made observable. The first half walks 200
 * seeded matches one orthogonal step per turn, so every step before the carrot is
 * a separate call whose returned phase can be read: the roll, the hand-off, a step
 * onto grass and a step that spends the last pip must all leave the phase outside
 * {@code MatchWon}; only the step whose destination is the carrot may win, and it
 * must win immediately — with the leftover budget still in hand on any roll above
 * one — rather than at the end of the turn. The second half commits a multi-step
 * path to the carrot and checks that the phase flips around that single commit
 * and that only its last step enters the carrot, never an intermediate one.
 *
 * <p>The win is also read back through the automation {@code state} line
 * ({@link Snapshot#of(Match)}), because that is the phase a scripted session sees;
 * and every state action is refused after the win, so no later moment can move
 * the phase again.
 */
class MatchWonTest {

    private static final int MATCHES = 200;
    private static final int PLY_LIMIT = 200;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @Test
    void theMatchEndsExactlyOnTheStepThatEntersTheCarrot() {
        assertNoStepBeforeTheCarrotWins();
        assertSpendingTheBudgetOnGrassDoesNotWin();
        assertAMultiStepCommitWinsOnItsFinalStepOnly();
        assertAWinEndsTheMatchForGood();
    }

    /**
     * The per-step half. One step per turn, so {@code match.phase()} is read after
     * every individual step: every destination other than the carrot must leave the
     * phase in {@code AwaitMove} (or {@code AwaitRoll} after the hand-off), and the
     * destination that is the carrot must be {@code MatchWon} in the same call that
     * moved the rabbit. A win that waited for the end of the turn, or that fired on
     * reaching any tile, fails here on the first turn it happens.
     */
    private static void assertNoStepBeforeTheCarrotWins() {
        int wins = 0;
        int winsBeforeTheBudgetRanOut = 0;
        int steps = 0;
        for (int i = 0; i < MATCHES; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            assertEquals(Match.BLUE, match.throwHands(1, 3),
                    "the scripted opening throw must be decisive for seed " + seed);
            assertNotWon(match, "after the opening throw", seed);

            int plies = 0;
            int winsBefore = wins;
            while (!Match.MATCH_WON.equals(match.phase())) {
                assertTrue(plies++ < PLY_LIMIT, "seed " + seed + " did not reach the carrot in "
                        + PLY_LIMIT + " turns, so the scripted play never reached a winning step");
                int roll = match.rollDie();
                assertEquals(Match.AWAIT_MOVE, match.phase(),
                        "seed " + seed + " rolled " + roll + " but left AwaitRoll");
                assertNotWon(match, "after the roll", seed);

                String mover = match.active();
                int[] from = match.position(mover);
                int[] next = nextStepTowardCarrot(match, seed);
                assertEquals(1, manhattan(from, next),
                        "seed " + seed + " can only step to an orthogonal neighbour, not from ("
                                + from[0] + "," + from[1] + ") to (" + next[0] + "," + next[1] + ")");
                int budgetBefore = match.budget();

                List<int[]> path = match.moveTo(next[0], next[1]);
                assertEquals(1, path.size(),
                        "seed " + seed + " clicked one tile but committed " + path.size() + " steps");
                assertArrayEquals(new int[]{from[0], from[1], next[0], next[1]}, path.get(0),
                        "seed " + seed + " recorded a step other than the one it took");
                steps++;

                if (isCarrot(next)) {
                    wins++;
                    assertEquals(Match.MATCH_WON, match.phase(),
                            "seed " + seed + " entered the carrot at (" + next[0] + "," + next[1]
                                    + ") but the phase is " + match.phase() + ", not MatchWon");
                    assertEquals(mover, match.winner(),
                            "seed " + seed + " ended with winner " + match.winner()
                                    + " instead of the rabbit that entered the carrot, " + mover);
                    assertArrayEquals(carrot(), match.position(mover),
                            "seed " + seed + " won without standing on the carrot");
                    assertEquals(Match.MATCH_WON, reportedPhase(match),
                            "seed " + seed + " is MatchWon in the engine but state reports "
                                    + reportedPhase(match));
                    if (budgetBefore > 1) {
                        winsBeforeTheBudgetRanOut++;
                    }
                } else {
                    assertNotWon(match, "after stepping onto (" + next[0] + "," + next[1] + ")", seed);
                    assertNull(match.winner(),
                            "seed " + seed + " has a winner without having entered the carrot");
                    assertEquals(budgetBefore - 1, match.budget().intValue(),
                            "seed " + seed + " moved one tile but spent "
                                    + (budgetBefore - match.budget()) + " of its " + budgetBefore
                                    + " budget");

                    if (match.budget() == 0) {
                        // Spending the last pip is not a winning event. The turn may
                        // hand off, but the phase must not pass through MatchWon.
                        assertEquals(Match.AWAIT_MOVE, match.phase(),
                                "seed " + seed + " at budget 0 is " + match.phase()
                                        + ", not AwaitMove");
                        match.endTurnIfSpent();
                    } else {
                        match.endTurn();
                    }
                    assertEquals(Match.AWAIT_ROLL, match.phase(),
                            "seed " + seed + " did not hand off to AwaitRoll");
                    assertNotWon(match, "after the hand-off", seed);
                }
            }
            assertEquals(winsBefore + 1, wins,
                    "seed " + seed + " left MatchWon without a step into the carrot");
            assertEquals(Match.MATCH_WON, match.phase(),
                    "seed " + seed + " ended the script outside MatchWon");
        }

        assertEquals(MATCHES, wins, "the script produced " + wins + " wins across 200 matches");
        assertTrue(steps >= MATCHES * 13,
                "only " + steps + " steps across 200 matches; the shortest route is 13 steps,"
                        + " so the scripted play did not walk to the carrot");
        assertTrue(winsBeforeTheBudgetRanOut >= MATCHES * 2 / 3,
                "only " + winsBeforeTheBudgetRanOut + " of " + MATCHES
                        + " wins happened while the rabbit still had budget before its final step,"
                        + " so the win waited for the budget rather than happening on the step into"
                        + " the carrot");
    }

    /**
     * At budget 0 on a non-carrot tile the match is still in {@code AwaitMove}: the
     * phase does not become {@code MatchWon} when the pips run out, and the
     * automatic hand-off does not either.
     */
    private static void assertSpendingTheBudgetOnGrassDoesNotWin() {
        int checked = 0;
        for (int i = 0; i < 100 && checked < 10; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            match.throwHands(1, 3);
            int roll = match.rollDie();
            if (roll < 2) {
                continue;
            }
            while (match.budget() > 0) {
                int[] next = stepAwayFromCarrot(match);
                if (next == null) {
                    break;
                }
                match.moveTo(next[0], next[1]);
                assertNotWon(match, "after walking onto (" + next[0] + "," + next[1] + ")", seed);
                assertNull(match.winner(),
                        "seed " + seed + " has a winner before entering the carrot");
            }
            if (match.budget() != 0) {
                continue;
            }
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "seed " + seed + " at budget 0 is " + match.phase() + ", not AwaitMove");
            assertNotWon(match, "at budget 0 on a non-carrot tile", seed);
            match.endTurnIfSpent();
            assertNotWon(match, "after the automatic hand-off", seed);
            assertEquals(Match.AWAIT_ROLL, match.phase(),
                    "seed " + seed + " did not hand off after spending its budget");
            checked++;
        }
        assertTrue(checked >= 5,
                "only " + checked + " matches spent a whole budget on non-carrot tiles, so the"
                        + " budget-exhaustion moment is barely exercised");
    }

    /**
     * The multi-step half. A click that commits two or more steps reaches the
     * carrot only on its last step, and the phase was not {@code MatchWon} before
     * the click and is {@code MatchWon} after it. No earlier step of the committed
     * path may be the carrot.
     */
    private static void assertAMultiStepCommitWinsOnItsFinalStepOnly() {
        Match match = freshMatchWhereTheCarrotCostsBetween(2, 6);
        String mover = match.active();
        int[] from = match.position(mover);
        int budget = match.budget();
        assertNotWon(match, "before a multi-step commit", match.board().seed());
        assertNull(match.winner(), "a winning commit starts with a winner already set");

        List<int[]> path = match.moveTo(BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW);
        assertTrue(path.size() >= 2,
                "the chosen match committed only " + path.size() + " step to the carrot");
        assertTrue(path.size() <= budget,
                "a " + path.size() + "-step commit was accepted on a " + budget + "-pip budget");
        assertArrayEquals(new int[]{from[0], from[1], path.get(0)[2], path.get(0)[3]}, path.get(0),
                "the committed path does not start at the rabbit");
        int[] last = path.get(path.size() - 1);
        assertArrayEquals(new int[]{last[2], last[3]}, carrot(),
                "the committed path to the carrot ends at (" + last[2] + "," + last[3] + ")");
        for (int i = 0; i < path.size() - 1; i++) {
            assertFalse(isCarrot(new int[]{path.get(i)[2], path.get(i)[3]}),
                    "step " + (i + 1) + " of the path enters the carrot before the last step");
        }

        assertEquals(Match.MATCH_WON, match.phase(),
                "the winning commit left the phase in " + match.phase());
        assertEquals(mover, match.winner(),
                "the winner is " + match.winner() + ", not the rabbit that made the winning step");
        assertArrayEquals(carrot(), match.position(mover),
                "the winner does not stand on the carrot");
        assertEquals(Match.MATCH_WON, reportedPhase(match),
                "the engine is MatchWon but state reports " + reportedPhase(match));
    }

    /** Once won, no state action moves the phase or the winner. */
    private static void assertAWinEndsTheMatchForGood() {
        Match match = freshMatchWhereTheCarrotCostsBetween(1, 6);
        String mover = match.active();
        match.moveTo(BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW);
        assertEquals(Match.MATCH_WON, match.phase(),
                "the match did not end on the step into the carrot");

        int[] stand = match.position(mover);
        assertThrows(IllegalStateException.class,
                () -> match.moveTo(stand[0], stand[1]),
                "movement is still accepted after the match was won");
        assertThrows(IllegalStateException.class, match::rollDie,
                "the die is still rolled after the match was won");
        assertThrows(IllegalStateException.class, match::endTurn,
                "a turn is still ended after the match was won");
        match.endTurnIfSpent();
        assertEquals(Match.MATCH_WON, match.phase(),
                "the automatic hand-off moved the phase out of MatchWon");
        assertEquals(mover, match.winner(),
                "the winner changed after the match was won");
        assertEquals(Match.MATCH_WON, reportedPhase(match),
                "state stopped reporting MatchWon after the win");

        // A second match on the same board must still start outside MatchWon:
        // RpsSelect is not a won state, even though the previous match ended.
        String seed = match.board().seed();
        Match next = Match.start(seed);
        assertNotWon(next, "on a fresh match of the same seed", seed);
        assertEquals(Match.RPS_SELECT, next.phase(),
                "a fresh match opened in " + next.phase() + ", not RpsSelect");
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private static void assertNotWon(Match match, String when, String seed) {
        assertFalse(Match.MATCH_WON.equals(match.phase()),
                "seed " + seed + " is MatchWon " + when + ", before entering the carrot");
        assertNull(match.winner(),
                "seed " + seed + " has winner " + match.winner() + " " + when
                        + ", before entering the carrot");
    }

    private static String reportedPhase(Match match) {
        return JsonParser.parseString(Snapshot.of(match)).getAsJsonObject().get("phase").getAsString();
    }

    private static int[] carrot() {
        return new int[]{BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW};
    }

    private static boolean isCarrot(int[] cell) {
        return cell[0] == BoardGenerator.CARROT_COL && cell[1] == BoardGenerator.CARROT_ROW;
    }

    private static boolean isCarrot(int col, int row) {
        return col == BoardGenerator.CARROT_COL && row == BoardGenerator.CARROT_ROW;
    }

    private static int manhattan(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }

    /**
     * The first step on the shortest route from the active rabbit to the carrot,
     * computed independently of {@link Match}: a 4-way BFS over passable tiles that
     * treats the other rabbit's tile as unlandable and the carrot as terminal. When
     * the other rabbit happens to cut the only route, any legal adjacent step is
     * taken instead; the other rabbit moves on its own turn, so the route reopens.
     */
    private static int[] nextStepTowardCarrot(Match match, String seed) {
        int[] blocked = match.position(Match.other(match.active()));
        int cols = match.board().cols();
        int rows = match.board().rows();
        int[] start = match.position(match.active());
        int[][] previous = new int[rows * cols][];
        boolean[][] seen = new boolean[rows][cols];
        Deque<int[]> queue = new ArrayDeque<>();
        seen[start[1]][start[0]] = true;
        queue.add(start);
        boolean found = false;
        while (!queue.isEmpty() && !found) {
            int[] cell = queue.removeFirst();
            for (int[] delta : STEPS) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!match.board().passable(col, row) || seen[row][col]) {
                    continue;
                }
                if (col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                seen[row][col] = true;
                previous[row * cols + col] = cell;
                if (isCarrot(col, row)) {
                    found = true;
                    break;
                }
                queue.addLast(new int[]{col, row});
            }
        }
        if (found) {
            LinkedList<int[]> route = new LinkedList<>();
            int[] walk = carrot();
            while (walk[0] != start[0] || walk[1] != start[1]) {
                route.addFirst(walk);
                walk = previous[walk[1] * cols + walk[0]];
            }
            return route.get(0);
        }
        int[] fallback = legalAdjacentStep(match);
        assertNotNull(fallback, "seed " + seed
                + " has no step available, which say a generated board cannot produce");
        return fallback;
    }

    /** Any adjacent passable tile that is not the other rabbit's tile. */
    private static int[] legalAdjacentStep(Match match) {
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        for (int[] delta : STEPS) {
            int col = from[0] + delta[0];
            int row = from[1] + delta[1];
            if (!match.board().passable(col, row)) {
                continue;
            }
            if (col == other[0] && row == other[1]) {
                continue;
            }
            return new int[]{col, row};
        }
        return null;
    }

    /**
     * An adjacent step that moves away from the carrot: the passable neighbour with
     * the greatest independent distance to the carrot, never the carrot itself and
     * never the other rabbit's tile. Growing the distance keeps the test's
     * exhaustion walk from accidentally winning.
     */
    private static int[] stepAwayFromCarrot(Match match) {
        int[][] toCarrot = distancesFromCarrot(match.board());
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        int[] best = null;
        int bestDistance = -1;
        for (int[] delta : STEPS) {
            int col = from[0] + delta[0];
            int row = from[1] + delta[1];
            if (!match.board().passable(col, row) || isCarrot(col, row)) {
                continue;
            }
            if (col == other[0] && row == other[1]) {
                continue;
            }
            int distance = toCarrot[row][col];
            if (distance > bestDistance) {
                best = new int[]{col, row};
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * A match in {@code AwaitMove} whose current roll can commit a path to the
     * carrot costing between {@code minimum} and {@code maximum} steps. The route is
     * measured by an independent BFS that avoids the other rabbit's tile, so the
     * budget check here is not the engine's own path finder describing itself.
     */
    private static Match freshMatchWhereTheCarrotCostsBetween(int minimum, int maximum) {
        for (int i = 0; i < MATCHES; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            match.throwHands(1, 3);
            int plies = 0;
            while (!Match.MATCH_WON.equals(match.phase()) && plies++ < PLY_LIMIT) {
                int roll = match.rollDie();
                int distance = distanceToCarrot(match);
                if (distance >= minimum && distance <= maximum && distance <= roll) {
                    return match;
                }
                int[] next = nextStepTowardCarrot(match, seed);
                match.moveTo(next[0], next[1]);
                if (!Match.MATCH_WON.equals(match.phase())) {
                    if (match.budget() == 0) {
                        match.endTurnIfSpent();
                    } else {
                        match.endTurn();
                    }
                }
            }
        }
        throw new AssertionError("no seed in 000000.." + (MATCHES - 1) + " offered a carrot"
                + " between " + minimum + " and " + maximum + " steps away within the roll");
    }

    /** The length of the shortest path to the carrot avoiding the other rabbit, or -1. */
    private static int distanceToCarrot(Match match) {
        int[] blocked = match.position(Match.other(match.active()));
        int[] start = match.position(match.active());
        int cols = match.board().cols();
        int rows = match.board().rows();
        int[][] distance = new int[rows][cols];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        distance[start[1]][start[0]] = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : STEPS) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!match.board().passable(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                if (col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                if (isCarrot(col, row)) {
                    return distance[row][col];
                }
                queue.addLast(new int[]{col, row});
            }
        }
        return -1;
    }

    /** Independent 4-way BFS distances from the carrot over passable tiles. */
    private static int[][] distancesFromCarrot(Board board) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        distance[BoardGenerator.CARROT_ROW][BoardGenerator.CARROT_COL] = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(carrot());
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
}
