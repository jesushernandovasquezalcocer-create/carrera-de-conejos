package rabbitrace.audio;

/**
 * The one gain formula, applied to every open line.
 *
 * <p>Mute is a gain of zero, not a pause: a line keeps the position it
 * had, and unmuting recomputes the same amplitude from the stored slider values.
 * Neither the levels nor the positions are inputs a mute writes to, which is why
 * the same values restore the same gains.
 */
public final class Mixing {

    /** What a line belongs to; ambience rides the {@code sfx} group. */
    public enum Group {
        SFX,
        MUSIC
    }

    /** The slider snapshot behind the formula: the three volumes and mute. */
    public record Levels(int master, int music, int sfx, boolean muted) {

        public Levels {
            volume("master", master);
            volume("music", music);
            volume("sfx", sfx);
        }

        /** {@code master} is 0–1 after {@code master / 100}. */
        double masterAmplitude() {
            return master / 100.0;
        }

        /** The group factor, on the same 0–1 scale. */
        double groupAmplitude(Group group) {
            return (group == Group.MUSIC ? music : sfx) / 100.0;
        }
    }

    /** One open line: its slot's group and trim, and where playback currently is. */
    public static final class Line {

        private final String slot;
        private final Group group;
        private final int gainDb;
        private long positionFrames;

        public Line(String slot, Group group, int gainDb) {
            if (gainDb < -12 || gainDb > 6) {
                throw new IllegalArgumentException("gainDb is outside -12…+6: " + gainDb);
            }
            this.slot = slot;
            this.group = group;
            this.gainDb = gainDb;
        }

        /**
         * {@code amplitude = 10^(gainDb / 20) × master × group × (muted ? 0 : 1)}.
         *
         * <p>A mute returns zero without consuming or resetting the position, so
         * unmuting is the same calculation on the same stored values.
         */
        public double amplitude(Levels levels) {
            if (levels.muted()) {
                return 0.0;
            }
            return Math.pow(10.0, gainDb / 20.0)
                    * levels.masterAmplitude()
                    * levels.groupAmplitude(group);
        }

        /** Playback moved on by this many frames; a mute never calls this. */
        public void advance(long frames) {
            if (frames < 0) {
                throw new IllegalArgumentException("frames cannot be negative: " + frames);
            }
            positionFrames += frames;
        }

        public long positionFrames() {
            return positionFrames;
        }

        public String slot() {
            return slot;
        }

        public Group group() {
            return group;
        }

        public int gainDb() {
            return gainDb;
        }
    }

    /**
     * The linear amplitude as the decibels a line's gain control takes:
     * {@code gainDb = 20 × log10(amplitude)}. Silence has no finite decibel
     * value, so it comes back as negative infinity for the caller to clamp to
     * its control's minimum.
     */
    public static double decibels(double amplitude) {
        if (amplitude < 0) {
            throw new IllegalArgumentException("amplitude cannot be negative: " + amplitude);
        }
        return amplitude == 0.0 ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(amplitude);
    }

    private Mixing() {
    }

    private static void volume(String key, int value) {
        if (value < 0 || value > 100 || value % 5 != 0) {
            throw new IllegalArgumentException(key + " takes an integer 0-100 in steps of 5: " + value);
        }
    }
}
