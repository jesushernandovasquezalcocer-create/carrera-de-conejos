package rabbitrace.game;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Child-process entry point.
 *
 * <p>It plays one full match per seed through the public rules surface — the
 * opening throw, then rolls, committed paths and turn hand-offs until the match
 * ends — and prints the seed, the {@code boardHash}, the die sequence, the winner
 * and the number of inputs it consumed. The input sequence is not a fixture: it
 * is derived from the rule state by the same fixed policy on every run, so two
 * JVMs that agree on every line also consumed the same inputs. There is no
 * automation, no window and no wall clock in the path, so two invocations are as
 * independent as two JVMs can be.
 */
public final class MatchDeterminismProbe {

    /** The seeds the check plays; every one is six Crockford characters. */
    private static final List<String> SEEDS = List.of("000000", "000001", "000002", "ABC123", "ZZZZZZ");

    private static final int ACTION_LIMIT = 600;
    private static final int CARROT_COL = BoardGenerator.CARROT_COL;
    private static final int CARROT_ROW = BoardGenerator.CARROT_ROW;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private MatchDeterminismProbe() {
    }

    public static List<String> seeds() {
        return SEEDS;
    }

    /** One line per seed, byte-stable, so two runs can be compared as text. */
    public static String transcript(List<String> seeds) {
        StringBuilder out = new StringBuilder();
        for (String seed : seeds) {
            out.append(play(seed)).append('\n');
        }
        return out.toString();
    }

    public static void main(String[] args) {
        byte[] bytes = transcript(SEEDS).getBytes(StandardCharsets.UTF_8);
        System.out.write(bytes, 0, bytes.length);
        System.out.flush();
    }

    /**
     * Plays one match on {@code rawSeed} to the end and reports what the engine
     * produced. The policy is deliberately simple and fully determined by the
     * rule state: throw a decisive hand, then on each turn roll, take the legal
     * target that is strictly closer to the carrot, and hand off when the budget
     * is spent or no legal target makes progress.
     */
    public static String play(String rawSeed) {
        Match match = Match.start(rawSeed);
        String seed = match.board().seed();
        StringBuilder rolls = new StringBuilder();
        int inputs = 0;

        String thrown = match.throwHands(1, 3);
        inputs++;
        if (!Match.BLUE.equals(thrown)) {
            throw new IllegalStateException("the scripted throw must be decisive for " + seed);
        }

        int actions = 0;
        while (!Match.MATCH_WON.equals(match.phase()) && actions < ACTION_LIMIT) {
            if (Match.AWAIT_ROLL.equals(match.phase())) {
                int roll = match.rollDie();
                if (rolls.length() > 0) {
                    rolls.append(',');
                }
                rolls.append(roll);
                inputs++;
                actions++;
                continue;
            }
            if (!Match.AWAIT_MOVE.equals(match.phase())) {
                break;
            }
            if (match.budget() != null && match.budget() == 0) {
                match.endTurnIfSpent();
                inputs++;
                actions++;
                continue;
            }
            int[] target = stepTowardTheCarrot(match);
            if (target == null) {
                if (match.stepsThisTurn() == 0) {
                    // No legal opening move: the match cannot end by winning here.
                    break;
                }
                match.endTurn();
                inputs++;
                actions++;
                continue;
            }
            match.moveTo(target[0], target[1]);
            inputs++;
            actions++;
            if (match.budget() != null && match.budget() == 0
                    && !Match.MATCH_WON.equals(match.phase())) {
                match.endTurnIfSpent();
                inputs++;
                actions++;
            }
        }

        String winner = match.winner() == null ? "none" : match.winner();
        return "seed=" + seed
                + " boardHash=" + match.board().hash()
                + " rolls=" + (rolls.length() == 0 ? "-" : rolls)
                + " winner=" + winner
                + " inputs=" + inputs;
    }

    /**
     * The offered tile with the smallest independent distance to the carrot, or
     * {@code null} when no offer gets closer than the tile the rabbit stands on.
     * The map is a plain BFS from the carrot that never steps on the other rabbit.
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
}
