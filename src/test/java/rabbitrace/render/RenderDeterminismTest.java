package rabbitrace.render;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rabbitrace.assets.AssetSource;
import rabbitrace.game.Match;
import rabbitrace.automation.Frames;
import rabbitrace.settings.Settings;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * a fixed seed picks the same decoration
 * variants and the same ground crop in two separate JVMs, and a manifest reorder
 * moves them only once {@code slotSetVersion} is bumped.
 *
 * <p>{@link RenderPlanProbe} prints the plan the shipped boot path builds from a
 * manifest directory. The test runs it in this JVM and in two child JVMs, then
 * compares the three transcripts. The reorder is a real one: the committed
 * manifest is copied, its {@code slots} array is reversed, and the same version
 * must reproduce the same picks — the group members are read in id order, so a
 * reorder cannot silently re-map a variant. Only after the version moves do the
 * variant and crop lines change.
 *
 * <p>Finally it renders a frame through {@link Frames} and {@link BoardPainter}
 * with the baseline, the bumped and the kit-less plans, so the plan is shown to
 * be the painter's input rather than a number only this test can see.
 */
class RenderDeterminismTest {

    private static final int CHILD_TIMEOUT_SECONDS = 120;
    private static final String SEED = "ABC123";

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();

    @TempDir
    Path temp;

    @Test
    void aFixedSeedPicksTheSameDecorationVariantsAndGroundCropAcrossTwoJvmsAndAReorderOnlyMovesThemAfterAVersionBump()
            throws Exception {
        Path assets = ROOT.resolve("assets");
        String baseline = RenderPlanProbe.describe(assets, SEED);

        // Two separate JVMs, plus this one, on the shipped manifest.
        assertTrue(baseline.equals(inSeparateJvm(assets)),
                "a separate JVM picked other decoration variants or another ground crop");
        assertTrue(baseline.equals(inSeparateJvm(assets)),
                "the two separate JVM runs disagree with each other");

        // The transcript is not empty and carries the two fields.
        assertTrue(count(baseline, "deco ") > 0,
                "no tile picked a decoration, so the variants are not exercised: " + baseline);
        assertTrue(count(baseline, "deco ") < TileMap.COLS * TileMap.ROWS,
                "every tile carries a decoration, so the plan is not choosing");
        assertTrue(count(baseline, "variant deco.flower ") > 0,
                "the deco.flower variant group is missing from the plan");
        int[] grass = cropOrigins(baseline, "ground.grass.field");
        int[] dirt = cropOrigins(baseline, "ground.dirt.field");
        assertTrue(grass[0] >= 0 && grass[0] <= RenderPlan.FIELD_WIDTH - RenderHash.BOARD_WIDTH,
                "the grass crop is outside its field: " + grass[0]);
        assertTrue(grass[1] >= 0 && grass[1] <= RenderPlan.FIELD_HEIGHT - RenderHash.BOARD_HEIGHT,
                "the grass crop is outside its field: " + grass[1]);
        assertTrue(dirt[0] >= 0 && dirt[0] <= RenderPlan.FIELD_WIDTH - RenderHash.BOARD_WIDTH,
                "the dirt crop is outside its field: " + dirt[0]);
        assertTrue(dirt[1] >= 0 && dirt[1] <= RenderPlan.FIELD_HEIGHT - RenderHash.BOARD_HEIGHT,
                "the dirt crop is outside its field: " + dirt[1]);

        // The seed is part of the choice: another valid seed moves the layer.
        assertTrue(!baseline.equals(RenderPlanProbe.describe(assets, "TQ57A1")),
                "a different seed picked the same variants and crop, so no seed reaches the hash");

        // A reorder with the same version changes nothing.
        Path reordered = scratchManifest(1, true);
        assertTrue(baseline.equals(RenderPlanProbe.describe(reordered, SEED)),
                "reversing the manifest's slots changed the variants or the crop without a bump");

        // The same reorder with a bumped version is what moves them.
        Path bumped = scratchManifest(2, true);
        String moved = RenderPlanProbe.describe(bumped, SEED);
        assertTrue(moved.startsWith("version 2\n"), "the scratch manifest carries the bumped version");
        assertTrue(!picksOf(baseline).equals(picksOf(moved)),
                "a slotSetVersion bump changed no variant and no decoration");
        // The two halves move for their own reason: comparing the whole transcript
        // would let the crops' movement stand in for the variants' (or the other
        // way round), so each choice is asserted on its own lines.
        assertTrue(!variantLines(baseline).equals(variantLines(moved)),
                "the variant picks ignored the slotSetVersion bump");
        assertTrue(!decoLines(baseline).equals(decoLines(moved)),
                "the decorations on tiles ignored the slotSetVersion bump");
        assertNotEquals(cropLine(baseline, "ground.grass.field"), cropLine(moved, "ground.grass.field"),
                "the ground crop ignored the slotSetVersion bump");
        assertNotEquals(cropLine(baseline, "ground.dirt.field"), cropLine(moved, "ground.dirt.field"),
                "the dirt crop ignored the slotSetVersion bump");

        // The painter consumes the plan: same board and seed, different plans,
        // different pixels. The region avoids the HUD band so only the plate and
        // the decorations can account for the difference.
        Match match = Match.start(SEED);
        AssetSource shipped = AssetSource.load(assets, message -> { });
        AssetSource bumpedAssets = AssetSource.load(bumped, message -> { });
        BufferedImage plate = BoardPainter.render(Frames.of(match, Settings.defaults(), shipped));
        BufferedImage changed = BoardPainter.render(Frames.of(match, Settings.defaults(), bumpedAssets));
        BufferedImage bare = BoardPainter.render(Frames.of(match, Settings.defaults(),
                AssetSource.placeholder()));
        assertTrue(differentPixels(plate, changed) > 1000,
                "the painter drew the same plate for two different render plans");
        assertTrue(differentPixels(plate, bare) > 1000,
                "a kit-less run drew the same plate as a manifest-driven one");
    }

