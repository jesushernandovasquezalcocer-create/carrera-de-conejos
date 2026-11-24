package rabbitrace.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rabbitrace.assets.AudioManifest;
import rabbitrace.assets.AudioSlot;
import rabbitrace.game.Match;
import rabbitrace.automation.Snapshot;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * a missing slot, an unreadable WAV and a
 * corrupt entry each produce silence plus one log line, with no exception and no
 * state change.
 *
 * <p>The accepted test takes the committed manifest — so the ten good slots are
 * the real ones — and breaks exactly three of them in a scratch tree. The other
 * seven must resolve byte-identically to the clean bank, the game state must be
 * untouched (audio is never game state), and the strict
 * {@link AudioManifest#load(Path)} must still reject the same file, which is
 * what keeps this tolerant path from weakening.
 */
class SoundBankTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** A field, not a method parameter: the report records the test as {@code Class#method}. */
    @TempDir
    Path temp;

    @Test
    void eachFailureIsSilentWithOneLogLineAndChangesNoState() throws IOException {
        // The state the bank must not touch: load failures are not game events.
        Match match = Match.start("ABC123");
        String stateBefore = Snapshot.of(match);

        SoundBank clean = SoundBank.load(MANIFEST, line -> {
            throw new AssertionError("the committed manifest must not log: " + line);
        });
        assertEquals(AudioSlot.values().length, clean.size(),
                "the committed manifest must resolve every slot");

        JsonObject manifest = read(MANIFEST);

        // 1. A slot the manifest does not carry.
        remove(manifest.getAsJsonArray("audio"), "sfx.hop");

        // 2. A file that exists, is named.wav, and is not a WAV.
        Path unreadable = temp.resolve("assets/audio/sfx/broken.wav");
        Files.createDirectories(unreadable.getParent());
        Files.writeString(unreadable, "this is not a WAV", StandardCharsets.UTF_8);
        JsonObject win = entry(manifest, "sfx.win");
        win.remove("synth");
        win.addProperty("file", "audio/sfx/broken.wav");

        // 3. An entry that breaks rules: the wrong group for the slot.
        entry(manifest, "sfx.dice.settle").addProperty("group", "voice");

        List<String> log = new ArrayList<>();
        SoundBank broken = SoundBank.load(writeManifest(manifest), log::add);

        List<String> failed = List.of("sfx.hop", "sfx.dice.settle", "sfx.win");
        for (String slot : failed) {
            assertTrue(broken.isSilent(slot), slot + " must be silent");
            assertTrue(broken.find(slot).isEmpty(), slot + " must have no playable entry");
        }
        assertEquals(failed, broken.silentSlots(),
                "only the three broken slots are silent, in inventory order");

        // Exactly one line per failure. Counting the substring proves a line names
        // its slot; the total proves a failure logs once, not twice, and that a
        // healthy entry logs nothing.
        assertEquals(failed.size(), log.size(), "one line per failure: " + log);
        for (String slot : failed) {
            assertEquals(1, log.stream().filter(line -> line.contains(slot)).count(),
                    "exactly one line names " + slot + ": " + log);
        }

        // No state change: the seven untouched slots resolve exactly as before...
        assertEquals(AudioSlot.values().length - failed.size(), broken.size());
        for (AudioSlot slot : AudioSlot.values()) {
            if (!broken.isSilent(slot.id())) {
                assertEquals(clean.find(slot.id()), broken.find(slot.id()),
                        slot.id() + " must not change because another slot failed");
            }
        }
        // ...and the game state is byte-identical across the whole load.
        assertEquals(stateBefore, Snapshot.of(match),
                "audio failures must not change game state");

        // The tolerant path does not weaken the strict validation path.
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> AudioManifest.load(writeManifest(manifest)));
        assertTrue(rejected.getMessage().contains("failed") || rejected.getMessage().contains("sfx."),
                "the strict load must name what it rejects: " + rejected.getMessage());
    }

    @Test
    void aMissingManifestIsAllSilentWithOneLine() {
        List<String> log = new ArrayList<>();
        SoundBank bank = SoundBank.load(temp.resolve("absent/manifest.json"), log::add);

        assertEquals(AudioSlot.values().length, bank.silentSlots().size());
        assertEquals(0, bank.size());
        assertEquals(1, log.size(), "an unreadable manifest is one line, not ten: " + log);
        assertTrue(log.get(0).contains("unreadable"), log.get(0));
    }

    private Path writeManifest(JsonObject manifest) throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Path file = assets.resolve("manifest.json");
        Files.writeString(file, GSON.toJson(manifest), StandardCharsets.UTF_8);
        return file;
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
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
}
