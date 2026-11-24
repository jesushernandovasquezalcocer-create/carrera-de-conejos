package rabbitrace.automation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rabbitrace.game.Board;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Match;
import rabbitrace.settings.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every turn takes at least one step
 * unless it passes through {@code NoLegalMove}, and never more steps than the
 * pips rolled.
 *
 * <p>Two halves, because a rule that only says "at least one" is proved by what
 * the engine refuses as much as by what a script happens to play. The direct half
 * rolls a die in {@code AwaitMove} with no step taken and checks that
 * {@code Terminar turno} is refused and the turn does not end by itself while
 * budget remains — so a zero-step turn has no path out except {@code NoLegalMove}.
 * The recorded half plays 200 seeded matches through the same automation commands a
 * scripted session sends, with {@code -Dautomation.log}'s {@link EventLog} on, and
 * checks each logged turn: the {@code moved} records between a {@code rolled} and
 * its {@code turnEnded}/{@code won} number between one and the roll's pips.
 *
 * <p>The exemption is a safety net: leaves every
 * passable tile at least two passable orthogonal neighbours, so no generated
 * board can box a rabbit in. The test asserts that at every {@code AwaitMove} the
 * roll produced, by requiring a legal target: if that ever failed, the turn would
 * have needed {@code NoLegalMove}, and this test would be judging the wrong
 * branch.
 */
class TurnStepBoundsTest {

    private static final int MATCHES = 200;
    private static final int PLY_LIMIT = 200;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @TempDir
    Path logs;

    @Test
    void everyTurnTakesAtLeastOneStepAndNoMoreThanTheRolledPips() throws Exception {
        assertZeroStepTurnIsRefused();
        assertStepsStopAtTheRolledPips();

        int turns = 0;
        int steps = 0;
        for (int i = 0; i < MATCHES; i++) {
            String seed = String.format("%06d", i);
            Path logFile = logs.resolve(seed + ".jsonl");
            Match match = Match.start(seed);
            try (EventLog log = EventLog.open(logFile.toString())) {
                Commands commands = new Commands(match, Settings.defaults(), log, () -> {});
                assertTrue(commands.handle("rps 1 3").startsWith("ok"),
                        "seed " + seed + " could not play the opening throw");
                assertTrue(Match.AWAIT_ROLL.equals(match.phase()),
                        "seed " + seed + " did not leave the opening throw in AwaitRoll");

                int[][] toCarrot = distancesFromCarrot(match.board());
                int plies = 0;
                while (!Match.MATCH_WON.equals(match.phase())) {
                    assertTrue(plies++ < PLY_LIMIT, "seed " + seed + " did not reach the carrot in "
                            + PLY_LIMIT + " turns, so no whole match was played");
                    assertTrue(commands.handle("press tirar").startsWith("ok"),
                            "seed " + seed + " could not roll in " + match.phase());

                    // a rolled turn on a generated board always has a move,
                    // so no turn can claim the NoLegalMove exemption.
                    List<int[]> targets = match.legalTargets();
                    assertFalse(targets.isEmpty(), "seed " + seed + " rolled into "
                            + match.phase() + " with every orthogonal neighbour blocked, so this turn"
                            + " would need NoLegalMove; generated boards must not reach it");

                    int[] target = closestToCarrot(targets, toCarrot);
                    assertTrue(commands.handle("tile " + target[0] + " " + target[1]).startsWith("ok"),
                            "seed " + seed + " could not move to ("
                                    + target[0] + "," + target[1] + ")");
                    if (!Match.MATCH_WON.equals(match.phase()) && Match.AWAIT_MOVE.equals(match.phase())) {
                        assertTrue(commands.handle("press terminar").startsWith("ok"),
                                "seed " + seed + " could not end the turn");
                    }
                }
            }
            int[] counted = assertLoggedTurnsStayWithinTheRoll(seed, logFile);
            turns += counted[0];
            steps += counted[1];
        }

        assertTrue(turns >= MATCHES * 2,
                "only " + turns + " turns across 200 matches, so the records are vacuous");
        assertTrue(steps >= MATCHES * 13,
                "only " + steps + " steps across 200 matches; the shortest route is 13 steps,"
                        + " so the matches did not play out");
    }

    /**
     * A turn with zero steps has no legal ending except the {@code NoLegalMove}
     * pass. In {@code AwaitMove} with the whole budget left, both endings must
     * refuse: {@code Terminar turno} throws, and the budget-0 auto-end does not
     * fire. The legal target check is the precondition that this is the branch
     * under test.
     */
    private static void assertZeroStepTurnIsRefused() {
        Match match = Match.start("123456");
        assertEquals(Match.BLUE, match.throwHands(1, 3),
                "the scripted opening throw must be decisive");
        int roll = match.rollDie();
        assertEquals(0, match.stepsThisTurn(), "a fresh AwaitMove starts with no step taken");
        assertEquals(roll, match.budget().intValue(), "the budget is the roll");
        assertFalse(match.legalTargets().isEmpty(),
                "a generated board must offer a move, so no turn needs NoLegalMove");

        assertThrows(IllegalStateException.class, match::endTurn,
                "Terminar turno must refuse a turn with zero steps taken");

        match.endTurnIfSpent();
        assertEquals(Match.AWAIT_MOVE, match.phase(),
                "a turn with budget left must not end by itself after zero steps");
        assertEquals(0, match.stepsThisTurn(),
                "the refused turn took no step");
        assertEquals(roll, match.budget().intValue(),
                "the refused turn still holds its whole budget");
    }

