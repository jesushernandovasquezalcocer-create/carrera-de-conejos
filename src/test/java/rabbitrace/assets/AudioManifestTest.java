package rabbitrace.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * every slot resolves through the
 * committed manifest to an existing WAV or a known {@link Synth} name, with no
 * slot missing and none twice.
 *
 * <p>The accepted test reads the real {@code assets/manifest.json}, so an empty
 * {@code audio} array, a dropped slot, a duplicate or a name the kit does not
 * know is red. The smaller tests below prove the resolver can see each of those
 * gaps, so a resolver that returns a constant cannot pass the first one alone.
 */
class AudioManifestTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * A field, not a method parameter: the report records a test as
     * {@code Class#method}, and JUnit appends {@code (Path)} to the name when a
     * parameter is injected, which would make the evidence invisible.
     */
    @TempDir
    Path temp;

    @Test
    void everySlotResolvesThroughTheManifest() throws IOException {
        AudioManifest manifest = AudioManifest.load(MANIFEST);
        List<String> inventory = Arrays.stream(AudioSlot.values())
                .map(AudioSlot::id)
                .toList();

        assertEquals(List.of(), manifest.missingSlots(),
                "every slot must have an entry in the manifest");

        // Read the array as well as the resolved map: a map would collapse a
        // duplicate silently, and this check is precisely "none appears twice".
        JsonArray raw = read(MANIFEST).getAsJsonArray("audio");
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (JsonElement element : raw) {
            occurrences.merge(element.getAsJsonObject().get("id").getAsString(), 1, Integer::sum);
        }
        assertEquals(inventory, new ArrayList<>(occurrences.keySet()),
                "the audio array must hold exactly the slots, in inventory order");
        occurrences.forEach((id, count) ->
                assertEquals(1, count, "audio slot '" + id + "' appears " + count + " times"));

        assertEquals(inventory, manifest.entries().stream().map(AudioAsset::id).toList());

        for (AudioSlot slot : AudioSlot.values()) {
            AudioAsset asset = manifest.resolve(slot.id());
            assertEquals(slot.group(), asset.group(), "group of " + slot.id());
            assertEquals(slot.loop(), asset.loop(), "loop flag of " + slot.id());
            assertTrue(asset.usesFile() != (asset.synth() != null),
                    "exactly one of file and synth for " + slot.id());
            if (asset.usesFile()) {
                assertTrue(asset.file().toLowerCase(Locale.ROOT).endsWith(".wav"),
                        "a file entry must name a WAV: " + asset.file());
                assertTrue(Files.isRegularFile(manifest.assetsRoot().resolve(asset.file())),
                        "the WAV must exist: " + asset.file());
            } else {
                assertNotNull(Synth.byName(asset.synth()),
                        "unknown synth name '" + asset.synth() + "' for " + slot.id());
            }
        }

        // The "existing WAV" half must be live, not merely typed: a manifest
        // entry that names a committed WAV resolves to that file.
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Files.createDirectories(assets.resolve("sfx"));
        writeWav(assets.resolve("sfx/probe.wav"));

        JsonObject fileManifest = read(MANIFEST);
        JsonObject click = entry(fileManifest, "ui.click");
        click.remove("synth");
        click.addProperty("file", "sfx/probe.wav");

        AudioManifest withFile = AudioManifest.load(writeManifest(temp, fileManifest));
        AudioAsset clickAsset = withFile.resolve("ui.click");
        assertEquals("sfx/probe.wav", clickAsset.file());
        assertTrue(clickAsset.usesFile(), "a file entry must resolve as a file");
        assertTrue(Files.isRegularFile(assets.resolve(clickAsset.file())),
                "the resolved WAV must be the committed file");
    }

    @Test
    void aMissingSlotIsReportedNotInvented() throws IOException {
        JsonObject manifest = read(MANIFEST);
        remove(manifest.getAsJsonArray("audio"), "sfx.hop");

        AudioManifest loaded = AudioManifest.load(writeManifest(temp, manifest));
        assertEquals(List.of("sfx.hop"), loaded.missingSlots());
        assertTrue(loaded.find("sfx.hop").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> loaded.resolve("sfx.hop"));
    }

    @Test
    void aDuplicateSlotIsRejected() throws IOException {
        JsonObject manifest = read(MANIFEST);
        JsonArray audio = manifest.getAsJsonArray("audio");
        audio.add(audio.get(0).deepCopy());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest(temp, manifest)));
        assertTrue(error.getMessage().contains("ui.click"), error.getMessage());
        assertTrue(error.getMessage().contains("twice"), error.getMessage());
    }

    @Test
    void anUnknownSynthNameIsRejected() throws IOException {
        JsonObject manifest = read(MANIFEST);
        entry(manifest, "sfx.hop").addProperty("synth", "kazoo");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest(temp, manifest)));
        assertTrue(error.getMessage().contains("kazoo"), error.getMessage());
    }

    @Test
    void aFileEntryMustPointAtAnExistingWav() throws IOException {
        JsonObject manifest = read(MANIFEST);
        JsonObject click = entry(manifest, "ui.click");
        click.remove("synth");
        click.addProperty("file", "audio/sfx/absent.wav");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest(temp, manifest)));
        assertTrue(error.getMessage().contains("absent.wav"), error.getMessage());
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Path writeManifest(Path temp, JsonObject manifest) throws IOException {
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

    private static void remove(JsonArray audio, String id) {
        for (int i = 0; i < audio.size(); i++) {
            if (audio.get(i).getAsJsonObject().get("id").getAsString().equals(id)) {
                audio.remove(i);
                return;
            }
        }
        throw new AssertionError("manifest has no audio entry '" + id + "'");
    }

    /** 16-bit PCM mono 44.1 kHz, the format requires. */
    private static void writeWav(Path file) throws IOException {
        AudioFormat format = new AudioFormat(44_100f, 16, 1, true, false);
        byte[] pcm = new byte[882];
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize())) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }
}
