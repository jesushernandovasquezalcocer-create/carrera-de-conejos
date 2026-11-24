package rabbitrace.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The k-th roll of a match is a
 * pure function of the match seed and k, so two matches on one seed share the
 * die sequence however their turns were played.
 *
 * <p>Three surfaces, because purity can break in three different ways. The
 * <em>move-order</em> part plays two matches on each seed: one walks toward the
 * carrot and wins, the other walks away from it. Their committed tiles differ,
 * so a die drawn from the board cursor, from the step count or from any other
 * state that moves with the rabbit fails at the first divergent turn. The
 * <em>interleaving</em> part rolls two live matches on one seed alternately, so
 * a single stream shared across matches (a static counter or a stream advanced
 * by construction) drifts immediately. The <em>seed</em> part requires different
 * seeds to produce different sequences, so a sequence that ignores the seed
 * cannot pass by being a function of k alone.
 *
 * <p>The controls keep the evidence from passing vacuously: the toward walk must
 * reach the carrot, both walks must consume several rolls, every observed roll
 * must sit in 1..6, and the play order is read from committed steps rather than
 * assumed, so a match that ignores input cannot claim two different orders.
 */
class DiePurityTest {

    private static final List<String> SEEDS = List.of("000000", "000001", "ABC123", "ZZZZZZ");

    /** Rolls compared across seeds and across matches. */
    private static final int ROLLS = 12;

    /** A whole toward-walk needs few turns; this only stops a broken engine looping. */
    private static final int TURN_LIMIT = 200;

    private static final int CARROT_COL = BoardGenerator.CARROT_COL;
    private static final int CARROT_ROW = BoardGenerator.CARROT_ROW;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @Test
    void theKthRollIsAPureFunctionOfTheSeedAndTheRollIndex() {
        assertDifferentMoveOrdersShareTheDieSequence();
        assertInterleavedMatchesShareTheDieSequence();
        assertDifferentSeedsProduceDifferentDieSequences();
    }

    // ------------------------------------------------------------------
    // Different move orders, one seed, one die sequence.
    // ------------------------------------------------------------------

    /**
     * For each seed: a match that walks toward the carrot to the win and a match
     * that walks away from it. The two order transcripts must differ before the
     * sequences are compared, so the equality is not carried by two identical
     * games; the away match must still be live while it produces the prefix.
     */
    private static void assertDifferentMoveOrdersShareTheDieSequence() {
        for (String seed : SEEDS) {
            Walk toward = play(seed, Policy.TOWARD, TURN_LIMIT);
            assertTrue(toward.won(),
                    "the toward walk on seed " + seed + " never reached the carrot,"
                            + " so the control did not exercise a full die sequence");
            assertTrue(toward.rolls().size() >= 3,
                    "seed " + seed + " produced only " + toward.rolls().size()
                            + " rolls toward the carrot; the check needs a real sequence");

            Walk away = play(seed, Policy.AWAY, toward.rolls().size() + 4);
            assertFalse(away.won(),
                    "the away walk on seed " + seed + " won before roll "
                            + toward.rolls().size() + ", so it has no comparable prefix");
            assertTrue(away.rolls().size() >= toward.rolls().size(),
                    "the away walk on seed " + seed + " produced only "
                            + away.rolls().size() + " rolls, fewer than the toward walk's "
                            + toward.rolls().size());

            assertNotEquals(toward.orders(), away.orders(),
                    "seed " + seed + " committed the same tiles in both matches,"
                            + " so the two move orders were not actually different");

            List<Integer> shared = away.rolls().subList(0, toward.rolls().size());
            assertEquals(toward.rolls(), shared,
                    "seed " + seed + " produced different die sequences for two"
                            + " different move orders: toward=" + toward.rolls()
                            + " away=" + shared);

            for (int k = 0; k < toward.rolls().size(); k++) {
                int observed = toward.rolls().get(k);
                assertEquals(observed, Match.die(seed, k + 1),
                        "seed " + seed + " roll " + (k + 1) + " was " + observed
                                + " through the match but " + Match.die(seed, k + 1)
                                + " from (seed, k)");
            }
        }
    }

