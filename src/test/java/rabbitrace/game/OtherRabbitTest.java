package rabbitrace.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rabbitrace.automation.Commands;
import rabbitrace.automation.EventLog;
import rabbitrace.automation.Snapshot;
import rabbitrace.settings.Settings;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * the other rabbit's tile is traversable by a
 * path and never occupied, bumped or captured.
 *
 * <p>Traversability is shown on real seeded matches. Every turn compares the
 * offer set with an independent BFS that walks across the other rabbit's tile and
 * stops at the other rabbit only as a destination, so an engine that treated the
 * tile as an obstacle would offer fewer tiles. The walk then prefers a target
 * whose only shortest route crosses that tile — the distance with the tile
 * blocked is longer than the distance with it open — commits that path and reads
 * both rabbits back, so the crossing is forced rather than incidental. The count
 * of such chokepoint crossings is asserted, so a board that never needed one
 * cannot make this vacuous.
 *
 * <p>Non-occupation is read from the four input surfaces: the tile is not
 * targetable, it is absent from {@code legalTargets} and from the state dump, a
 * click on it is ignored, a step key toward it (when adjacent) is ignored, and a
 * direct {@code moveTo} throws — each leaving both rabbits, the budget and the
 * phase exactly as before. After every crossing the other rabbit still stands on
 * the tile the path crossed, so it was neither pushed, consumed nor replaced.
 */
class OtherRabbitTest {

    private static final int SEEDS = 150;
    private static final int PLY_LIMIT = 300;
    private static final int[][] NESW = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final int CARROT_COL = BoardGenerator.CARROT_COL;
    private static final int CARROT_ROW = BoardGenerator.CARROT_ROW;

    @TempDir
    Path logs;

    @Test
    void theOtherRabbitsTileIsTraversableAndNeverOccupiedBumpedOrCaptured() throws Exception {
        assertPathsCrossTheOtherRabbit();
        assertTheOtherRabbitIsNeverAnOutcome();
    }

    // ------------------------------------------------------------------
    // Traversable by a path.
    // ------------------------------------------------------------------

