package rabbitrace.render;

import rabbitrace.game.Match;

/**
 * The die as presentation: which face it shows and the
 * small tilt, bob and scale the painter gives it while the tumble runs.
 *
 * <p>Pure math, like the rest of the painter: no window, no clock and no
 * random. The caller supplies the tumble's elapsed fraction, read from the same
 * beat clock that releases the 600 ms {@code dieTumble} beat, so the tumble adds
 * no second timer and the duration stays the only one.
 *
 * <p>The cycle is {@link #STEPS} faces over the tumble — 600 ms / 8 = 75 ms per
 * face, inside the 70–90 ms read of the beat — and the last fifth of the tumble
 * eases through a {@link #POP} overshoot before the face rests. A negative
 * fraction means "no live tumble": the die stands still and shows the rolled
 * value, which is exactly what the offline {@code frame} probe renders, so that
 * probe stays deterministic.
 */
public final class Die {

    /** Faces across one tumble: 600 ms / 8 = 75 ms each. */
    public static final int STEPS = 8;

    /** Largest tilt during the tumble, in degrees; the rest pose is upright. */
    public static final double MAX_TILT = 9;

    /** Largest vertical bob during the tumble, in virtual pixels. */
    public static final int MAX_BOB = 3;

    /** The settle pop's peak overshoot: 1.10 is 10% larger than the rest face. */
    public static final double POP = 0.10;

    /** The fraction of the tumble where the settle pop starts. */
    private static final double POP_START = 0.8;

    /**
     * One rendered state of the die: the face 1–6, the tilt in degrees, the
     * vertical bob in virtual pixels (positive is down) and the face's scale.
     */
    public record Pose(int face, double tilt, double offsetY, double scale) {
    }

    private Die() {
    }

    /**
     * The die's pose for a phase, the rolled value and the tumble's elapsed
     * fraction. {@code roll} is the value the match already drew (public in
     * {@code Rolling}), or {@code null} when no die is in hand; then the dim
     * face is 1. {@code tumble} is in {@code [0, 1)} while the window's tumble
     * is live and negative when it is not, so the offline probe gets a fixed
     * face and no motion.
     */
    public static Pose pose(String phase, Integer roll, double tumble) {
        int value = roll == null ? 1 : Math.max(1, Math.min(6, roll));
        if (!Match.ROLLING.equals(phase) || tumble < 0) {
            return new Pose(value, 0, 0, 1);
        }
        double progress = Math.max(0, Math.min(1, tumble));
        // Eight faces over the tumble: step 0 at 0 ms, step 7 at 525 ms. The
        // roll is the value the die must settle on, so the faces count down to
        // its neighbour and the settle is one last step.
        int step = Math.min(STEPS - 1, (int) (progress * STEPS));
        int face = 1 + Math.floorMod(value - 1 + step * 5, 6);
        double wave = 2 * Math.PI * 3 * progress;
        double damping = 1 - progress * 0.6;
        double tilt = MAX_TILT * Math.sin(wave) * damping;
        // Two bobs over the tumble, on their own phase from the tilt, and both
        // start and end at the rest height so the window never jumps.
        double offsetY = MAX_BOB * Math.sin(2 * Math.PI * 2 * progress) * damping;
        return new Pose(face, tilt, offsetY, settlePop(progress));
    }

    /**
     * The settle pop: flat until {@link #POP_START}, then one smooth overshoot
     * that returns to the rest size exactly as the tumble ends. No timer is
     * involved — it is the tumble's own fraction.
     */
    private static double settlePop(double progress) {
        if (progress < POP_START) {
            return 1;
        }
        double within = (progress - POP_START) / (1 - POP_START);
        return 1 + POP * Math.sin(Math.PI * within);
    }
}
