package rabbitrace.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The audio half of {@code assets/manifest.json}.
 *
 * <p>Every slot resolves through this class to either a committed WAV or
 * a known {@link Synth} name; a slot may be absent (it is silent), so
 * {@link #missingSlots()} reports the gap instead of hiding it, and
 * {@link #resolve(String)} refuses to invent an entry. Structural errors — an
 * invented id, a duplicate, both sources, an unknown synth, a file that is not
 * an existing WAV — are rejected at load with a message naming the entry.
 *
 * <p>Playback is not here: {@code rabbitrace.audio} opens the resolved asset
 * through the seam. {@link #loadForPlayback(Path,
 * Consumer)} is the runtime reading of the same file, where a failure is
 * silence with one log line rather than an exception.
 */
public final class AudioManifest {

    private final Path assetsRoot;
    private final Map<String, AudioAsset> byId;

    private AudioManifest(Path assetsRoot, Map<String, AudioAsset> byId) {
        this.assetsRoot = assetsRoot;
        this.byId = byId;
    }

    /**
     * Parses the manifest at {@code manifestFile}, resolving {@code file}
     * entries against its directory (paths are relative to {@code assets/}).
     */
    public static AudioManifest load(Path manifestFile) throws IOException {
        Path absolute = manifestFile.toAbsolutePath().normalize();
        Path root = absolute.getParent();
        String json = Files.readString(absolute, StandardCharsets.UTF_8);
        return parse(json, root);
    }

    static AudioManifest parse(String json, Path assetsRoot) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("manifest is not a JSON object: " + e.getMessage(), e);
        }
        if (!root.has("audio") || !root.get("audio").isJsonArray()) {
            throw new IllegalArgumentException("manifest has no 'audio' array");
        }

        Map<String, AudioAsset> byId = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("audio")) {
            AudioAsset asset = entry(element, assetsRoot);
            if (byId.putIfAbsent(asset.id(), asset) != null) {
                throw new IllegalArgumentException(
                        "audio slot '" + asset.id() + "' appears twice in the manifest");
            }
        }
        return new AudioManifest(assetsRoot, byId);
    }

    /**
     * The runtime load: a missing slot, an
     * unreadable WAV and an entry that breaks a rule each leave that slot silent
     * with <em>one</em> line, and never throw into the game. The strict
     * {@link #load(Path)} above remains the validation path, so the
     * two readings of the same file are separate on purpose.
     *
     * <p>The returned manifest simply omits a failed slot: {@link #find(String)}
     * is empty, {@link #missingSlots()} names it, and no other entry changes.
     *
     * @param manifestFile the committed {@code assets/manifest.json}
     * @param log where a failure's one line goes ({@code System.err} in
     *                     the game, a collector in a test)
     */
    public static AudioManifest loadForPlayback(Path manifestFile, Consumer<String> log) {
        Path absolute = manifestFile.toAbsolutePath().normalize();
        Path root = absolute.getParent();
        JsonObject document;
        try {
            document = JsonParser.parseString(
                    Files.readString(absolute, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            log.accept("audio: manifest '" + absolute + "' is unreadable ("
                    + reason(e) + "); every slot is silent");
            return new AudioManifest(root, new LinkedHashMap<>());
        }
        if (!document.has("audio") || !document.get("audio").isJsonArray()) {
            log.accept("audio: manifest '" + absolute + "' has no 'audio' array; "
                    + "every slot is silent");
            return new AudioManifest(root, new LinkedHashMap<>());
        }

        // Group the raw entries before resolving: a duplicate and an invented id
        // are both failures of one slot, not of the whole file.
        Map<String, List<JsonElement>> byId = new LinkedHashMap<>();
        for (JsonElement element : document.getAsJsonArray("audio")) {
            String id = entryId(element);
            if (id == null) {
                log.accept("audio: an entry is not an object with a string 'id'; it is ignored");
                continue;
            }
            byId.computeIfAbsent(id, key -> new ArrayList<>()).add(element);
        }

        Map<String, AudioAsset> resolved = new LinkedHashMap<>();
        for (AudioSlot slot : AudioSlot.values()) {
            List<JsonElement> candidates = byId.remove(slot.id());
            if (candidates == null || candidates.isEmpty()) {
                log.accept("audio slot '" + slot.id() + "' is missing; it is silent");
                continue;
            }
            if (candidates.size() > 1) {
                log.accept("audio slot '" + slot.id() + "' appears " + candidates.size()
                        + " times; it is silent");
                continue;
            }
            try {
                resolved.put(slot.id(), entry(candidates.get(0), root));
            } catch (RuntimeException e) {
                log.accept("audio slot '" + slot.id() + "' is invalid (" + reason(e)
                        + "); it is silent");
            }
        }
        // Anything still here is not a slot; it cannot make one of them sound.
        for (String invented : byId.keySet()) {
            log.accept("audio id '" + invented + "' is not a slot; it is ignored");
        }
        return new AudioManifest(root, resolved);
    }

    private static String entryId(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonElement id = element.getAsJsonObject().get("id");
        return id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                ? id.getAsString()
                : null;
    }

    private static String reason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private static AudioAsset entry(JsonElement element, Path assetsRoot) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("an 'audio' entry is not an object: " + element);
        }
        JsonObject entry = element.getAsJsonObject();
        String id = string(entry, "id");
        AudioSlot slot = AudioSlot.byId(id);
        if (slot == null) {
            throw new IllegalArgumentException(
                    "audio id '" + id + "' is not a slot");
        }

        String file = optionalString(entry, "file");
        String synth = optionalString(entry, "synth");
        if ((file == null) == (synth == null)) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' needs exactly one of 'file' and 'synth'");
        }
        long durationMs;
        if (file != null) {
            file = existingWav(id, file, assetsRoot);
            durationMs = wavDurationMs(id, file, assetsRoot);
        } else {
            Synth kit = Synth.byName(synth);
            if (kit == null) {
                throw new IllegalArgumentException(
                        "audio slot '" + id + "' names unknown synth '" + synth + "'");
            }
            durationMs = kit.durationMs();
        }

        String group = string(entry, "group");
        if (!group.equals(slot.group())) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' belongs to group '" + slot.group()
                            + "', not '" + group + "'");
        }
        boolean loop = bool(entry, "loop");
        if (loop != slot.loop()) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has loop=" + loop + "; the slot says "
                            + slot.loop());
        }
        double gainDb = gainDb(entry, id);
        licence(entry, id);

        long loopStartMs = 0;
        long loopEndMs = 0;
        if (loop) {
            // required when loop is true, defaulting to 0 and
            // the source duration; the pair must sit inside the source.
            loopStartMs = millis(entry, "loopStartMs", 0, id);
            loopEndMs = millis(entry, "loopEndMs", durationMs, id);
            if (loopStartMs < 0 || loopStartMs >= loopEndMs || loopEndMs > durationMs) {
                throw new IllegalArgumentException(
                        "audio slot '" + id + "' declares loop points " + loopStartMs + ".."
                                + loopEndMs + " ms, which are not 0 <= start < end <= "
                                + durationMs + " ms");
            }
        }
        return new AudioAsset(id, group, loop, file, synth, gainDb,
                durationMs, loopStartMs, loopEndMs);
    }

    /**
     * The per-slot trim: a number in −12…+6. Absent
     * means 0. The value is read as a number rather than truncated to an
     * integer, so −12.5 is outside the clamp instead of silently becoming −12.
     */
    private static double gainDb(JsonObject entry, String id) {
        if (!entry.has("gainDb") || entry.get("gainDb").isJsonNull()) {
            return 0.0;
        }
        JsonElement value = entry.get("gainDb");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has a non-numeric 'gainDb': " + value);
        }
        double number = value.getAsDouble();
        if (number < -12.0 || number > 6.0) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has gainDb " + value
                            + ", outside −12…+6");
        }
        return number;
    }

    /**
     * Enforces the credit rule: a credit, when present, carries all five
     * fields, and an entry whose licence is not CC0 must carry one. The shipped
     * boot kit is CC0 and therefore declares no credit at all; a slot-level
     * {@code licence} is accepted as the declaration of a swapped-in file's
     * terms, with {@code credit.licence} as the fallback for a credit-only
     * entry.
     */
    private static void licence(JsonObject entry, String id) {
        String declared = declaredLicence(entry, id);
        JsonObject credit = credit(entry, id);
        if (declared == null && credit != null) {
            declared = credit.get("licence").getAsString();
        }
        if (declared != null && !isCc0(declared) && credit == null) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' declares licence '" + declared
                            + "' without a 'credit' record");
        }
    }

    private static String declaredLicence(JsonObject entry, String id) {
        if (!entry.has("licence") || entry.get("licence").isJsonNull()) {
            return null;
        }
        JsonElement value = entry.get("licence");
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String field : new String[] {"spdx", "licence", "license"}) {
                if (object.has(field) && object.get(field).isJsonPrimitive()
                        && object.get(field).getAsJsonPrimitive().isString()) {
                    return object.get(field).getAsString();
                }
            }
        }
        throw new IllegalArgumentException(
                "audio slot '" + id + "' has a 'licence' that names no terms: " + value);
    }

    private static JsonObject credit(JsonObject entry, String id) {
        if (!entry.has("credit") || entry.get("credit").isJsonNull()) {
            return null;
        }
        if (!entry.get("credit").isJsonObject()) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has a 'credit' that is not an object: "
                            + entry.get("credit"));
        }
        JsonObject credit = entry.getAsJsonObject("credit");
        for (String field : new String[] {"title", "author", "licence", "source", "changes"}) {
            if (!credit.has(field) || !credit.get(field).isJsonPrimitive()
                    || !credit.get(field).getAsJsonPrimitive().isString()
                    || credit.get(field).getAsString().isBlank()) {
                throw new IllegalArgumentException(
                        "audio slot '" + id + "' has a credit without a non-empty '"
                                + field + "'");
            }
        }
        return credit;
    }

    private static boolean isCc0(String licence) {
        return licence.strip().toUpperCase(Locale.ROOT).startsWith("CC0");
    }

    /** A loop point in whole milliseconds; absent or null means the default. */
    private static long millis(JsonObject entry, String field, long fallback, String id) {
        if (!entry.has(field) || entry.get(field).isJsonNull()) {
            return fallback;
        }
        JsonElement value = entry.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has a non-numeric '" + field + "': " + value);
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number)) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has a fractional '" + field + "': " + value);
        }
        return value.getAsLong();
    }

    /** The real length of the committed WAV, so a loop point can be checked. */
    private static long wavDurationMs(String id, String relative, Path assetsRoot) {
        Path file = assetsRoot.resolve(relative);
        AudioFileFormat format;
        try {
            format = AudioSystem.getAudioFileFormat(file.toFile());
        } catch (UnsupportedAudioFileException | IOException e) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' names a WAV whose duration cannot be read: "
                            + relative, e);
        }
        AudioFormat audio = format.getFormat();
        long frames = format.getFrameLength();
        if (frames == AudioSystem.NOT_SPECIFIED) {
            int frameSize = audio.getFrameSize();
            long bytes = format.getByteLength();
            frames = frameSize > 0 && bytes != AudioSystem.NOT_SPECIFIED ? bytes / frameSize : -1;
        }
        float rate = audio.getSampleRate();
        if (frames < 0 || rate <= 0) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' names a WAV whose duration cannot be determined: "
                            + relative);
        }
        return Math.round(frames * 1000.0 / rate);
    }

    private static String existingWav(String id, String file, Path assetsRoot) {
        String relative = file.replace('\\', '/');
        Path resolved = assetsRoot.resolve(relative).normalize();
        if (!resolved.startsWith(assetsRoot.normalize())) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' points outside assets/: " + file);
        }
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".wav")) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' must name a WAV, not '" + file + "'");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' names a file that does not exist: " + file);
        }
        return relative;
    }

    /** The ten slots this manifest does not carry, in inventory order. */
    public List<String> missingSlots() {
        List<String> missing = new ArrayList<>();
        for (AudioSlot slot : AudioSlot.values()) {
            if (!byId.containsKey(slot.id())) {
                missing.add(slot.id());
            }
        }
        return List.copyOf(missing);
    }

    /** The resolved entries, in inventory order. */
    public List<AudioAsset> entries() {
        List<AudioAsset> resolved = new ArrayList<>();
        for (AudioSlot slot : AudioSlot.values()) {
            AudioAsset asset = byId.get(slot.id());
            if (asset != null) {
                resolved.add(asset);
            }
        }
        return Collections.unmodifiableList(resolved);
    }

    /** The entry for a slot, or empty when the manifest omits it. */
    public Optional<AudioAsset> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The entry for a slot; a missing slot is an error, not silence. */
    public AudioAsset resolve(String id) {
        AudioAsset asset = byId.get(id);
        if (asset == null) {
            throw new IllegalArgumentException(
                    "no audio entry for slot '" + id + "'");
        }
        return asset;
    }

    /** The directory {@code file} entries resolve against. */
    public Path assetsRoot() {
        return assetsRoot;
    }

    private static String string(JsonObject entry, String field) {
        if (!entry.has(field) || !entry.get(field).isJsonPrimitive()
                || !entry.get(field).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "audio entry " + entry + " has no string '" + field + "'");
        }
        return entry.get(field).getAsString();
    }

    private static String optionalString(JsonObject entry, String field) {
        if (!entry.has(field) || entry.get(field).isJsonNull()) {
            return null;
        }
        return string(entry, field);
    }

    private static boolean bool(JsonObject entry, String field) {
        if (!entry.has(field) || !entry.get(field).isJsonPrimitive()
                || !entry.get(field).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(
                    "audio entry " + entry + " has no boolean '" + field + "'");
        }
        return entry.get(field).getAsBoolean();
    }
}