    private void assertPathsCrossTheOtherRabbit() {
        int turns = 0;
        int requiredCrossings = 0;
        int crossingCommits = 0;
        int seedsWithCrossing = 0;

        for (int index = 0; index < SEEDS; index++) {
            String seed = String.format("%06d", index);
            Match match = Match.start(seed);
            assertEquals(Match.BLUE, match.throwHands(1, 3),
                    "the scripted opening throw must be decisive for seed " + seed);

            int[][] toCarrot = distancesToCarrot(match.board());
            boolean seedCrossed = false;
            int plies = 0;
            while (!Match.MATCH_WON.equals(match.phase())) {
                assertTrue(plies++ < PLY_LIMIT, "seed " + seed + " did not finish within "
                        + PLY_LIMIT + " turns, so the walk never confronted the other rabbit");
                match.rollDie();
                turns++;

                Board board = match.board();
                String activeName = match.active();
                String otherName = Match.other(activeName);
                int[] from = match.position(activeName);
                int[] other = match.position(otherName);
                int budget = match.budget();

                // the tile is not an obstacle. An independent BFS that
                // expands it describes exactly the tiles the engine may offer.
                int[][] across = reach(board, from, null);
                assertOffersMatchTheIndependentReach(match, across, seed);
                assertOtherTileIsNotOnOffer(match, other, seed);
                assertFalse(match.targetable(other[0], other[1]),
                        "seed " + seed + " calls the other rabbit's tile ("
                                + other[0] + "," + other[1] + ") targetable");

                int[][] avoid = reach(board, from, other);
                int[] target = null;
                for (int[] candidate : match.legalTargets()) {
                    int cross = across[candidate[1]][candidate[0]];
                    int around = avoid[candidate[1]][candidate[0]];
                    // The shortest route with the tile blocked is longer, so every
                    // shortest route to this target passes over the other rabbit.
                    if (cross >= 1 && cross <= budget && (around < 0 || around > cross)) {
                        target = candidate;
                        break;
                    }
                }
                List<int[]> path;
                boolean required = target != null;
                if (target == null) {
                    target = crossingTarget(board, from, other, match.legalTargets());
                }
                if (target == null) {
                    target = closestToCarrot(match.legalTargets(), toCarrot);
                }
                assertNotNull(target, "seed " + seed + " rolled with no legal target");

                List<int[]> expected = shortestNesw(board, from, other, target[0], target[1]);
                assertNotNull(expected, "seed " + seed + " offers (" + target[0] + ","
                        + target[1] + ") with no shortest path in the independent reference");
                int[] otherBefore = match.position(otherName);
                int stepsBefore = match.stepsThisTurn();

                path = match.moveTo(target[0], target[1]);
                assertPathEquals(expected, path, seed, target);
                assertEquals(stepsBefore + path.size(), match.stepsThisTurn(),
                        "seed " + seed + " counted a different number of steps than the"
                                + " committed path");
                assertEquals(budget - path.size(), match.budget() == null ? 0 : match.budget().intValue(),
                        "seed " + seed + " spent a different budget than the committed path");

                assertArrayEquals(otherBefore, match.position(otherName),
                        "seed " + seed + " moved, bumped or captured the other rabbit; it"
                                + " stood on (" + otherBefore[0] + "," + otherBefore[1] + ")");
                assertFalse(Arrays.equals(match.position(activeName), match.position(otherName)),
                        "seed " + seed + " ended the move on the other rabbit's tile");

                int crossed = crossedIndex(path, otherBefore);
                if (crossed >= 0) {
                    crossingCommits++;
                    seedCrossed = true;
                    assertTrue(crossed < path.size() - 1,
                            "seed " + seed + " occupied the other rabbit's tile ("
                                    + otherBefore[0] + "," + otherBefore[1] + ") as the last step");
                    if (required) {
                        requiredCrossings++;
                    }
                }
                if (required) {
                    assertTrue(crossed >= 0, "seed " + seed + " says every shortest route to ("
                            + target[0] + "," + target[1] + ") crosses ("
                            + otherBefore[0] + "," + otherBefore[1] + ") but the committed path did not");
                }

                boolean carrotTarget = target[0] == CARROT_COL && target[1] == CARROT_ROW;
                if (carrotTarget) {
                    assertEquals(Match.MATCH_WON, match.phase(),
                            "seed " + seed + " entered the carrot without winning");
                } else {
                    assertFalse(Match.MATCH_WON.equals(match.phase()),
                            "seed " + seed + " won on a tile that is not the carrot");
                    assertNull(match.winner(), "seed " + seed + " has a winner before the carrot");
                    if (match.budget() == 0) {
                        match.endTurnIfSpent();
                    } else {
                        match.endTurn();
                    }
                    assertEquals(Match.AWAIT_ROLL, match.phase(),
                            "seed " + seed + " did not hand off to AwaitRoll");
                }
            }
            if (seedCrossed) {
                seedsWithCrossing++;
            }
        }

        assertTrue(requiredCrossings >= 10, "only " + requiredCrossings + " of " + turns
                + " turns needed a path through the other rabbit, so the traversal rule was barely"
                + " confronted");
        assertTrue(crossingCommits >= 30, "only " + crossingCommits + " committed paths over "
                + turns + " turns crossed the other rabbit, so traversability is close to vacuous");
        assertTrue(seedsWithCrossing >= 5, "only " + seedsWithCrossing + " of " + SEEDS
                + " seeds crossed the other rabbit at all");
    }

