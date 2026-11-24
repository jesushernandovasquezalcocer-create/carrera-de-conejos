package rabbitrace.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * every sprite slot whose
 * {@code licence.spdx} is not CC0 carries a non-empty {@code creditEs}, and the
 * credits text derives from the manifest with no hardcoded names in code.
 *
 * <p>The committed manifest is inspected directly, so a future non-CC0 file
 * slot without its Spanish credit is red. Because the shipped kit is all
 * placeholders and therefore CC0, the same rule is proved against mutated copies
 * (missing and blank {@code creditEs}) and a credited copy is rendered: its row
 * must carry the slot id and the manifest's exact text, and changing that text
 * must move the row. A generator that hardcodes a name cannot pass both halves.
 */
class SpriteCreditsTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");
    private static final Path CREDITS = ROOT.resolve("docs/CREDITS.md");

    @Test
    void everyNonCc0SpriteSlotCarriesItsManifestCreditEs() throws IOException {
        JsonObject shipped = read(MANIFEST);

        // The committed inventory: every non-CC0 sprite licence names its credit.
        assertEquals(List.of(), problems(shipped),
                "assets/manifest.json carries a non-CC0 sprite slot without a "
                        + "non-empty creditEs");

        // The committed credits text is the generator's output, not a hand list.
        assertTrue(Files.isRegularFile(CREDITS),
                "docs/CREDITS.md must be committed next to the manifest");
        assertEquals(Credits.render(shipped),
                normalized(Files.readString(CREDITS, StandardCharsets.UTF_8)),
                "docs/CREDITS.md must be generated from assets/manifest.json, "
                        + "not hand-written");

        // The shipped placeholder kit is CC0, so it contributes no sprite rows.
        assertEquals(List.of(), spriteRows(Credits.render(shipped)),
                "a CC0 (or unlicensed placeholder) sprite slot must not get a row");

        // A non-CC0 slot with no creditEs is refused, naming the slot.
        JsonObject missing = shipped.deepCopy();
        creditedRock(missing, null);
        assertTrue(problems(missing).stream()
                        .anyMatch(p -> p.contains("prop.rock.a") && p.contains("creditEs")),
                "a non-CC0 sprite without creditEs was accepted: " + problems(missing));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Credits.render(missing),
                "the credits generator must refuse a non-CC0 sprite without creditEs");
        assertTrue(refused.getMessage().contains("prop.rock.a"), refused.getMessage());

        JsonObject blank = shipped.deepCopy();
        creditedRock(blank, "   ");
        assertTrue(problems(blank).stream()
                        .anyMatch(p -> p.contains("prop.rock.a") && p.contains("creditEs")),
                "a blank creditEs was accepted: " + problems(blank));

        // A credited slot renders exactly the manifest's text for that slot.
        JsonObject credited = shipped.deepCopy();
        creditedRock(credited, "Obra de Estudio Ñandú — editada por Taller Sur");
        assertEquals(List.of(), problems(credited),
                "a non-CC0 sprite with a non-empty creditEs must be accepted");
        String rendered = Credits.render(credited);
        String row = spriteRow(rendered, "prop.rock.a");
        assertTrue(row.contains("Obra de Estudio Ñandú — editada por Taller Sur"), row);
        assertTrue(row.contains("CC BY 4.0"), row);
        assertTrue(row.contains("https://example.test/rock"), row);
        assertTrue(row.contains("art/rock.png"), row);

        // Change only the manifest text: the row moves, so no name is in the code.
        slot(credited, "prop.rock.a").getAsJsonObject("licence")
                .addProperty("creditEs", "Crédito distinto — Nadie Conocido");
        String rerendered = Credits.render(credited);
        String moved = spriteRow(rerendered, "prop.rock.a");
        assertTrue(moved.contains("Crédito distinto — Nadie Conocido"), moved);
        assertFalse(moved.contains("Estudio Ñandú"),
                "a generated row cannot keep a stale credit text: " + moved);
    }

    /**
     * Every clause states about the inventory, as human-readable
     * violations. An empty list means every non-CC0 sprite licence has a
     * non-empty {@code creditEs}.
     */
    private static List<String> problems(JsonObject manifest) {
        List<String> problems = new ArrayList<>();
        JsonElement elements = manifest.get("slots");
        if (elements == null || !elements.isJsonArray()) {
            problems.add("the manifest has no 'slots' array");
            return problems;
        }
        for (JsonElement element : elements.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                problems.add("a 'slots' entry is not an object: " + element);
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = string(entry, "id");
            JsonElement licenceElement = entry.get("licence");
            if (id == null || licenceElement == null || !licenceElement.isJsonObject()) {
                continue;
            }
            JsonObject licence = licenceElement.getAsJsonObject();
            String spdx = string(licence, "spdx");
            if (spdx == null || isCc0(spdx)) {
                continue;
            }
            String creditEs = string(licence, "creditEs");
            if (creditEs == null || creditEs.isBlank()) {
                problems.add("sprite slot '" + id + "' has licence '" + spdx
                        + "' but no non-empty creditEs");
            }
        }
        return problems;
    }

    /** Turns one placeholder into a {@code file} slot with the given credit text. */
    private static void creditedRock(JsonObject manifest, String creditEs) {
        JsonObject rock = slot(manifest, "prop.rock.a");
        rock.addProperty("source", "file");
        rock.addProperty("file", "art/rock.png");
        rock.add("licence", licence("CC BY 4.0", creditEs));
    }

    /** One licence object. A null {@code creditEs} is JSON null on a CC0 kit. */
    private static JsonObject licence(String spdx, String creditEs) {
        JsonObject licence = new JsonObject();
        licence.addProperty("spdx", spdx);
        licence.addProperty("source", "https://example.test/rock");
        licence.addProperty("author", "Estudio Ñandú");
        licence.addProperty("modified", true);
        if (creditEs == null) {
            licence.add("creditEs", JsonNull.INSTANCE);
        } else {
            licence.addProperty("creditEs", creditEs);
        }
        return licence;
    }

    /** The data rows of the generated Sprite table, in manifest order. */
    private static List<String> spriteRows(String rendered) {
        List<String> rows = new ArrayList<>();
        boolean sprites = false;
        for (String line : normalized(rendered).split("\n")) {
            if (line.equals("## Sprites")) {
                sprites = true;
            } else if (line.startsWith("## ")) {
                sprites = false;
            } else if (sprites && line.startsWith("| `")) {
                rows.add(line);
            }
        }
        return rows;
    }

    private static String spriteRow(String rendered, String id) {
        for (String line : spriteRows(rendered)) {
            if (line.startsWith("| `" + id + "`")) {
                return line;
            }
        }
        return fail("no sprite credits row for '" + id + "' in: " + rendered);
    }

    private static boolean isCc0(String licence) {
        return licence.strip().toUpperCase(Locale.ROOT).startsWith("CC0");
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

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    /** The working tree is checked out CRLF; the generator emits LF. */
    private static String normalized(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
