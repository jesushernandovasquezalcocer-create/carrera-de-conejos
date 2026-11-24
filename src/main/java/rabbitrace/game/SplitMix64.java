package rabbitrace.game;

/**
 * SplitMix64 with an explicit draw counter.
 *
 * <p>The counter is the contract: {@code (seed, cursor)} pins the stream position,
 * and {@link #cursor()} is what the state dump reports as {@code rngCursor}.
 * {@code java.util.Random} and {@code Math.random} are banned,
 * so every draw in the game goes through here.
 */
public final class SplitMix64 {

    private static final long GAMMA = 0x9e3779b97f4a7c15L;
    private static final long MIX_A = 0xbf58476d1ce4e5b9L;
    private static final long MIX_B = 0x94d049bb133111ebL;

    private long state;
    private long cursor;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        state += GAMMA;
        cursor++;
        long z = state;
        z = (z ^ (z >>> 30)) * MIX_A;
        z = (z ^ (z >>> 27)) * MIX_B;
        return z ^ (z >>> 31);
    }

    /** Bounded draw, deterministic across machines and JDKs. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        return (int) Long.remainderUnsigned(nextLong(), bound);
    }

    /** Draws consumed so far; the state dump's {@code rngCursor}. */
    public long cursor() {
        return cursor;
    }
}
