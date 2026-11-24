package rabbitrace.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the manifest's {@code slotSetVersion}, the
 * lock's and the kit's agree, and adding a slot without refreshing the kit and
 * bumping fails the check.
 *
 * <p>The test reads the committed kit, not a fixture: every one of the 42
 * {@code source: placeholder} slots must have a PNG at its declared size and the
 * index must be exactly what {@link PlaceholderKit#write} produces. The negative
 * control adds a placeholder slot to a scratch copy of the shipped manifest while
 * leaving the lock and the kit untouched, and requires the check to fail naming
 * the new slot and the bump — with all three versions still equal, so it is the
 * coverage comparison, not a lucky version mismatch, that catches it. A second
 * control bumps only one of the three and requires the disagreement to be named.
 */
class PlaceholderKitTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path SHIPPED = ROOT.resolve("assets");

    @TempDir
    static Path temp;

    @Test
    void manifestLockAndKitAgreeAndAnAddedSlotWithoutABumpFails() throws IOException {
        assertTrue(PlaceholderKit.problems(SHIPPED).isEmpty(),
                "the committed manifest, lock and kit disagree: " + PlaceholderKit.problems(SHIPPED));

        int manifestVersion = PlaceholderKit.manifestVersion(SHIPPED);
        assertEquals(1, manifestVersion, "the shipped slotSetVersion is initial 1");
        assertEquals(manifestVersion, PlaceholderKit.lockVersion(SHIPPED),
                "assets/manifest.lock.json must record the manifest's slotSetVersion");
        assertEquals(manifestVersion, PlaceholderKit.kitVersion(SHIPPED),
                "assets/placeholders/kit.json must record the manifest's slotSetVersion");

        // The kit is generated, never hand-drawn: its index is what the generator
        // writes from the manifest, and it covers exactly the placeholder slots.
        assertEquals(
                Files.readString(SHIPPED.resolve(PlaceholderKit.DIRECTORY).resolve(PlaceholderKit.INDEX),
                        StandardCharsets.UTF_8),
                PlaceholderKit.renderIndex(SHIPPED),
                "assets/placeholders/kit.json is not what the generator writes; run placeholderKit");
        List<PlaceholderKit.Covered> expected = PlaceholderKit.expected(SHIPPED);
        assertEquals(42, expected.size(),
                "the shipped manifest has 42 source:placeholder slots that the kit must cover");
        for (PlaceholderKit.Covered slot : expected) {
            assertTrue(Files.isRegularFile(
                            SHIPPED.resolve(PlaceholderKit.DIRECTORY).resolve(slot.id() + ".png")),
                    "the kit has no file for slot '" + slot.id() + "'");
        }

        // Negative control: one added slot, no kit refresh, no bump anywhere.
        Path scratch = copyKit(temp.resolve("added-slot"));
        JsonObject manifest = read(scratch.resolve(ManifestLock.MANIFEST));
        JsonObject duplicate = firstPlaceholder(manifest).deepCopy();
        duplicate.addProperty("id", "prop.rock.c");
        manifest.getAsJsonArray("slots").add(duplicate);
        write(scratch.resolve(ManifestLock.MANIFEST), manifest);

        // The versions still agree; the coverage check is the only thing that can
        // catch an added slot that was not accompanied by a kit refresh and bump.
        assertEquals(1, PlaceholderKit.manifestVersion(scratch));
        assertEquals(1, PlaceholderKit.lockVersion(scratch));
        assertEquals(1, PlaceholderKit.kitVersion(scratch));
        List<String> added = PlaceholderKit.problems(scratch);
        assertTrue(added.stream().anyMatch(p -> p.contains("prop.rock.c")
                        && p.contains("bump") && p.contains("kit")),
                "adding a slot without refreshing the kit must fail naming the slot and the bump; got "
                        + added);
        assertTrue(added.stream().noneMatch(p -> p.contains("do not agree")),
                "the added-slot control must be caught by coverage while the three versions still agree; got "
                        + added);

        // Negative control: only the manifest's version moves. The three-way
        // agreement must be the clause that fails.
        manifest.addProperty("slotSetVersion", 2);
        write(scratch.resolve(ManifestLock.MANIFEST), manifest);
        assertTrue(PlaceholderKit.problems(scratch).stream()
                        .anyMatch(p -> p.contains("do not agree") && p.contains("manifest.slotSetVersion 2")),
                "a manifest bump with a stale lock and kit must fail the three-way agreement; got "
                        + PlaceholderKit.problems(scratch));
    }

    private static Path copyKit(Path destination) throws IOException {
        Path assets = destination.resolve("assets");
        Files.createDirectories(assets.resolve(PlaceholderKit.DIRECTORY));
        Files.copy(SHIPPED.resolve(ManifestLock.MANIFEST), assets.resolve(ManifestLock.MANIFEST));
        Files.copy(SHIPPED.resolve(ManifestLock.LOCK), assets.resolve(ManifestLock.LOCK));
        try (Stream<Path> files = Files.list(SHIPPED.resolve(PlaceholderKit.DIRECTORY))) {
            for (Path source : files.toList()) {
                Files.copy(source, assets.resolve(PlaceholderKit.DIRECTORY).resolve(source.getFileName()));
            }
        }
        return assets;
    }

    private static JsonObject firstPlaceholder(JsonObject manifest) {
        for (JsonElement element : manifest.getAsJsonArray("slots")) {
            JsonObject slot = element.getAsJsonObject();
            if ("placeholder".equals(slot.get("source").getAsString())) {
                return slot;
            }
        }
        throw new IllegalStateException("the shipped manifest has no source:placeholder slot");
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void write(Path path, JsonObject object) throws IOException {
        Files.writeString(path, object.toString(), StandardCharsets.UTF_8);
    }
}
