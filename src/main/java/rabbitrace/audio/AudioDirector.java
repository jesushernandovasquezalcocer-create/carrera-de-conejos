package rabbitrace.audio;

import rabbitrace.assets.AudioAsset;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The audio side of the shell.
 *
 * <p>An automation run is silent unless {@code -Dautomation.audio=1} is present, and a
 * headless run or a run with {@code -Dautomation.animations=0} is silent regardless.
 * A silent director never asks {@link AudioSystem} for a line: no device is
 * opened, and audio cannot touch state or timing. A missing device is silence
 * with one log line, never an exception into the game.
 *
 * <p>The two beds are real lines ({@link BedLine}): {@code music.menu} is
 * opened at launch, {@code amb.nature.loop} when a match starts, and both stream
 * through {@code SourceDataLine}. {@link #screen(String)} asks {@link LoopBeds}
 * which bed the current screen chooses; a daemon ticker advances the 400 ms
 * envelope every 25 ms and writes {@code formula × envelope} to each line's gain
 * control, so the crossfade is audible while the app runs rather than only when
 * a probe asks. Mute is a gain of zero, never a pause. Audio is never game state
 * which is why nothing here is visible through the
 * state dump.
 */
public final class AudioDirector implements AutoCloseable {

    /** The Título bed. */
    static final String LOOP_SLOT = LoopBeds.MUSIC_SLOT;

    /** What the ready line reports, and what the scenario replay asserts. */
    public enum Status {
        /** No line was requested or the mode forbids one. */
        SILENT,
        /** A line was opened and is held for the life of the run. */
        OPEN,
        /** A line was requested but the device would not give one. */
        UNAVAILABLE
    }

    /** The device call, injectable so a test can prove silence never opens a line. */
    interface LineOpener {
        SourceDataLine open(javax.sound.sampled.AudioFormat format) throws Exception;
    }

    /** The SFX device call, injectable so a test can prove the voice pool's rule. */
    interface VoiceOpener {
        SfxVoices.Voice open(AudioAsset asset, Path assetsRoot) throws Exception;
    }

    /** What the {@code audio} probe reads from a bed's real line. */
    public record LineEvidence(boolean open, boolean muted, double gainDb, long frames,
                               int sampleRate, double gain, long opens) {
    }

    private final Status status;
    /** The menu bed; opened at launch so the gain formula always has a line. */
    private final BedLine music;
    /** The ambience bed; opened the first time a match starts. */
    private final BedLine ambience;
    /** The slots this run can play; a missing or broken one is already silent. */
    private final SoundBank bank;
    /** The two beds and their 400 ms crossfade; audio, never game state. */
    private final LoopBeds beds;
    private final ScheduledExecutorService ticker;
    /** The four-voice SFX pool that every cue passes through. */
    private final SfxVoices voices;
    /** Opens one cue; a real run uses a {@code Clip}, a test uses a double. */
    private final VoiceOpener voiceOpener;
    private final Path assetsRoot;
    private volatile Mixing.Levels levels =
            new Mixing.Levels(100, 100, 100, false);
    /** False until the launch screen chose a bed (before that, the line plays the formula). */
    private volatile boolean screenChosen;

    private AudioDirector(Status status, BedLine music, BedLine ambience, SoundBank bank,
                          LoopBeds beds, ScheduledExecutorService ticker,
                          SfxVoices voices, VoiceOpener voiceOpener, Path assetsRoot) {
        this.status = status;
        this.music = music;
        this.ambience = ambience;
        this.bank = bank;
        this.beds = beds;
        this.ticker = ticker;
        this.voices = voices;
        this.voiceOpener = voiceOpener;
        this.assetsRoot = assetsRoot;
    }

    private static AudioDirector silent(Status status) {
        return new AudioDirector(status, null, null, SoundBank.silent(), new LoopBeds(), null,
                new SfxVoices(), null, null);
    }

    /**
     * @param requested {@code -Dautomation.audio=1} (or {@code true})
     * @param headless {@code -Dautomation.headless=true}
     * @param animations whether the animation table runs ({@code -Dautomation.animations}
     *                   is not {@code 0})
     */
    public static AudioDirector start(boolean requested, boolean headless, boolean animations) {
        return start(requested, headless, animations, AudioSystem::getSourceDataLine);
    }

    /**
     * Starts the audio side and loads the cue bank from the committed manifest.
     * A silent mode reads no manifest and opens no line; a sounding run resolves
     * each slot through {@link SoundBank}, where
     * failures are silence plus one log line rather than an exception.
     *
     * <p>{@code -Dautomation.audio.device=absent} is the scenario replay's
     * deterministic unplugged device: the one device call
     * refuses, so a sounding run takes the failure path — silence, one log
     * line — instead of depending on the machine's mixer.
     */
    public static AudioDirector start(boolean requested, boolean headless, boolean animations,
                                      Path manifestFile) {
        if (!requested || headless || !animations) {
            return silent(Status.SILENT);
        }
        Path root = manifestFile == null ? null : manifestFile.toAbsolutePath().getParent();
        LineOpener opener = "absent".equals(System.getProperty("automation.audio.device", "").trim())
                ? format -> {
                    throw new LineUnavailableException("no audio device (automation.audio.device=absent)");
                }
                : AudioSystem::getSourceDataLine;
        return start(requested, headless, animations, opener,
                ClipVoice::open, SoundBank.load(manifestFile), root);
    }

    static AudioDirector start(boolean requested, boolean headless, boolean animations,
                               LineOpener opener) {
        return start(requested, headless, animations, opener, ClipVoice::open,
                SoundBank.silent(), null);
    }

    static AudioDirector start(boolean requested, boolean headless, boolean animations,
                               LineOpener opener, SoundBank bank) {
        return start(requested, headless, animations, opener, ClipVoice::open, bank, null);
    }

    static AudioDirector start(boolean requested, boolean headless, boolean animations,
                               LineOpener opener, VoiceOpener voiceOpener,
                               SoundBank bank, Path assetsRoot) {
        if (!requested || headless || !animations) {
            return silent(Status.SILENT);
        }
        AudioAsset menu = bank.find(LoopBeds.MUSIC_SLOT).orElse(null);
        AudioAsset nature = bank.find(LoopBeds.AMBIENCE_SLOT).orElse(null);
        BedLine music = new BedLine(LoopBeds.MUSIC_SLOT, Mixing.Group.MUSIC,
                trim(menu), BedLine.MUSIC_RATE, menu, assetsRoot, opener);
        if (!music.open()) {
            // failure is silence, with one log line (written by BedLine).
            return new AudioDirector(Status.UNAVAILABLE, null, null, bank,
                    new LoopBeds(), null, new SfxVoices(), voiceOpener, assetsRoot);
        }
        BedLine ambience = new BedLine(LoopBeds.AMBIENCE_SLOT, Mixing.Group.SFX,
                trim(nature), BedLine.AMBIENCE_RATE, nature, assetsRoot, opener);
        // the ambience loop is 22.05 kHz; opening it is deferred until a
        // match starts, so Título holds only the menu line.
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audio-beds");
            t.setDaemon(true);
            return t;
        });
        AudioDirector director = new AudioDirector(Status.OPEN, music, ambience, bank,
                new LoopBeds(), ticker, new SfxVoices(), voiceOpener, assetsRoot);
        ticker.scheduleAtFixedRate(director::tick, 25, 25, TimeUnit.MILLISECONDS);
        return director;
    }

    private static int trim(AudioAsset asset) {
        return asset == null ? 0 : (int) asset.gainDb();
    }

    /**
     * the screen is what chooses the bed. Título plays
     * the menu loop, entering Partida crossfades to the ambience over 400 ms,
     * and the overlays over the board change nothing. The lines follow the
     * model: the ambience line opens at the first match and the menu line is
     * closed once its fade out ends.
     */
    public synchronized void screen(String screen) {
        screenChosen = true;
        beds.enter(screen);
        reflect();
    }

    /** The two beds right now; the dev-only {@code audio} probe prints this. */
    public LoopBeds.Snapshot beds() {
        return beds.snapshot();
    }

    /** A bed's real line: its gain control, mute switch, playback position and open count. */
    public LineEvidence line(String slot) {
        BedLine bed = LoopBeds.AMBIENCE_SLOT.equals(slot) ? ambience : music;
        if (bed == null) {
            return new LineEvidence(false, false, 0.0, 0L, sampleRate(slot), 0.0, 0L);
        }
        BedLine.Evidence evidence = bed.evidence();
        return new LineEvidence(evidence.open(), evidence.muted(), evidence.gainDb(),
                evidence.frames(), evidence.sampleRate(), evidence.gain(), evidence.opens());
    }

    /**
     * Fires one SFX cue. The cue enters the voice
     * pool, so a fifth concurrent cue drops the oldest and the two single-voice
     * slots retrigger instead of stacking. A silent run, a slot the bank could
     * not resolve, or a device that refuses a clip is silence with one log line.
     */
    public synchronized void cue(String slot) {
        if (status != Status.OPEN || slot == null) {
            return;
        }
        AudioAsset asset = bank.find(slot).orElse(null);
        if (asset == null || asset.loop()) {
            // A loop slot is a bed, never an SFX cue; a silent slot stays silent.
            return;
        }
        try {
            voices.play(slot, voiceOpener.open(asset, assetsRoot));
        } catch (Exception e) {
            // failure is silence, never an exception into the game.
            System.err.println("audio cue " + slot + " unavailable: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** The live SFX voices, oldest first; the dev-only probe prints them. */
    public List<String> activeCues() {
        return voices.activeSlots();
    }

    /**
     * The cues this run actually started, in play order: what fires when, as the voice pool saw it.
     * Each entry's {@code frames} is the real {@code Clip}'s length, so the
     * dev-only {@code audio} probe reports played clips rather than a counter
     * kept beside them. Audio is never game state, which is why this is
     * a probe and not a field of {@code state}.
     */
    public CueEvidence cues() {
        List<SfxVoices.Fired> played = voices.fired();
        StringBuilder history = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SfxVoices.Fired cue : played) {
            if (history.length() > 0) {
                history.append(',');
            }
            history.append(cue.slot());
            counts.merge(cue.slot(), 1, Integer::sum);
        }
        SfxVoices.Fired last = played.isEmpty() ? null : played.get(played.size() - 1);
        return new CueEvidence(played.size(), last == null ? null : last.slot(),
                last == null ? 0L : last.frames(), history.toString(), Map.copyOf(counts));
    }

    /**
     * The {@code audio} probe's cue log: how many, which last, its clip length,
     * the order, and how many times each slot played.
     */
    public record CueEvidence(int count, String last, long lastFrames, String history,
                              Map<String, Integer> counts) {
    }

    private static int sampleRate(String slot) {
        return LoopBeds.AMBIENCE_SLOT.equals(slot) ? BedLine.AMBIENCE_RATE : BedLine.MUSIC_RATE;
    }

    /**
     * The formula applied to the line(s) this director opened: a muted level
     * drives every gain control to its minimum (and the line's MUTE to true) while
     * playback positions are left exactly where they were. Unmuting recomputes the
     * same amplitude from the stored sliders.
     */
    public synchronized void apply(Mixing.Levels levels) {
        this.levels = levels;
        reflect();
    }

    /** One ticker step: move the envelope to now and write it to the lines. */
    private synchronized void tick() {
        if (status != Status.OPEN) {
            return;
        }
        beds.tick();
        reflect();
    }

    /**
     * Makes the real lines match the bed model: a playing bed is started and
     * carries its envelope; a stopped bed is muted and its line closed.
     */
    private void reflect() {
        if (status != Status.OPEN) {
            return;
        }
        LoopBeds.Snapshot state = beds.snapshot();
        if (!screenChosen) {
            // Before the launch screen picks a bed, the one open line carries
            // setting so a launch with Silenciar on is already silent.
            if (music != null) {
                music.apply(1.0, levels);
            }
            return;
        }
        follow(music, state.music());
        follow(ambience, state.ambience());
    }

    private void follow(BedLine bed, LoopBeds.Bed state) {
        if (bed == null) {
            return;
        }
        if (state.playing()) {
            bed.start();
            bed.apply(state.gain(), levels);
        } else {
            bed.stop();
        }
    }

    /** Where the opened menu line is playing; mute never moves or resets it. */
    public long positionFrames() {
        return music == null ? 0L : music.evidence().frames();
    }

    /** {@code silent}, {@code open} or {@code unavailable} on the ready line. */
    public String token() {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }

    public Status status() {
        return status;
    }

    /** The slots this run can play; every failure is already a silent slot. */
    public SoundBank bank() {
        return bank;
    }

    @Override
    public void close() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        voices.close();
        if (music != null) {
            music.close();
        }
        if (ambience != null) {
            ambience.close();
        }
    }
}
