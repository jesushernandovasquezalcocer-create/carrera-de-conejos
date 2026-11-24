package rabbitrace.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The sprite side of the asset seam.
 *
 * <p>Today it answers the two questions the state dump needs: whether the run is
 * drawing the placeholder kit or the final files, and the manifest's
 * {@code slotSetVersion}. The painter's {@code image}/{@code meta} queries land
 * with the sprite work that consumes them; this class already resolves the same
 * manifest so the answer is derived, never hardcoded.
 *
 * <p>A missing or unparseable manifest is a procedural
 * source with one warning, not a boot failure. The same is true of the
 * kit: the game has no state in which missing art stops play.
 */
public final class AssetSource {

    /** The kit, or the procedural last resort, is on screen. */
    public static final String PLACEHOLDER = "placeholder";

    /** Every sprite slot resolves to a committed file. */
    public static final String FILE = "file";

    /**
     * One manifest slot as the renderer needs it:
     * the id (which gives its variant group), where it is placed, its
     * declared source size, the virtual footprint it is drawn at (absent for a
     * {@code field}) and a field's per-tile sample window. Every vector is
     * {@code [w, h]} and {@code [0, 0]} when the entry does not declare it.
     * {@code transforms} is the allowed subset, in manifest order.
     */
    public record Slot(String id, String placement, int width, int height,
                       int footprintWidth, int footprintHeight,
                       int sampleWidth, int sampleHeight,
                       int sliceLeft, int sliceTop, int sliceRight, int sliceBottom,
                       List<String> transforms) {
    }

    private final String kind;
    private final int slotSetVersion;
    private final List<Slot> slots;

    private AssetSource(String kind, int slotSetVersion, List<Slot> slots) {
        this.kind = kind;
        this.slotSetVersion = slotSetVersion;
        this.slots = List.copyOf(slots);
    }

    /** The status for a run that has not loaded a manifest. */
    public static AssetSource placeholder() {
        return new AssetSource(PLACEHOLDER, 0, List.of());
    }

    /** Loads {@code <root>/manifest.json}, warning once if it cannot be read. */
    public static AssetSource load(Path root) {
        return load(root, System.err::println);
    }

    /** True when {@code -Dassets.strict=true} turns warnings into boot errors. */
    private static boolean strict() {
        return Boolean.parseBoolean(System.getProperty("assets.strict", "false"));
    }

    /**
     * One warning line for a condition that is survivable; in strict mode
     * the same line is a boot error instead.
     */
    private static void warn(Consumer<String> log, String message) {
        log.accept(message);
        if (strict()) {
            throw new IllegalStateException(message + " (assets.strict=true)");
        }
    }

    /**
     * Loads {@code <root>/manifest.json} and reports {@link #FILE} only when every
     * sprite slot that is not {@code procedural} names an existing file. A
     * {@code placeholder} slot, a missing file, or an unreadable manifest is
     * {@link #PLACEHOLDER}.
     */
    public static AssetSource load(Path root, Consumer<String> log) {
        Path assetsRoot = root.toAbsolutePath().normalize();
        Path manifestFile = assetsRoot.resolve("manifest.json");
        JsonObject manifest;
        try {
            manifest = JsonParser.parseString(Files.readString(manifestFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // absent or unparseable means the procedural source.
            warn(log, "assets: manifest '" + manifestFile + "' is unreadable ("
                    + e.getMessage() + "); drawing placeholders");
            return placeholder();
        }

        int version = manifest.has("slotSetVersion") && manifest.get("slotSetVersion").isJsonPrimitive()
                ? manifest.get("slotSetVersion").getAsInt()
                : 0;
        List<Slot> slots = readSlots(manifest);
        if (!isFileKit(manifest, assetsRoot, log)) {
            return new AssetSource(PLACEHOLDER, version, slots);
        }
        return new AssetSource(FILE, version, slots);
    }

    /**
     * The sprite entries the renderer's choices read. A malformed entry is
     * skipped, never fatal: the manifest's own schema checks own that failure
     * and the painter must keep answering.
     */
    private static List<Slot> readSlots(JsonObject manifest) {
        JsonElement entries = manifest.get("slots");
        if (entries == null || !entries.isJsonArray()) {
            return List.of();
        }
        List<Slot> slots = new ArrayList<>();
        for (JsonElement element : entries.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject slot = element.getAsJsonObject();
            String id = text(slot, "id");
            if (id == null || id.isBlank()) {
                continue;
            }
            int width = 0;
            int height = 0;
            JsonElement size = slot.get("size");
            try {
                if (size != null && size.isJsonArray() && size.getAsJsonArray().size() >= 2) {
                    width = size.getAsJsonArray().get(0).getAsInt();
                    height = size.getAsJsonArray().get(1).getAsInt();
                }
            } catch (RuntimeException e) {
                width = 0;
                height = 0;
            }
            int[] footprint = vector(slot, "footprint");
            int[] sample = vector(slot, "sample");
            int[] slice = quad(slot, "nineSlice");
            slots.add(new Slot(id, text(slot, "placement"), width, height,
                    footprint[0], footprint[1], sample[0], sample[1],
                    slice[0], slice[1], slice[2], slice[3], names(slot, "transforms")));
        }
        return List.copyOf(slots);
    }

    /** A string array in manifest order, or an empty list when absent. */
    private static List<String> names(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element != null && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isString()) {
                names.add(element.getAsString());
            }
        }
        return List.copyOf(names);
    }

