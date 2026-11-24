package rabbitrace.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code assets/manifest.lock.json}: every
 * {@code source: file} slot mapped to its artifact's SHA-256, model and seed.
 *
 * <p>The lock is generated from the manifest by {@link #write}, never
 * hand-edited. Hash verification is a <em>test-time</em> obligation: the
 * shipped run never hashes its files, and a mutated artifact still draws — it is
 * {@code gradlew test} that fails, naming the slot. {@link #problems} is that
 * check; an empty list means every lock-listed file is the accepted one.
 */
public final class ManifestLock {

    /** The lock's schema shape, like the manifest's {@code v}. */
    public static final int SCHEMA = 1;

    public static final String LOCK = "manifest.lock.json";
    public static final String MANIFEST = "manifest.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private ManifestLock() {
    }

    /** Regenerates the lock's exact text from the manifest's {@code source: file} slots. */
    public static String render(Path assetsRoot) throws IOException {
        JsonObject manifest = read(assetsRoot.resolve(MANIFEST));
        JsonObject lock = new JsonObject();
        lock.addProperty("v", SCHEMA);
        lock.addProperty("slotSetVersion", integer(manifest, "slotSetVersion", 0));
        lock.addProperty("checkedOn", string(manifest, "checkedOn"));

        JsonObject slots = new JsonObject();
        JsonElement entries = manifest.get("slots");
        if (entries != null && entries.isJsonArray()) {
            for (JsonElement element : entries.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject slot = element.getAsJsonObject();
                if (!"file".equals(string(slot, "source"))) {
                    continue;
                }
                String id = string(slot, "id");
                String file = string(slot, "file");
                if (id == null || file == null) {
                    throw new IOException("a source 'file' slot has no id or file path");
                }
                JsonObject provenance = slot.has("provenance") && slot.get("provenance").isJsonObject()
                        ? slot.getAsJsonObject("provenance")
                        : new JsonObject();
                JsonObject entry = new JsonObject();
                entry.addProperty("file", file);
                entry.addProperty("sha256", sha256(assetsRoot.resolve(file).normalize()));
                entry.add("model", member(provenance, "model"));
                entry.add("seed", member(provenance, "seed"));
                slots.add(id, entry);
            }
        }
        lock.add("slots", slots);
        return GSON.toJson(lock) + "\n";
    }

    /** Writes {@code assets/manifest.lock.json}; the only writer is the generator. */
    public static void write(Path assetsRoot) throws IOException {
        Files.writeString(assetsRoot.resolve(LOCK), render(assetsRoot), StandardCharsets.UTF_8);
    }

    /**
     * Every rule a file can break, as messages
     * that name the slot. Empty means every lock-listed artifact still hashes to
     * its accepted bytes.
     */
    public static List<String> problems(Path assetsRoot) throws IOException {
        List<String> problems = new ArrayList<>();
        Path lockPath = assetsRoot.resolve(LOCK);
        if (!Files.isRegularFile(lockPath)) {
            problems.add("assets/" + LOCK + " is missing: no artifact is lock-listed");
            return problems;
        }

        JsonObject manifest;
        JsonObject lock;
        try {
            manifest = read(assetsRoot.resolve(MANIFEST));
        } catch (IOException | RuntimeException e) {
            problems.add("assets/" + MANIFEST + " is unreadable (" + e.getMessage() + ")");
            return problems;
        }
        try {
            lock = read(lockPath);
        } catch (IOException | RuntimeException e) {
            problems.add("assets/" + LOCK + " is unreadable (" + e.getMessage() + ")");
            return problems;
        }

        JsonElement declared = lock.get("slots");
        if (declared == null || !declared.isJsonObject()) {
            problems.add("assets/" + LOCK + " has no 'slots' object");
            return problems;
        }
        JsonObject locked = declared.getAsJsonObject();

        Set<String> fileSlots = new LinkedHashSet<>();
        JsonElement entries = manifest.get("slots");
        if (entries != null && entries.isJsonArray()) {
            for (JsonElement element : entries.getAsJsonArray()) {
                if (!element.isJsonObject() || !"file".equals(string(element.getAsJsonObject(), "source"))) {
                    continue;
                }
                JsonObject slot = element.getAsJsonObject();
                String id = string(slot, "id");
                String where = "slot '" + (id == null ? "<unknown>" : id) + "'";
                if (id == null) {
                    problems.add("a source 'file' slot has no id");
                    continue;
                }
                fileSlots.add(id);
                String file = string(slot, "file");
                if (file == null || file.isBlank()) {
                    problems.add(where + " is source 'file' but names no artifact");
                    continue;
                }
                JsonElement entryElement = locked.get(id);
                if (entryElement == null || !entryElement.isJsonObject()) {
                    problems.add(where + " artifact '" + file + "' is not listed in assets/" + LOCK);
                    continue;
                }
                JsonObject entry = entryElement.getAsJsonObject();
                String pinned = string(entry, "sha256");
                Path artifact = assetsRoot.resolve(file).normalize();
                if (!Files.isRegularFile(artifact)) {
                    problems.add(where + " artifact '" + file + "' is missing");
                    continue;
                }
                if (pinned == null) {
                    problems.add(where + " lock entry has no 'sha256'");
                    continue;
                }
                String actual = sha256(artifact);
                if (!pinned.equalsIgnoreCase(actual)) {
                    problems.add(where + " artifact '" + file + "' does not match the lock (locked "
                            + shortHash(pinned) + ", on disk " + shortHash(actual) + ")");
                }
            }
        }

        for (String id : locked.keySet()) {
            if (!fileSlots.contains(id)) {
                problems.add("slot '" + id + "' is lock-listed but the manifest has no matching source:file slot");
            }
        }
        return problems;
    }

    /** The test-time obligation as one exception, naming every slot that moved. */
    public static void verify(Path assetsRoot) throws IOException {
        List<String> problems = problems(assetsRoot);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("assets/manifest.lock.json disagrees with the tree:\n  "
                    + String.join("\n  ", problems));
        }
    }

    /** {@code gradlew manifestLock}: regenerate {@code assets/manifest.lock.json}. */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "assets");
        write(root);
        System.out.println("manifestLock: wrote " + root.resolve(LOCK));
    }

    private static JsonObject read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonElement member(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null ? JsonNull.INSTANCE : element;
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static int integer(JsonObject object, String field, int fallback) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt()
                : fallback;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String shortHash(String value) {
        return value.length() > 12 ? value.substring(0, 12) + "…" : value;
    }
}
