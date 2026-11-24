package rabbitrace.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the committed {@code assets/manifest.json}
 * parses, carries schema {@code v == 1}, and every {@code id} in {@code slots}
 * and {@code audio} together is unique, lowercase and dotted.
 *
 * <p>The accepted test reads the real file, not a fixture: a malformed manifest,
 * a bumped schema, an uppercase or undotted id, or the same id on both arrays is
 * red. The ids are read from the raw arrays so a map cannot collapse a duplicate
 * before it is counted.
 */
class ManifestSchemaTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");

    /** Lowercase letters and digits, one or more dot-separated segments. */
    private static final Pattern LOWER_DOTTED = Pattern.compile("[a-z0-9]+(\\.[a-z0-9]+)+");

    @Test
    void manifestParsesAndIDsAreUniqueLowercaseAndDotted() throws IOException {
        JsonObject manifest = read(MANIFEST);

        assertTrue(manifest.has("v"), "the manifest has no top-level 'v'");
        assertTrue(manifest.get("v").isJsonPrimitive()
                        && manifest.get("v").getAsJsonPrimitive().isNumber(),
                "the manifest 'v' is not a number: " + manifest.get("v"));
        assertEquals(1, manifest.get("v").getAsInt(), "the manifest schema must be v == 1");

        List<String> ids = new ArrayList<>();
        ids.addAll(idsOf(manifest, "slots"));
        ids.addAll(idsOf(manifest, "audio"));

        assertFalse(ids.isEmpty(),
                "the manifest declares no ids at all; would assert nothing");

        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String id : ids) {
            occurrences.merge(id, 1, Integer::sum);
        }
        occurrences.forEach((id, count) -> assertEquals(1, count,
                "'" + id + "' appears " + count + " times across slots and audio; ids are unique"));

        List<String> offenders = new ArrayList<>();
        for (String id : ids) {
            String reason = whyNotLowercaseAndDotted(id);
            if (reason != null) {
                offenders.add("'" + id + "' " + reason);
            }
        }
        assertTrue(offenders.isEmpty(),
                "every id must be lowercase and dotted: " + offenders);
    }

    /**
     * The ids of one top-level array, in file order. An absent or non-array
     * section is a schema failure, not an empty list.
     */
    private static List<String> idsOf(JsonObject manifest, String section) {
        assertTrue(manifest.has(section), "the manifest has no '" + section + "' array");
        JsonElement element = manifest.get(section);
        assertTrue(element.isJsonArray(), "the manifest '" + section + "' is not an array");
        JsonArray array = element.getAsJsonArray();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement entry = array.get(i);
            assertTrue(entry.isJsonObject(), section + "[" + i + "] is not an object");
            JsonObject object = entry.getAsJsonObject();
            assertTrue(object.has("id") && object.get("id").isJsonPrimitive()
                            && object.get("id").getAsJsonPrimitive().isString(),
                    section + "[" + i + "] has no string 'id'");
            ids.add(object.get("id").getAsString());
        }
        return ids;
    }

    /** {@code null} when the id satisfies, otherwise the clause it breaks. */
    private static String whyNotLowercaseAndDotted(String id) {
        if (id.isEmpty()) {
            return "is empty";
        }
        if (!id.equals(id.toLowerCase(Locale.ROOT))) {
            return "is not lowercase";
        }
        if (!LOWER_DOTTED.matcher(id).matches()) {
            return "is not dotted lowercase segments (^(dot-separated [a-z0-9]+)$)";
        }
        return null;
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
