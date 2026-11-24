package rabbitrace.assets;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mutating one byte of a lock-listed file
 * fails {@code gradlew test} naming the slot, while the shipped run still draws
 * the accepted file.
 *
 * <p>The shipped manifest has no {@code source: file} slot until the art phase
 * so the mutation is exercised on a scratch copy of the same schema:
 * one file slot, a real artifact, and a lock produced by
 * {@link ManifestLock#write}. Flipping one byte must turn the validator red with
 * the slot's id in the message — the failure {@code gradlew test} reports. The
 * unmutated copy proves the check is not red for everything, and
 * {@link AssetSource} proves the boot path deliberately does not hash: the
 * mutated file still resolves and still draws.
 *
 * <p>The last assertion regenerates the lock for the committed tree. Editing
 * {@code manifest.lock.json} by hand, or adding a file slot without regenerating
 * it, therefore fails this test — which is what "generated, never hand-edited"
 * means.
 */
class ManifestLockTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();

    private static final String SLOT = "prop.rock.a";

    @TempDir
    static Path temp;

    @Test
    void mutatingOneByteOfALockListedFileFailsNamingTheSlotWhileTheShippedRunStillDrawsIt()
            throws IOException {
        Path shipped = ROOT.resolve("assets");

        // The committed lock is current: no file slot is unlisted or stale, and
        // every listed artifact hashes to its accepted bytes.
        assertTrue(ManifestLock.problems(shipped).isEmpty(),
                "the committed assets/manifest.lock.json disagrees with the shipped tree: "
                        + ManifestLock.problems(shipped));
        assertEquals(
                Files.readString(shipped.resolve(ManifestLock.LOCK), StandardCharsets.UTF_8),
                ManifestLock.render(shipped),
                "assets/manifest.lock.json is not what the generator writes; regenerate it, never hand-edit it");

        // A scratch kit with exactly one source:file slot, so AssetSource can
        // report 'file' without 42 artifacts.
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Path artifact = assets.resolve("art/rock.png");
        Files.createDirectories(artifact.getParent());
        byte[] accepted = new byte[64];
        for (int i = 0; i < accepted.length; i++) {
            accepted[i] = (byte) (i * 7 + 3);
        }
        Files.write(artifact, accepted);
        Files.writeString(assets.resolve(ManifestLock.MANIFEST), manifest(), StandardCharsets.UTF_8);

        ManifestLock.write(assets);
        assertTrue(ManifestLock.problems(assets).isEmpty(),
                "the unmutated kit must satisfy the lock: " + ManifestLock.problems(assets));

        // The shipped run draws the accepted file and hashes nothing.
        List<String> warnings = new ArrayList<>();
        assertEquals(AssetSource.FILE, AssetSource.load(assets, warnings::add).kind(),
                "an accepted lock-listed file must resolve to 'file'");
        assertTrue(warnings.isEmpty(), "an accepted file must not warn: " + warnings);

        // One byte is one mutation. The test-time check must fail and name the slot.
        accepted[0] ^= 0x01;
        Files.write(artifact, accepted);
        List<String> problems = ManifestLock.problems(assets);
        assertTrue(problems.stream().anyMatch(p -> p.contains("slot '" + SLOT + "'")
                        && p.contains("does not match the lock")),
                "mutating one byte of a lock-listed file must fail naming '" + SLOT
                        + "'; got " + problems);

        // …and the shipped run still draws it: hash verification is not a boot check.
        warnings.clear();
        assertEquals(AssetSource.FILE, AssetSource.load(assets, warnings::add).kind(),
                "the shipped run must still draw the mutated file, not fall back to its placeholder");
        assertTrue(warnings.isEmpty(), "the shipped run does not hash, so it must not warn: " + warnings);
    }

    /** A one-slot manifest in shape, enough for the lock and AssetSource. */
    private static String manifest() {
        JsonObject licence = new JsonObject();
        licence.addProperty("spdx", "CC0-1.0");
        licence.addProperty("source", "generated:rabbit-race/test");
        licence.addProperty("author", "Rabbit Race");
        licence.addProperty("modified", false);
        licence.add("creditEs", com.google.gson.JsonNull.INSTANCE);

        JsonObject provenance = new JsonObject();
        provenance.addProperty("tool", "test");
        provenance.addProperty("model", "fixture");
        provenance.addProperty("seed", 7);

        JsonObject slot = new JsonObject();
        slot.addProperty("id", SLOT);
        slot.addProperty("kind", "sprite");
        slot.addProperty("placement", "tile");
        slot.addProperty("source", "file");
        slot.addProperty("file", "art/rock.png");
        slot.add("size", vector(168, 240));
        slot.add("footprint", vector(84, 120));
        slot.add("anchor", vector(0.5, 1.0));
        slot.addProperty("layer", 4);
        slot.addProperty("sort", "row");
        slot.add("licence", licence);
        slot.add("provenance", provenance);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("v", 1);
        manifest.addProperty("slotSetVersion", 1);
        manifest.addProperty("checkedOn", "2026-11-19");
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        slots.add(slot);
        manifest.add("slots", slots);
        return manifest.toString();
    }

    private static com.google.gson.JsonArray vector(double x, double y) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        array.add(x);
        array.add(y);
        return array;
    }
}
