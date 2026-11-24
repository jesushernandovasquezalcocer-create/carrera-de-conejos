package rabbitrace.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * both loop entries declare loop points with
 * {@code 0 <= start < end <= duration}, and a loop point outside the source
 * fails validation.
 *
 * <p>The accepted test reads the committed manifest, so dropping either loop
 * entry's points or moving one past its source is red. The other tests take a
 * WAV of known length and the kit's own silence bed and try each way out of the
 * range, so a validator that merely returns the entry cannot pass.
 */
class AudioLoopTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** A field, not a method parameter: the report records the test as {@code Class#method}. */
    @TempDir
    Path temp;

    @Test
    void bothLoopEntriesDeclarePointsInsideTheirDuration() throws IOException {
        AudioManifest manifest = AudioManifest.load(MANIFEST);
        JsonObject raw = read(MANIFEST);

        List<AudioAsset> loops = manifest.entries().stream().filter(AudioAsset::loop).toList();
        assertEquals(List.of("amb.nature.loop", "music.menu"),
                loops.stream().map(AudioAsset::id).toList(),
                "the manifest has exactly two loop slots");

        for (AudioAsset loop : loops) {
            JsonObject entry = entry(raw, loop.id());
            assertTrue(entry.has("loopStartMs"), loop.id() + " must declare loopStartMs");
            assertTrue(entry.has("loopEndMs"), loop.id() + " must declare loopEndMs");
            assertTrue(loop.durationMs() > 0, loop.id() + " must have a real duration");
            assertTrue(loop.loopStartMs() >= 0, loop.id() + " start must be >= 0");
            assertTrue(loop.loopStartMs() < loop.loopEndMs(),
                    loop.id() + " start must be below end: " + loop.loopStartMs()
                            + ".." + loop.loopEndMs());
            assertTrue(loop.loopEndMs() <= loop.durationMs(),
                    loop.id() + " end must fit its " + loop.durationMs() + " ms source: "
                            + loop.loopEndMs());
        }

        // The bound must be the file's real duration, not a constant: a 100 ms
        // WAV accepts its own length and rejects one millisecond more.
        writeProbe(100);
        AudioManifest fitting = AudioManifest.load(manifestWithProbeLoop(0L, 100L));
        AudioAsset ambience = fitting.resolve("amb.nature.loop");
        assertEquals(100L, ambience.durationMs(), "duration comes from the WAV header");
        assertEquals(0L, ambience.loopStartMs());
        assertEquals(100L, ambience.loopEndMs());

        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(manifestWithProbeLoop(0L, 101L)),
                "a loop end past the file must fail validation");
        assertTrue(outside.getMessage().contains("amb.nature.loop"), outside.getMessage());
        assertTrue(outside.getMessage().contains("loop points"), outside.getMessage());
    }

    @Test
    void aLoopEndPastTheFileIsRejected() throws IOException {
        writeProbe(100);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(manifestWithProbeLoop(20L, 150L)));
        assertTrue(error.getMessage().contains("amb.nature.loop"), error.getMessage());
        assertTrue(error.getMessage().contains("0 <= start < end <="), error.getMessage());
    }

    @Test
    void aLoopStartAtOrPastItsEndIsRejected() throws IOException {
        writeProbe(100);
        for (long start : new long[] {50L, 100L}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AudioManifest.load(manifestWithProbeLoop(start, 50L)),
                    "start " + start + " must not reach its end");
            assertTrue(error.getMessage().contains("amb.nature.loop"), error.getMessage());
        }
    }

    @Test
    void aNegativeLoopStartIsRejected() throws IOException {
        writeProbe(100);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(manifestWithProbeLoop(-1L, 100L)));
        assertTrue(error.getMessage().contains("amb.nature.loop"), error.getMessage());
    }

    @Test
    void anAbsentLoopEndDefaultsToTheFileDuration() throws IOException {
        writeProbe(100);
        AudioManifest loaded = AudioManifest.load(manifestWithProbeLoop(10L, null));
        AudioAsset ambience = loaded.resolve("amb.nature.loop");
        assertEquals(100L, ambience.loopEndMs(),
                "the default loop end is the file duration");
    }

    @Test
    void aLoopEndPastTheKitCueIsRejected() throws IOException {
        JsonObject manifest = read(MANIFEST);
        entry(manifest, "music.menu").addProperty("loopEndMs",
                Synth.SILENCE.durationMs() + 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest(manifest)));
        assertTrue(error.getMessage().contains("music.menu"), error.getMessage());
        assertTrue(error.getMessage().contains("loop points"), error.getMessage());
    }

    /** A real WAV of the given length, at the format requires. */
    private void writeProbe(long milliseconds) throws IOException {
        Path file = temp.resolve("assets/audio/sfx/probe.wav");
        Files.createDirectories(file.getParent());
        AudioFormat format = new AudioFormat(44_100f, 16, 1, true, false);
        int frames = Math.round(44_100f * milliseconds / 1000f);
        byte[] pcm = new byte[frames * format.getFrameSize()];
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }

    /** The committed manifest with ambience pointed at the probe WAV. */
    private Path manifestWithProbeLoop(long start, Long end) throws IOException {
        JsonObject manifest = read(MANIFEST);
        JsonObject ambience = entry(manifest, "amb.nature.loop");
        ambience.remove("synth");
        ambience.addProperty("file", "audio/sfx/probe.wav");
        ambience.addProperty("loopStartMs", start);
        if (end == null) {
            ambience.remove("loopEndMs");
        } else {
            ambience.addProperty("loopEndMs", end);
        }
        return writeManifest(manifest);
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private Path writeManifest(JsonObject manifest) throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Path file = assets.resolve("manifest.json");
        Files.writeString(file, GSON.toJson(manifest), StandardCharsets.UTF_8);
        return file;
    }

    private static JsonObject entry(JsonObject manifest, String id) {
        for (JsonElement element : manifest.getAsJsonArray("audio")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(id)) {
                return entry;
            }
        }
        throw new AssertionError("manifest has no audio entry '" + id + "'");
    }
}
