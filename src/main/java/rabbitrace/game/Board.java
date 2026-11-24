package rabbitrace.game;

import java.util.List;

/**
 * One generated board: its tiles, the cosmetic dirt layer, and the exact PRNG
 * position that produced it.
 *
 * <p>Tiles carry the kind characters: grass is
 * passable and the four obstacle kinds are not. Dirt is cosmetic and lives in
 * its own layer, so decoration never changes passability.
 *
 * <p>{@code templateBoard} is true only for the fallback: a board loaded
 * from {@code assets/board/template-15x9.txt} after 500 attempts failed, reported
 * with the seed surface plus a visible "template board" mark rather than
 * pretending the seed produced the layout.
 */
public record Board(String seed, char[][] tiles, boolean[][] dirt, long rngCursor,
                    String canonical, String hash, boolean templateBoard) {

    public static final char GRASS = '.';
    public static final char ROCK = 'o';
    public static final char BUSH = 'b';
    public static final char STUMP = 't';
    public static final char FENCE = 'f';

    public int cols() {
        return tiles[0].length;
    }

    public int rows() {
        return tiles.length;
    }

    public boolean blocking(int col, int row) {
        return isBlocking(tiles[row][col]);
    }

    public boolean passable(int col, int row) {
        return inside(col, row) && !isBlocking(tiles[row][col]);
    }

    /** The kind a blocking tile carries; grass for a passable tile. */
    public char kindAt(int col, int row) {
        return tiles[row][col];
    }

    public static boolean isBlocking(char kind) {
        return kind == ROCK || kind == BUSH || kind == STUMP || kind == FENCE;
    }

    public boolean inside(int col, int row) {
        return col >= 0 && row >= 0 && row < rows() && col < cols();
    }

    public boolean dirtAt(int col, int row) {
        return dirt[row][col];
    }

    /**
     * This board with one tile replaced, and the canonical serialisation and hash
     * recomputed so {@code state} keeps describing the board the rules read.
     *
     * <p>Dev-only scene setup ({@code Commands}), for the rules' safety net:
     * the board rules never box a rabbit in, so a no-move turn can only be
     * reached on a hand-built board. The result deliberately need not satisfy
     * them.
     */
    public Board withTile(int col, int row, char kind) {
        if (!inside(col, row)) {
            throw new IllegalArgumentException("tile outside the board: " + col + "," + row);
        }
        char[][] nextTiles = new char[rows()][];
        boolean[][] nextDirt = new boolean[rows()][];
        for (int r = 0; r < rows(); r++) {
            nextTiles[r] = tiles[r].clone();
            nextDirt[r] = dirt[r].clone();
        }
        nextTiles[row][col] = kind;
        String nextCanonical = BoardGenerator.canonical(nextTiles);
        String nextHash = Fnv.hex(Fnv.hash64(nextCanonical));
        return new Board(seed, nextTiles, nextDirt, rngCursor, nextCanonical, nextHash, templateBoard);
    }

    /**
     * This board with the cosmetic decoration layer stripped: every tile
     * keeps its kind and the dirt layer reads empty. The tiles are copied, so the
     * stripped board shares no mutable state with this one.
     */
    public Board withoutDecoration() {
        char[][] bareTiles = new char[rows()][];
        for (int row = 0; row < rows(); row++) {
            bareTiles[row] = tiles[row].clone();
        }
        return new Board(seed, bareTiles, new boolean[rows()][cols()], rngCursor,
                canonical, hash, templateBoard);
    }

    /** The canonical 9-row serialisation. */
    public List<String> rowsAsStrings() {
        return canonical.lines().toList();
    }
}