    // ------------------------------------------------------------------
    // Two live matches, rolled alternately.
    // ------------------------------------------------------------------

    /**
     * Two matches on one seed advanced in lockstep, the rolls taken alternately.
     * Each side sees the same k-th value, so no match consumes another's draws;
     * the policy walks both away from the carrot and the matches are required to
     * stay live for the whole compared prefix.
     */
    private static void assertInterleavedMatchesShareTheDieSequence() {
        for (String seed : SEEDS) {
            Match first = startMatch(seed);
            Match second = startMatch(seed);
            List<Integer> firstRolls = new ArrayList<>();
            List<Integer> secondRolls = new ArrayList<>();

            for (int k = 1; k <= ROLLS; k++) {
                assertFalse(Match.MATCH_WON.equals(first.phase()),
                        "the first interleaved match on seed " + seed
                                + " ended before roll " + k);
                int a = first.rollDie();
                int b = second.rollDie();
                firstRolls.add(a);
                secondRolls.add(b);

                assertEquals(a, b, "seed " + seed + " roll " + k
                        + " differed between two live matches: " + a + " vs " + b);
                assertEquals(a, Match.die(seed, k),
                        "seed " + seed + " roll " + k + " was " + a
                                + " in the match but " + Match.die(seed, k)
                                + " from (seed, k)");
                assertTrue(a >= 1 && a <= 6,
                        "seed " + seed + " roll " + k + " is outside 1..6: " + a);

                advanceOneTurn(first);
                advanceOneTurn(second);
            }

            assertEquals(ROLLS, firstRolls.size(),
                    "the interleaved probe did not collect " + ROLLS + " rolls");
            assertEquals(firstRolls, secondRolls,
                    "seed " + seed + " diverged between interleaved matches");
        }
    }

    // ------------------------------------------------------------------
    // The seed is an input to the sequence.
    // ------------------------------------------------------------------

    /**
     * The same fixed policy on several seeds. A sequence that ignores the seed
     * yields one entry; a real function of (seed, k) yields more than one.
     */
    private static void assertDifferentSeedsProduceDifferentDieSequences() {
        Set<List<Integer>> sequences = new HashSet<>();
        for (String seed : SEEDS) {
            Walk walk = play(seed, Policy.AWAY, ROLLS);
            assertEquals(ROLLS, walk.rolls().size(),
                    "seed " + seed + " produced " + walk.rolls().size()
                            + " rolls, not the " + ROLLS + " compared");
            for (int k = 0; k < walk.rolls().size(); k++) {
                int roll = walk.rolls().get(k);
                assertTrue(roll >= 1 && roll <= 6,
                        "seed " + seed + " roll " + (k + 1)
                                + " is outside 1..6: " + roll);
            }
            sequences.add(walk.rolls());
        }
        assertTrue(sequences.size() > 1,
                "every seed produced the same die sequence " + sequences.iterator().next()
                        + ", so the sequence does not depend on the match seed");
    }

    // ------------------------------------------------------------------
    // Scripted walks.
    // ------------------------------------------------------------------

    /** Which legal target the walk commits on each turn. */
    private enum Policy {
        /** The offered tile nearest the carrot; it ends on the carrot. */
        TOWARD,
        /** The offered tile farthest from the carrot, never the carrot itself. */
        AWAY
    }

    /** One played match: the rolls consumed and the tile committed after each. */
    private record Walk(List<Integer> rolls, List<String> orders, boolean won) {
    }

