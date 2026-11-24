package rabbitrace.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rabbitrace.game.Board;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Match;
import rabbitrace.render.BoardPainter;
import rabbitrace.render.Frame;
import rabbitrace.render.TileMap;
import rabbitrace.settings.Settings;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * a clicked tile commits the shortest legal path with
 * ties broken N, E, S, W; a tile whose path is missing, blocked or over budget
 * renders untargetable and ignores the click.
 *
 * <p>The click is exercised through the same pointer path a scripted session uses
 * ({@link Commands#handle}), with the {@code moved} events of {@code -Dautomation.log}
 * read back as the committed path. That path is compared with an independent
 * reference: a BFS from the goal plus a greedy N, E, S, W walk, which is exactly
 * the lexicographic first shortest path. Matches record how many clicked targets
 * had more than one shortest route, so the tie-break half is not vacuous.
 *
 * <p>Untargetability is checked twice: a click on a blocked tile, the other
 * rabbit's tile and the over-budget carrot must leave every state field untouched
 * (ignored, not refused), and the painter's frame must receive exactly the
 * targetable set and veil every other tile. The render half renders the same
 * state twice, once with the tile targetable, and asserts the pixel darkens under
 * the veil and that a real target's pixel does not move.
 */
class ClickPathTest {

    private static final int SEEDS = 60;
    private static final int PLY_LIMIT = 200;
    private static final int[][] NESW = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final int CARROT_COL = BoardGenerator.CARROT_COL;
    private static final int CARROT_ROW = BoardGenerator.CARROT_ROW;

    @TempDir
    Path logs;

    @Test
    void aClickedTileCommitsTheShortestNeswPathAndUntargetableTilesAreIgnored() throws Exception {
        assertClickedTilesCommitTheShortestNeswPath();
        assertUntargetableTilesIgnoreTheClick();
        assertUntargetableTilesRenderGreyed();
    }

    // ------------------------------------------------------------------
    // The clicked path.
    // ------------------------------------------------------------------

    /**
     * Plays seeded matches by clicking one offered tile per turn. Every click's
     * recorded steps must equal the independent lexicographic-shortest path, the
     * rabbit must end on the clicked tile, and the budget must fall by exactly the
     * path's length. Ambiguous targets are preferred, and their count is asserted
     * so an implementation with an arbitrary tie-break cannot pass on unambiguous
     * boards alone.
     */
    private void assertClickedTilesCommitTheShortestNeswPath() throws Exception {
        int clicks = 0;
        int ambiguousClicks = 0;

        for (int index = 0; index < SEEDS; index++) {
            String seed = String.format("%06d", index);
            Match match = Match.start(seed);
            Path logFile = logs.resolve("clicks-" + seed + ".jsonl");
            try (EventLog log = EventLog.open(logFile.toString())) {
                Commands commands = new Commands(match, Settings.defaults(), log, () -> {});
                assertTrue(commands.handle("rps 1 3").startsWith("ok"),
                        "seed " + seed + " could not play the opening throw");
                int[][] toCarrot = distancesToCarrot(match.board());

                int plies = 0;
                while (!Match.MATCH_WON.equals(match.phase())) {
                    if (plies++ >= PLY_LIMIT) {
                        break;
                    }
                    assertTrue(commands.handle("press tirar").startsWith("ok"),
                            "seed " + seed + " could not roll in " + match.phase());

                    List<int[]> targets = match.legalTargets();
                    assertFalse(targets.isEmpty(),
                            "seed " + seed + " rolled with no legal target in AwaitMove");

                    Choice choice = choose(match, targets, toCarrot);
                    List<int[]> expected = shortestNesw(match.board(), match.position(match.active()),
                            choice.cell()[0], choice.cell()[1], match.position(Match.other(match.active())));
                    assertNotNull(expected,
                            "seed " + seed + " offers (" + choice.cell()[0] + ","
                                    + choice.cell()[1] + ") but the independent reference finds no"
                                    + " shortest path, so the offer is wrong");

                    int[] before = match.position(match.active());
                    String actor = match.active();
                    int budgetBefore = match.budget();
                    int recordsBefore = readRecords(logFile).size();
                    String reply = commands.handle("tile " + choice.cell()[0] + " " + choice.cell()[1]);
                    assertEquals("ok", reply,
                            "seed " + seed + " rejected a click on the legal target ("
                                    + choice.cell()[0] + "," + choice.cell()[1] + "): " + reply);

                    List<int[]> recorded = movedSteps(readRecords(logFile), recordsBefore);
                    assertPathEquals(expected, recorded, seed, choice.cell());
                    // At budget 0 the click also hands the turn off, so the next
                    // turn's budget is null; that is exactly the whole budget spent.
                    int remaining = match.budget() == null ? 0 : match.budget();
                    assertEquals(expected.size(), budgetBefore - remaining,
                            "seed " + seed + " clicked (" + choice.cell()[0] + ","
                                    + choice.cell()[1] + ") on a " + budgetBefore + "-pip budget but"
                                    + " spent " + (budgetBefore - remaining) + " pips for a "
                                    + expected.size() + "-step path");
                    assertArrayEquals(choice.cell(), match.position(actor),
                            "seed " + seed + " did not end the committed path on the"
                                    + " clicked tile");
                    assertFalse(sameCell(before, choice.cell()),
                            "seed " + seed + " clicked the tile it already stood on");

                    clicks++;
                    if (choice.ways() > 1) {
                        ambiguousClicks++;
                    }

                    if (!Match.MATCH_WON.equals(match.phase()) && Match.AWAIT_MOVE.equals(match.phase())) {
                        assertTrue(commands.handle("press terminar").startsWith("ok"),
                                "seed " + seed + " could not end the turn");
                    }
                }
            }
        }

        assertTrue(clicks >= 100,
                "only " + clicks + " tiles were clicked across " + SEEDS
                        + " seeded matches, so the commit path is barely exercised");
        assertTrue(ambiguousClicks >= 20,
                "only " + ambiguousClicks + " of " + clicks + " clicked tiles had more than"
                        + " one shortest path, so the N, E, S, W tie-break was barely confronted");
    }

    /** A target plus how many shortest paths reach it, so ambiguous clicks can be preferred. */
    private record Choice(int[] cell, long ways) {
    }

    private static Choice choose(Match match, List<int[]> targets, int[][] toCarrot) {
        Choice bestAmbiguous = null;
        Choice bestClear = null;
        int ambiguousDistance = Integer.MAX_VALUE;
        int clearDistance = Integer.MAX_VALUE;
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        for (int[] target : targets) {
            int[][] distance = distancesFrom(match.board(), target[0], target[1], other);
            long ways = distance[from[1]][from[0]] < 0 ? 0 : waysTo(from[0], from[1], target[0], target[1],
                    match.board(), distance);
            int toGoal = toCarrot[target[1]][target[0]];
            if (ways > 1) {
                if (toGoal < ambiguousDistance) {
                    ambiguousDistance = toGoal;
                    bestAmbiguous = new Choice(target, ways);
                }
            } else if (toGoal < clearDistance) {
                clearDistance = toGoal;
                bestClear = new Choice(target, ways);
            }
        }
        return bestAmbiguous != null ? bestAmbiguous : bestClear;
    }

    private static void assertPathEquals(List<int[]> expected, List<int[]> recorded, String seed, int[] target) {
        assertEquals(expected.size(), recorded.size(),
                "seed " + seed + " committed " + recorded.size() + " steps to ("
                        + target[0] + "," + target[1] + "), the N, E, S, W shortest path costs "
                        + expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), recorded.get(i),
                    "seed " + seed + " committed step " + (i + 1) + " of the path to ("
                            + target[0] + "," + target[1] + ") as " + java.util.Arrays.toString(
                            recorded.get(i)) + ", the N, E, S, W tie-break selects "
                            + java.util.Arrays.toString(expected.get(i)));
        }
    }

    // ------------------------------------------------------------------
    // Untargetable tiles.
    // ------------------------------------------------------------------

    /**
     * Three untargetable kinds in a live {@code AwaitMove}: a blocking tile (no
     * path), the other rabbit's tile (not occupiable) and the carrot on a first
     * turn (over budget). Each must be absent from the offers, absent from the
     * painter's targets, and a click must return {@code ok} with every state field
     * unchanged — ignored, not refused. A legal click afterwards proves the same
     * pointer path is live in that turn, so the refusals are not a dead board.
     */
    private void assertUntargetableTilesIgnoreTheClick() throws Exception {
        int checked = 0;
        for (int index = 0; index < 200 && checked < 8; index++) {
            String seed = String.format("%06d", index);
            Match match = Match.start(seed);
            Path logFile = logs.resolve("ignored-" + seed + ".jsonl");
            try (EventLog log = EventLog.open(logFile.toString())) {
                Commands commands = new Commands(match, Settings.defaults(), log, () -> {});
                commands.handle("rps 1 3");
                assertTrue(commands.handle("press tirar").startsWith("ok"),
                        "seed " + seed + " could not roll");
                assertEquals(Match.AWAIT_MOVE, match.phase(),
                        "seed " + seed + " is not in AwaitMove after its roll");

                List<int[]> cases = new ArrayList<>();
                int[] blocked = firstBlockingTile(match.board());
                if (blocked != null) {
                    cases.add(blocked);
                }
                cases.add(match.position(Match.other(match.active())));
                cases.add(new int[]{CARROT_COL, CARROT_ROW});
                int[] distant = passableBeyondBudget(match);
                if (distant != null) {
                    cases.add(distant);
                }

                for (int[] cell : cases) {
                    assertFalse(match.targetable(cell[0], cell[1]),
                            "seed " + seed + " calls (" + cell[0] + "," + cell[1]
                                    + ") targetable although a click must ignore it");
                    assertFalse(offered(match, cell[0], cell[1]),
                            "seed " + seed + " offers (" + cell[0] + "," + cell[1]
                                    + ") although its path is missing, blocked or over budget");
                    assertFalse(painterTargets(match, cell[0], cell[1]),
                            "seed " + seed + " hands (" + cell[0] + "," + cell[1]
                                    + ") to the painter as targetable");

                    String before = Snapshot.of(match);
                    int stepsBefore = match.stepsThisTurn();
                    String reply = commands.handle("tile " + cell[0] + " " + cell[1]);
                    assertEquals("ok", reply,
                            "seed " + seed + " replied '" + reply + "' to an ignored click on ("
                                    + cell[0] + "," + cell[1] + "), so it was refused instead");
                    assertEquals(before, Snapshot.of(match),
                            "seed " + seed + " changed state on a click on the untargetable ("
                                    + cell[0] + "," + cell[1] + ")");
                    assertEquals(stepsBefore, match.stepsThisTurn(),
                            "seed " + seed + " counted a step for an ignored click");
                    Commands.Click click = commands.lastClick();
                    assertEquals(cell[0], click.col(),
                            "seed " + seed + " did not resolve the click to (" + cell[0]
                                    + "," + cell[1] + ")");
                    assertEquals(cell[1], click.row(),
                            "seed " + seed + " did not resolve the click to (" + cell[0]
                                    + "," + cell[1] + ")");
                }

                List<int[]> targets = match.legalTargets();
                assertFalse(targets.isEmpty(),
                        "seed " + seed + " offers no target, so the ignored clicks prove nothing");
                int[] target = targets.get(0);
                String actor = match.active();
                assertEquals("ok", commands.handle("tile " + target[0] + " " + target[1]),
                        "seed " + seed + " rejected a legal click after the ignored ones");
                assertArrayEquals(target, match.position(actor),
                        "seed " + seed + " did not move on the legal click");
                checked++;
            }
        }
        assertTrue(checked >= 8,
                "only " + checked + " matches offered the three untargetable kinds");
    }

    // ------------------------------------------------------------------
    // The render surface.
    // ------------------------------------------------------------------

    /**
     * The painter's input is exactly the targetable set, and the painter acts on
     * it: rendering the same frame with one over-budget tile added to its targets
     * darkens nothing else and lifts the veil from that tile. A target tile's pixel
     * is identical in both renders, so the veil is the only difference.
     */
    private void assertUntargetableTilesRenderGreyed() {
        for (int index = 0; index < 200; index++) {
            String seed = String.format("%06d", index);
            Match match = Match.start(seed);
            match.throwHands(1, 3);
            match.rollDie();
            Frame frame = Frames.of(match);
            if (frame.targets().isEmpty()) {
                continue;
            }
            int[] untargetable = passableBeyondBudget(match);
            if (untargetable == null) {
                continue;
            }

            assertEquals(keySet(match.legalTargets()), keySet(frame.targets()),
                    "seed " + seed + " hands the painter a different set than the engine"
                            + " calls targetable");

            int[] target = frame.targets().get(0);
            BufferedImage veiled = BoardPainter.render(frame);
            BufferedImage plain = BoardPainter.render(withTarget(frame, untargetable));
            int untargetableCentre = pixel(veiled, untargetable[0], untargetable[1]);
            int untargetablePlain = pixel(plain, untargetable[0], untargetable[1]);
            assertNotEquals(untargetableCentre, untargetablePlain,
                    "seed " + seed + " paints (" + untargetable[0] + ","
                            + untargetable[1] + ") the same whether it is targetable or not");
            assertTrue(luminance(untargetableCentre) < luminance(untargetablePlain),
                    "seed " + seed + " does not grey (" + untargetable[0] + ","
                            + untargetable[1] + "): " + Integer.toHexString(untargetableCentre)
                            + " is not darker than " + Integer.toHexString(untargetablePlain));
            assertEquals(pixel(veiled, target[0], target[1]), pixel(plain, target[0], target[1]),
                    "seed " + seed + " veils the targetable tile (" + target[0] + ","
                            + target[1] + ") too");
            return;
        }
        org.junit.jupiter.api.Assertions.fail(
                "no seeded AwaitMove state offered an over-budget tile to render");
    }

    private static Frame withTarget(Frame frame, int[] extra) {
        List<int[]> targets = new ArrayList<>(frame.targets());
        targets.add(extra);
        return new Frame(frame.board(), frame.blue(), frame.brown(), frame.carrot(),
                frame.screen(), frame.phase(),
                frame.p1Hand(), frame.p2Hand(), frame.p1Committed(), frame.p2Committed(),
                frame.active(), frame.turn(), frame.roll(), frame.endTurnEnabled(),
                frame.seed(), frame.rngCursor(), frame.boardHash(), targets, frame.opener(),
                frame.settings(), frame.seedField(), frame.seedValid(), frame.plan(),
                frame.dieTumble());
    }

    private static int pixel(BufferedImage image, int col, int row) {
        return image.getRGB(TileMap.tileCentreX(col), TileMap.tileCentreY(row));
    }

    private static int luminance(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return red + green + blue;
    }

    private static boolean painterTargets(Match match, int col, int row) {
        for (int[] target : Frames.of(match).targets()) {
            if (target[0] == col && target[1] == row) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Set<String> keySet(List<int[]> cells) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (int[] cell : cells) {
            keys.add(cell[0] + "," + cell[1]);
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // The independent path reference: BFS from the goal, then greedy N, E, S, W.
    // ------------------------------------------------------------------

    /**
     * The lexicographic first shortest path, found independently of the engine: a
     * BFS from the goal gives the distance of every tile, then each step takes the
     * first N, E, S, W neighbour that is one closer to the goal. The carrot is
     * terminal (never expanded on the way to another tile) and the other rabbit's
     * tile is traversable but not a destination.
     */
    private static List<int[]> shortestNesw(Board board, int[] from, int goalCol, int goalRow, int[] other) {
        if (!board.inside(goalCol, goalRow) || board.blocking(goalCol, goalRow)) {
            return null;
        }
        if (goalCol == from[0] && goalRow == from[1]) {
            return null;
        }
        if (goalCol == other[0] && goalRow == other[1]) {
            return null;
        }
        int[][] distance = distancesFrom(board, goalCol, goalRow, other);
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

    /**
     * Distances from {@code (goalCol, goalRow)} over passable tiles, with the
     * carrot terminal when it is not the goal and the other rabbit traversable as
     * an intermediate.
     */
    private static int[][] distancesFrom(Board board, int goalCol, int goalRow, int[] other) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            java.util.Arrays.fill(row, -1);
        }
        boolean goalIsCarrot = goalCol == CARROT_COL && goalRow == CARROT_ROW;
        Deque<int[]> queue = new ArrayDeque<>();
        distance[goalRow][goalCol] = 0;
        queue.add(new int[]{goalCol, goalRow});
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : NESW) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!board.inside(col, row) || board.blocking(col, row)) {
                    continue;
                }
                if (distance[row][col] >= 0) {
                    continue;
                }
                if (!goalIsCarrot && col == CARROT_COL && row == CARROT_ROW) {
                    // the carrot is entered last, never traversed toward a
                    // different goal.
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.addLast(new int[]{col, row});
            }
        }
        return distance;
    }

    /** How many shortest paths reach the goal, capped at 2 (only "more than one" matters). */
    private static long waysTo(int col, int row, int goalCol, int goalRow, Board board, int[][] distance) {
        long[][] memo = new long[board.rows()][board.cols()];
        for (long[] rowValues : memo) {
            java.util.Arrays.fill(rowValues, -1);
        }
        return waysTo(col, row, board, distance, memo);
    }

    private static long waysTo(int col, int row, Board board, int[][] distance, long[][] memo) {
        if (distance[row][col] == 0) {
            return 1;
        }
        if (memo[row][col] >= 0) {
            return memo[row][col];
        }
        long total = 0;
        for (int[] delta : NESW) {
            int nextCol = col + delta[0];
            int nextRow = row + delta[1];
            if (!board.inside(nextCol, nextRow) || board.blocking(nextCol, nextRow)
                    || distance[nextRow][nextCol] != distance[row][col] - 1) {
                continue;
            }
            total += waysTo(nextCol, nextRow, board, distance, memo);
            if (total > 1) {
                total = 2;
                break;
            }
        }
        memo[row][col] = total;
        return total;
    }

    // ------------------------------------------------------------------
    // State helpers.
    // ------------------------------------------------------------------

    private static int[][] distancesToCarrot(Board board) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            java.util.Arrays.fill(row, -1);
        }
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

    private static int[] firstBlockingTile(Board board) {
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (board.blocking(col, row)) {
                    return new int[]{col, row};
                }
            }
        }
        return null;
    }

    /**
     * A passable tile the active rabbit cannot reach within the remaining budget,
     * never the carrot, either rabbit's tile or a target. Its absence proves the
     * veil covers ordinary over-budget grass, not just obstacles.
     */
    private static int[] passableBeyondBudget(Match match) {
        Board board = match.board();
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        int budget = match.budget();
        int[][] distance = distancesFrom(board, from[0], from[1], other);
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (!board.passable(col, row) || (col == CARROT_COL && row == CARROT_ROW)) {
                    continue;
                }
                if (sameCell(new int[]{col, row}, from) || sameCell(new int[]{col, row}, other)) {
                    continue;
                }
                if (distance[row][col] > budget) {
                    return new int[]{col, row};
                }
            }
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

    private static boolean sameCell(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }

    private static List<JsonObject> readRecords(Path file) throws IOException {
        List<JsonObject> records = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return records;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(JsonParser.parseString(line.strip()).getAsJsonObject());
            }
        }
        return records;
    }

    /** The {@code moved} steps of one click, in order. */
    private static List<int[]> movedSteps(List<JsonObject> records, int fromIndex) {
        List<int[]> steps = new ArrayList<>();
        for (int i = fromIndex; i < records.size(); i++) {
            JsonObject record = records.get(i);
            if (!"moved".equals(record.get("event").getAsString())) {
                continue;
            }
            JsonArray from = record.getAsJsonArray("from");
            JsonArray to = record.getAsJsonArray("to");
            steps.add(new int[]{from.get(0).getAsInt(), from.get(1).getAsInt(),
                    to.get(0).getAsInt(), to.get(1).getAsInt()});
        }
        return steps;
    }
}
