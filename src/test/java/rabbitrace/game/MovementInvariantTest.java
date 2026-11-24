package rabbitrace.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Across 200 seeded matches of scripted play,
 * no rabbit ever stands on a blocking tile or on the other rabbit's tile.
 *
 * <p>The script plays the automation's own surfaces: {@link Match#legalTargets()}
 * offers the tiles calls targetable, and the click always takes the target
 * closest to the carrot, so a match finishes instead of wandering. After the
 * opening throw, every roll, every committed move and every hand-off, both
 * positions are read back and checked against passability recomputed from the
 * board's kind characters. Each step a move returns is also checked not to enter
 * a blocking tile. The other rabbit's tile may be traversed by a path but is
 * never a resting position, so the resting positions — not the tiles a
 * multi-tile hop passes over — carry the "never stands on it" half of the movement rule.
 */
class MovementInvariantTest {

    private static final int MATCHES = 200;
    private static final int PLY_LIMIT = 200;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @Test
    void noRabbitStandsOnABlockingTileOrOnTheOtherRabbit() {
        Set<String> seeds = new HashSet<>();
        int moves = 0;
        int steps = 0;
        for (int i = 0; i < MATCHES; i++) {
            String seed = String.format("%06d", i);
            assertTrue(seeds.add(seed), "plays 200 distinct seeds");

            Match match = Match.start(seed);
            assertInvariant(match, seed, "before the opening throw");
            assertEquals(Match.BLUE, match.throwHands(1, 3),
                    "the scripted opening throw must be decisive for seed " + seed);
            assertInvariant(match, seed, "after the opening throw");

            int[][] toCarrot = distancesFromCarrot(match.board());
            int plies = 0;
            while (!Match.MATCH_WON.equals(match.phase())) {
                assertTrue(plies++ < PLY_LIMIT, "seed " + seed + " did not reach the carrot in "
                        + PLY_LIMIT + " turns, so the scripted play never exercised a whole match");
                match.rollDie();
                assertInvariant(match, seed, "after the roll");

                List<int[]> targets = match.legalTargets();
                assertFalse(targets.isEmpty(), "seed " + seed + " has no legal target in AwaitMove");
                int[] target = closestToCarrot(targets, toCarrot);
                List<int[]> path = match.moveTo(target[0], target[1]);
                assertFalse(path.isEmpty(), "the scripted move committed no step");
                for (int[] step : path) {
                    Board board = match.board();
                    assertTrue(board.inside(step[2], step[3]),
                            "seed " + seed + " stepped outside the board to ("
                                    + step[2] + "," + step[3] + ")");
                    assertFalse(board.blocking(step[2], step[3]),
                            "seed " + seed + " stepped onto the blocking tile ("
                                    + step[2] + "," + step[3] + ") '" + board.kindAt(step[2], step[3]) + "'");
                    steps++;
                }
                moves++;
                assertInvariant(match, seed, "after a committed move");

                if (!Match.MATCH_WON.equals(match.phase())) {
                    if (match.budget() == 0) {
                        match.endTurnIfSpent();
                    } else {
                        match.endTurn();
                    }
                    assertInvariant(match, seed, "after the hand-off");
                }
            }
            assertEquals(Match.MATCH_WON, match.phase(),
                    "the scripted match on seed " + seed + " ended outside MatchWon");
            assertNotNull(match.winner(), "seed " + seed + " ended without a winner");
        }

        assertEquals(MATCHES, seeds.size(), "runs on 200 distinct seeds");
        assertTrue(moves >= MATCHES * 2,
                "only " + moves + " moves across 200 matches, so the scripted play is vacuous");
        assertTrue(steps >= MATCHES * 13,
                "only " + steps + " steps across 200 matches; the shortest route is 13 steps,"
                        + " so the matches did not play out");
    }

    /**
     * At one observable state: both rabbits stand on passable tiles and never
     * on the same tile. Passability is recomputed here from {@link Board#kindAt}
     * rather than asked of the movement code whose target selection is under
     * test.
     */
    private static void assertInvariant(Match match, String seed, String when) {
        Board board = match.board();
        int[] blue = match.blue();
        int[] brown = match.brown();
        assertStanding(board, blue, Match.BLUE, seed, when);
        assertStanding(board, brown, Match.BROWN, seed, when);
        assertFalse(Arrays.equals(blue, brown),
                "both rabbits stand on (" + blue[0] + "," + blue[1] + ") " + when + " for seed " + seed);
    }

    private static void assertStanding(Board board, int[] cell, String rabbit, String seed, String when) {
        assertTrue(board.inside(cell[0], cell[1]),
                "" + rabbit + " stands outside the board at (" + cell[0] + "," + cell[1]
                        + ") " + when + " for seed " + seed);
        char kind = board.kindAt(cell[0], cell[1]);
        assertFalse(Board.isBlocking(kind),
                "" + rabbit + " stands on the blocking tile (" + cell[0] + "," + cell[1]
                        + ") '" + kind + "' " + when + " for seed " + seed);
    }

    /** The target with the smallest board distance to the carrot; first wins a tie. */
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
        assertNotNull(best, "no legal target can reach the carrot");
        return best;
    }

    /**
     * A 4-way BFS over the board's passable tiles from the carrot, independent of
     * {@link Match}'s own path finder, so the script's choice of target cannot
     * rely on the code under test to describe the board.
     */
    private static int[][] distancesFromCarrot(Board board) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        distance[BoardGenerator.CARROT_ROW][BoardGenerator.CARROT_COL] = 0;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW});
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] step : STEPS) {
                int col = cell[0] + step[0];
                int row = cell[1] + step[1];
                if (board.passable(col, row) && distance[row][col] < 0) {
                    distance[row][col] = distance[cell[1]][cell[0]] + 1;
                    queue.addLast(new int[]{col, row});
                }
            }
        }
        return distance;
    }
}
