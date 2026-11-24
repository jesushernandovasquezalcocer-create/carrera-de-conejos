package rabbitrace.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates {@code docs/CREDITS.md} from {@code assets/manifest.json}.
 *
 * <p>One row per sprite slot whose {@code licence.spdx} is not CC0, built from
 * that slot's own {@code licence.creditEs},
 * and one row per audio slot whose licence is not CC0, built from that slot's
 * own {@code credit} object: a row is the
 * entry, not a second transcription of it. The shipped boot kit is CC0 and
 * declares no credit, so the generated file has no rows until a non-CC0 file
 * lands, at which point regenerating is the only edit needed.
 *
 * <p>The file is regenerated with
 * {@code java -cp <classes> rabbitrace.assets.Credits assets/manifest.json docs/CREDITS.md};
 * a test compares the committed file to this output, so a hand-written row is
 * red.
 */
public final class Credits {

    /** The research document's machine-readable columns. */
    private static final String[] AUDIO_COLUMNS =
            {"Slot", "File", "Title", "Author", "Licence", "Source", "Changes"};

    /** The sprite licence fields: the row is the manifest entry, not a copy. */
    private static final String[] SPRITE_COLUMNS =
            {"Slot", "File", "Credit (es)", "Licence", "Source"};

    private Credits() {
    }

    /** Renders the document for the manifest at {@code manifestFile}. */
    public static String render(Path manifestFile) throws IOException {
        String json = Files.readString(manifestFile, StandardCharsets.UTF_8);
        JsonObject manifest;
        try {
            manifest = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException(
                    "manifest is not a JSON object: " + e.getMessage(), e);
        }
        return render(manifest);
    }

    /** Renders the document for a parsed manifest: sprite rows then audio rows. */
    public static String render(JsonObject manifest) {
        if (manifest == null || !manifest.has("audio")
                || !manifest.get("audio").isJsonArray()) {
            throw new IllegalArgumentException(
                    "manifest has no 'audio' array");
        }

        List<String[]> spriteRows = spriteRows(manifest);
        List<String[]> audioRows = new ArrayList<>();
        for (JsonElement element : manifest.getAsJsonArray("audio")) {
            String[] row = row(element);
            if (row != null) {
                audioRows.add(row);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("# Credits\n\n")
                .append("Generated from `assets/manifest.json` by ")
                .append("`rabbitrace.assets.Credits`.\n")
                .append("Do not edit by hand: the generator owns this file.\n\n")
                .append("## Sprites\n\n");
        if (spriteRows.isEmpty()) {
            out.append("No non-CC0 sprite assets are shipped; the placeholder kit ")
                    .append("is CC0.\n");
        } else {
            table(out, SPRITE_COLUMNS, spriteRows);
        }
        out.append("\n## Audio\n\n");
        if (audioRows.isEmpty()) {
            out.append("No non-CC0 audio assets are shipped; the boot kit is CC0.\n");
        } else {
            table(out, AUDIO_COLUMNS, audioRows);
        }
        return out.toString();
    }

    /** One table: the header, the rule, then the rows. */
    private static void table(StringBuilder out, String[] columns, List<String[]> rows) {
        out.append('|');
        for (String column : columns) {
            out.append(' ').append(column).append(" |");
        }
        out.append("\n|");
        for (int i = 0; i < columns.length; i++) {
            out.append(" --- |");
        }
        out.append('\n');
        for (String[] row : rows) {
            out.append('|');
            for (String cell : row) {
                out.append(' ').append(cell).append(" |");
            }
            out.append('\n');
        }
    }

    /**
     * One row per sprite slot whose {@code licence.spdx} is not CC0
     * A non-CC0 licence without a non-empty
     * {@code creditEs} is refused rather than rendered as a blank row.
     */
    private static List<String[]> spriteRows(JsonObject manifest) {
        List<String[]> rows = new ArrayList<>();
        JsonElement elements = manifest.get("slots");
        if (elements == null || !elements.isJsonArray()) {
            return rows;
        }
        for (JsonElement element : elements.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(
                        "a 'slots' entry is not an object: " + element);
            }
            JsonObject entry = element.getAsJsonObject();
            String id = requiredString(entry, "id");
            String licence = declaredLicence(entry);
            if (licence == null || isCc0(licence)) {
                continue;
            }
            String creditEs = creditEs(entry, id);
            String file = entry.has("file") && entry.get("file").isJsonPrimitive()
                    && entry.get("file").getAsJsonPrimitive().isString()
                    ? code(entry.get("file").getAsString())
                    : "—";
            rows.add(new String[] {
                code(id),
                file,
                escape(creditEs),
                escape(licence),
                escape(licenceField(entry, "source"))
            });
        }
        return rows;
    }

    /** {@code creditEs} is required—non-empty—when {@code spdx} is not CC0. */
    private static String creditEs(JsonObject entry, String id) {
        JsonElement licence = entry.get("licence");
        if (licence == null || !licence.isJsonObject()) {
            throw new IllegalArgumentException(
                    "sprite slot '" + id + "' declares a non-CC0 licence without "
                            + "a 'licence' object");
        }
        JsonObject object = licence.getAsJsonObject();
        JsonElement value = object.get("creditEs");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(
                    "sprite slot '" + id + "' declares a non-CC0 licence without "
                            + "a non-empty 'creditEs'");
        }
        return value.getAsString();
    }

