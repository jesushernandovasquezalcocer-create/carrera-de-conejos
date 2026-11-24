package rabbitrace.audio;

import rabbitrace.assets.AudioAsset;
import rabbitrace.assets.Synth;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.nio.file.Path;

/**
 * One SFX voice: a cue up to 2 s is held in a {@code Clip}.
 *
 * <p>A slot backed by a committed WAV opens that file; a slot the manifest
 * resolves to the placeholder kit is rendered here, in memory, at the
 * sample rate. The clip is what plays; the pool in {@link SfxVoices} only
 * decides which clips may be live.
 */
final class ClipVoice implements SfxVoices.Voice {

    /** SFX and music are 44.1 kHz. */
    static final int RATE = 44_100;

    private final Clip clip;

    private ClipVoice(Clip clip) {
        this.clip = clip;
    }

    /** Opens the asset on a fresh clip; the caller starts it through the pool. */
    static ClipVoice open(AudioAsset asset, Path assetsRoot) throws Exception {
        Clip clip = AudioSystem.getClip();
        if (asset.usesFile()) {
            Path file = assetsRoot == null ? Path.of(asset.file()) : assetsRoot.resolve(asset.file());
            try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
                clip.open(in);
            }
        } else {
            byte[] pcm = samples(asset.synth(), RATE);
            clip.open(new AudioFormat(RATE, 16, 1, true, false), pcm, 0, pcm.length);
        }
        return new ClipVoice(clip);
    }

    @Override
    public void start() {
        clip.start();
    }

    @Override
    public void stop() {
        clip.stop();
        clip.flush();
        clip.close();
    }

    @Override
    public boolean isRunning() {
        return clip.isRunning();
    }

    /** The opened clip's length in sample frames; 0 if no clip is open. */
    @Override
    public long frames() {
        return clip.getFrameLength();
    }

    /**
     * The placeholder kit's cue, synthesised to 16-bit mono PCM.
     *
     * <p>The recipes are the kit's, not a general chiptune synth: one or more
     * sine notes with a short attack/decay, or a deterministic noise burst. No
     * random source and no wall clock, so the same cue renders the same bytes.
     */
    static byte[] samples(String synthName, int rate) {
        Synth synth = Synth.byName(synthName);
        if (synth == null) {
            return new byte[0];
        }
        int frames = (int) (synth.durationMs() * rate / 1000L);
        double[] out = new double[frames];
        switch (synth) {
            case CLICK -> tone(out, rate, 0, frames, 1000, 1000, 0.6, 3, 8);
            case TURN_CHANGE -> {
                int half = frames / 2;
                tone(out, rate, 0, half, 440, 440, 0.5, 5, 20);
                tone(out, rate, half, frames - half, 554, 554, 0.5, 5, 20);
            }
            case HOP -> tone(out, rate, 0, frames, 660, 880, 0.5, 5, 25);
            case BUMP_BLOCKED -> {
                tone(out, rate, 0, frames, 110, 110, 0.5, 5, 40);
                noise(out, 0, frames / 2, 0.25);
            }
            case DICE_ROLL -> {
                int clatter = Math.max(1, frames / 8);
                for (int i = 0; i < 4; i++) {
                    noise(out, i * frames / 4, clatter, 0.35);
                }
            }
            case DICE_SETTLE -> noise(out, 0, frames, 0.25);
            case RPS_REVEAL -> {
                int half = frames / 2;
                tone(out, rate, 0, half, 523, 523, 0.5, 5, 15);
                tone(out, rate, half, frames - half, 659, 659, 0.5, 5, 15);
            }
            case WIN -> {
                int third = frames / 3;
                tone(out, rate, 0, third, 523, 523, 0.45, 5, 15);
                tone(out, rate, third, third, 659, 659, 0.45, 5, 15);
                tone(out, rate, 2 * third, frames - 2 * third, 784, 784, 0.45, 5, 40);
            }
            case SILENCE -> {
                // the two beds are silence while no file is committed.
            }
        }
        return encode(out);
    }

    private static void tone(double[] out, int rate, int start, int length,
                             double fromHz, double toHz, double gain,
                             int attackMs, int releaseMs) {
        if (length <= 0) {
            return;
        }
        double seconds = (double) length / rate;
        for (int i = 0; i < length && start + i < out.length; i++) {
            double t = (double) i / rate;
            double hz = fromHz + (toHz - fromHz) * i / length;
            double phase = 2.0 * Math.PI * (fromHz * t + (hz - fromHz) * t * t / (2.0 * seconds));
            out[start + i] += gain * envelope(i, length, rate, attackMs, releaseMs) * Math.sin(phase);
        }
    }

    /** A deterministic noise burst with the same attack/decay shape. */
    private static void noise(double[] out, int start, int length, double gain) {
        long state = 0x2545F4914F6CDD1DL;
        int attack = Math.max(1, length / 10);
        for (int i = 0; i < length && start + i < out.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            double value = ((state >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
            double decay = i < attack ? (double) i / attack : 1.0 - (double) i / length;
            out[start + i] += gain * value * Math.max(0.0, decay);
        }
    }

    private static double envelope(int frame, int length, int rate,
                                   int attackMs, int releaseMs) {
        int attack = Math.max(1, attackMs * rate / 1000);
        int release = Math.max(1, releaseMs * rate / 1000);
        if (frame < attack) {
            return (double) frame / attack;
        }
        int remaining = length - frame;
        if (remaining < release) {
            return (double) remaining / release;
        }
        return 1.0;
    }

    private static byte[] encode(double[] samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double clamped = Math.max(-1.0, Math.min(1.0, samples[i]));
            int value = (int) Math.round(clamped * 32767.0);
            pcm[2 * i] = (byte) (value & 0xFF);
            pcm[2 * i + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }
}