    /**
     * Plays until the turn limit or a won match, committing exactly one shortest
     * path per roll. The orders list records where each turn ended, which is what
     * proves the two policies moved differently.
     */
    private static Walk play(String seed, Policy policy, int turnLimit) {
        Match match = startMatch(seed);
        List<Integer> rolls = new ArrayList<>();
        List<String> orders = new ArrayList<>();
        boolean won = false;

        for (int turn = 0; turn < turnLimit && !Match.MATCH_WON.equals(match.phase()); turn++) {
            assertEquals(Match.AWAIT_ROLL, match.phase(),
                    "seed " + seed + " is " + match.phase()
                            + " before turn " + (turn + 1) + ", not AwaitRoll");
            rolls.add(match.rollDie());
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "seed " + seed + " left AwaitRoll into " + match.phase()
                            + " on turn " + (turn + 1));

            int[] target = pick(match, policy);
            assertNotNull(target,
                    "seed " + seed + " offered no " + policy + " target on turn "
                            + (turn + 1) + ", so the walk cannot move");
            List<int[]> steps = match.moveTo(target[0], target[1]);
            assertFalse(steps.isEmpty(),
                    "seed " + seed + " committed an empty path on turn " + (turn + 1));
            int[] landed = steps.get(steps.size() - 1);
            orders.add(landed[0] + "," + landed[1]);

            if (Match.MATCH_WON.equals(match.phase())) {
                won = true;
                break;
            }
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "seed " + seed + " left AwaitMove on a non-winning turn "
                            + (turn + 1));
            if (match.budget() != null && match.budget() == 0) {
                match.endTurnIfSpent();
            } else {
                match.endTurn();
            }
        }
        return new Walk(List.copyOf(rolls), List.copyOf(orders), won);
    }

    /** Opens a match whose opening throw is decisive, always blue's. */
    private static Match startMatch(String seed) {
        Match match = Match.start(seed);
        assertEquals(Match.RPS_SELECT, match.phase(),
                "seed " + seed + " did not open in RpsSelect");
        assertEquals(Match.BLUE, match.throwHands(1, 3),
                "the scripted opening throw of seed " + seed + " was not blue's");
        assertEquals(Match.AWAIT_ROLL, match.phase(),
                "the opening throw of seed " + seed + " left the match in "
                        + match.phase());
        return match;
    }

    /** After a roll, commit the chosen policy target. */
    private static void advanceOneTurn(Match match) {
        int[] target = pick(match, Policy.AWAY);
        assertNotNull(target, "the interleaved probe found no legal target");
        List<int[]> steps = match.moveTo(target[0], target[1]);
        assertFalse(steps.isEmpty(), "the interleaved probe committed an empty path");
        assertFalse(Match.MATCH_WON.equals(match.phase()),
                "the interleaved probe reached the carrot before the prefix ended");
        if (match.budget() != null && match.budget() == 0) {
            match.endTurnIfSpent();
        } else {
            match.endTurn();
        }
    }

    /** Chooses one legal target, reading only the match's own offer. */
    private static int[] pick(Match match, Policy policy) {
        List<int[]> targets = match.legalTargets();
        assertFalse(targets.isEmpty(),
                "the match offered no legal target at all");
        int[][] distance = distancesFromCarrot(match.board());
        int[] best = null;
        int bestValue = policy == Policy.TOWARD ? Integer.MAX_VALUE : Integer.MIN_VALUE;

        for (int[] target : targets) {
            boolean carrot = target[0] == CARROT_COL && target[1] == CARROT_ROW;
            if (policy == Policy.AWAY && carrot) {
                continue;
            }
            int value = distance[target[1]][target[0]];
            if (value < 0) {
                // Unreachable from the carrot: the farthest possible square.
                value = policy == Policy.AWAY ? Integer.MAX_VALUE / 2 : Integer.MAX_VALUE;
            }
            if (policy == Policy.TOWARD ? value < bestValue : value > bestValue) {
                best = target;
                bestValue = value;
            }
        }
        return best;
    }

    /** 4-way BFS from the carrot over passable tiles; -1 where unreachable. */
    private static int[][] distancesFromCarrot(Board board) {
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
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }
}