    /** A textual licence field, or an em dash when the key is absent. */
    private static String licenceField(JsonObject entry, String name) {
        JsonElement licence = entry.get("licence");
        if (licence == null || !licence.isJsonObject()) {
            return "—";
        }
        JsonElement value = licence.getAsJsonObject().get(name);
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "—";
    }

    /** Writes {@link #render(Path)} to {@code creditsFile} as UTF-8. */
    public static void write(Path manifestFile, Path creditsFile) throws IOException {
        Path parent = creditsFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(creditsFile, render(manifestFile), StandardCharsets.UTF_8);
    }

    /** Regenerates the committed file: {@code Credits <manifest.json> <CREDITS.md>}. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: Credits <assets/manifest.json> <CREDITS.md>");
        }
        write(Path.of(args[0]), Path.of(args[1]));
    }

    /**
     * One audio entry as a row, or {@code null} when it is CC0 or declares no
     * licence (the boot kit). A non-CC0 entry without a complete credit is
     * refused rather than rendered as an empty row.
     */
    private static String[] row(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(
                    "an 'audio' entry is not an object: " + element);
        }
        JsonObject entry = element.getAsJsonObject();
        String id = requiredString(entry, "id");
        String licence = declaredLicence(entry);
        if (licence == null || isCc0(licence)) {
            return null;
        }

        JsonObject credit = entry.has("credit") && entry.get("credit").isJsonObject()
                ? entry.getAsJsonObject("credit")
                : null;
        if (credit == null) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' declares licence '" + licence
                            + "' without a 'credit' record");
        }

        String file = entry.has("file") && entry.get("file").isJsonPrimitive()
                && entry.get("file").getAsJsonPrimitive().isString()
                ? code(entry.get("file").getAsString())
                : "—";
        return new String[] {
            code(id),
            file,
            field(credit, "title", id),
            field(credit, "author", id),
            field(credit, "licence", id),
            field(credit, "source", id),
            field(credit, "changes", id)
        };
    }

    /**
     * The entry's own terms, in the same two forms {@code AudioManifest} accepts:
     * a slot-level {@code licence} string or object, with {@code credit.licence}
     * as the fallback.
     */
    private static String declaredLicence(JsonObject entry) {
        if (entry.has("licence") && !entry.get("licence").isJsonNull()) {
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
                    "an entry has a 'licence' that names no terms: " + value);
        }
        if (entry.has("credit") && entry.get("credit").isJsonObject()) {
            JsonObject credit = entry.getAsJsonObject("credit");
            if (credit.has("licence") && credit.get("licence").isJsonPrimitive()
                    && credit.get("licence").getAsJsonPrimitive().isString()) {
                return credit.get("licence").getAsString();
            }
        }
        return null;
    }

    private static boolean isCc0(String licence) {
        return licence.strip().toUpperCase(Locale.ROOT).startsWith("CC0");
    }

    private static String field(JsonObject credit, String name, String id) {
        if (!credit.has(name) || !credit.get(name).isJsonPrimitive()
                || !credit.get(name).getAsJsonPrimitive().isString()
                || credit.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has a credit without a non-empty '"
                            + name + "'");
        }
        return escape(credit.get(name).getAsString());
    }

    private static String requiredString(JsonObject entry, String name) {
        if (!entry.has(name) || !entry.get(name).isJsonPrimitive()
                || !entry.get(name).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "audio entry " + entry + " has no string '" + name + "'");
        }
        return entry.get(name).getAsString();
    }

    /** One cell; a pipe would end the column and a newline would end the row. */
    private static String escape(String text) {
        return text.replace("\r", " ").replace("\n", " ").replace("|", "\\|").strip();
    }

    private static String code(String text) {
        return "`" + text.replace("`", "'") + "`";
    }
}
