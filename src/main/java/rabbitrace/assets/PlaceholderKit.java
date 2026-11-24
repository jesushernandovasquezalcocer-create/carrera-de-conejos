package rabbitrace.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The placeholder kit: one committed PNG per
 * {@code source: placeholder} sprite slot, at the slot's declared size, plus
 * {@code kit.json}, which records the {@code placeholderVersion} the kit covers.
 *
 * <p>The manifest gives two version numbers two jobs. {@code slotSetVersion} bumps whenever
 * a slot is added, removed or renamed, and the manifest, the lock and the kit must
 * agree on it. The kit also names every slot it covers, so adding a slot without
 * refreshing the kit is visible: {@link #problems} compares the manifest's
 * placeholder slots with the kit's coverage, names any slot the kit does not have,
 * and asks for the bump. {@link #write} is the generator; the kit is committed and
 * never hand-drawn.
 */
public final class PlaceholderKit {

    /** The kit directory, relative to {@code assets/}. */
    public static final String DIRECTORY = "placeholders";

    /** The kit's version record, relative to the kit directory. */
    public static final String INDEX = "kit.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private PlaceholderKit() {
    }

    /** One {@code source: placeholder} slot as the kit must cover it. */
    public record Covered(String id, int width, int height) {
    }

    /** The manifest's {@code slotSetVersion}, or −1 when absent. */
    public static int manifestVersion(Path assetsRoot) throws IOException {
        return integer(read(assetsRoot.resolve(ManifestLock.MANIFEST)), "slotSetVersion", -1);
    }

    /** The lock's {@code slotSetVersion}, or −1 when the lock is absent. */
    public static int lockVersion(Path assetsRoot) throws IOException {
        Path lock = assetsRoot.resolve(ManifestLock.LOCK);
        return Files.isRegularFile(lock) ? integer(read(lock), "slotSetVersion", -1) : -1;
    }

    /** The kit's {@code placeholderVersion}, or −1 when the kit is absent. */
    public static int kitVersion(Path assetsRoot) throws IOException {
        Path index = assetsRoot.resolve(DIRECTORY).resolve(INDEX);
        return Files.isRegularFile(index) ? integer(read(index), "placeholderVersion", -1) : -1;
    }

    /** Every {@code source: placeholder} slot, in manifest order. */
    public static List<Covered> expected(Path assetsRoot) throws IOException {
        return expected(read(assetsRoot.resolve(ManifestLock.MANIFEST)));
    }

    private static List<Covered> expected(JsonObject manifest) {
        List<Covered> slots = new ArrayList<>();
        JsonElement entries = manifest.get("slots");
        if (entries == null || !entries.isJsonArray()) {
            return slots;
        }
        for (JsonElement element : entries.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject slot = element.getAsJsonObject();
            if (!"placeholder".equals(string(slot, "source"))) {
                continue;
            }
            String id = string(slot, "id");
            int[] size = vector(slot, "size");
            slots.add(new Covered(id, size[0], size[1]));
        }
        return slots;
    }

    /** Regenerates the kit's exact {@code kit.json} text from the manifest. */
    public static String renderIndex(Path assetsRoot) throws IOException {
        JsonObject manifest = read(assetsRoot.resolve(ManifestLock.MANIFEST));
        JsonObject index = new JsonObject();
        index.addProperty("placeholderVersion", integer(manifest, "slotSetVersion", 0));
        JsonArray slots = new JsonArray();
        for (Covered slot : expected(manifest)) {
            if (slot.id() == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", slot.id());
            entry.addProperty("file", slot.id() + ".png");
            JsonArray size = new JsonArray();
            size.add(slot.width());
            size.add(slot.height());
            entry.add("size", size);
            slots.add(entry);
        }
        index.add("slots", slots);
        return GSON.toJson(index) + "\n";
    }

    /** Writes the kit's PNGs and {@code kit.json}; the only writer is the generator. */
    public static void write(Path assetsRoot) throws IOException {
        Path kit = assetsRoot.resolve(DIRECTORY);
        Files.createDirectories(kit);
        for (Covered slot : expected(assetsRoot)) {
            if (slot.id() == null || slot.width() <= 0 || slot.height() <= 0) {
                throw new IOException("a source:placeholder slot has no id or declared size: " + slot);
            }
            Path file = kit.resolve(slot.id() + ".png");
            ImageIO.write(draw(slot), "png", file.toFile());
        }
        Files.writeString(kit.resolve(INDEX), renderIndex(assetsRoot), StandardCharsets.UTF_8);
    }

    /** {@code gradlew placeholderKit}: regenerate the committed kit. */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "assets");
        write(root);
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve(DIRECTORY))) {
            System.out.println("placeholderKit: wrote " + files.count()
                    + " files under " + root.resolve(DIRECTORY));
        }
    }

    /**
     * Every rule states, as messages that name the slot: the manifest,
     * the lock and the kit agree on the version; the kit covers every
     * {@code source: placeholder} slot and nothing else; every slot's PNG exists at
     * its declared size. An added slot without a refreshed kit and a bump is the
     * first mismatch this reports.
     */
    public static List<String> problems(Path assetsRoot) throws IOException {
        List<String> problems = new ArrayList<>();

        JsonObject manifest;
        try {
            manifest = read(assetsRoot.resolve(ManifestLock.MANIFEST));
        } catch (IOException | RuntimeException e) {
            problems.add("assets/" + ManifestLock.MANIFEST + " is unreadable (" + e.getMessage() + ")");
            return problems;
        }
        int manifestVersion = integer(manifest, "slotSetVersion", -1);

        int lockVersion = -1;
        Path lockPath = assetsRoot.resolve(ManifestLock.LOCK);
        if (!Files.isRegularFile(lockPath)) {
            problems.add("assets/" + ManifestLock.LOCK + " is missing; its slotSetVersion cannot agree");
        } else {
            try {
                lockVersion = integer(read(lockPath), "slotSetVersion", -1);
            } catch (IOException | RuntimeException e) {
                problems.add("assets/" + ManifestLock.LOCK + " is unreadable (" + e.getMessage() + ")");
            }
        }

        JsonObject kit = null;
        int kitVersion = -1;
        Path indexPath = assetsRoot.resolve(DIRECTORY).resolve(INDEX);
        if (!Files.isRegularFile(indexPath)) {
            problems.add("assets/" + DIRECTORY + "/" + INDEX + " is missing; the kit declares no placeholderVersion");
        } else {
            try {
                kit = read(indexPath);
                kitVersion = integer(kit, "placeholderVersion", -1);
            } catch (IOException | RuntimeException e) {
                problems.add("assets/" + DIRECTORY + "/" + INDEX + " is unreadable (" + e.getMessage() + ")");
            }
        }

        if (manifestVersion < 0 || lockVersion < 0 || kitVersion < 0
                || manifestVersion != lockVersion || lockVersion != kitVersion) {
            problems.add("manifest.slotSetVersion " + manifestVersion + ", the lock's " + lockVersion
                    + " and the kit's " + kitVersion + " do not agree; all three must match");
        }

        List<Covered> expected = expected(manifest);
        Map<String, int[]> covered = kit == null ? Map.of() : covered(kit, problems);
        for (Covered slot : expected) {
            if (slot.id() == null) {
                problems.add("a source:placeholder slot has no id; the kit cannot cover it");
                continue;
            }
            String where = "slot '" + slot.id() + "'";
            int[] declared = covered.get(slot.id());
            if (declared == null) {
                problems.add(where + " was added but the kit does not cover it; refresh the kit and bump "
                        + "slotSetVersion in the manifest, the lock and the kit");
                continue;
            }
            if (declared[0] != slot.width() || declared[1] != slot.height()) {
                problems.add(where + " declares size " + slot.width() + "×" + slot.height()
                        + " but the kit covers " + declared[0] + "×" + declared[1]
                        + "; refresh the kit");
            }
            Path png = assetsRoot.resolve(DIRECTORY).resolve(slot.id() + ".png");
            if (!Files.isRegularFile(png)) {
                problems.add(where + " has no kit file assets/" + DIRECTORY + "/" + slot.id() + ".png");
                continue;
            }
            try {
                BufferedImage image = ImageIO.read(png.toFile());
                if (image == null) {
                    problems.add(where + " kit file is not a readable PNG");
                } else if (image.getWidth() != slot.width() || image.getHeight() != slot.height()) {
                    problems.add(where + " kit file is " + image.getWidth() + "×" + image.getHeight()
                            + ", the slot declares " + slot.width() + "×" + slot.height());
                }
            } catch (IOException e) {
                problems.add(where + " kit file is unreadable (" + e.getMessage() + ")");
            }
        }

        for (String id : covered.keySet()) {
            boolean present = expected.stream().anyMatch(slot -> id.equals(slot.id()));
            if (!present) {
                problems.add("the kit covers '" + id + "' but the manifest has no matching source:placeholder slot; "
                        + "the kit is stale");
            }
        }
        return problems;
    }

    /** The kit's recorded coverage, rejecting duplicate ids. */
    private static Map<String, int[]> covered(JsonObject kit, List<String> problems) {
        Map<String, int[]> covered = new LinkedHashMap<>();
        JsonElement entries = kit.get("slots");
        if (entries == null || !entries.isJsonArray()) {
            problems.add("assets/" + DIRECTORY + "/" + INDEX + " has no 'slots' array");
            return covered;
        }
        for (JsonElement element : entries.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                problems.add("the kit has an entry that is not an object");
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = string(entry, "id");
            if (id == null) {
                problems.add("the kit has an entry without an id");
                continue;
            }
            int[] size = vector(entry, "size");
            if (covered.put(id, size) != null) {
                problems.add("the kit covers '" + id + "' more than once");
            }
        }
        return covered;
    }

    private static BufferedImage draw(Covered slot) {
        BufferedImage image = new BufferedImage(
                Math.max(1, slot.width()), Math.max(1, slot.height()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paint(g, slot, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * The flat palette fills: silhouettes for props, a two-tone field, a solid
     * border plate, a rabbit with its scarf. The kit is allowed to look plain; it
     * is not allowed to be partial.
     */
    private static void paint(Graphics2D g, Covered slot, int w, int h) {
        String id = slot.id();
        Color ink = new Color(0x19, 0x1B, 0x10);
        if (id.endsWith(".field")) {
            field(g, id, w, h);
        } else if ("border.plate".equals(id)) {
            g.setColor(new Color(0x6B, 0x70, 0x14));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0x42, 0x4D, 0x11));
            int band = Math.max(8, Math.min(w, h) / 12);
            g.fillRect(0, 0, w, band);
            g.fillRect(0, h - band, w, band);
            g.fillRect(0, 0, band, h);
            g.fillRect(w - band, 0, band, h);
        } else if (id.startsWith("ui.")) {
            ui(g, id, w, h);
        } else if (id.startsWith("rabbit.")) {
            rabbit(g, id, w, h);
        } else if (id.startsWith("shadow.")) {
            g.setColor(new Color(0x19, 0x1B, 0x10, 0x66));
            g.fill(new Ellipse2D.Double(w * 0.1, h * 0.1, w * 0.8, h * 0.8));
        } else if ("goal.carrot.glow".equals(id)) {
            for (int i = 6; i >= 1; i--) {
                double size = w * (0.25 + 0.1 * i);
                g.setColor(new Color(0xC4, 0x9D, 0x47, 18 * i));
                g.fill(new Ellipse2D.Double((w - size) / 2, (h - size) / 2, size, size));
            }
        } else if ("goal.carrot.base".equals(id)) {
            g.setColor(new Color(0x5C, 0x80, 0x3F));
            g.fill(new Ellipse2D.Double(w * 0.36, h * 0.04, w * 0.28, h * 0.24));
            g.setColor(new Color(0xE5, 0x65, 0x02));
            int[] xs = {w / 2, (int) (w * 0.3), (int) (w * 0.7)};
            int[] ys = {(int) (h * 0.96), (int) (h * 0.24), (int) (h * 0.24)};
            g.fillPolygon(xs, ys, 3);
        } else if (id.startsWith("rps.hand.")) {
            g.setColor(parchment());
            g.fill(new RoundRectangle2D.Double(w * 0.08, h * 0.08, w * 0.84, h * 0.84, w * 0.18, h * 0.18));
            g.setColor(ink);
            g.setStroke(new BasicStroke(Math.max(2, w / 60f)));
            g.draw(new RoundRectangle2D.Double(w * 0.08, h * 0.08, w * 0.84, h * 0.84, w * 0.18, h * 0.18));
            g.setColor(new Color(0x8E, 0x65, 0x33));
            g.fill(new Ellipse2D.Double(w * 0.3, h * 0.3, w * 0.4, h * 0.4));
        } else if (id.startsWith("deco.")) {
            g.setColor(id.contains("flower") ? new Color(0xDF, 0xC6, 0x9B)
                    : id.contains("pebble") ? new Color(0xA9, 0x94, 0x84) : new Color(0x5C, 0x80, 0x3F));
            g.fill(new Ellipse2D.Double(w * 0.2, h * 0.35, w * 0.6, h * 0.6));
        } else {
            prop(g, id, w, h);
        }
    }

    private static void field(Graphics2D g, String id, int w, int h) {
        boolean dirt = id.startsWith("ground.dirt");
        g.setColor(dirt ? new Color(0x8E, 0x65, 0x33) : new Color(0x8B, 0x8A, 0x1E));
        g.fillRect(0, 0, w, h);
        g.setColor(dirt ? new Color(0x6E, 0x4C, 0x25) : new Color(0x6B, 0x70, 0x14));
        int step = Math.max(24, Math.min(w, h) / 12);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                if (((x / step) + (y / step)) % 2 == 0) {
                    g.fillOval(x, y, step, step / 2);
                }
            }
        }
    }

    private static void ui(Graphics2D g, String id, int w, int h) {
        Color fill = parchment();
        if (id.endsWith(".hover")) {
            fill = new Color(0xEC, 0xD6, 0xB0);
        } else if (id.endsWith(".pressed")) {
            fill = new Color(0xCB, 0xB1, 0x87);
        } else if (id.endsWith(".disabled")) {
            fill = new Color(0xB9, 0xAE, 0x9B);
        }
        g.setColor(fill);
        g.fillRect(0, 0, w, h);
        int inset = Math.max(3, Math.min(w, h) / 40);
        g.setColor(new Color(0x4F, 0x3E, 0x2B));
        g.setStroke(new BasicStroke(Math.max(2, inset)));
        g.drawRect(inset, inset, w - 2 * inset, h - 2 * inset);
        g.setColor(new Color(0x19, 0x1B, 0x10));
        int bar = Math.max(4, Math.min(w, h) / 18);
        g.fillRect(inset * 2, inset * 2, w - inset * 4, bar);
    }

    private static void rabbit(Graphics2D g, String id, int w, int h) {
        boolean brown = id.startsWith("rabbit.brown");
        Color fur = brown ? new Color(0xCA, 0x7C, 0x41) : new Color(0xE4, 0xE2, 0xDF);
        Color scarf = brown ? new Color(0x8F, 0x32, 0x2D) : new Color(0x33, 0x56, 0x8E);
        g.setColor(fur);
        g.fill(new Ellipse2D.Double(w * 0.16, h * 0.28, w * 0.68, h * 0.66));
        g.fill(new Ellipse2D.Double(w * 0.3, h * 0.02, w * 0.18, h * 0.3));
        g.fill(new Ellipse2D.Double(w * 0.52, h * 0.02, w * 0.18, h * 0.3));
        g.setColor(scarf);
        g.fillRect((int) (w * 0.2), (int) (h * 0.44), (int) (w * 0.6), Math.max(3, h / 12));
        g.setColor(new Color(0x19, 0x1B, 0x10));
        g.fill(new Ellipse2D.Double(w * 0.33, h * 0.33, w * 0.08, h * 0.08));
        g.fill(new Ellipse2D.Double(w * 0.59, h * 0.33, w * 0.08, h * 0.08));
    }

    private static void prop(Graphics2D g, String id, int w, int h) {
        Color fill;
        if (id.startsWith("prop.bush")) {
            fill = new Color(0x26, 0x34, 0x13);
        } else if (id.startsWith("prop.rock")) {
            fill = new Color(0xA9, 0x94, 0x84);
        } else {
            fill = new Color(0x4F, 0x3E, 0x2B);
        }
        g.setColor(fill);
        g.fill(new Ellipse2D.Double(w * 0.08, h * 0.08, w * 0.84, h * 0.84));
        g.setColor(new Color(0x19, 0x1B, 0x10, 0x55));
        g.setStroke(new BasicStroke(Math.max(2, w / 90f)));
        g.draw(new Ellipse2D.Double(w * 0.08, h * 0.08, w * 0.84, h * 0.84));
    }

    private static Color parchment() {
        return new Color(0xDF, 0xC6, 0x9B);
    }

    private static JsonObject read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static int integer(JsonObject object, String field, int fallback) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : fallback;
    }

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
}
