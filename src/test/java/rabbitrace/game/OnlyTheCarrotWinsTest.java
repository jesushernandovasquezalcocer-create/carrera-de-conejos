package rabbitrace.game;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import rabbitrace.automation.Snapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The carrot is the only winning tile, so
 * reaching any tile other than the carrot never enters {@code MatchWon}.
 *
 * <p>Three surfaces are exercised, because a win that fired on any other tile
 * could hide in any of them. The sweep walks seeded matches tile by tile toward
 * every reachable non-carrot square and reads {@code match.phase()} after every
 * committed path: the arrival must leave the match in {@code AwaitMove} with no
 * winner, and the automation {@code state} line must not report {@code MatchWon}
 * either. Because a committed path lands on its intermediate tiles too, one
 * assertion after the commit also catches a win that fired mid-path.
 *
 * <p>The offer probe is the exhaustive half: at each of many turns every target
 * the match offers is replayed from the same seed and committed on a fresh match,
 * so a tile is judged even when the walk would never choose it. A greedier walk
 * cannot accidentally accept a win the probes reject.
 *
 * <p>The last part is the control that keeps the test from passing vacuously: the
 * same matches are driven to the carrot and must enter {@code MatchWon} there.
 * A game that never wins anything would fail it, and a game that wins on grass
 * fails the first two parts.
 */
class OnlyTheCarrotWinsTest {

    private static final int SWEEP_MATCHES = 40;
    private static final int WALK_TURNS = 80;
    private static final int PROBE_MATCHES = 24;
    private static final int PROBE_TURNS = 12;
    private static final int PLY_LIMIT = 600;

    private static final int CARROT_COL = BoardGenerator.CARROT_COL;
    private static final int CARROT_ROW = BoardGenerator.CARROT_ROW;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** Script actions: a roll, a hand-off, or a committed move to a cell. */
    private static final int[] ROLL = {-1, -1};
    private static final int[] END_TURN = {-2, -2};

    @Test
    void reachingAnyTileOtherThanTheCarrotNeverEntersMatchWon() {
        assertEveryNonCarrotArrivalLeavesTheMatchLive();
        assertEveryOfferedGrassTargetLeavesTheMatchLive();
        assertReachingTheCarrotStillWins();
    }

    // ------------------------------------------------------------------
    // The sweep: read the phase after every arrival off the carrot.
    // ------------------------------------------------------------------

    /**
     * Walks seeded matches to as many distinct non-carrot tiles as their rolls
     * allow, asserting after every committed path that the arrival was not a win.
     * Visited tiles are counted against the board's own passable set so the sweep
     * proves coverage rather than merely claiming it.
     */
    private static void assertEveryNonCarrotArrivalLeavesTheMatchLive() {
        int steps = 0;
        int arrivals = 0;
        int completeSweeps = 0;
        int bestCoverage = 0;
        int winsAtTheCarrot = 0;

        for (int i = 0; i < SWEEP_MATCHES; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            assertEquals(Match.BLUE, match.throwHands(1, 3),
                    "the scripted opening throw must be decisive for seed " + seed);

            Board board = match.board();
            boolean[][] visited = new boolean[board.rows()][board.cols()];
            int[] start = match.position(match.active());
            visited[start[1]][start[0]] = true;

            int turns = 0;
            while (turns < WALK_TURNS) {
                if (Match.AWAIT_ROLL.equals(match.phase())) {
                    match.rollDie();
                    turns++;
                }
                int[] target = nearestUnvisitedTarget(match, visited);
                if (target == null) {
                    // Nothing new is within reach: spend the turn walking toward the
                    // nearest unvisited tile so the sweep does not stall on its own trail.
                    target = targetTowardUnvisited(match, visited);
                }
                if (target == null) {
                    if (match.stepsThisTurn() == 0) {
                        // No legal opening move: NoLegalMove path, not a win.
                        break;
                    }
                    endTurn(match);
                    continue;
                }

                List<int[]> path = match.moveTo(target[0], target[1]);
                assertFalse(path.isEmpty(), "seed " + seed
                        + " committed an empty path to (" + target[0] + "," + target[1] + ")");
                assertArrayEquals(target, match.position(match.active()),
                        "seed " + seed + " committed a path to (" + target[0] + ","
                                + target[1] + ") but the rabbit stands at ("
                                + match.position(match.active())[0] + ","
                                + match.position(match.active())[1] + ")");
                assertNotWon(match, seed, target, arrivals);
                arrivals++;

                for (int[] step : path) {
                    assertFalse(isCarrot(step[2], step[3]),
                            "seed " + seed + " stepped onto the carrot during the sweep");
                    visited[step[3]][step[2]] = true;
                    steps++;
                }
                if (match.budget() == 0) {
                    match.endTurnIfSpent();
                }
            }

            int passable = 0;
            int seen = 0;
            for (int row = 0; row < board.rows(); row++) {
                for (int col = 0; col < board.cols(); col++) {
                    if (!board.passable(col, row) || isCarrot(col, row)) {
                        continue;
                    }
                    passable++;
                    if (visited[row][col]) {
                        seen++;
                    }
                }
            }
            if (seen == passable) {
                completeSweeps++;
            }
            bestCoverage = Math.max(bestCoverage, seen);

            if (driveToTheCarrot(match)) {
                winsAtTheCarrot++;
            }
        }

        assertTrue(steps >= SWEEP_MATCHES * 100,
                "only " + steps + " steps across " + SWEEP_MATCHES
                        + " matches, so few non-carrot tiles were reached");
        assertTrue(arrivals >= SWEEP_MATCHES * 30,
                "only " + arrivals + " committed paths ended off the carrot across "
                        + SWEEP_MATCHES + " matches");
        assertTrue(completeSweeps >= 25,
                "only " + completeSweeps + " of " + SWEEP_MATCHES
                        + " sweeps visited every passable non-carrot tile (best seen: "
                        + bestCoverage + "), so the rule was not tried on the whole board");
        assertTrue(winsAtTheCarrot >= SWEEP_MATCHES * 3 / 4,
                "only " + winsAtTheCarrot + " of " + SWEEP_MATCHES
                        + " matches could be driven to the carrot, so a game that never wins would"
                        + " also satisfy the non-carrot assertions");
    }

