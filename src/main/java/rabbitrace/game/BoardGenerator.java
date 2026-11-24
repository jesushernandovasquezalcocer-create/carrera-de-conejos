package rabbitrace.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * The board generator: grass, the three clearings,
 * mirror-paired obstacle clusters, then cosmetic dirt.
 *
 * <p>Each attempt is drawn from one SplitMix64 stream seeded per. An attempt
 * is accepted only when it satisfies, and only after the
 * repair has cleared every violation on the board that is returned; rejected
 * attempts replay the pipeline with the next draws, up to 500 attempts. When all
 * 500 fail, fallback template is returned flagged as a template board
 *
 */
public final class BoardGenerator {

    public static final int VERSION = 1;
    public static final int COLS = 15;
    public static final int ROWS = 9;
    public static final int CARROT_COL = 7;
    public static final int CARROT_ROW = 0;
    public static final int[] BLUE_START = {2, 8};
    public static final int[] BROWN_START = {12, 8};

    private static final int MIN_BLOCKING = 27;
    private static final int MAX_BLOCKING = 37;
    /** step 5: a seed gets this many attempts before fallback. */
    static final int MAX_ATTEMPTS = 500;
    private static final Path TEMPLATE = Path.of("assets", "board", "template-15x9.txt");
    private static final char[] KINDS = {
            Board.ROCK, Board.BUSH, Board.STUMP, Board.FENCE};
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private BoardGenerator() {
    }

    public static Board generate(String rawSeed) {
        return generate(rawSeed, BoardGenerator::valid);
    }