    /** The first target whose independent shortest path steps over {@code other}, or null. */
    private static int[] crossingTarget(Board board, int[] from, int[] other, List<int[]> targets) {
        for (int[] target : targets) {
            List<int[]> path = shortestNesw(board, from, other, target[0], target[1]);
            if (path != null && crossedIndex(path, other) >= 0) {
                return target;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Never occupied, bumped or captured.
    // ------------------------------------------------------------------

    private void assertTheOtherRabbitIsNeverAnOutcome() throws Exception {
        int checked = 0;
        for (int index = 0; index < 60 && checked < 10; index++) {
            String seed = String.format("%06d", index);
            Match match = Match.start(seed);
            Path logFile = logs.resolve("other-" + seed + ".jsonl");
            try (EventLog log = EventLog.open(logFile.toString())) {
                Commands commands = new Commands(match, Settings.defaults(), log, () -> {});
                assertTrue(commands.handle("rps 1 3").startsWith("ok"),
                        "seed " + seed + " could not play the opening throw");
                assertTrue(commands.handle("press tirar").startsWith("ok"),
                        "seed " + seed + " could not roll");
                assertEquals(Match.AWAIT_MOVE, match.phase(),
                        "seed " + seed + " is not in AwaitMove after its roll");

                String activeName = match.active();
                String otherName = Match.other(activeName);
                int[] from = match.position(activeName);
                int[] other = match.position(otherName);
                int budgetBefore = match.budget();
                String before = Snapshot.of(match);

                assertFalse(match.targetable(other[0], other[1]),
                        "seed " + seed + " calls the other rabbit's tile targetable");
                assertFalse(offered(match, other[0], other[1]),
                        "seed " + seed + " offers the other rabbit's tile as a target");
                assertFalse(reportedOffers(match).contains(key(other[0], other[1])),
                        "seed " + seed + " hides the other rabbit's tile in the engine but"
                                + " state offers it");

                assertThrows(IllegalArgumentException.class,
                        () -> match.moveTo(other[0], other[1]),
                        "seed " + seed + " accepted a path onto the other rabbit's tile");
                assertUnchanged(match, before, seed, "a refused move onto the other rabbit");
                assertArrayEquals(other, match.position(otherName),
                        "seed " + seed + " bumped the other rabbit with a refused move");

                assertEquals("ok", commands.handle("tile " + other[0] + " " + other[1]),
                        "seed " + seed + " refused the click on the other rabbit instead of"
                                + " ignoring it");
                assertEquals(before, Snapshot.of(match),
                        "seed " + seed + " changed state on a click on the other rabbit");
                Commands.Click click = commands.lastClick();
                assertEquals(other[0], click.col(),
                        "seed " + seed + " did not resolve the click to the other rabbit");
                assertEquals(other[1], click.row(),
                        "seed " + seed + " did not resolve the click to the other rabbit");
                assertArrayEquals(other, match.position(otherName),
                        "seed " + seed + " moved the other rabbit with a click on it");
                assertArrayEquals(from, match.position(activeName),
                        "seed " + seed + " moved onto the other rabbit's tile with a click");

                if (adjacent(from, other)) {
                    String chord = chordToward(from, other);
                    assertEquals("ok", commands.handle("key " + chord),
                            "seed " + seed + " refused the step key toward the other rabbit"
                                    + " instead of ignoring it");
                    assertEquals(before, Snapshot.of(match),
                            "seed " + seed + " changed state on a step key toward the"
                                    + " other rabbit");
                    assertArrayEquals(other, match.position(otherName),
                            "seed " + seed + " bumped the other rabbit with a step key");
                }

                assertEquals(budgetBefore, match.budget(),
                        "seed " + seed + " spent budget on an ignored input");
                assertNull(match.winner(), "seed " + seed + " captured the other rabbit");
                assertEquals(0, match.stepsThisTurn(),
                        "seed " + seed + " counted a step for an ignored input");
                checked++;
            }
        }
        assertTrue(checked >= 10,
                "only " + checked + " matches reached a live AwaitMove to probe the other"
                        + " rabbit's tile");
    }

    private static void assertUnchanged(Match match, String before, String seed, String what) {
        assertEquals(before, Snapshot.of(match),
                "seed " + seed + " changed state on " + what);
    }

    // ------------------------------------------------------------------
    // The offer set against an independent reach.
    // ------------------------------------------------------------------

    private static void assertOtherTileIsNotOnOffer(Match match, int[] other, String seed) {
        assertFalse(offered(match, other[0], other[1]),
                "seed " + seed + " offers the other rabbit's tile ("
                        + other[0] + "," + other[1] + ") although it cannot be occupied");
        assertFalse(reportedOffers(match).contains(key(other[0], other[1])),
                "seed " + seed + " offers the other rabbit's tile in state");
    }

    /**
     * The engine's offers must be exactly the tiles the independent BFS reaches
     * within the budget, excluding the active rabbit's own tile and the other
     * rabbit's tile. The BFS walks over the other rabbit's tile, so an engine that
     * blocked it would both withhold tiles beyond it and fail here.
     */
    private static void assertOffersMatchTheIndependentReach(Match match, int[][] across, String seed) {
        Board board = match.board();
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        int budget = match.budget();
        Set<String> expected = new HashSet<>();
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (!board.passable(col, row) || sameCell(col, row, from) || sameCell(col, row, other)) {
                    continue;
                }
                if (across[row][col] >= 1 && across[row][col] <= budget) {
                    expected.add(key(col, row));
                }
            }
        }
        assertEquals(expected, offersOf(match),
                "seed " + seed + " offers a different set of tiles than the " + budget
                        + "-pip reach that may cross the other rabbit");
    }

    // ------------------------------------------------------------------
    // The independent path reference, east of the engine.
    // ------------------------------------------------------------------

    /**
     * The lexicographic first shortest path: a BFS from the goal gives every
     * tile's distance, then each step takes the first N, E, S, W neighbour one
     * closer. The carrot is terminal unless it is the goal; the other rabbit's
     * tile is passable in the middle and refused as a destination.
     */
    private static List<int[]> shortestNesw(Board board, int[] from, int[] other, int goalCol, int goalRow) {
        if (!board.inside(goalCol, goalRow) || board.blocking(goalCol, goalRow)) {
            return null;
        }
        if ((goalCol == from[0] && goalRow == from[1])
                || (goalCol == other[0] && goalRow == other[1])) {
            return null;
        }
        int[][] distance = distancesFrom(board, goalCol, goalRow);
        if (distance[from[1]][from[0]] < 0) {
            return null;
        }
        List<int[]> path = new ArrayList<>();
        int[] at = from.clone();
        int remaining = distance[at[1]][at[0]];
        while (remaining > 0) {
            int[] next = null;
            for (int[] delta : NESW) {
                int col = at[0] + delta[0];
                int row = at[1] + delta[1];
                if (!board.inside(col, row) || board.blocking(col, row)) {
                    continue;
                }
                if (distance[row][col] == remaining - 1) {
                    next = new int[]{col, row};
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            path.add(new int[]{at[0], at[1], next[0], next[1]});
            at = next;
            remaining--;
        }
        return path;
    }

    /** Distances from a goal over passable tiles, the carrot terminal when it is not the goal. */
    private static int[][] distancesFrom(Board board, int goalCol, int goalRow) {
        boolean goalIsCarrot = goalCol == CARROT_COL && goalRow == CARROT_ROW;
        int[][] distance = emptyDistances(board);
        Deque<int[]> queue = new ArrayDeque<>();
        distance[goalRow][goalCol] = 0;
        queue.add(new int[]{goalCol, goalRow});
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : NESW) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!board.inside(col, row) || board.blocking(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                if (!goalIsCarrot && col == CARROT_COL && row == CARROT_ROW) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }

    /**
     * Distances from {@code from} over passable tiles; {@code blocked} is a tile
     * the search may not enter (the other rabbit, when judging whether a route
     * avoids it). The carrot records a distance but is never expanded.
     */
    private static int[][] reach(Board board, int[] from, int[] blocked) {
        int[][] distance = emptyDistances(board);
        Deque<int[]> queue = new ArrayDeque<>();
        distance[from[1]][from[0]] = 0;
        queue.add(from);
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : NESW) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!board.inside(col, row) || board.blocking(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                if (blocked != null && col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                if (col == CARROT_COL && row == CARROT_ROW) {
                    continue;
                }
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }

    private static int[][] distancesToCarrot(Board board) {
        int[][] distance = emptyDistances(board);
        Deque<int[]> queue = new ArrayDeque<>();
        distance[CARROT_ROW][CARROT_COL] = 0;
        queue.add(new int[]{CARROT_COL, CARROT_ROW});
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : NESW) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!board.inside(col, row) || board.blocking(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.addLast(new int[]{col, row});
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

    // ------------------------------------------------------------------
    // Small reads.
    // ------------------------------------------------------------------

    /** The index of the intermediate step that lands on {@code other}, or -1. */
    private static int crossedIndex(List<int[]> path, int[] other) {
        for (int i = 0; i < path.size() - 1; i++) {
            int[] step = path.get(i);
            if (step[2] == other[0] && step[3] == other[1]) {
                return i;
            }
        }
        return -1;
    }

    private static void assertPathEquals(List<int[]> expected, List<int[]> actual, String seed, int[] target) {
        assertEquals(expected.size(), actual.size(),
                "seed " + seed + " committed " + actual.size() + " steps to ("
                        + target[0] + "," + target[1] + "); the independent shortest path costs "
                        + expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i),
                    "seed " + seed + " committed step " + (i + 1) + " of the path to ("
                            + target[0] + "," + target[1] + ") as " + Arrays.toString(actual.get(i))
                            + "; the independent path says " + Arrays.toString(expected.get(i)));
        }
    }

    private static int[] closestToCarrot(List<int[]> targets, int[][] toCarrot) {
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] target : targets) {
            int distance = toCarrot[target[1]][target[0]];
            if (distance >= 0 && distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean offered(Match match, int col, int row) {
        for (int[] target : match.legalTargets()) {
            if (target[0] == col && target[1] == row) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> offersOf(Match match) {
        Set<String> cells = new HashSet<>();
        for (int[] target : match.legalTargets()) {
            cells.add(key(target[0], target[1]));
        }
        return cells;
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

    /** The movement chord from {@code from} to the adjacent {@code to}. */
    private static String chordToward(int[] from, int[] to) {
        int dc = to[0] - from[0];
        int dr = to[1] - from[1];
        if (dc == 0 && dr == -1) {
            return "W";
        }
        if (dc == 1 && dr == 0) {
            return "D";
        }
        if (dc == 0 && dr == 1) {
            return "S";
        }
        if (dc == -1 && dr == 0) {
            return "A";
        }
        throw new IllegalArgumentException("not an orthogonal neighbour");
    }

    private static boolean adjacent(int[] from, int[] to) {
        return Math.abs(from[0] - to[0]) + Math.abs(from[1] - to[1]) == 1;
    }

    private static boolean sameCell(int col, int row, int[] cell) {
        return col == cell[0] && row == cell[1];
    }

    private static String key(int col, int row) {
        return col + "," + row;
    }
}
