package rabbitrace.assets;

import java.util.Arrays;

/**
 * The placeholder kit's synthesised cues.
 *
 * <p>A manifest entry names one of these instead of a file while the CC0 set is
 * not committed, which is the whole point of the boot kit: the slot still
 * resolves and swapping in a real WAV is a manifest edit, never a code edit
 * The names are the kit's own — an entry naming anything else is a
 * manifest error, not a silent fallback.
 */
public enum Synth {

    /** 1 kHz sine, 15 ms, 3 ms attack / 8 ms decay. */
    CLICK("click", 15),
    /** Two-note 440 → 554 Hz, 2 × 100 ms. */
    TURN_CHANGE("turn-change", 200),
    /** 660 → 880 Hz sine chirp, 90 ms. */
    HOP("hop", 90),
    /** 110 Hz sine plus a short noise burst, 120 ms. */
    BUMP_BLOCKED("bump-blocked", 120),
    /** Four filtered-noise clatters over 450 ms. */
    DICE_ROLL("dice-roll", 450),
    /** One 60 ms filtered-noise tick. */
    DICE_SETTLE("dice-settle", 60),
    /** Two-note 523 → 659 Hz, 2 × 90 ms. */
    RPS_REVEAL("rps-reveal", 180),
    /** 523 / 659 / 784 Hz arpeggio, 3 × 140 ms. */
    WIN("win", 420),
    /**
     * Silence, for the two beds while no audio file is committed. One second is
     * the kit's loop length: long enough for a bed to be a bed, short enough
     * that the placeholder costs nothing.
     */
    SILENCE("silence", 1000);

    private final String name;
    private final long durationMs;

    Synth(String name, long durationMs) {
        this.name = name;
        this.durationMs = durationMs;
    }

    /** The {@code synth} value a manifest entry writes. */
    public String synthName() {
        return name;
    }

    /** How long the kit renders this cue, in milliseconds. */
    public long durationMs() {
        return durationMs;
    }

    /** The cue with this name, or {@code null} when the kit does not know it. */
    public static Synth byName(String name) {
        return Arrays.stream(values())
                .filter(synth -> synth.name.equals(name))
                .findFirst()
                .orElse(null);
    }
}
