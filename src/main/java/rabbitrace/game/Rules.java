package rabbitrace.game;

/**
 * The pure rules seam.
 *
 * <p>Breakpoints belong on these methods, never inside a painting callback: this
 * package is AWT-free, so a debug session can stop in the rules and read locals
 * with no window open.
 */
public final class Rules {

    private Rules() {
    }

    /**
     * Begins a match from one seed: normalising the
     * seed here is what fixes the board and every draw that follows it.
     */
    public static Match begin(String rawSeed) {
        String seed = Seed.normalise(rawSeed);
        Board board = BoardGenerator.generate(seed);
        return new Match(board);
    }
}
