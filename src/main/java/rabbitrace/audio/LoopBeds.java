package rabbitrace.audio;

import rabbitrace.game.Match;

import java.util.function.LongSupplier;

/**
 * The two sound beds: {@code music.menu} on Título and
 * {@code amb.nature.loop} from entering Partida until Título.
 *
 * <p>Título → Partida crossfades once over {@link #CROSSFADE_MS}; Pausa, Ajustes
 * and a rematch over the board change no bed, so the ambience is started once
 * and keeps its playback position. Returning to Título crossfades back to the
 * menu loop. Entering Ajustes from Título leaves the menu loop alone.
 *
 * <p>The model is audio, not game state: nothing here
 * is part of the state dump. The clock is injectable so a test can pin the
 * 400 ms envelope without waiting on it.
 */
public final class LoopBeds {

    /** The crossfade lasts 400 ms in both directions. */
    public static final long CROSSFADE_MS = 400;

    /** The Título bed. */
    public static final String MUSIC_SLOT = "music.menu";

    /** The Partida bed, which survives Pausa, Ajustes and a rematch. */
    public static final String AMBIENCE_SLOT = "amb.nature.loop";

    /** One loop as a probe sees it. {@code gain} is the crossfade envelope (0–1). */
    public record Bed(String slot, boolean playing, long starts, double gain, long positionMs) {
    }

    /** The whole bed state: the chosen bed, the fade in progress, and both loops. */
    public record Snapshot(String bed, boolean crossfading, long crossfadeMs, long crossfades,
                           Bed music, Bed ambience) {
    }

    /** One loop's playback: whether it runs, how often it was started, where it is. */
    private static final class Loop {

        private final String slot;
        private boolean playing;
        private long starts;
        private double gain;
        private long playedMs;
        private long startedAtMs;

        private Loop(String slot) {
            this.slot = slot;
        }

        private void start(long now) {
            playing = true;
            starts++;
            playedMs = 0;
            startedAtMs = now;
            gain = 1.0;
        }

        private void stop(long now) {
            if (!playing) {
                return;
            }
            playedMs += Math.max(0, now - startedAtMs);
            playing = false;
            gain = 0.0;
        }

        private long positionMs(long now) {
            return playing ? playedMs + Math.max(0, now - startedAtMs) : playedMs;
        }
    }

    /** A fade in flight: from one loop (and its captured gain) to the other. */
    private static final class Crossfade {

        private final Loop from;
        private final Loop to;
        private final long startedAtMs;
        private final double fromGain;
        private final double toGain;

        private Crossfade(Loop from, Loop to, long now, double fromGain, double toGain) {
            this.from = from;
            this.to = to;
            this.startedAtMs = now;
            this.fromGain = fromGain;
            this.toGain = toGain;
        }
    }

    private final LongSupplier clock;
    private final Loop music = new Loop(MUSIC_SLOT);
    private final Loop ambience = new Loop(AMBIENCE_SLOT);
    /** The bed the current screen chooses, even while a fade is still running. */
    private String bed = MUSIC_SLOT;
    private Crossfade crossfade;
    private long crossfades;

    /** A model on the system clock, which is what a real launch uses. */
    public LoopBeds() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    /** A model on an injectable millisecond clock. */
    LoopBeds(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Tells the beds which screen the game is on. Only
     * Título and Partida change a bed: an overlay (Pausa, Ajustes, Confirmación)
     * or a rematch leaves the ambience running where it was.
     */
    public synchronized void enter(String screen) {
        long now = clock.getAsLong();
        if (Match.TITULO.equals(screen)) {
            choose(music, ambience, now);
        } else if (Match.PARTIDA.equals(screen)) {
            choose(ambience, music, now);
        }
    }

    private void choose(Loop target, Loop other, long now) {
        bed = target.slot;
        update(now);
        // Already the chosen bed: a pause, an overlay or a rematch is not a
        // restart and not a second crossfade.
        if (target.playing && (crossfade == null || crossfade.to == target)) {
            return;
        }
        boolean fresh = !target.playing;
        if (fresh) {
            target.start(now);
        }
        if (other.playing) {
            // A loop that just started enters the fade from silence; one caught
            // mid-fade carries its current envelope into the new fade.
            double from = fresh ? 0.0 : target.gain;
            target.gain = from;
            crossfade = new Crossfade(other, target, now, other.gain, from);
            crossfades++;
        } else {
            target.gain = 1.0;
        }
    }

    /**
     * Advances the fade to {@code now}. {@link AudioDirector}'s ticker calls it
     * every 25 ms, so the envelope moves while the app runs and not only when a
     * screen changes or a probe asks; both directions end at exactly
     * {@link #CROSSFADE_MS}.
     */
    private void update(long now) {
        if (crossfade == null) {
            return;
        }
        double progress = (now - crossfade.startedAtMs) / (double) CROSSFADE_MS;
        if (progress < 0) {
            progress = 0;
        }
        if (progress >= 1.0) {
            crossfade.from.gain = 0.0;
            crossfade.from.stop(now);
            crossfade.to.gain = 1.0;
            crossfade = null;
            return;
        }
        crossfade.from.gain = crossfade.fromGain * (1.0 - progress);
        crossfade.to.gain = crossfade.toGain + (1.0 - crossfade.toGain) * progress;
    }

    /** Moves the envelope to now; the director's ticker calls this every 25 ms. */
    public synchronized void tick() {
        update(clock.getAsLong());
    }

    /** The bed state right now, with the envelope advanced to this instant. */
    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        update(now);
        return new Snapshot(bed, crossfade != null, CROSSFADE_MS, crossfades,
                bedOf(music, now), bedOf(ambience, now));
    }

    private static Bed bedOf(Loop loop, long now) {
        return new Bed(loop.slot, loop.playing, loop.starts, loop.gain, loop.positionMs(now));
    }
}