    /** A two-component vector as {@code {w, h}}, or {@code {0, 0}} when absent. */
    private static int[] vector(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() < 2) {
            return new int[]{0, 0};
        }
        try {
            return new int[]{value.getAsJsonArray().get(0).getAsInt(),
                    value.getAsJsonArray().get(1).getAsInt()};
        } catch (RuntimeException e) {
            return new int[]{0, 0};
        }
    }

    /**
     * A four-component vector as {@code {l, t, r, b}}, or {@code {0, 0, 0, 0}} when
     * absent ({@code nineSlice}).
     */
    private static int[] quad(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() < 4) {
            return new int[]{0, 0, 0, 0};
        }
        try {
            return new int[]{value.getAsJsonArray().get(0).getAsInt(),
                    value.getAsJsonArray().get(1).getAsInt(),
                    value.getAsJsonArray().get(2).getAsInt(),
                    value.getAsJsonArray().get(3).getAsInt()};
        } catch (RuntimeException e) {
            return new int[]{0, 0, 0, 0};
        }
    }

    private static String text(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    /**
     * True only when every sprite slot resolves to a real file. A {@code file}
     * slot whose artifact is absent is one warning line and that slot's
     * placeholder, not a boot failure unless strict.
     */
    private static boolean isFileKit(JsonObject manifest, Path assetsRoot, Consumer<String> log) {
        JsonElement slots = manifest.get("slots");
        if (slots == null || !slots.isJsonArray()) {
            return false;
        }
        JsonArray entries = slots.getAsJsonArray();
        boolean sawSprite = false;
        boolean complete = true;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject slot = element.getAsJsonObject();
            String source = slot.has("source") && slot.get("source").isJsonPrimitive()
                    ? slot.get("source").getAsString()
                    : "";
            if ("procedural".equals(source)) {
                continue;
            }
            sawSprite = true;
            if (!"file".equals(source)) {
                complete = false;
                continue;
            }
            String id = slot.has("id") && slot.get("id").isJsonPrimitive()
                    ? slot.get("id").getAsString()
                    : "<unknown>";
            String file = slot.has("file") && slot.get("file").isJsonPrimitive()
                    ? slot.get("file").getAsString()
                    : "";
            Path artifact = file.isEmpty() ? null : assetsRoot.resolve(file).normalize();
            if (artifact == null || !Files.isRegularFile(artifact)) {
                warn(log, "assets: slot '" + id + "' file '" + file
                        + "' is missing; drawing its placeholder");
                complete = false;
            }
        }
        return sawSprite && complete;
    }

    /** {@link #PLACEHOLDER} or {@link #FILE}, the {@code state.assets} value. */
    public String kind() {
        return kind;
    }

    /** The manifest's {@code slotSetVersion}, or 0 when it declares none. */
    public int slotSetVersion() {
        return slotSetVersion;
    }

    /** The manifest's sprite slots in manifest order; empty without a manifest. */
    public List<Slot> slots() {
        return slots;
    }
}
