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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A turn's budget equals the roll and
 * never increases after a step.
 *
 * <p>Three direct halves and one recorded half. The direct halves hold one match
 * and read the budget between single steps, after a multi-step click commit, and
 * after a refused move — a refill is only visible between steps, which is exactly
 * where a scripted match rarely looks. The recorded half plays 200 seeded matches
 * through the automation commands a scripted session sends, with
 * {@code -Dautomation.log}'s {@link EventLog} on, and checks each {@code rolled}
 * event's {@code budget} against its {@code roll}, and each {@code moved} event's
 * {@code budget} against {@code pips - step}: the budget is the roll minus the
 * steps taken, at every recorded step of every turn.
 *
 * <p>The budget also has to survive the automation surface (state), so each direct
 * check reads the budget back through {@link Snapshot#of(Match)} rather than the
 * in-memory field alone: a state line that reports a different budget is the same
 * defect to a script.
 */
class TurnBudgetTest {

    private static final int MATCHES = 200;
    private static final int PLY_LIMIT = 200;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @TempDir
    Path logs;

    @Test
    void theBudgetEqualsTheRollAndNeverIncreasesAfterAStep() throws Exception {
        assertBudgetIsTheRollAndFallsOnePerStep();
        assertMultiStepCommitSpendsExactlyItsSteps();
        assertRefusedMovesLeaveTheBudgetUnchanged();
        assertNextTurnGetsItsOwnRoll();

        int turns = 0;
        for (int i = 0; i < MATCHES; i++) {
            turns += assertRecordedBudgetsFollowTheRoll(i);
        }
        assertTrue(turns >= MATCHES * 2,
                "only " + turns + " turns across 200 matches, so the records are vacuous");
    }

    /**
     * The whole budget, spent one orthogonal step at a time. Before the roll there
     * is no budget; at the roll it equals the pips; after step {@code n} it is
     * exactly {@code roll - n} and strictly smaller than after step {@code n - 1}.
     */
    private static void assertBudgetIsTheRollAndFallsOnePerStep() {
        Match match = Match.start("123456");
        assertEquals(Match.BLUE, match.throwHands(1, 3),
                "the scripted opening throw must be decisive");
        assertNull(match.budget(), "no budget exists before the roll");
        int roll = match.rollDie();
        assertEquals(roll, match.roll().intValue(), "the roll is the pips rolled");
        assertEquals(roll, match.budget().intValue(), "the budget equals the roll");
        assertEquals(roll, reportedBudget(match),
                "state reports the roll as the budget");

        int previous = roll;
        for (int spent = 1; spent <= roll; spent++) {
            int[] target = adjacentStepTarget(match);
            List<int[]> path = match.moveTo(target[0], target[1]);
            assertEquals(1, path.size(),
                    "a target one tile away must cost exactly one step");
            assertEquals(spent, match.stepsThisTurn(),
                    "step " + spent + " of a " + roll + "-pip turn is counted once");
            assertEquals(roll - spent, match.budget().intValue(),
                    "after step " + spent + " the budget is the roll minus " + spent);
            assertTrue(match.budget() < previous,
                    "the budget rose after step " + spent + " (" + previous + " -> "
                            + match.budget() + ")");
            assertEquals(match.budget(), Integer.valueOf(reportedBudget(match)),
                    "state reports the same budget as the match after step " + spent);
            previous = match.budget();
            assertFalse(Match.MATCH_WON.equals(match.phase()),
                    "walking the pips out must not enter the carrot");
        }
        assertEquals(0, previous, "every rolled pip was spent, none more");
    }

    /**
     * A click that commits several steps at once is one input, not a fresh turn:
     * the budget falls by the number of steps in the path and is not restored to
     * the roll. A target at least two orthogonal tiles away has a path of at least
     * two steps, so the path cannot be confused with a single step.
     */
    private static void assertMultiStepCommitSpendsExactlyItsSteps() {
        Match match = freshMatchWithRollAtLeast(3);
        int roll = match.roll().intValue();
        int[] from = match.position(match.active());
        int[] target = null;
        for (int[] candidate : match.legalTargets()) {
            if (Math.abs(candidate[0] - from[0]) + Math.abs(candidate[1] - from[1]) >= 2) {
                target = candidate;
                break;
            }
        }
        assertNotNull(target,
                "a " + roll + "-pip roll offered no target two tiles away, so a multi-step commit"
                        + " cannot be judged");
        int steps = match.moveTo(target[0], target[1]).size();
        assertTrue(steps >= 2, "a target two tiles away must commit at least two steps, not "
                + steps);
        assertTrue(steps <= roll, "a legal target cannot cost more than the roll");
        assertEquals(roll - steps, match.budget().intValue(),
                "a " + steps + "-step commit spends " + steps + " of the " + roll + "-pip budget,"
                        + " not a refill and not the whole roll");
        assertEquals(roll - steps, reportedBudget(match),
                "state reports the budget a multi-step commit left");
        assertEquals(steps, match.stepsThisTurn(),
                "the committed path is counted as its steps");
    }

    /**
     * A move the engine refuses is not a step, so it may not change the budget in
     * either direction. Two refusals: a target beyond the last spent pip, and a
     * blocking tile. Both must throw, and the budget must still be exactly where
     * the last accepted step left it — the risk is a refill on refusal.
     */
    private static void assertRefusedMovesLeaveTheBudgetUnchanged() {
        Match spent = Match.start("123456");
        spent.throwHands(1, 3);
        int roll = spent.rollDie();
        for (int step = 0; step < roll; step++) {
            int[] target = adjacentStepTarget(spent);
            spent.moveTo(target[0], target[1]);
        }
        assertEquals(0, spent.budget().intValue(),
                "spending every pip must leave a zero budget");
        int[] beyond = adjacentStepTarget(spent);
        assertThrows(IllegalArgumentException.class,
                () -> spent.moveTo(beyond[0], beyond[1]),
                "a step beyond the rolled pips must be refused");
        assertEquals(0, spent.budget().intValue(),
                "a refused step must not refill the spent budget");
        assertEquals(roll, spent.stepsThisTurn(),
                "a refused step must not be counted");

        Match matched = freshMatchWithRollAtLeast(1);
        int[] blocked = blockingTile(matched.board());
        assertNotNull(blocked, "the board has no blocking tile, so the refusal cannot be judged");
        int budget = matched.budget();
        int steps = matched.stepsThisTurn();
        assertThrows(IllegalArgumentException.class,
                () -> matched.moveTo(blocked[0], blocked[1]),
                "a blocking tile must be refused as a move");
        assertEquals(budget, matched.budget().intValue(),
                "a refused move must leave the budget untouched");
        assertEquals(steps, matched.stepsThisTurn(),
                "a refused move must leave the step count untouched");
    }

    /** A hand-off discards the old budget; the next turn's budget is its own roll. */
    private static void assertNextTurnGetsItsOwnRoll() {
        Match match = Match.start("123456");
        match.throwHands(1, 3);
        int firstRoll = match.rollDie();
        int[] target = adjacentStepTarget(match);
        match.moveTo(target[0], target[1]);
        match.endTurn();
        assertEquals(Match.AWAIT_ROLL, match.phase(),
                "ending a turn with a step taken must hand off to AwaitRoll");
        assertNull(match.budget(), "a new turn holds no budget before its roll");

        int secondRoll = match.rollDie();
        assertEquals(secondRoll, match.budget().intValue(),
                "the next turn's budget equals its own " + secondRoll + "-pip roll, not the"
                        + " previous turn's " + firstRoll);
        assertEquals(secondRoll, reportedBudget(match),
                "state reports the new turn's roll as the budget");
    }

    /**
     * One recorded match's budget ledger. A {@code rolled} event opens a turn and
     * must record {@code budget == roll}. Every {@code moved} event must record
     * {@code budget == pips - step}: exactly the pips still unspent after the steps
     * logged so far, so the budget falls with each step and can never rise within
     * the turn. A {@code turnEnded} or {@code won} event closes it.
     */
    private int assertRecordedBudgetsFollowTheRoll(int index) throws Exception {
        String seed = String.format("%06d", index);
        Path logFile = logs.resolve("budget-" + seed + ".jsonl");
        Match match = Match.start(seed);
        try (EventLog log = EventLog.open(logFile.toString())) {
            Commands commands = new Commands(match, Settings.defaults(), log, () -> {});
            assertTrue(commands.handle("rps 1 3").startsWith("ok"),
                    "seed " + seed + " could not play the opening throw");
            int[][] toCarrot = distancesFromCarrot(match.board());
            int plies = 0;
            while (!Match.MATCH_WON.equals(match.phase())) {
                assertTrue(plies++ < PLY_LIMIT, "seed " + seed + " did not reach the carrot in "
                        + PLY_LIMIT + " turns, so no whole match was recorded");
                assertTrue(commands.handle("press tirar").startsWith("ok"),
                        "seed " + seed + " could not roll in " + match.phase());
                List<int[]> targets = match.legalTargets();
                assertFalse(targets.isEmpty(),
                        "seed " + seed + " rolled with no legal target");
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
        return assertLoggedBudgets(seed, logFile);
    }

    private static int assertLoggedBudgets(String seed, Path logFile) throws IOException {
        List<JsonObject> records = readRecords(logFile);
        int turns = 0;
        boolean open = false;
        int pips = 0;
        int lastBudget = 0;
        for (JsonObject record : records) {
            String event = record.get("event").getAsString();
            switch (event) {
                case "rolled" -> {
                    assertFalse(open, "seed " + seed + " rolled again before the previous turn ended");
                    assertNotNull(record.get("roll"), "seed " + seed + " logged a roll without pips");
                    assertNotNull(record.get("budget"), "seed " + seed + " logged a roll without a budget");
                    pips = record.get("roll").getAsInt();
                    assertEquals(pips, record.get("budget").getAsInt(),
                            "seed " + seed + " rolled " + pips + " but opened the turn with budget "
                                    + record.get("budget"));
                    lastBudget = pips;
                    open = true;
                }
                case "moved" -> {
                    assertTrue(open, "seed " + seed + " recorded a step outside a turn");
                    assertNotNull(record.get("step"), "seed " + seed + " logged a step without 'step'");
                    assertNotNull(record.get("budget"), "seed " + seed + " logged a step without 'budget'");
                    int step = record.get("step").getAsInt();
                    int budget = record.get("budget").getAsInt();
                    assertTrue(step >= 1 && step <= pips,
                            "seed " + seed + " logged step " + step + " of a " + pips + "-pip turn");
                    assertEquals(pips - step, budget,
                            "seed " + seed + " recorded step " + step + " of a " + pips
                                    + "-pip turn with budget " + budget + ", not " + (pips - step));
                    assertTrue(budget <= lastBudget,
                            "seed " + seed + " raised the budget after a step, from " + lastBudget
                                    + " to " + budget);
                    lastBudget = budget;
                }
                case "turnEnded", "won" -> {
                    assertTrue(open, "seed " + seed + " closed " + event + " outside a turn");
                    turns++;
                    open = false;
                }
                default -> {
                    // Opening-throw and screen records carry no turn budget.
                }
            }
        }
        assertFalse(open, "seed " + seed + " left a turn unclosed in the record");
        assertTrue(turns >= 2, "seed " + seed + " recorded only " + turns + " turns");
        return turns;
    }

    /** A match on the lowest seed whose first roll is at least {@code minimum}. */
    private static Match freshMatchWithRollAtLeast(int minimum) {
        for (int i = 0; i < 1000; i++) {
            Match match = Match.start(String.format("%06d", i));
            match.throwHands(1, 3);
            if (match.rollDie() >= minimum) {
                return match;
            }
        }
        throw new AssertionError("no seed in 000000..000999 rolls " + minimum + " or more");
    }

    private static int reportedBudget(Match match) {
        return JsonParser.parseString(Snapshot.of(match)).getAsJsonObject().get("budget").getAsInt();
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

    private static int[] blockingTile(Board board) {
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (board.blocking(col, row)) {
                    return new int[]{col, row};
                }
            }
        }
        return null;
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
            java.util.Arrays.fill(row, -1);
        }
        distance[BoardGenerator.CARROT_ROW][BoardGenerator.CARROT_COL] = 0;
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
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
}