    /**
     * The pipeline with an explicit validation rule, so a test can force
     * every attempt to fail and reach fallback. Production always passes
     * {@link #valid}.
     */
    static Board generate(String rawSeed, Predicate<char[][]> validator) {
        String seed = Seed.normalise(rawSeed);
        long h = Fnv.hash64("rabbit-race/board/" + VERSION + "/" + seed);
        SplitMix64 rng = new SplitMix64(h);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            char[][] tiles = attempt(rng);
            if (tiles == null) {
                continue;
            }
            // repairs rather than discarding an attempt outright; a
            // board that cannot be repaired, or that the repair invalidates, is
            // rejected and the next draws start a new attempt.
            if (!baseValid(tiles) || !repair(tiles) || !validator.test(tiles)) {
                continue;
            }
            boolean[][] dirt = paintDirt(tiles, rng);
            String canonical = canonical(tiles);
            String hash = Fnv.hex(Fnv.hash64(canonical));
            return new Board(seed, tiles, dirt, rng.cursor(), canonical, hash, false);
        }
        // step 6: 500 attempts failed, so the template is returned and
        // flagged rather than throwing, so the seed surface can mark the board.
        return template(seed, rng.cursor());
    }

    /**
     * fallback: {@code assets/board/template-15x9.txt}, nine rows of
     * fifteen legend characters ({@code.} grass, {@code,} dirt, {@code C}
     * carrot, {@code 1}/{@code 2} the starts, {@code o b t f} obstacles).
     *
     * <p>The template is authored as the left half plus the centre column; the
     * loader writes each tile and its mirror, so holds by construction. The
     * loaded board is validated against here, so an edited template that
     * stops satisfying them fails loudly instead of silently rotting.
     */
    private static Board template(String seed, long cursor) {
        List<String> lines;
        try {
            lines = Files.readAllLines(TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the fallback template "
                    + TEMPLATE.toAbsolutePath(), e);
        }
        if (lines.size() != ROWS) {
            throw new IllegalStateException(" " + TEMPLATE + " has " + lines.size()
                    + " rows, expected " + ROWS);
        }

        char[][] tiles = new char[ROWS][COLS];
        boolean[][] dirt = new boolean[ROWS][COLS];
        for (int row = 0; row < ROWS; row++) {
            String line = lines.get(row).strip();
            if (line.length() != COLS) {
                throw new IllegalStateException(" " + TEMPLATE + " row " + row
                        + " is " + line.length() + " characters, expected " + COLS);
            }
            for (int col = 0; col < COLS; col++) {
                char legend = line.charAt(col);
                tiles[row][col] = switch (legend) {
                    case '.', 'C', '1', '2' -> Board.GRASS;
                    case ',' -> Board.GRASS;
                    case 'o' -> Board.ROCK;
                    case 'b' -> Board.BUSH;
                    case 't' -> Board.STUMP;
                    case 'f' -> Board.FENCE;
                    default -> throw new IllegalStateException(" " + TEMPLATE
                            + " row " + row + " has the unknown legend character '" + legend + "'");
                };
                dirt[row][col] = legend == ',';
            }
        }
        if (tiles[CARROT_ROW][CARROT_COL] != Board.GRASS
                || lines.get(CARROT_ROW).strip().charAt(CARROT_COL) != 'C') {
            throw new IllegalStateException(" " + TEMPLATE
                    + " must mark the carrot as 'C' at (" + CARROT_COL + "," + CARROT_ROW + ")");
        }

        // by construction: columns 0-7 dictate their mirrors.
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col <= CARROT_COL; col++) {
                tiles[row][mirror(col)] = tiles[row][col];
                dirt[row][mirror(col)] = dirt[row][col];
            }
        }
        if (!valid(tiles)) {
            throw new IllegalStateException(" " + TEMPLATE
                    + " does not satisfy; the fallback cannot be used");
        }
        String canonical = canonical(tiles);
        String hash = Fnv.hex(Fnv.hash64(canonical));
        return new Board(seed, tiles, dirt, cursor, canonical, hash, true);
    }

    /** One pipeline attempt, or {@code null} when it cannot reach the count. */
    private static char[][] attempt(SplitMix64 rng) {
        char[][] tiles = new char[ROWS][COLS];
        for (char[] row : tiles) {
            Arrays.fill(row, Board.GRASS);
        }

        int guard = 0;
        while (blockingCount(tiles) < MIN_BLOCKING) {
            if (++guard > 500) {
                return null;
            }
            char kind = KINDS[rng.nextInt(KINDS.length)];
            placeCluster(tiles, kind, rng);
        }
        return tiles;
    }

    /**
     * Tries one cluster: generated in shape, written into columns 0–7
     * only, mirrored to {@code 14 - col}. An invalid cluster is redrawn.
     */
    private static void placeCluster(char[][] tiles, char kind, SplitMix64 rng) {
        int[][] shape = shape(kind, rng);
        shape = normalise(shape);
        int width = 0;
        int height = 0;
        for (int[] cell : shape) {
            width = Math.max(width, cell[0] + 1);
            height = Math.max(height, cell[1] + 1);
        }
        if (width > 8 || height > ROWS) {
            return;
        }
        int anchorCol = width == 8 ? 0 : rng.nextInt(8 - width);
        int anchorRow = height == ROWS ? 0 : rng.nextInt(ROWS - height);

        for (int[] cell : shape) {
            int col = anchorCol + cell[0];
            int row = anchorRow + cell[1];
            if (reserved(col, row) || reserved(mirror(col), row)) {
                return;
            }
            if (col != 7 && (tiles[row][col] != Board.GRASS
                    || tiles[row][mirror(col)] != Board.GRASS)) {
                return;
            }
        }

        for (int[] cell : shape) {
            int col = anchorCol + cell[0];
            int row = anchorRow + cell[1];
            if (blocking(tiles, col, row)) {
                // Column 7 is the expected mirror overlap; it is counted once.
                continue;
            }
            tiles[row][col] = kind;
            if (col != 7) {
                tiles[row][mirror(col)] = kind;
            }
        }
    }

    private static int[][] shape(char kind, SplitMix64 rng) {
        if (kind == Board.STUMP) {
            return new int[][]{{0, 0}};
        }
        if (kind == Board.ROCK) {
            if (rng.nextInt(2) == 0) {
                return new int[][]{{0, 0}};
            }
            return rng.nextInt(2) == 0
                    ? new int[][]{{0, 0}, {1, 0}}
                    : new int[][]{{0, 0}, {0, 1}};
        }
        if (kind == Board.FENCE) {
            int length = 2 + rng.nextInt(3);
            int[][] cells = new int[length][2];
            boolean horizontal = rng.nextInt(2) == 0;
            for (int i = 0; i < length; i++) {
                cells[i][0] = horizontal ? i : 0;
                cells[i][1] = horizontal ? 0 : i;
            }
            return cells;
        }
        // BUSH: a 4-connected blob of 2-4 tiles, grown from a seed cell.
        int size = 2 + rng.nextInt(3);
        List<int[]> cells = new ArrayList<>();
        cells.add(new int[]{0, 0});
        int guard = 0;
        while (cells.size() < size && ++guard < 40) {
            int[] from = cells.get(rng.nextInt(cells.size()));
            int[] step = switch (rng.nextInt(4)) {
                case 0 -> new int[]{1, 0};
                case 1 -> new int[]{-1, 0};
                case 2 -> new int[]{0, 1};
                default -> new int[]{0, -1};
            };
            int[] next = {from[0] + step[0], from[1] + step[1]};
            if (cells.stream().noneMatch(cell -> cell[0] == next[0] && cell[1] == next[1])) {
                cells.add(next);
            }
        }
        return cells.toArray(new int[0][]);
    }

    private static int[][] normalise(int[][] cells) {
        int minCol = Integer.MAX_VALUE;
        int minRow = Integer.MAX_VALUE;
        for (int[] cell : cells) {
            minCol = Math.min(minCol, cell[0]);
            minRow = Math.min(minRow, cell[1]);
        }
        int[][] out = new int[cells.length][2];
        for (int i = 0; i < cells.length; i++) {
            out[i][0] = cells[i][0] - minCol;
            out[i][1] = cells[i][1] - minRow;
        }
        return out;
    }

    /** 2-4 mirrored blobs of 2-4 passable grass tiles, cosmetic only. */
    private static boolean[][] paintDirt(char[][] tiles, SplitMix64 rng) {
        boolean[][] dirt = new boolean[ROWS][COLS];
        int blobs = 2 + rng.nextInt(3);
        for (int i = 0; i < blobs; i++) {
            int col = rng.nextInt(8);
            int row = rng.nextInt(ROWS);
            int size = 2 + rng.nextInt(3);
            for (int step = 0; step < size; step++) {
                int c = Math.min(7, col + (step % 2));
                int r = Math.min(ROWS - 1, row + (step / 2));
                if (!reserved(c, r) && tiles[r][c] == Board.GRASS) {
                    dirt[r][c] = true;
                    dirt[r][mirror(c)] = true;
                }
            }
        }
        return dirt;
    }

    /**
     * Every rule is checked explicitly on the returned board rather than
     * inferred from the generator. The check covers everything except the shape
     * rules, which the repair pass handles instead of rejecting on first sight.
     */
    static boolean baseValid(char[][] tiles) {
        if (tiles.length != ROWS || tiles[0].length != COLS) {
            return false;
        }
        for (char[] row : tiles) {
            for (char kind : row) {
                if (kind != Board.GRASS && !Board.isBlocking(kind)) {
                    return false;
                }
            }
        }
        if (blocking(tiles, CARROT_COL, CARROT_ROW)
                || blocking(tiles, BLUE_START[0], BLUE_START[1])
                || blocking(tiles, BROWN_START[0], BROWN_START[1])) {
            return false;
        }
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                if (reserved(col, row) && blocking(tiles, col, row)) {
                    return false;
                }
                if (tiles[row][col] != tiles[row][mirror(col)]) {
                    return false;
                }
            }
        }
        int blocking = blockingCount(tiles);
        if (blocking < MIN_BLOCKING || blocking > MAX_BLOCKING) {
            return false;
        }
        int blue = distance(tiles, BLUE_START[0], BLUE_START[1], CARROT_COL, CARROT_ROW);
        int brown = distance(tiles, BROWN_START[0], BROWN_START[1], CARROT_COL, CARROT_ROW);
        return blue >= 13 && blue <= 20 && brown == blue && connected(tiles);
    }

    /** on the board that is returned, after any repair. */
    static boolean valid(char[][] tiles) {
        return baseValid(tiles) && violations(tiles).isEmpty();
    }

    /**
     * deterministic repair, in place. While a violation exists and
     * the blocking count is above 27, unblock the lowest-indexed blocking tile
     * whose removal clears at least one violation, together with its mirror tile.
     * A board that cannot be repaired without breaking another guarantee, or that
     * would fall below 27 blocking tiles, is rejected.
     *
     * @return whether the board is now free of violations
     */
    private static boolean repair(char[][] tiles) {
        while (true) {
            List<String> before = violations(tiles);
            if (before.isEmpty()) {
                return true;
            }
            if (blockingCount(tiles) <= MIN_BLOCKING) {
                return false;
            }

            int chosenCol = -1;
            int chosenRow = -1;
            for (int row = 0; row < ROWS && chosenCol < 0; row++) {
                for (int col = 0; col < COLS; col++) {
                    if (!blocking(tiles, col, row)) {
                        continue;
                    }
                    char kind = tiles[row][col];
                    char mirrorKind = tiles[row][mirror(col)];
                    tiles[row][col] = Board.GRASS;
                    tiles[row][mirror(col)] = Board.GRASS;
                    boolean clears = clearsOne(before, violations(tiles));
                    tiles[row][col] = kind;
                    tiles[row][mirror(col)] = mirrorKind;
                    if (clears) {
                        chosenCol = col;
                        chosenRow = row;
                        break;
                    }
                }
            }
            if (chosenCol < 0) {
                return false;
            }

            tiles[chosenRow][chosenCol] = Board.GRASS;
            tiles[chosenRow][mirror(chosenCol)] = Board.GRASS;
            // re-validate after each unblock, and stop if the count falls
            // below the 27 blocking tiles requires.
            if (blockingCount(tiles) < MIN_BLOCKING || !baseValid(tiles)) {
                return false;
            }
        }
    }

    /** Whether {@code after} has cleared at least one violation {@code before} had. */
    private static boolean clearsOne(List<String> before, List<String> after) {
        for (String violation : before) {
            if (!after.contains(violation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The violations of a board, each with a stable id so the repair can tell
     * which one a candidate unblock clears: {@code D} for a dead end, {@code H} and
     * {@code V} for a horizontal or vertical pinch run of 4+.
     */
    static List<String> violations(char[][] tiles) {
        List<String> found = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                if (!blocking(tiles, col, row) && passableNeighbours(tiles, col, row) < 2) {
                    found.add("D" + col + "," + row);
                }
            }
        }
        for (int row = 0; row < ROWS; row++) {
            int run = 0;
            for (int col = 0; col <= COLS; col++) {
                if (col < COLS && pinch(tiles, col, row)) {
                    run++;
                } else {
                    if (run >= 4) {
                        found.add("H" + row + "," + (col - run) + "," + run);
                    }
                    run = 0;
                }
            }
        }
        for (int col = 0; col < COLS; col++) {
            int run = 0;
            for (int row = 0; row <= ROWS; row++) {
                if (row < ROWS && pinch(tiles, col, row)) {
                    run++;
                } else {
                    if (run >= 4) {
                        found.add("V" + col + "," + (row - run) + "," + run);
                    }
                    run = 0;
                }
            }
        }
        return found;
    }

    private static int passableNeighbours(char[][] tiles, int col, int row) {
        int count = 0;
        for (int[] step : STEPS) {
            int nextCol = col + step[0];
            int nextRow = row + step[1];
            if (nextCol >= 0 && nextRow >= 0 && nextCol < COLS && nextRow < ROWS
                    && !blocking(tiles, nextCol, nextRow)) {
                count++;
            }
        }
        return count;
    }

    /**
     * pinch: a passable tile whose only two passable orthogonal neighbours are
     * opposite each other. Board edges count as blocked, so an edge tile with two
     * in-board neighbours on opposite sides of its row or column is a pinch.
     */
    private static boolean pinch(char[][] tiles, int col, int row) {
        if (blocking(tiles, col, row)) {
            return false;
        }
        boolean left = open(tiles, col - 1, row);
        boolean right = open(tiles, col + 1, row);
        boolean up = open(tiles, col, row - 1);
        boolean down = open(tiles, col, row + 1);
        return (left && right && !up && !down) || (up && down && !left && !right);
    }

    private static boolean open(char[][] tiles, int col, int row) {
        return col >= 0 && row >= 0 && col < COLS && row < ROWS && !blocking(tiles, col, row);
    }

    /**
     * one component. A 4-way walk from the carrot must reach every passable
     * tile, so there are no pockets and no unreachable island. The carrot is
     * already known passable when this is called.
     */
    static boolean connected(char[][] tiles) {
        boolean[][] seen = new boolean[ROWS][COLS];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen[CARROT_ROW][CARROT_COL] = true;
        queue.add(new int[]{CARROT_COL, CARROT_ROW});
        int reached = 1;
        while (!queue.isEmpty()) {
            int[] cell = queue.remove();
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int col = cell[0] + step[0];
                int row = cell[1] + step[1];
                if (col < 0 || row < 0 || col >= COLS || row >= ROWS
                        || seen[row][col] || blocking(tiles, col, row)) {
                    continue;
                }
                seen[row][col] = true;
                reached++;
                queue.add(new int[]{col, row});
            }
        }
        return reached == ROWS * COLS - blockingCount(tiles);
    }

    static int distance(char[][] tiles, int fromCol, int fromRow, int toCol, int toRow) {
        int[][] distance = new int[ROWS][COLS];
        for (int[] row : distance) {
            Arrays.fill(row, -1);
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        distance[fromRow][fromCol] = 0;
        queue.add(new int[]{fromCol, fromRow});
        while (!queue.isEmpty()) {
            int[] cell = queue.remove();
            int col = cell[0];
            int row = cell[1];
            if (col == toCol && row == toRow) {
                return distance[row][col];
            }
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nextCol = col + step[0];
                int nextRow = row + step[1];
                if (nextCol < 0 || nextRow < 0 || nextCol >= COLS || nextRow >= ROWS
                        || blocking(tiles, nextCol, nextRow)
                        || distance[nextRow][nextCol] >= 0) {
                    continue;
                }
                distance[nextRow][nextCol] = distance[row][col] + 1;
                queue.add(new int[]{nextCol, nextRow});
            }
        }
        return -1;
    }

    private static boolean blocking(char[][] tiles, int col, int row) {
        return Board.isBlocking(tiles[row][col]);
    }

    private static int blockingCount(char[][] tiles) {
        int count = 0;
        for (char[] row : tiles) {
            for (char kind : row) {
                if (Board.isBlocking(kind)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int mirror(int col) {
        return COLS - 1 - col;
    }

    /** The 3x3 clearings around both starts and the carrot, plus the carrot tile. */
    static boolean reserved(int col, int row) {
        return near(BLUE_START[0], BLUE_START[1], col, row)
                || near(BROWN_START[0], BROWN_START[1], col, row)
                || near(CARROT_COL, CARROT_ROW, col, row);
    }

    private static boolean near(int cc, int cr, int col, int row) {
        return Math.abs(cc - col) <= 1 && Math.abs(cr - row) <= 1;
    }

    /** Rows of kind characters, newline separated; this is the hashed form. */
    public static String canonical(char[][] tiles) {
        StringBuilder sb = new StringBuilder(ROWS * (COLS + 1));
        for (int row = 0; row < ROWS; row++) {
            if (row > 0) {
                sb.append('\n');
            }
            sb.append(tiles[row]);
        }
        return sb.toString();
    }
}