    /**
     * The upper bound, driven directly: a turn that spends its pips one
     * orthogonal step at a time may do so exactly {@code roll} times, and a step
     * after the last pip is refused. A single click cannot show this bound when
     * the clicked path's length already equals the budget.
     */
    private static void assertStepsStopAtTheRolledPips() {
        Match match = Match.start("123456");
        match.throwHands(1, 3);
        int roll = match.rollDie();
        for (int spent = 1; spent <= roll; spent++) {
            int[] target = adjacentStepTarget(match);
            List<int[]> path = match.moveTo(target[0], target[1]);
            assertEquals(1, path.size(),
                    "a target one tile away must cost exactly one step");
            assertEquals(spent, match.stepsThisTurn(),
                    "step " + spent + " of a " + roll + "-pip turn is counted once");
            assertEquals(roll - spent, match.budget().intValue(),
                    "the budget loses exactly one point per step");
            assertFalse(Match.MATCH_WON.equals(match.phase()),
                    "walking the pips out must not enter the carrot");
        }
        assertEquals(roll, match.stepsThisTurn(),
                "a turn takes exactly the pips it spends one step at a time");
        assertEquals(0, match.budget().intValue(),
                "every rolled pip was spent, none more");

        int[] beyond = adjacentStepTarget(match);
        assertThrows(IllegalArgumentException.class,
                () -> match.moveTo(beyond[0], beyond[1]),
                "a step beyond the rolled pips must be refused");

        match.endTurnIfSpent();
        assertEquals(Match.AWAIT_ROLL, match.phase(),
                "a spent turn hands off with no extra step");
    }

    /** A legal target exactly one orthogonal tile from the active rabbit. */
    private static int[] adjacentStepTarget(Match match) {
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        for (int[] step : STEPS) {
            int col = from[0] + step[0];
            int row = from[1] + step[1];
            if (!match.board().passable(col, row)) {
                continue;
            }
            if (col == other[0] && row == other[1]) {
                continue;
            }
            if (col == BoardGenerator.CARROT_COL && row == BoardGenerator.CARROT_ROW) {
                continue;
            }
            return new int[]{col, row};
        }
        throw new AssertionError("the active rabbit has no adjacent legal step");
    }

    /**
     * One recorded match's turn ledger. A {@code rolled} event opens a turn with
     * the pip count; every {@code moved} record is one committed step; a
     * {@code turnEnded} or {@code won} event closes it. At each close: at least
     * one step, and no more steps than the pips. Returns {turns, steps}.
     */
    private static int[] assertLoggedTurnsStayWithinTheRoll(String seed, Path logFile) throws IOException {
        List<JsonObject> records = readRecords(logFile);
        int turns = 0;
        int steps = 0;
        boolean open = false;
        int pips = 0;
        int taken = 0;
        for (JsonObject record : records) {
            String event = record.get("event").getAsString();
            switch (event) {
                case "rolled" -> {
                    assertFalse(open, "seed " + seed + " rolled again before the previous turn ended");
                    assertNotNull(record.get("roll"), "seed " + seed + " logged a roll without pips");
                    open = true;
                    pips = record.get("roll").getAsInt();
                    taken = 0;
                }
                case "moved" -> {
                    assertTrue(open, "seed " + seed + " recorded a step outside a turn");
                    taken++;
                    assertTrue(taken <= pips, "seed " + seed + " took step " + taken
                            + " of a " + pips + "-pip turn");
                }
                case "turnEnded", "won" -> {
                    assertTrue(open, "seed " + seed + " closed " + event + " outside a turn");
                    assertTrue(taken >= 1, "seed " + seed + " ended a turn with zero steps"
                            + " although the board offered a move (the turn did not pass through NoLegalMove)");
                    assertTrue(taken <= pips, "seed " + seed + " ended a turn with " + taken
                            + " steps, more than the " + pips + " pips rolled");
                    turns++;
                    steps += taken;
                    open = false;
                }
                default -> {
                    // Opening-throw and screen records are not turn steps.
                }
            }
        }
        assertFalse(open, "seed " + seed + " left a turn unclosed in the record");
        assertTrue(turns >= 2, "seed " + seed + " recorded only " + turns + " turns");
        return new int[]{turns, steps};
    }

    private static List<JsonObject> readRecords(Path logFile) throws IOException {
        assertTrue(Files.isRegularFile(logFile), "the automation wrote no event log at " + logFile);
        List<JsonObject> records = new ArrayList<>();
        for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                records.add(JsonParser.parseString(trimmed).getAsJsonObject());
            }
        }
        assertFalse(records.isEmpty(), "the event log at " + logFile + " is empty");
        return records;
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
     * {@link Match}'s own path finder, so the script's choice of target never asks
     * the code under test to describe the board.
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