    /** Runs the probe in a fresh JVM and returns exactly the bytes it wrote. */
    private static String inSeparateJvm(Path assets) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path exe = Path.of(java + ".exe");
        List<String> launch = new ArrayList<>();
        launch.add(Files.isRegularFile(exe) ? exe.toString() : java.toString());
        launch.add("-cp");
        launch.add(System.getProperty("java.class.path"));
        launch.add(RenderPlanProbe.class.getName());
        launch.add(assets.toString());
        launch.add(SEED);

        Process process = new ProcessBuilder(launch).redirectErrorStream(true).start();
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        if (!process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the child JVM did not finish within " + CHILD_TIMEOUT_SECONDS + " s");
        }
        assertEquals(0, process.exitValue(),
                "the child JVM exited " + process.exitValue() + ": "
                        + new String(output, StandardCharsets.UTF_8));
        return new String(output, StandardCharsets.UTF_8);
    }

    /** The committed manifest copied into a scratch assets tree, with the slots reversed. */
    private Path scratchManifest(int slotSetVersion, boolean reorder) throws Exception {
        Path directory = Files.createDirectories(temp.resolve("assets-" + slotSetVersion
                + (reorder ? "-reordered" : "-ordered")));
        JsonObject manifest = JsonParser.parseString(Files.readString(
                        ROOT.resolve("assets/manifest.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (reorder) {
            JsonArray slots = manifest.getAsJsonArray("slots");
            JsonArray reversed = new JsonArray();
            for (int i = slots.size() - 1; i >= 0; i--) {
                reversed.add(slots.get(i));
            }
            manifest.add("slots", reversed);
        }
        manifest.addProperty("slotSetVersion", slotSetVersion);
        Files.writeString(directory.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest),
                StandardCharsets.UTF_8);
        return directory;
    }

    /**
     * The plan's decoration choices alone: the {@code variant} and {@code deco}
     * lines, without the version or the crop lines. A bump assertion on this value
     * cannot be satisfied by a ground crop that moved while the variants stood
     * still — the hole the verifier named at 8561387.
     */
    private static String picksOf(String transcript) {
        return linesStartingWith(transcript, "variant ") + linesStartingWith(transcript, "deco ");
    }

    /** Only the {@code variant <group> <col> <row> <member>} lines. */
    private static String variantLines(String transcript) {
        return linesStartingWith(transcript, "variant ");
    }

    /** Only the {@code deco <col> <row> <slot>} lines. */
    private static String decoLines(String transcript) {
        return linesStartingWith(transcript, "deco ");
    }

    private static String linesStartingWith(String text, String prefix) {
        StringBuilder lines = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                lines.append(line).append('\n');
            }
        }
        return lines.toString();
    }

    private static String cropLine(String transcript, String fieldId) {
        return lineStartingWith(transcript, "crop " + fieldId + " ");
    }

    private static void assertInRange(String line, int fieldWidth) {
        assertTrue(!line.isEmpty(), "the plan carries no crop line for a field");
        String[] parts = line.split(" ");
        int x = Integer.parseInt(parts[2]);
        int y = Integer.parseInt(parts[3]);
        assertTrue(x >= 0 && x <= fieldWidth - RenderHash.BOARD_WIDTH,
                "crop x outside the field: " + line);
        assertTrue(y >= 0 && y <= RenderPlan.FIELD_HEIGHT - RenderHash.BOARD_HEIGHT,
                "crop y outside the field: " + line);
    }

    private static int[] cropOrigins(String transcript, String fieldId) {
        String[] parts = cropLine(transcript, fieldId).split(" ");
        assertEquals(4, parts.length, "malformed crop line: " + cropLine(transcript, fieldId));
        return new int[]{Integer.parseInt(parts[2]), Integer.parseInt(parts[3])};
    }

    private static String lineStartingWith(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return "";
    }

    private static int count(String text, String prefix) {
        int total = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                total++;
            }
        }
        return total;
    }

    /** Pixels that differ inside the board, below the HUD band. */
    private static int differentPixels(BufferedImage a, BufferedImage b) {
        int differing = 0;
        for (int y = 250; y < 760; y++) {
            for (int x = 180; x < 1420; x++) {
                if ((a.getRGB(x, y) & 0xffffff) != (b.getRGB(x, y) & 0xffffff)) {
                    differing++;
                }
            }
        }
        return differing;
    }
}
