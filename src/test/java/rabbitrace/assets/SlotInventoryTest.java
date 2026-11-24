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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code slots} contains exactly the 48
 * sprite slots, and every entry carries the fields its {@code source}
 * requires.
 *
 * <p>The test reads the committed {@code assets/manifest.json}, not a fixture.
 * It fails if a slot is missing, extra or duplicated, or if an entry's fields do
 * not match what requires for its {@code source} — so deleting a field from
 * the real file turns it red. Three mutated copies prove the same rules can fail
 * (a missing anchor, a removed slot, and a {@code file} entry with none of the
 * fields a file source requires); those controls matter because no real entry is
 * {@code source: file} until the art phase.
 */
class SlotInventoryTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");

    /** 48 sprite slots, in table order. */
    private static final List<String> SPEC_SLOTS = List.of(
            "border.plate",
            "ground.grass.field", "ground.dirt.field",
            "deco.flower.a", "deco.flower.b", "deco.tuft.a", "deco.tuft.b",
            "deco.pebble.a", "deco.pebble.b",
            "prop.rock.a", "prop.rock.b", "prop.bush.a", "prop.bush.b",
            "prop.stump.a", "prop.fence.segment", "prop.fence.post",
            "shadow.prop", "shadow.rabbit",
            "goal.carrot.base", "goal.carrot.glow",
            "rabbit.blue.idle.n", "rabbit.blue.idle.e",
            "rabbit.blue.idle.s", "rabbit.blue.idle.w",
            "rabbit.blue.win", "rabbit.blue.lose",
            "rabbit.brown.idle.n", "rabbit.brown.idle.e",
            "rabbit.brown.idle.s", "rabbit.brown.idle.w",
            "rabbit.brown.win", "rabbit.brown.lose",
            "rps.hand.rock", "rps.hand.paper", "rps.hand.scissors",
            "dice.face.1", "dice.face.2", "dice.face.3",
            "dice.face.4", "dice.face.5", "dice.face.6",
            "ui.panel",
            "ui.button.normal", "ui.button.hover", "ui.button.pressed", "ui.button.disabled",
            "ui.banner", "ui.logo");

    /**
     * UI rows declare no footprint ("—"): they are drawn into a layout
     * rectangle (9-slice or free placement), not at a sprite footprint. They are
     * still placeholder slots with a declared source size and anchor.
     */
    private static final Set<String> NO_FOOTPRINT = Set.of(
            "ui.panel",
            "ui.button.normal", "ui.button.hover", "ui.button.pressed", "ui.button.disabled",
            "ui.banner", "ui.logo");

    private static final Set<String> PLACEMENTS = Set.of("tile", "field", "free");
    private static final Set<String> SOURCES = Set.of("placeholder", "file", "procedural");
    private static final Set<String> SORTS = Set.of("row", "fixed", "none");

    @Test
    void slotsAreExactlyTheFortyEightOfSectionThreeAndCarryTheirRequiredFields() throws IOException {
        JsonObject manifest = read(MANIFEST);

        List<String> problems = inspect(manifest);
        assertTrue(problems.isEmpty(),
                "assets/manifest.json does not match the inventory:\n " + String.join("\n  ", problems));

        // Negative controls: the same rules must reject a manifest that carries
        // exactly the defects names. Without these, a validator that had
        // stopped checking anything would still pass on the committed file.

        JsonObject missingField = manifest.deepCopy();
        slot(missingField, "prop.rock.a").remove("anchor");
        assertTrue(inspect(missingField).stream()
                        .anyMatch(p -> p.contains("prop.rock.a") && p.contains("anchor")),
                "a tile placeholder without 'anchor' was accepted; the source requires those fields");

        JsonObject missingSlot = manifest.deepCopy();
        removeSlot(missingSlot, "deco.flower.a");
        assertTrue(inspect(missingSlot).stream()
                        .anyMatch(p -> p.contains("deco.flower.a")),
                "a manifest missing deco.flower.a was accepted; the inventory must be exactly the 48 slots");

        JsonObject fileWithoutItsFields = manifest.deepCopy();
        slot(fileWithoutItsFields, "prop.rock.a").addProperty("source", "file");
        List<String> fileProblems = inspect(fileWithoutItsFields);
        assertTrue(fileProblems.stream().anyMatch(p -> p.contains("prop.rock.a") && p.contains("'file'")),
                "a source:file entry without a file path was accepted: " + fileProblems);
        assertTrue(fileProblems.stream().anyMatch(p -> p.contains("prop.rock.a") && p.contains("licence")),
                "a source:file entry without a licence was accepted: " + fileProblems);
        assertTrue(fileProblems.stream().anyMatch(p -> p.contains("prop.rock.a") && p.contains("provenance")),
                "a source:file entry without provenance was accepted: " + fileProblems);
    }

    /**
     * Every clause states, as a list of human-readable violations. An empty
     * list means the manifest satisfies inventory and per-source
     * field rules.
     */
    private static List<String> inspect(JsonObject manifest) {
        List<String> problems = new ArrayList<>();
        JsonElement slotsElement = manifest.get("slots");
        if (slotsElement == null || !slotsElement.isJsonArray()) {
            problems.add("the manifest has no 'slots' array");
            return problems;
        }
        JsonArray slots = slotsElement.getAsJsonArray();

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            JsonElement element = slots.get(i);
            if (!element.isJsonObject()) {
                problems.add("slots[" + i + "] is not an object");
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = string(entry, "id");
            if (id == null) {
                problems.add("slots[" + i + "] has no string 'id'");
                continue;
            }
            ids.add(id);
            inspectEntry(entry, id, problems);
        }

        // Exactly set: 48 ids, none missing, none extra, none twice.
        Set<String> expected = new LinkedHashSet<>(SPEC_SLOTS);
        assertEquals(48, expected.size(), "the table in this test must list 48 slots");
        if (ids.size() != SPEC_SLOTS.size()) {
            problems.add("'slots' holds " + ids.size() + " entries; declares exactly 48");
        }
        for (String id : SPEC_SLOTS) {
            long count = ids.stream().filter(id::equals).count();
            if (count == 0) {
                problems.add("slot '" + id + "' is missing from 48 slots");
            } else if (count > 1) {
                problems.add("slot '" + id + "' appears " + count + " times in 'slots'");
            }
        }
        for (String id : ids) {
            if (!expected.contains(id)) {
                problems.add("slot '" + id + "' is not one of 48 slots");
            }
        }
        return problems;
    }

    /** field rules for one entry, keyed by its {@code source} and placement. */
    private static void inspectEntry(JsonObject entry, String id, List<String> problems) {
        String where = "slot '" + id + "'";
        String kind = string(entry, "kind");
        if (kind == null) {
            problems.add(where + " has no string 'kind'");
        } else if (!"sprite".equals(kind)) {
            problems.add(where + " kind is '" + kind + "'; every 'slots' entry is kind 'sprite'");
        }

        String placement = string(entry, "placement");
        if (placement == null) {
            problems.add(where + " has no string 'placement'");
        } else if (!PLACEMENTS.contains(placement)) {
            problems.add(where + " placement is '" + placement + "'; expected one of " + PLACEMENTS);
        }

        String source = string(entry, "source");
        if (source == null) {
            problems.add(where + " has no string 'source'");
        } else if (!SOURCES.contains(source)) {
            problems.add(where + " source is '" + source + "'; expected one of " + SOURCES);
        }

        requireInt(entry, "layer", where, problems);
        String sort = string(entry, "sort");
        if (sort == null) {
            problems.add(where + " has no string 'sort'");
        } else if (!SORTS.contains(sort)) {
            problems.add(where + " sort is '" + sort + "'; expected one of " + SORTS);
        }

        if ("field".equals(placement)) {
            // field slots declare size and sample and omit footprint and anchor.
            requireVector(entry, "size", where, problems);
            requireVector(entry, "sample", where, problems);
            forbid(entry, "footprint", where, problems);
            forbid(entry, "anchor", where, problems);
            return;
        }

        if ("procedural".equals(source)) {
            // procedural slots omit size and file; the painter still needs
            // a footprint and an anchor to place them.
            requireVector(entry, "footprint", where, problems);
            requireAnchor(entry, where, problems);
            forbid(entry, "size", where, problems);
            forbid(entry, "file", where, problems);
            return;
        }

        if (source == null) {
            return;
        }

        // placeholder and file: size always; footprint unless declares "—";
        // anchor because anchors every drawn sprite.
        requireVector(entry, "size", where, problems);
        if (!NO_FOOTPRINT.contains(id)) {
            requireVector(entry, "footprint", where, problems);
        }
        requireAnchor(entry, where, problems);
        forbid(entry, "sample", where, problems);

        if ("file".equals(source)) {
            if (string(entry, "file") == null) {
                problems.add(where + " is source 'file' but has no string 'file' path");
            }
            requireObject(entry, "licence", Set.of("spdx", "source", "author", "modified", "creditEs"),
                    where, problems);
            requireObject(entry, "provenance",
                    Set.of("tool", "model", "seed", "generationId", "prompt", "post"), where, problems);
        }
    }

    private static void requireObject(JsonObject entry, String field, Set<String> keys,
                                      String where, List<String> problems) {
        JsonElement element = entry.get(field);
        if (element == null || !element.isJsonObject()) {
            problems.add(where + " is source 'file' but has no '" + field + "' object");
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : keys) {
            if (!object.has(key)) {
                problems.add(where + " '" + field + "' has no '" + key + "' key");
            }
        }
    }

    private static void requireInt(JsonObject entry, String field, String where, List<String> problems) {
        JsonElement element = entry.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            problems.add(where + " has no numeric '" + field + "'");
            return;
        }
        if (element.getAsInt() < 1) {
            problems.add(where + " has '" + field + "' " + element.getAsInt() + "; layers start at 1");
        }
    }

    /** A two-component vector of positive numbers: size, footprint, sample or anchor. */
    private static void requireVector(JsonObject entry, String field, String where, List<String> problems) {
        JsonElement element = entry.get(field);
        if (element == null || !element.isJsonArray()) {
            problems.add(where + " has no '" + field + "' array");
            return;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 2) {
            problems.add(where + " '" + field + "' has " + array.size() + " components; expected 2");
            return;
        }
        for (int i = 0; i < 2; i++) {
            JsonElement component = array.get(i);
            if (!component.isJsonPrimitive() || !component.getAsJsonPrimitive().isNumber()) {
                problems.add(where + " '" + field + "[" + i + "]' is not a number");
            } else if (component.getAsDouble() < 0) {
                problems.add(where + " '" + field + "[" + i + "]' is negative");
            }
        }
    }

    /** anchor is a fraction of the source image, so both parts are 0…1. */
    private static void requireAnchor(JsonObject entry, String where, List<String> problems) {
        requireVector(entry, "anchor", where, problems);
        JsonElement element = entry.get("anchor");
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 2) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            JsonElement component = element.getAsJsonArray().get(i);
            if (component.isJsonPrimitive() && component.getAsJsonPrimitive().isNumber()
                    && component.getAsDouble() > 1) {
                problems.add(where + " 'anchor[" + i + "]' is " + component.getAsDouble()
                        + "an anchor is a 0…1 fraction of the source image");
            }
        }
    }

    private static void forbid(JsonObject entry, String field, String where, List<String> problems) {
        if (entry.has(field)) {
            problems.add(where + " carries '" + field + "', which its placement/source omits");
        }
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static JsonObject slot(JsonObject manifest, String id) {
        for (JsonElement element : manifest.getAsJsonArray("slots")) {
            JsonObject entry = element.getAsJsonObject();
            if (id.equals(string(entry, "id"))) {
                return entry;
            }
        }
        throw new IllegalStateException("the control could not find slot '" + id + "'");
    }

    private static void removeSlot(JsonObject manifest, String id) {
        JsonArray slots = manifest.getAsJsonArray("slots");
        for (int i = 0; i < slots.size(); i++) {
            if (id.equals(string(slots.get(i).getAsJsonObject(), "id"))) {
                slots.remove(i);
                return;
            }
        }
        throw new IllegalStateException("the control could not find slot '" + id + "'");
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
