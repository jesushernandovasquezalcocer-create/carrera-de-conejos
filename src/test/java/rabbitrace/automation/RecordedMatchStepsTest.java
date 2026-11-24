package rabbitrace.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In every recorded match each step joins
 * orthogonally adjacent tiles; no step spans two tiles and none is diagonal.
 *
 * <p>The match is played through the automation command surface — the same
 * {@code rps}, {@code press tirar}, {@code tile} and {@code press terminar} a
 * scripted session sends — with {@code -Dautomation.log}'s {@link EventLog} on, so
 * every step exists as a recorded {@code moved} event before it is judged. The
 * script then reads the log back and checks the record, not the in-memory step
 * list: a step that is not in the record was not played, and a record whose
 * {@code from}/{@code to} differ by anything but one orthogonal tile fails.
 *
 * <p>The script's choice of tile reuses the automation's own {@link Match#legalTargets()}
 * offers, as the movement test does, so a whole match finishes and the recording is not
 * vacuous; the board-distance map that picks the target is recomputed here from
 * {@link Board#passable} rather than asked of the movement code.
 */
class RecordedMatchStepsTest {

    private static final int MATCHES = 200;
    private static final int PLY_LIMIT = 200;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @TempDir
    Path logs;

    @Test
    void everyRecordedStepJoinsOrthogonallyAdjacentTiles() throws Exception {
        int recordedSteps = 0;
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
                            + PLY_LIMIT + " turns, so no whole match was recorded");
                    assertTrue(commands.handle("press tirar").startsWith("ok"),
                            "seed " + seed + " could not roll in " + match.phase());

                    List<int[]> targets = match.legalTargets();
                    assertFalse(targets.isEmpty(), "seed " + seed + " has no legal target in AwaitMove");
                    int[] target = closestToCarrot(targets, toCarrot);
                    assertTrue(commands.handle("tile " + target[0] + " " + target[1]).startsWith("ok"),
                            "seed " + seed + " could not move to (" + target[0] + "," + target[1] + ")");

                    if (!Match.MATCH_WON.equals(match.phase()) && Match.AWAIT_MOVE.equals(match.phase())) {
                        assertTrue(commands.handle("press terminar").startsWith("ok"),
                                "seed " + seed + " could not end the turn");
                    }
                }
            }
            assertEquals(Match.MATCH_WON, match.phase(),
                    "the recorded match on seed " + seed + " ended outside MatchWon");
            assertNotNull(match.winner(), "the recorded match on seed " + seed + " ended without a winner");

            recordedSteps += assertEveryRecordedStepIsAdjacent(seed, logFile);
        }
        assertTrue(recordedSteps >= MATCHES * 13,
                "only " + recordedSteps + " steps were recorded across 200 matches; shortest"
                        + " route is 13 steps, so the recordings did not play out");
    }

    /**
     * One recorded match's claim: every {@code moved} event names a step between
     * two tiles one orthogonal move apart. {@code |dcol| + |drow| == 1} is exactly
     * The tiles are adjacent, no step skips a tile, and no diagonal has sum 2.
     */
    private static int assertEveryRecordedStepIsAdjacent(String seed, Path logFile) throws IOException {
        List<JsonObject> records = readRecords(logFile);
        int steps = 0;
        for (JsonObject record : records) {
            if (!"moved".equals(record.get("event").getAsString())) {
                continue;
            }
            steps++;
            int[] from = cell(record, "from", seed);
            int[] to = cell(record, "to", seed);
            int dCol = to[0] - from[0];
            int dRow = to[1] - from[1];
            assertEquals(1, Math.abs(dCol) + Math.abs(dRow),
                    "seed " + seed + " recorded step " + record.get("step") + " (seq "
                            + record.get("seq") + ") from (" + from[0] + "," + from[1] + ") to ("
                            + to[0] + "," + to[1] + "), a move of (" + dCol + "," + dRow
                            + ") that is not one orthogonal tile");
        }
        assertTrue(steps >= 13,
                "seed " + seed + " recorded only " + steps + " steps, less than the shortest route");
        return steps;
    }

    private static List<JsonObject> readRecords(Path logFile) throws IOException {
        assertTrue(Files.isRegularFile(logFile), "the automation wrote no event log at " + logFile);
        List<JsonObject> records = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                records.add(JsonParser.parseString(trimmed).getAsJsonObject());
            }
        }
        assertFalse(records.isEmpty(), "the event log at " + logFile + " is empty");
        return records;
    }

    private static int[] cell(JsonObject record, String field, String seed) {
        JsonElement value = record.get(field);
        assertNotNull(value, "seed " + seed + " recorded a moved event without '" + field + "'");
        assertTrue(value.isJsonArray(), "seed " + seed + " recorded '" + field + "' as " + value);
        JsonArray cell = value.getAsJsonArray();
        assertEquals(2, cell.size(), "seed " + seed + " recorded '" + field + "' as " + cell);
        return new int[]{cell.get(0).getAsInt(), cell.get(1).getAsInt()};
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
