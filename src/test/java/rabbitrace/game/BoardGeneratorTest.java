package rabbitrace.game;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * for 200 distinct seeds, every returned board
 * satisfies. The path lengths are recomputed here with a plain BFS
 * over {@link Board#passable}, so the check does not trust the generator's own
 * distance helper.
 */
class BoardGeneratorTest {

    private static final int[][] STARTS = {
            BoardGenerator.BLUE_START, BoardGenerator.BROWN_START};
    private static final int[][] CLEARINGS = {
            BoardGenerator.BLUE_START, BoardGenerator.BROWN_START,
            {BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW}};
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Test
    void twoHundredSeedsSatisfyG1ToG4AndG8() {
        Set<String> seeds = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String seed = String.format("%06d", i);
            assertTrue(seeds.add(seed), "the check needs 200 distinct seeds");

            Board board = BoardGenerator.generate(seed);
            assertGeometry(board, seed);
            assertMirror(board, seed);
            assertClearings(board, seed);
            assertBlockingCount(board, seed);
            assertDistance(board, seed);
        }
        assertEquals(200, seeds.size(), "runs on 200 distinct seeds");
    }

    /**
     * the mirror invariant on the returned board itself. The mirror
     * column is derived from the board's own width, not from the generator's
     * helper, so a generator that stopped pairing its clusters cannot hide
     * behind a symmetry claim.
     */
    @Test
    void everyGeneratedBoardIsMirrorSymmetric() {
        Set<String> seeds = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String seed = String.format("%06d", i);
            assertTrue(seeds.add(seed), "the check needs 200 distinct seeds");

            Board board = BoardGenerator.generate(seed);
            for (int row = 0; row < board.rows(); row++) {
                String canonicalRow = board.rowsAsStrings().get(row);
                assertEquals(new StringBuilder(canonicalRow).reverse().toString(), canonicalRow,
                        "canonical row " + row + " of seed " + seed + " is not mirror-symmetric");
                for (int col = 0; col < board.cols(); col++) {
                    int mirrorCol = board.cols() - 1 - col;
                    assertEquals(board.kindAt(col, row), board.kindAt(mirrorCol, row),
                            "seed " + seed + " has '" + board.kindAt(col, row) + "' at ("
                                    + col + "," + row + ") but '" + board.kindAt(mirrorCol, row)
                                    + "' at (" + mirrorCol + "," + row + ")");
                }
            }
        }
        assertEquals(200, seeds.size(), "runs on 200 distinct seeds");
    }

    /**
     * the carrot and both starts are passable, a walk from each start
     * reaches every passable tile, and no passable tile is an island. The walk is
     * a plain BFS over {@link Board#passable}, so the check does not trust the
     * generator's own connectivity helper.
     */
    @Test
    void everyPassableTileIsReachableFromBothStarts() {
        Set<String> seeds = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String seed = String.format("%06d", i);
            assertTrue(seeds.add(seed), "the check needs 200 distinct seeds");

            Board board = BoardGenerator.generate(seed);
            assertTrue(board.passable(BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW),
                    "the carrot is not passable for seed " + seed);
            for (int[] start : STARTS) {
                assertTrue(board.passable(start[0], start[1]),
                        "start (" + start[0] + "," + start[1] + ") is not passable for seed " + seed);
                boolean[][] reached = reachable(board, start);
                for (int row = 0; row < board.rows(); row++) {
                    for (int col = 0; col < board.cols(); col++) {
                        if (board.passable(col, row)) {
                            assertTrue(reached[row][col],
                                    "seed " + seed + " leaves the passable tile (" + col + ","
                                            + row + ") unreachable from start (" + start[0] + ","
                                            + start[1] + ")");
                        }
                    }
                }
            }
            assertNoIsland(board, seed);
        }
        assertEquals(200, seeds.size(), "runs on 200 distinct seeds");
    }

    /**
     * no returned board has a dead end or a pinch run of four or
     * more. Both are recomputed from {@link Board#passable} on the board the
     * generator returned, so a repair that only ran on a discarded pre-repair
     * layout cannot satisfy this check.
     */
    @Test
    void noReturnedBoardHasADeadEndOrALongPinchRun() {
        Set<String> seeds = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String seed = String.format("%06d", i);
            assertTrue(seeds.add(seed), "the check needs 200 distinct seeds");

            Board board = BoardGenerator.generate(seed);
            assertNoDeadEnds(board, seed);
            assertNoLongPinchRuns(board, seed);
        }
        assertEquals(200, seeds.size(), "runs on 200 distinct seeds");
    }

    /**
     * the same seed and generator version produce the same canonical
     * serialisation byte for byte. Two child JVMs generate the 50 seeds
     * independently, and their raw output must equal each other and an
     * independently assembled expectation in this JVM — so an empty or skipped
     * child cannot satisfy the check by matching itself.
     */
    @Test
    void sameSeedAndVersionAreByteIdenticalAcrossTwoJvms() throws Exception {
        List<String> seeds = BoardCanonicalProbe.seeds();
        assertEquals(50, seeds.size(), "runs on 50 seeds");
        assertEquals(50, new HashSet<>(seeds).size(), "needs 50 distinct seeds");

        StringBuilder expected = new StringBuilder();
        expected.append("version ").append(BoardGenerator.VERSION).append('\n');
        for (String seed : seeds) {
            Board board = BoardGenerator.generate(seed);
            assertEquals(9, board.rowsAsStrings().size(),
                    "serialises nine rows for seed " + seed);
            expected.append("seed ").append(seed).append('\n');
            expected.append(board.canonical()).append('\n');
        }

        byte[] first = generateInSeparateJvm();
        byte[] second = generateInSeparateJvm();

        assertArrayEquals(first, second,
                "two separate JVMs produced different canonical serialisations");
        assertEquals(expected.toString(), new String(first, StandardCharsets.UTF_8),
                "a separate JVM's canonical serialisation differs from the 50-seed expectation");
    }

    /**
     * changing one character of a seed changes the
     * board. Fifty distinct base seeds spread across the Crockford alphabet are
     * each mutated at one position to a different valid character; at least 45 of
     * the 50 pairs must produce a different canonical board. The comparison is the
     * returned serialisation, not the seed hash, so a generator that hashed the
     * seed but ignored it cannot pass.
     */
    @Test
    void changingOneSeedCharacterChangesTheBoardInAtLeast45Of50Pairs() {
        int changed = 0;
        Set<String> bases = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String base = baseSeed(i);
            assertTrue(bases.add(base), "needs 50 distinct base seeds");

            int position = i % Seed.LENGTH;
            char from = base.charAt(position);
            char to = Seed.ALPHABET.charAt(
                    (Seed.ALPHABET.indexOf(from) + 1 + i % 7) % Seed.ALPHABET.length());
            String mutated = base.substring(0, position) + to + base.substring(position + 1);

            assertNotEquals(base, mutated, "pairs must not be the same seed");
            assertEquals(1, differences(base, mutated),
                    "pairs must differ by exactly one character: " + base + " vs " + mutated);

            Board original = BoardGenerator.generate(base);
            Board other = BoardGenerator.generate(mutated);
            if (!original.canonical().equals(other.canonical())) {
                changed++;
            }
        }
        assertTrue(changed >= 45, "a one-character seed change moved the board in only "
                + changed + " of 50 pairs; at least 45 are required");
    }

    /**
     * a validator that rejects every attempt exhausts 500
     * attempts, and the generator returns template flagged as a template
     * board. The returned board is then held to through the public reads a
     * player's board uses, and to the shipped template text, so a rotted template
     * or a fabricated fallback cannot pass.
     */
    @Test
    void aGeneratorForcedToFailFiveHundredAttemptsReturnsTheValidatedTemplate() throws Exception {
        assertEquals(500, BoardGenerator.MAX_ATTEMPTS, "the fallback follows 500 attempts");

        AtomicInteger validations = new AtomicInteger();
        Board board = BoardGenerator.generate("ABC123", tiles -> {
            validations.incrementAndGet();
            return false;
        });
        assertTrue(validations.get() > 0,
                "the forced-failure validator was never consulted, so no attempt was rejected");
        assertTrue(board.templateBoard(),
                "a board that never validates must be flagged as a template board");
        String seed = "ABC123";

        assertGeometry(board, seed);
        assertMirror(board, seed);
        assertClearings(board, seed);
        assertBlockingCount(board, seed);
        assertDistance(board, seed);
        assertNoDeadEnds(board, seed);
        assertNoLongPinchRuns(board, seed);
        assertEveryPassableTileReachable(board, seed);
        assertMatchesTemplateFile(board);

        assertFalse(BoardGenerator.generate(seed).templateBoard(),
                "an ordinary seed must not be reported as a template board");
    }

    /**
     * decoration never touches logic. On 50 boards, the board with
     * its decoration layer stripped is compared tile by tile with the generated
     * board — kinds, passability, reachability from both starts and the full BFS
     * distance field from both starts must be identical. The sample must also
     * carry real decoration, so a generator that painted nothing cannot satisfy
     * the check by comparing two bare boards.
     */
    @Test
    void strippingTheDecorationLayerLeavesPassabilityReachabilityAndDistancesUnchanged() {
        Set<String> seeds = new HashSet<>();
        int decoratedBoards = 0;
        int decoratedTiles = 0;
        for (int i = 0; i < 50; i++) {
            String seed = String.format("%06d", i);
            assertTrue(seeds.add(seed), "needs 50 distinct seeds");

            Board decorated = BoardGenerator.generate(seed);
            Board stripped = decorated.withoutDecoration();

            int dirt = 0;
            for (int row = 0; row < decorated.rows(); row++) {
                for (int col = 0; col < decorated.cols(); col++) {
                    if (decorated.dirtAt(col, row)) {
                        dirt++;
                    }
                    assertFalse(stripped.dirtAt(col, row),
                            "seed " + seed + " keeps dirt at (" + col + "," + row
                                    + ") after the decoration layer is stripped");
                    assertEquals(decorated.kindAt(col, row), stripped.kindAt(col, row),
                            "stripping decoration changed the kind at (" + col + "," + row
                                    + ") for seed " + seed);
                    assertEquals(decorated.passable(col, row), stripped.passable(col, row),
                            "stripping decoration changed passability at (" + col + ","
                                    + row + ") for seed " + seed);
                }
            }
            decoratedTiles += dirt;
            if (dirt > 0) {
                decoratedBoards++;
            }
            assertEquals(decorated.canonical(), stripped.canonical(),
                    "stripping decoration changed the canonical board for seed " + seed);

            for (int[] start : STARTS) {
                assertTrue(Arrays.deepEquals(reachable(decorated, start),
                                reachable(stripped, start)),
                        "stripping decoration changed reachability from start ("
                                + start[0] + "," + start[1] + ") for seed " + seed);
                assertTrue(Arrays.deepEquals(distanceField(decorated, start),
                                distanceField(stripped, start)),
                        "stripping decoration changed the distance field from start ("
                                + start[0] + "," + start[1] + ") for seed " + seed);
                assertEquals(distance(decorated, start), distance(stripped, start),
                        "stripping decoration changed the carrot distance from start ("
                                + start[0] + "," + start[1] + ") for seed " + seed);
            }
        }
        assertEquals(50, seeds.size(), "runs on 50 distinct seeds");
        assertTrue(decoratedBoards >= 45,
                "only " + decoratedBoards + " of 50 boards carry decoration and "
                        + decoratedTiles + " tiles are painted; the check would be vacuous");
    }

    /** both starts reach every passable tile of the returned template. */
    private static void assertEveryPassableTileReachable(Board board, String seed) {
        for (int[] start : STARTS) {
            boolean[][] reached = reachable(board, start);
            for (int row = 0; row < board.rows(); row++) {
                for (int col = 0; col < board.cols(); col++) {
                    assertTrue(!board.passable(col, row) || reached[row][col],
                            "template tile (" + col + "," + row + ") is unreachable from start ("
                                    + start[0] + "," + start[1] + ")");
                }
            }
        }
        assertNoIsland(board, seed);
    }

    /**
     * The fallback is the shipped file, not an invented board: the returned rows
     * equal {@code assets/board/template-15x9.txt}, with its legend markers read as
     * the grass they stand for.
     */
    private static void assertMatchesTemplateFile(Board board) throws Exception {
        List<String> expected = new ArrayList<>();
        for (String line : Files.readAllLines(
                Path.of("assets", "board", "template-15x9.txt"), StandardCharsets.UTF_8)) {
            expected.add(line.strip().replace('C', '.').replace('1', '.')
                    .replace('2', '.').replace(',', '.'));
        }
        assertEquals(expected, board.rowsAsStrings(),
                "the fallback board is not the shipped template");
        assertEquals(9, expected.size(), "the template is nine rows");
        assertTrue(expected.stream().allMatch(row -> row.length() == 15),
                "every template row is fifteen characters");
    }

    /**
     * Base seed {@code i}: its first two characters encode {@code i} in base 32
     * (so all 50 are distinct), and the rest are fixed rotations through the
     * alphabet, so the pairs exercise letters as well as digits.
     */
    private static String baseSeed(int i) {
        StringBuilder seed = new StringBuilder(Seed.LENGTH);
        seed.append(Seed.ALPHABET.charAt((i / Seed.ALPHABET.length()) % Seed.ALPHABET.length()));
        seed.append(Seed.ALPHABET.charAt(i % Seed.ALPHABET.length()));
        for (int j = 0; j < Seed.LENGTH - 2; j++) {
            seed.append(Seed.ALPHABET.charAt((i * (3 + 2 * j) + 7 + 6 * j) % Seed.ALPHABET.length()));
        }
        return seed.toString();
    }

    /** The number of positions at which two same-length seeds differ. */
    private static int differences(String first, String second) {
        assertEquals(first.length(), second.length(), "compares seeds of one length");
        int count = 0;
        for (int i = 0; i < first.length(); i++) {
            if (first.charAt(i) != second.charAt(i)) {
                count++;
            }
        }
        return count;
    }

    /** Runs the probe in a fresh JVM and returns exactly the bytes it wrote. */
    private static byte[] generateInSeparateJvm() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path exe = Path.of(java + ".exe");
        List<String> launch = new ArrayList<>();
        launch.add(Files.isRegularFile(exe) ? exe.toString() : java.toString());
        launch.add("-cp");
        launch.add(System.getProperty("java.class.path"));
        launch.add(BoardCanonicalProbe.class.getName());

        Process process = new ProcessBuilder(launch).redirectErrorStream(true).start();
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the child JVM did not finish within 120 s");
        }
        assertEquals(0, process.exitValue(),
                "the child JVM exited " + process.exitValue() + ": "
                        + new String(output, StandardCharsets.UTF_8));
        return output;
    }

    /** every passable tile has at least 2 passable orthogonal neighbours. */
    private static void assertNoDeadEnds(Board board, String seed) {
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (!board.passable(col, row)) {
                    continue;
                }
                int neighbours = 0;
                for (int[] step : STEPS) {
                    if (board.passable(col + step[0], row + step[1])) {
                        neighbours++;
                    }
                }
                assertTrue(neighbours >= 2, "seed " + seed + " returns a dead end at ("
                        + col + "," + row + ") with " + neighbours + " passable neighbour(s)");
            }
        }
    }

    /**
     * no sequence of 4+ orthogonally adjacent pinches runs along one axis. A
     * pinch is a passable tile whose only two passable neighbours are opposite.
     */
    private static void assertNoLongPinchRuns(Board board, String seed) {
        for (int row = 0; row < board.rows(); row++) {
            int run = 0;
            for (int col = 0; col <= board.cols(); col++) {
                if (col < board.cols() && pinch(board, col, row)) {
                    run++;
                } else {
                    assertTrue(run < 4, "seed " + seed + " returns a horizontal pinch run of "
                            + run + " tiles at row " + row + " ending at column " + (col - 1));
                    run = 0;
                }
            }
        }
        for (int col = 0; col < board.cols(); col++) {
            int run = 0;
            for (int row = 0; row <= board.rows(); row++) {
                if (row < board.rows() && pinch(board, col, row)) {
                    run++;
                } else {
                    assertTrue(run < 4, "seed " + seed + " returns a vertical pinch run of "
                            + run + " tiles at column " + col + " ending at row " + (row - 1));
                    run = 0;
                }
            }
        }
    }

    /** A pinch: passable, exactly two passable orthogonal neighbours, opposite. */
    private static boolean pinch(Board board, int col, int row) {
        if (!board.passable(col, row)) {
            return false;
        }
        boolean left = board.passable(col - 1, row);
        boolean right = board.passable(col + 1, row);
        boolean up = board.passable(col, row - 1);
        boolean down = board.passable(col, row + 1);
        return (left && right && !up && !down) || (up && down && !left && !right);
    }

    /** 15x9, the fixed cells, and only tile kinds. */
    private static void assertGeometry(Board board, String seed) {
        assertEquals(15, board.cols(), "width for seed " + seed);
        assertEquals(9, board.rows(), "height for seed " + seed);
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                char kind = board.kindAt(col, row);
                if (kind != Board.GRASS && !Board.isBlocking(kind)) {
                    fail("seed " + seed + " has a kind outside at ("
                            + col + "," + row + "): '" + kind + "'");
                }
            }
        }
        assertTrue(board.passable(BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW),
                "the carrot at (7,0) is passable for seed " + seed);
        for (int[] start : STARTS) {
            assertTrue(board.passable(start[0], start[1]),
                    "start (" + start[0] + "," + start[1] + ") is passable for seed " + seed);
        }
    }

    /** the explicit mirror invariant, not an inference from the generator. */
    private static void assertMirror(Board board, String seed) {
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                assertEquals(board.kindAt(col, row), board.kindAt(14 - col, row),
                        "seed " + seed + " is not mirror-symmetric at (" + col + "," + row + ")");
            }
        }
    }

    /** the 3x3 clearings around both starts and the carrot hold no obstacle. */
    private static void assertClearings(Board board, String seed) {
        for (int[] centre : CLEARINGS) {
            for (int row = Math.max(0, centre[1] - 1); row <= Math.min(8, centre[1] + 1); row++) {
                for (int col = Math.max(0, centre[0] - 1); col <= Math.min(14, centre[0] + 1); col++) {
                    assertTrue(!board.blocking(col, row),
                            "seed " + seed + " blocks the clearing at (" + col + "," + row
                                    + ") around (" + centre[0] + "," + centre[1] + ")");
                }
            }
        }
    }

    /** 27-37 blocking tiles inclusive. */
    private static void assertBlockingCount(Board board, String seed) {
        int blocking = 0;
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (board.blocking(col, row)) {
                    blocking++;
                }
            }
        }
        assertTrue(blocking >= 27 && blocking <= 37,
                "seed " + seed + " has " + blocking + " blocking tiles, expected 27-37");
    }

    /** both starts are 13-20 steps from the carrot, and the distances match. */
    private static void assertDistance(Board board, String seed) {
        int blue = distance(board, BoardGenerator.BLUE_START);
        int brown = distance(board, BoardGenerator.BROWN_START);
        assertTrue(blue >= 13 && blue <= 20,
                "seed " + seed + " needs " + blue + " steps from start A, expected 13-20");
        assertTrue(brown >= 13 && brown <= 20,
                "seed " + seed + " needs " + brown + " steps from start B, expected 13-20");
        assertEquals(blue, brown, "seed " + seed + " is not fair between the starts");
    }

    /**
     * The full 4-way BFS distance field over passable tiles from a start; -1 for
     * every passable tile the walk cannot reach, and for blocked tiles.
     */
    private static int[][] distanceField(Board board, int[] start) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        distance[start[1]][start[0]] = 0;
        queue.add(new int[]{start[0], start[1]});
        while (!queue.isEmpty()) {
            int[] cell = queue.remove();
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int col = cell[0] + step[0];
                int row = cell[1] + step[1];
                if (col < 0 || row < 0 || col >= board.cols() || row >= board.rows()
                        || !board.passable(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.add(new int[]{col, row});
            }
        }
        return distance;
    }

    /** 4-way BFS over passable tiles; -1 when the carrot is unreachable. */
    private static int distance(Board board, int[] start) {
        int[][] distance = new int[board.rows()][board.cols()];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        distance[start[1]][start[0]] = 0;
        queue.add(new int[]{start[0], start[1]});
        while (!queue.isEmpty()) {
            int[] cell = queue.remove();
            if (cell[0] == BoardGenerator.CARROT_COL && cell[1] == BoardGenerator.CARROT_ROW) {
                return distance[cell[1]][cell[0]];
            }
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int col = cell[0] + step[0];
                int row = cell[1] + step[1];
                if (col < 0 || row < 0 || col >= board.cols() || row >= board.rows()
                        || !board.passable(col, row) || distance[row][col] >= 0) {
                    continue;
                }
                distance[row][col] = distance[cell[1]][cell[0]] + 1;
                queue.add(new int[]{col, row});
            }
        }
        return -1;
    }

    /** 4-way BFS from a start over passable tiles. */
    private static boolean[][] reachable(Board board, int[] start) {
        boolean[][] reached = new boolean[board.rows()][board.cols()];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        reached[start[1]][start[0]] = true;
        queue.add(start);
        while (!queue.isEmpty()) {
            int[] cell = queue.remove();
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int col = cell[0] + step[0];
                int row = cell[1] + step[1];
                if (board.passable(col, row) && !reached[row][col]) {
                    reached[row][col] = true;
                    queue.add(new int[]{col, row});
                }
            }
        }
        return reached;
    }

    /** a passable tile with no passable orthogonal neighbour is an island. */
    private static void assertNoIsland(Board board, String seed) {
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (!board.passable(col, row)) {
                    continue;
                }
                int neighbours = 0;
                for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (board.passable(col + step[0], row + step[1])) {
                        neighbours++;
                    }
                }
                assertTrue(neighbours > 0, "seed " + seed + " leaves the passable tile at ("
                        + col + "," + row + ") as an island");
            }
        }
    }
}
