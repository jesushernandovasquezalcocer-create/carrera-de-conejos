package rabbitrace.audio;

import org.junit.jupiter.api.Test;
import rabbitrace.assets.AudioAsset;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four SFX voices, oldest dropped first, with
 * {@code sfx.hop} and {@code sfx.bump.blocked} as single-voice retriggers.
 *
 * <p>The cues go through {@link AudioDirector#cue(String)}, the path the
 * controller fires from, and the voices are faked only at the device boundary —
 * the same seam {@code MixingTest} uses for the beds. Each assertion reads the
 * pool the run actually plays through, so a counter that never reaches a line
 * cannot pass.
 */
class SfxVoicesTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");

    @Test
    void aFifthConcurrentCueDropsTheOldestAndHopRetriggersOneVoice() {
        FakeVoices opened = new FakeVoices();
        try (AudioDirector director = AudioDirector.start(true, false, true,
                format -> new FakeLine().line(), opened::open,
                SoundBank.load(MANIFEST), ROOT)) {
            assertEquals(AudioDirector.Status.OPEN, director.status(),
                    "the director must hold the lines this test reads");

            // Four distinct cues fill the pool in order.
            for (String slot : List.of("ui.click", "sfx.turn.change",
                    "sfx.dice.roll", "sfx.dice.settle")) {
                director.cue(slot);
            }
            assertEquals(List.of("ui.click", "sfx.turn.change",
                            "sfx.dice.roll", "sfx.dice.settle"), director.activeCues(),
                    "four concurrent cues must all hold a voice");
            FakeVoice click = opened.last("ui.click");
            assertTrue(click.isRunning(), "the oldest of four voices must still play");

            // The fifth cue drops the oldest, not any other.
            director.cue("sfx.win");
            assertEquals(List.of("sfx.turn.change", "sfx.dice.roll",
                            "sfx.dice.settle", "sfx.win"), director.activeCues(),
                    "a fifth concurrent SFX must drop the oldest voice");
            assertFalse(click.isRunning(), "the dropped voice must be stopped");
            assertEquals(4, opened.running(), "the pool is four voices");

            // A fast path fires hop repeatedly. The first hop takes the free
            // voice left by turn.change; the second replaces it.
            director.cue("sfx.hop");
            FakeVoice firstHop = opened.last("sfx.hop");
            assertTrue(firstHop.isRunning());
            director.cue("sfx.hop");
            FakeVoice secondHop = opened.last("sfx.hop");
            assertNotSame(firstHop, secondHop, "each hop must retrigger a voice");
            assertFalse(firstHop.isRunning(), "sfx.hop must never overlap itself");
            assertEquals(1, count(director.activeCues(), "sfx.hop"),
                    "a retrigger must leave exactly one hop voice");
            assertEquals(4, director.activeCues().size(),
                    "a retrigger must not consume a fifth voice");

            // The retrigger replaced only hop; the other three cues still play.
            for (String slot : List.of("sfx.dice.roll", "sfx.dice.settle", "sfx.win")) {
                assertTrue(opened.last(slot).isRunning(),
                        slot + " must survive a hop retrigger");
            }
            assertEquals(4, opened.running(),
                    "the pool must hold exactly four live voices after the retrigger");
        }
    }

    private static long count(List<String> slots, String slot) {
        return slots.stream().filter(slot::equals).count();
    }

    /** Opens a fake voice per cue and remembers them by slot, in open order. */
    private static final class FakeVoices {

        private final Map<String, List<FakeVoice>> bySlot = new LinkedHashMap<>();
        private final List<FakeVoice> all = new ArrayList<>();

        SfxVoices.Voice open(AudioAsset asset, Path assetsRoot) {
            FakeVoice voice = new FakeVoice(asset.id());
            bySlot.computeIfAbsent(asset.id(), id -> new ArrayList<>()).add(voice);
            all.add(voice);
            return voice;
        }

        FakeVoice last(String slot) {
            List<FakeVoice> voices = bySlot.get(slot);
            if (voices == null || voices.isEmpty()) {
                throw new AssertionError("no cue was opened for " + slot);
            }
            return voices.get(voices.size() - 1);
        }

        long running() {
            return all.stream().filter(SfxVoices.Voice::isRunning).count();
        }
    }

    /** One opened cue; the pool starts and stops it, the test reads it back. */
    private static final class FakeVoice implements SfxVoices.Voice {

        private final String slot;
        private boolean running;

        FakeVoice(String slot) {
            this.slot = slot;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public long frames() {
            return 1_000L;
        }

        @Override
        public String toString() {
            return "FakeVoice[" + slot + ", running=" + running + "]";
        }
    }

    /**
     * The {@link SourceDataLine} the bed needs to make the director sound. It
     * exposes no gain control and never plays; the test reads only the SFX pool.
     */
    private static final class FakeLine {

        private boolean open;

        SourceDataLine line() {
            return (SourceDataLine) Proxy.newProxyInstance(
                    SourceDataLine.class.getClassLoader(),
                    new Class<?>[]{SourceDataLine.class},
                    this::invoke);
        }

        private Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "open":
                    open = true;
                    return null;
                case "isOpen":
                    return open;
                case "isControlSupported":
                    return false;
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
}
