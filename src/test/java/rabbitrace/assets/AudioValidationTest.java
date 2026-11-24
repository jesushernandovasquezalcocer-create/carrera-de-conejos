package rabbitrace.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * manifest validation rejects {@code file} and
 * {@code synth} together, an unknown group, a {@code gainDb} outside −12…+6, and
 * a non-CC0 entry without {@code credit}.
 *
 * <p>The accepted test reads the committed manifest and holds every entry to
 * the clamp and the credit rule, so a shipped trim outside the range or a
 * non-CC0 entry with no credit is red. Each rejection below mutates that same
 * manifest one rule at a time, so a validator that ignores a field is visible
 * even while the committed file stays green.
 */
class AudioValidationTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** A field, not a method parameter: the report records the test as {@code Class#method}. */
    @TempDir
    Path temp;

    @Test
    void manifestValidationRejectsTheFourMalformedEntries() throws IOException {
        AudioManifest manifest = AudioManifest.load(MANIFEST);
        assertEquals(AudioSlot.values().length, manifest.entries().size(),
                "the committed manifest must load all ten slots");
        for (AudioAsset asset : manifest.entries()) {
            assertTrue(asset.gainDb() >= -12.0 && asset.gainDb() <= 6.0,
                    asset.id() + " carries a trim outside −12…+6: " + asset.gainDb());
        }

        // 1. file and synth together.
        JsonObject both = read(MANIFEST);
        entry(both, "sfx.hop").addProperty("file", "audio/sfx/hop.wav");
        IllegalArgumentException together = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest("both", both)),
                "an entry with both a file and a synth must be rejected");
        assertTrue(together.getMessage().contains("sfx.hop"), together.getMessage());
        assertTrue(together.getMessage().contains("exactly one"), together.getMessage());

        // 2. an unknown group.
        JsonObject group = read(MANIFEST);
        entry(group, "sfx.hop").addProperty("group", "voice");
        IllegalArgumentException unknownGroup = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest("group", group)),
                "a group outside sfx/ambience/music must be rejected");
        assertTrue(unknownGroup.getMessage().contains("sfx.hop"), unknownGroup.getMessage());
        assertTrue(unknownGroup.getMessage().contains("voice"), unknownGroup.getMessage());

        // 3. gainDb outside −12…+6, including a fractional value just outside:
        // truncating to int would turn −12.5 into −12 and let it pass.
        for (double outside : new double[] {-13.0, 7.0, -12.5, 6.5}) {
            JsonObject gain = read(MANIFEST);
            entry(gain, "sfx.hop").addProperty("gainDb", outside);
            IllegalArgumentException badGain = assertThrows(IllegalArgumentException.class,
                    () -> AudioManifest.load(writeManifest("gain", gain)),
                    "gainDb " + outside + " must be outside the clamp");
            assertTrue(badGain.getMessage().contains("sfx.hop"), badGain.getMessage());
            assertTrue(badGain.getMessage().contains("gainDb"), badGain.getMessage());
        }

        // The clamp is inclusive: both ends load and keep their exact value.
        for (double inside : new double[] {-12.0, 6.0, -6.5}) {
            JsonObject gain = read(MANIFEST);
            entry(gain, "sfx.hop").addProperty("gainDb", inside);
            AudioAsset hop = AudioManifest.load(writeManifest("inside", gain)).resolve("sfx.hop");
            assertEquals(inside, hop.gainDb(), 1e-9,
                    "gainDb " + inside + " is inside −12…+6");
        }

        // 4a. A non-CC0 licence with no credit at all.
        JsonObject uncredited = read(MANIFEST);
        entry(uncredited, "sfx.hop").addProperty("licence", "CC-BY-4.0");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest("uncredited", uncredited)),
                "a non-CC0 entry without credit must be rejected");
        assertTrue(missing.getMessage().contains("sfx.hop"), missing.getMessage());
        assertTrue(missing.getMessage().contains("CC-BY-4.0"), missing.getMessage());
        assertTrue(missing.getMessage().contains("credit"), missing.getMessage());

        // 4b. A credit that claims a non-CC0 licence but omits a required field.
        JsonObject incomplete = read(MANIFEST);
        entry(incomplete, "sfx.hop").add("credit", credit("CC-BY-4.0", false));
        IllegalArgumentException partial = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest("incomplete", incomplete)),
                "a credit missing one of its five fields must be rejected");
        assertTrue(partial.getMessage().contains("sfx.hop"), partial.getMessage());
        assertTrue(partial.getMessage().contains("title"), partial.getMessage());

        // 4c. The same licence is accepted once the credit is complete.
        JsonObject credited = read(MANIFEST);
        entry(credited, "sfx.hop").add("credit", credit("CC-BY-4.0", true));
        AudioManifest accepted = AudioManifest.load(writeManifest("credited", credited));
        assertEquals("sfx.hop", accepted.resolve("sfx.hop").id(),
                "a complete credit satisfies the non-CC0 rule");

        // CC0 is the default and needs no credit; the example's CC0 credit is
        // still a valid record when one is present.
        JsonObject cc0 = read(MANIFEST);
        entry(cc0, "sfx.hop").add("credit", credit("CC0-1.0", true));
        assertNotNull(AudioManifest.load(writeManifest("cc0", cc0)).resolve("sfx.hop"));
    }

    private static JsonObject credit(String licence, boolean complete) {
        JsonObject credit = new JsonObject();
        credit.addProperty("licence", licence);
        credit.addProperty("author", "Kenney");
        credit.addProperty("source", "https://kenney.nl/assets/impact-sounds");
        credit.addProperty("changes", "transcoded to 16-bit PCM WAV");
        if (complete) {
            credit.addProperty("title", "impactSoft_medium_000");
        }
        return credit;
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private Path writeManifest(String name, JsonObject manifest) throws IOException {
        Path assets = Files.createDirectories(temp.resolve(name).resolve("assets"));
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
