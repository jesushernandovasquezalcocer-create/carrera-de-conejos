package rabbitrace.audio;

import rabbitrace.assets.AudioAsset;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;
import java.util.Locale;

/**
 * One of the two loop beds, as a real audio line.
 *
 * <p>{@link LoopBeds} decides which bed is chosen and how far its 400 ms fade
 * has run; this class is the line that decision reaches. The amplitude written
 * to the line's {@code MASTER_GAIN} control is the formula times the bed's
 * crossfade envelope, and the source is streamed through {@code SourceDataLine}
 * by a daemon pump thread, so a loop longer than the heap is never held.
 *
 * <p>The placeholder kit's two loops synthesise silence; a run whose
 * manifest names a WAV streams that file. Either way the line, its gain control
 * and its frame position are the real ones — the probe reads them back instead
 * of trusting the model.
 */
final class BedLine implements AutoCloseable {

    /** Music is 44.1 kHz. */
    static final int MUSIC_RATE = 44_100;

    /** Ambience is 22.05 kHz. */
    static final int AMBIENCE_RATE = 22_050;

    /** What a probe can read from the line itself. */
    record Evidence(boolean open, boolean muted, double gainDb, long frames,
                    int sampleRate, double gain, long opens) {
    }

    private final String slot;
    private final Mixing.Line track;
    private final AudioDirector.LineOpener opener;
    private final Path file;
    private final AudioFormat format;
    private final int sampleRate;
    private final int chunkFrames;

    private SourceDataLine line;
    private FloatControl gain;
    private BooleanControl mute;
    private Thread pump;
    /** How many times this bed created a line: a restart closes one and opens another. */
    private long opens;
    private volatile boolean running;
    private volatile double envelope;
    private volatile Mixing.Levels levels;

    BedLine(String slot, Mixing.Group group, int gainDb, int sampleRate,
            AudioAsset asset, Path assetsRoot, AudioDirector.LineOpener opener) {
        this.slot = slot;
        this.track = new Mixing.Line(slot, group, gainDb);
        this.opener = opener;
        this.sampleRate = sampleRate;
        this.format = new AudioFormat(sampleRate, 16, 1, true, false);
        this.chunkFrames = Math.max(1, sampleRate / 20);
        this.file = asset != null && asset.usesFile() && assetsRoot != null
                ? assetsRoot.resolve(asset.file())
                : null;
        this.levels = new Mixing.Levels(100, 100, 100, false);
    }

    Mixing.Line track() {
        return track;
    }

    /**
     * Opens the line and takes its controls, without streaming yet. A device
     * that refuses a line gets the failure rule: silence and one log line,
     * never an exception into the game.
     */
    synchronized boolean open() {
        if (line == null) {
            try {
                SourceDataLine opened = opener.open(format);
                opened.open(format, chunkFrames * format.getFrameSize());
                opened.start();
                line = opened;
                gain = opened.isControlSupported(FloatControl.Type.MASTER_GAIN)
                        ? (FloatControl) opened.getControl(FloatControl.Type.MASTER_GAIN)
                        : null;
                mute = opened.isControlSupported(BooleanControl.Type.MUTE)
                        ? (BooleanControl) opened.getControl(BooleanControl.Type.MUTE)
                        : null;
                opens++;
            } catch (Exception e) {
                // failure is silence, with one log line.
                System.err.println("audio bed " + slot + " unavailable: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                line = null;
                gain = null;
                mute = null;
                return false;
            }
            if (mute != null) {
                mute.setValue(false);
            }
        } else if (!line.isOpen()) {
            try {
                line.open(format, chunkFrames * format.getFrameSize());
                line.start();
                opens++;
            } catch (Exception e) {
                System.err.println("audio bed " + slot + " unavailable: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                line = null;
                gain = null;
                mute = null;
                return false;
            }
        }
        return true;
    }

    /** Opens the line if needed, then streams the loop on a daemon pump. */
    synchronized boolean start() {
        if (!open()) {
            return false;
        }
        running = true;
        if (pump == null || !pump.isAlive()) {
            pump = new Thread(this::pump, "audio-bed-" + slot);
            pump.setDaemon(true);
            pump.start();
        }
        return true;
    }

    /** The fade has moved the bed to silence: stop streaming and mute the line. */
    synchronized void stop() {
        running = false;
        envelope = 0.0;
        if (line != null) {
            if (mute != null) {
                mute.setValue(true);
            }
            if (gain != null) {
                gain.setValue(gain.getMinimum());
            }
            line.stop();
            line.flush();
            line.close();
            line = null;
            gain = null;
            mute = null;
            pump = null;
        }
    }

    /** The formula times the crossfade envelope, written to the line control. */
    synchronized void apply(double envelope, Mixing.Levels levels) {
        this.envelope = Math.max(0.0, Math.min(1.0, envelope));
        this.levels = levels;
        if (line == null || gain == null) {
            return;
        }
        double base = track.amplitude(levels);
        double amplitude = base * this.envelope;
        if (levels.muted() || amplitude <= 0.0) {
            if (mute != null) {
                mute.setValue(true);
            }
            gain.setValue(gain.getMinimum());
            return;
        }
        if (mute != null) {
            mute.setValue(false);
        }
        float db = (float) Mixing.decibels(amplitude);
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
    }

    /** The line's own controls and playback position, and the envelope they carry. */
    synchronized Evidence evidence() {
        if (line == null || !line.isOpen()) {
            return new Evidence(false, false, 0.0, 0L, sampleRate, 0.0, opens);
        }
        boolean muted = mute != null && mute.getValue();
        double db = gain == null ? 0.0 : gain.getValue();
        long frames = Math.max(0L, line.getLongFramePosition());
        double base = levels == null ? 0.0 : track.amplitude(levels);
        double amplitude = muted ? 0.0 : Math.pow(10.0, db / 20.0);
        double carried = base <= 0.0 ? 0.0 : amplitude / base;
        carried = Math.max(0.0, Math.min(1.0, carried));
        return new Evidence(true, muted, round(db, 2), frames, sampleRate,
                round(carried, 3), opens);
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10.0, places);
        return Math.round(value * scale) / scale;
    }

    private void pump() {
        byte[] chunk = new byte[chunkFrames * format.getFrameSize()];
        SourceDataLine out = line;
        if (out == null) {
            return;
        }
        try {
            while (running) {
                if (file == null) {
                    // the placeholder kit's two loops are silence.
                    out.write(chunk, 0, chunk.length);
                } else {
                    try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
                        int read;
                        while (running && (read = in.read(chunk)) >= 0) {
                            out.write(chunk, 0, read);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // A closed or stopped line ends the pump; the slot stays silent.
            if (running && !(e instanceof IllegalStateException)) {
                System.err.println("audio bed " + slot + " stopped: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        } finally {
            running = false;
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (line != null) {
            line.stop();
            line.close();
            line = null;
            gain = null;
            mute = null;
        }
    }

    @Override
    public String toString() {
        return "BedLine[" + slot + ", " + String.format(Locale.ROOT, "%.3f", envelope) + "]";
    }
}
