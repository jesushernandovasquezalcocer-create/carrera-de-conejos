package rabbitrace.audio;

import org.junit.jupiter.api.Test;
import rabbitrace.assets.AudioAsset;
import rabbitrace.assets.AudioManifest;
import rabbitrace.assets.AudioSlot;
import rabbitrace.game.Match;
import rabbitrace.automation.Commands;
import rabbitrace.automation.EventLog;
import rabbitrace.render.Animations;
import rabbitrace.settings.Settings;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mute is a gain of zero on every open line. It does not move a slider and does not move playback, so
 * unmuting restores the same amplitude.
 *
 * <p>The line is the one {@link AudioDirector} opens, faked only at the device
 * boundary: the gain and mute read below are the controls on that line, not a
 * formula the test evaluates for itself. The settings change goes through the
 * controller's {@code set} command, so the assertion fails if the app ignores
 * the mute instead of applying it. The same rule holds across every slot's
 * line, with the group and trim the committed manifest gives each.
 */
class MixingTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");

    private static final long PLAYING_FRAMES = 66_150;

    @Test
    void mutingTheSettingsZeroesTheOpenLinesGainAndUnmutingRestoresIt() throws Exception {
        FakeDevice device = new FakeDevice();
        Settings settings = Settings.defaults();

        try (AudioDirector director =
                     AudioDirector.start(true, false, true, format -> device.line());
             EventLog log = EventLog.open(null)) {
            assertEquals(AudioDirector.Status.OPEN, director.status(),
                    "the director must hold the line this test reads");

            // Apply the launched sliders: master 0.8 × music 0.4 at a 0 dB trim.
            director.apply(levels(settings));
            float playingDb = device.gain.getValue();
            assertEquals(Mixing.decibels(0.8 * 0.4), playingDb, 1e-4,
                    "the open line must carry gain for the stored sliders");
            assertEquals(0.8 * 0.4, effectiveAmplitude(device), 1e-4,
                    "the open line must not start silent");

            Commands commands = new Commands(Match.start("ABC123"), settings, log,
                    () -> { }, Animations.fromFlag("0"), System::nanoTime, director::apply);

            // Silenciar is a change like any other, and it is independent
            // of the volume values.
            assertEquals("ok true", commands.handle("set muted true"));
            assertEquals(80, settings.master(), "muting must not move the master slider");
            assertEquals(40, settings.music(), "muting must not move the music slider");
            assertEquals(70, settings.sfx(), "muting must not move the sfx slider");
            assertTrue(settings.muted(), "the controller must record the mute");

            // The gain control on the opened line is at its minimum and the line
            // is muted, so its effective amplitude is exactly zero. The test
            // fails here if `Commands` forgot to apply the change.
            assertEquals(device.gain.getMinimum(), device.gain.getValue(), 0.0f,
                    "muting must drive the open line's gain to zero");
            assertTrue(device.mute.getValue(), "muting must mute the open line");
            assertEquals(0.0, effectiveAmplitude(device), 0.0,
                    "a muted line must contribute no amplitude");

            // Mute is a gain of zero, not a pause: nothing stops, flushes or
            // drains the line, and playback stays where it was.
            assertEquals(PLAYING_FRAMES, director.positionFrames(),
                    "muting must leave playback where it was");
            assertEquals(0, device.stopCalls, "muting must not stop the line");
            assertEquals(0, device.flushCalls, "muting must not flush the line");
            assertEquals(0, device.drainCalls, "muting must not drain the line");

            // unmuting is the same formula on the same values.
            assertEquals("ok false", commands.handle("set muted false"));
            assertEquals(80, settings.master(), "unmuting must not move the master slider");
            assertEquals(40, settings.music(), "unmuting must not move the music slider");
            assertEquals(70, settings.sfx(), "unmuting must not move the sfx slider");
            assertEquals(playingDb, device.gain.getValue(), 0.0f,
                    "unmuting must restore the gain the line had before the mute");
            assertEquals(0.8 * 0.4, effectiveAmplitude(device), 1e-4,
                    "the line must play again at its amplitude");
            assertFalse(device.mute.getValue(), "unmuting must unmute the line");            assertEquals(PLAYING_FRAMES, director.positionFrames(),
                    "the line must not have lost playback while muted");
        }
    }

    /**
     * {@code muted} zeroes every line's gain
     * while playback positions are kept; unmuting restores the amplitude
     * {@code 10^(gainDb / 20) × master × group} for the stored values.
     *
     * <p>Every slot is a line, with the group and trim the committed
     * manifest carries, plus two probe trims the shipped CC0 set leaves at 0 dB
     * — so dropping the {@code 10^(gainDb / 20)} term cannot pass. Mute and
     * unmute are asked of the same lines, and the line the director actually
     * opens is read back from its device gain and mute controls.
     */
    @Test
    void mutingZeroesEveryLinesGainAndUnmutingRestoresTheStoredFormula() throws Exception {
        List<Mixing.Line> lines = new ArrayList<>();
        for (AudioAsset asset : AudioManifest.load(MANIFEST).entries()) {
            Mixing.Group group = "music".equals(asset.group())
                    ? Mixing.Group.MUSIC : Mixing.Group.SFX;
            lines.add(new Mixing.Line(asset.id(), group, (int) asset.gainDb()));
        }
        lines.add(new Mixing.Line("probe.sfx.trim", Mixing.Group.SFX, -6));
        lines.add(new Mixing.Line("probe.music.trim", Mixing.Group.MUSIC, 3));
        assertEquals(AudioSlot.values().length + 2, lines.size(),
                "the ten slots plus two trim probes");

        Settings settings = Settings.defaults();  // 80 / 40 / 70, unmuted
        Mixing.Levels stored = levels(settings);
        Mixing.Levels muted = new Mixing.Levels(settings.master(), settings.music(),
                settings.sfx(), true);

        // Each line opened earlier or later, so its position is its own.
        Map<String, Long> positions = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).advance(4_410L * (i + 1));
            positions.put(lines.get(i).slot(), lines.get(i).positionFrames());
        }

        Map<String, Double> before = new LinkedHashMap<>();
        for (Mixing.Line line : lines) {
            double amplitude = line.amplitude(stored);
            before.put(line.slot(), amplitude);
            assertEquals(formula(line, stored), amplitude, 1e-12,
                    line.slot() + " must carry amplitude for the stored sliders");
            assertEquals(0.0, line.amplitude(muted), 0.0,
                    line.slot() + " must be silent while muted");
            assertEquals(positions.get(line.slot()), line.positionFrames(),
                    line.slot() + " must keep its playback position while muted");
        }

        // Unmuting is the same formula on the same stored values: the very same
        // number, and the positions are still where they were.
        for (Mixing.Line line : lines) {
            assertEquals(before.get(line.slot()), line.amplitude(stored), 0.0,
                    line.slot() + " must restore the amplitude it had before the mute");
            assertEquals(formula(line, stored), line.amplitude(stored), 1e-12,
                    line.slot() + " must still match formula after unmuting");
            assertEquals(positions.get(line.slot()), line.positionFrames(),
                    line.slot() + " must not have lost playback while muted");
        }

        // The one line the app opens obeys the same rule at the device boundary,
        // not only in the formula.
        FakeDevice device = new FakeDevice();
        try (AudioDirector director =
                     AudioDirector.start(true, false, true, format -> device.line())) {
            director.apply(stored);
            float playingDb = device.gain.getValue();
            assertEquals(Mixing.decibels(0.8 * 0.4), playingDb, 1e-4,
                    "the open line must carry gain for the stored sliders");

            settings.set("muted", "true");
            director.apply(levels(settings));
            assertEquals(device.gain.getMinimum(), device.gain.getValue(), 0.0f,
                    "muting must drive the open line's gain to zero");
            assertTrue(device.mute.getValue(), "muting must mute the open line");
            assertEquals(PLAYING_FRAMES, director.positionFrames(),
                    "muting must leave playback where it was");

            settings.set("muted", "false");
            director.apply(levels(settings));
            assertEquals(playingDb, device.gain.getValue(), 0.0f,
                    "unmuting must restore the gain the line had before the mute");
            assertFalse(device.mute.getValue(), "unmuting must unmute the open line");
            assertEquals(PLAYING_FRAMES, director.positionFrames(),
                    "the line must not have lost playback while muted");
        }
    }

    /** evaluated here, independently of {@link Mixing.Line#amplitude}. */
    private static double formula(Mixing.Line line, Mixing.Levels levels) {
        return Math.pow(10.0, line.gainDb() / 20.0)
                * levels.master() / 100.0
                * (line.group() == Mixing.Group.MUSIC ? levels.music() : levels.sfx()) / 100.0;
    }

    /** from the line's own controls: gain amplitude × (muted ? 0: 1). */
    private static double effectiveAmplitude(FakeDevice device) {
        double gain = Math.pow(10.0, device.gain.getValue() / 20.0);
        return device.mute.getValue() ? 0.0 : gain;
    }

    private static Mixing.Levels levels(Settings settings) {
        return new Mixing.Levels(settings.master(), settings.music(),
                settings.sfx(), settings.muted());
    }

    /**
     * A {@link SourceDataLine} that only implements what the director touches:
     * a MASTER_GAIN control, a MUTE switch and a playback position. Calls that
     * would pause or discard playback are counted so the test can prove mute
     * never makes them.
     */
    private static final class FakeDevice {

        final FakeGain gain = new FakeGain();
        final FakeMute mute = new FakeMute();
        final AtomicLong position = new AtomicLong(PLAYING_FRAMES);
        int stopCalls;
        int flushCalls;
        int drainCalls;
        private boolean open;

        SourceDataLine line() {
            return (SourceDataLine) Proxy.newProxyInstance(
                    SourceDataLine.class.getClassLoader(),
                    new Class<?>[]{SourceDataLine.class},
                    this::invoke);
        }

        private Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getControl":
                    return args[0] == FloatControl.Type.MASTER_GAIN ? gain
                            : args[0] == BooleanControl.Type.MUTE ? mute : null;
                case "isControlSupported":
                    return args[0] == FloatControl.Type.MASTER_GAIN
                            || args[0] == BooleanControl.Type.MUTE;
                case "getLongFramePosition":
                    return position.get();
                case "getFramePosition":
                    return (int) position.get();
                case "getMicrosecondPosition":
                    return position.get() * 1_000_000L / 44_100L;
                case "open":
                    open = true;
                    return null;
                case "isOpen":
                    return open;
                case "start":
                    return null;
                case "stop":
                    stopCalls++;
                    return null;
                case "flush":
                    flushCalls++;
                    return null;
                case "drain":
                    drainCalls++;
                    return null;
                case "close":
                    open = false;
                    return null;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "fake SourceDataLine";
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0f;
            }
            return null;
        }
    }

    /** The gain control a real line exposes; the director writes into it. */
    private static final class FakeGain extends FloatControl {

        FakeGain() {
            super(FloatControl.Type.MASTER_GAIN, -80f, 6f, 0.01f, 0, -6f, "dB");
        }
    }

    /** The switch that makes a minimum gain an actual zero. */
    private static final class FakeMute extends BooleanControl {

        FakeMute() {
            super(BooleanControl.Type.MUTE, false, "Muted", "Audible");
        }
    }
}