    /**
     * A greedy walk over unvisited tiles: the closest passable, non-carrot square
     * within the remaining budget that the rabbit has not yet reached. Ties go to
     * the first in row-major order, so the walk is deterministic.
     */
    private static int[] nearestUnvisitedTarget(Match match, boolean[][] visited) {
        Board board = match.board();
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        int budget = match.budget();
        int[][] distance = distancesAvoidingCarrot(board, from);
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (visited[row][col] || isCarrot(col, row) || !board.passable(col, row)) {
                    continue;
                }
                if (col == other[0] && row == other[1]) {
                    continue;
                }
                int steps = distance[row][col];
                if (steps >= 1 && steps <= budget && steps < bestDistance) {
                    best = new int[]{col, row};
                    bestDistance = steps;
                }
            }
        }
        return best;
    }

    /**
     * The legal offer that makes the most progress toward the nearest tile the
     * sweep has not reached, or {@code null} when no offer gets closer. The map is
     * a multi-source BFS seeded on every unvisited passable non-carrot tile.
     */
    private static int[] targetTowardUnvisited(Match match, boolean[][] visited) {
        Board board = match.board();
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        int[][] toGo = distanceToUnvisited(board, visited, other);
        int current = toGo[from[1]][from[0]];
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] offer : match.legalTargets()) {
            if (isCarrot(offer[0], offer[1])) {
                continue;
            }
            int steps = toGo[offer[1]][offer[0]];
            if (steps >= 0 && steps < bestDistance) {
                best = offer;
                bestDistance = steps;
            }
        }
        if (best != null && current >= 0 && bestDistance >= current) {
            return null;
        }
        return best;
    }

    /** Multi-source BFS distances to the nearest unvisited passable non-carrot tile. */
    private static int[][] distanceToUnvisited(Board board, boolean[][] visited, int[] blocked) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        Deque<int[]> queue = new ArrayDeque<>();
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (visited[row][col] || isCarrot(col, row) || !board.passable(col, row)) {
                    continue;
                }
                if (col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                distance[row][col] = 0;
                queue.addLast(new int[]{col, row});
            }
        }
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : STEPS) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!board.passable(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                if (col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }

    // ------------------------------------------------------------------
    // The probe: every offered non-carrot target, replayed from its seed.
    // ------------------------------------------------------------------

    /**
     * At every turn of several seeded matches, commits every non-carrot target the
     * match offers, each on a freshly replayed match. The offer set is the product
     * of the engine, so committing each of its members is the widest statement of
     * "reaching any tile other than the carrot" the automation can make without
     * guessing which tiles a player would click.
     */
    private static void assertEveryOfferedGrassTargetLeavesTheMatchLive() {
        int probes = 0;
        int multiStepProbes = 0;

        for (int i = 0; i < PROBE_MATCHES; i++) {
            String seed = String.format("%06d", i);
            List<int[]> script = new ArrayList<>();
            Match match = Match.start(seed);
            assertEquals(Match.BLUE, match.throwHands(1, 3),
                    "the scripted opening throw must be decisive for seed " + seed);

            int turns = 0;
            while (turns < PROBE_TURNS) {
                if (Match.AWAIT_ROLL.equals(match.phase())) {
                    script.add(ROLL);
                    match.rollDie();
                    turns++;
                }

                List<int[]> offers = new ArrayList<>(match.legalTargets());
                assertFalse(offers.isEmpty(),
                        "seed " + seed + " rolled with no legal target in AwaitMove");

                for (int[] offer : offers) {
                    if (isCarrot(offer[0], offer[1])) {
                        continue;
                    }
                    Probe probe = replayAndCommit(seed, script, offer);
                    assertNotWon(probe.match(), seed, offer, probes);
                    assertArrayEquals(offer, probe.match().position(probe.match().active()),
                            "seed " + seed + " replayed a commit to (" + offer[0] + ","
                                    + offer[1] + ") but the rabbit stands elsewhere");
                    assertFalse(probe.path().isEmpty(),
                            "seed " + seed + " committed an empty path to (" + offer[0]
                                    + "," + offer[1] + ")");
                    int[] last = probe.path().get(probe.path().size() - 1);
                    assertArrayEquals(offer, new int[]{last[2], last[3]},
                            "seed " + seed + " committed a " + probe.path().size()
                                    + "-step path to (" + offer[0] + "," + offer[1]
                                    + ") that ends at (" + last[2] + "," + last[3] + ")");
                    if (probe.path().size() > 1) {
                        multiStepProbes++;
                    }
                    probes++;
                }

                int[] progress = firstGrassOffer(offers);
                if (progress == null) {
                    if (match.stepsThisTurn() == 0) {
                        break;
                    }
                    script.add(END_TURN);
                    endTurn(match);
                    continue;
                }
                script.add(new int[]{progress[0], progress[1]});
                match.moveTo(progress[0], progress[1]);
                if (match.budget() == 0) {
                    script.add(END_TURN);
                    match.endTurnIfSpent();
                }
            }
        }

        assertTrue(probes >= 2000,
                "only " + probes + " offered non-carrot targets were committed, so the"
                        + " rule was barely tried");
        assertTrue(multiStepProbes >= 1000,
                "only " + multiStepProbes + " probes landed more than one step from the"
                        + " rabbit, so mid-path wins would be hard to catch");
    }

    private record Probe(Match match, List<int[]> path) {
    }

    /** Replays a recorded action script on a fresh match, then commits {@code target}. */
    private static Probe replayAndCommit(String seed, List<int[]> script, int[] target) {
        Match match = Match.start(seed);
        assertEquals(Match.BLUE, match.throwHands(1, 3),
                "the replay of seed " + seed + " must start with the scripted throw");
        for (int[] action : script) {
            if (action == ROLL) {
                match.rollDie();
            } else if (action == END_TURN) {
                endTurn(match);
            } else {
                match.moveTo(action[0], action[1]);
            }
        }
        List<int[]> path = match.moveTo(target[0], target[1]);
        return new Probe(match, path);
    }

    /** The first offered tile that is neither the carrot nor already a carrot step. */
    private static int[] firstGrassOffer(List<int[]> offers) {
        for (int[] offer : offers) {
            if (!isCarrot(offer[0], offer[1])) {
                return offer;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The control: the carrot must still win.
    // ------------------------------------------------------------------

    /**
     * Drives seeded matches to the carrot through legal turns. Without this, a
     * game that can never enter {@code MatchWon} would satisfy every non-carrot
     * assertion above; the control fails it.
     */
    private static void assertReachingTheCarrotStillWins() {
        int attempts = 0;
        int wins = 0;
        for (int i = 0; i < SWEEP_MATCHES; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            match.throwHands(1, 3);
            attempts++;
            if (!driveToTheCarrot(match)) {
                continue;
            }
            wins++;
            assertEquals(Match.MATCH_WON, match.phase(),
                    "seed " + seed + " entered the carrot but the phase is "
                            + match.phase());
            assertEquals(Match.MATCH_WON, reportedPhase(match),
                    "seed " + seed + " is MatchWon in the engine but state reports "
                            + reportedPhase(match));
            String winner = match.winner();
            assertNotNull(winner, "seed " + seed + " won without a winner");
            assertArrayEquals(new int[]{CARROT_COL, CARROT_ROW}, match.position(winner),
                    "seed " + seed + " won without standing on the carrot");
        }
        assertTrue(wins >= attempts * 3 / 4,
                "only " + wins + " of " + attempts + " control matches reached the carrot,"
                        + " so the non-carrot assertions are not discriminating");
    }

    /** Walks legal turns toward the carrot; false when a turn has no legal opening move. */
    private static boolean driveToTheCarrot(Match match) {
        int plies = 0;
        while (!Match.MATCH_WON.equals(match.phase())) {
            if (plies++ > PLY_LIMIT) {
                return false;
            }
            if (Match.AWAIT_ROLL.equals(match.phase())) {
                match.rollDie();
            }
            int[] target = stepTowardTheCarrot(match);
            if (target == null) {
                if (match.stepsThisTurn() == 0) {
                    return false;
                }
                endTurn(match);
                continue;
            }
            match.moveTo(target[0], target[1]);
            if (match.budget() == 0 && !Match.MATCH_WON.equals(match.phase())) {
                match.endTurnIfSpent();
            }
        }
        return true;
    }

    /**
     * The offered tile with the smallest independent distance to the carrot, and
     * none at all when every offer costs more than the current tile. The map is a
     * plain BFS from the carrot that never steps on the other rabbit.
     */
    private static int[] stepTowardTheCarrot(Match match) {
        Board board = match.board();
        int[] other = match.position(Match.other(match.active()));
        int[][] distance = distancesFromCarrot(board, other);
        int[] from = match.position(match.active());
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] offer : match.legalTargets()) {
            int value = distance[offer[1]][offer[0]];
            if (value < 0) {
                continue;
            }
            if (best == null || value < bestDistance) {
                best = offer;
                bestDistance = value;
            }
        }
        int current = distance[from[1]][from[0]];
        if (best != null && current >= 0 && bestDistance >= current) {
            return null;
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Movement, observation and distance helpers.
    // ------------------------------------------------------------------

    private static void endTurn(Match match) {
        if (match.budget() != null && match.budget() == 0) {
            match.endTurnIfSpent();
        } else {
            match.endTurn();
        }
    }

    /**
     * The arrival contract: a non-carrot destination leaves the match playable in
     * {@code AwaitMove}, with no winner, and the automation {@code state} line agrees.
     */
    private static void assertNotWon(Match match, String seed, int[] at, int index) {
        assertEquals(Match.AWAIT_MOVE, match.phase(),
                "seed " + seed + " reached (" + at[0] + "," + at[1] + ") off the carrot"
                        + " (arrival " + (index + 1) + ") but the phase is " + match.phase());
        assertNull(match.winner(),
                "seed " + seed + " has winner " + match.winner() + " after reaching ("
                        + at[0] + "," + at[1] + "), which is not the carrot");
        assertNotEquals(Match.MATCH_WON, reportedPhase(match),
                "seed " + seed + " reached (" + at[0] + "," + at[1] + ") off the carrot but"
                        + " state reports MatchWon");
        assertArrayEquals(at, match.position(match.active()),
                "seed " + seed + " is not standing on the tile it just reached");
    }

    private static String reportedPhase(Match match) {
        return JsonParser.parseString(Snapshot.of(match)).getAsJsonObject()
                .get("phase").getAsString();
    }

    /** A 4-way BFS that treats the carrot as terminal only, as the rules require. */
    private static int[][] distancesAvoidingCarrot(Board board, int[] from) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        distance[from[1]][from[0]] = 0;
        Deque<int[]> queue = new ArrayDeque<>();
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
                if (isCarrot(col, row)) {
                    continue;
                }
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }

    /** Plain 4-way BFS from the carrot over passable tiles, never landing on {@code blocked}. */
    private static int[][] distancesFromCarrot(Board board, int[] blocked) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        distance[CARROT_ROW][CARROT_COL] = 0;
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{CARROT_COL, CARROT_ROW});
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : STEPS) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!board.passable(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                if (col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }

    private static boolean isCarrot(int col, int row) {
        return col == CARROT_COL && row == CARROT_ROW;
    }
}
