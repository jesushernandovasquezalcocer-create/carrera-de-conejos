package rabbitrace.assets;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * every non-CC0 slot has a
 * {@code CREDITS.md} row generated from the same manifest entry, and with the
 * shipped CC0 set that file has no audio rows.
 *
 * <p>The accepted test renders the committed manifest, compares the committed
 * file to that rendering, then feeds the generator a fixture with non-CC0
 * entries: each row must carry that entry's own title, author, licence, source
 * and changes, retitling the entry must move the row, and a CC0 entry must stay
 * rowless. A generator that returns a constant cannot pass both halves.
 */
class CreditsTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");
    private static final Path CREDITS = ROOT.resolve("docs/CREDITS.md");

    @Test
    void everyNonCc0SlotHasAGeneratedRow() throws IOException {
        JsonObject shipped = read(MANIFEST);
        String generated = Credits.render(shipped);

        assertEquals(List.of(), tableRows(generated),
                "the shipped CC0 set must have no audio rows");

        assertTrue(Files.isRegularFile(CREDITS),
                "docs/CREDITS.md must be committed next to the manifest");
        assertEquals(generated, normalized(Files.readString(CREDITS, StandardCharsets.UTF_8)),
                "docs/CREDITS.md must be generated from assets/manifest.json, "
                        + "not hand-written");

        // A non-CC0 file slot, with the credit as a nested object.
        JsonObject fixture = shipped.deepCopy();
        JsonObject hop = audioEntry(fixture, "sfx.hop");
        hop.remove("synth");
        hop.addProperty("file", "audio/sfx/hop.wav");
        JsonObject hopCredit = credit("impactSoft_medium_000", "Kenney", "CC BY 4.0",
                "https://kenney.nl/assets/impact-sounds", "transcoded to 16-bit PCM WAV");
        hop.add("credit", hopCredit);

        // A second non-CC0 slot declared through the slot-level object form.
        JsonObject menu = audioEntry(fixture, "music.menu");
        menu.remove("synth");
        menu.addProperty("file", "audio/music/menu.wav");
        menu.add("credit", credit("menu-loop-01", "Tallbeard", "CC BY 3.0",
                "https://tallbeard.itch.io/music-loop-bundle", "trimmed and converted to WAV"));
        JsonObject menuLicence = new JsonObject();
        menuLicence.addProperty("spdx", "CC BY 3.0");
        menuLicence.addProperty("source", "https://tallbeard.itch.io/music-loop-bundle");
        menuLicence.addProperty("author", "Tallbeard");
        menu.add("licence", menuLicence);

        // A CC0 credit is not a row: CC0 needs no attribution.
        JsonObject click = audioEntry(fixture, "ui.click");
        click.add("credit", credit("click_001", "Kenney", "CC0-1.0",
                "https://kenney.nl/assets/ui-audio", "transcoded"));

        String rendered = Credits.render(fixture);
        assertEquals(2, tableRows(rendered).size(),
                "one row per non-CC0 audio slot, and none for the CC0 entry: "
                        + tableRows(rendered));

        String hopRow = row(rendered, "sfx.hop");
        for (String value : new String[] {"impactSoft_medium_000", "Kenney", "CC BY 4.0",
                "https://kenney.nl/assets/impact-sounds", "transcoded to 16-bit PCM WAV"}) {
            assertTrue(hopRow.contains(value), "hop row must carry '" + value + "': " + hopRow);
        }
        assertTrue(hopRow.contains("audio/sfx/hop.wav"), hopRow);

        String menuRow = row(rendered, "music.menu");
        for (String value : new String[] {"menu-loop-01", "Tallbeard", "CC BY 3.0",
                "https://tallbeard.itch.io/music-loop-bundle", "trimmed and converted to WAV"}) {
            assertTrue(menuRow.contains(value), "menu row must carry '" + value + "': " + menuRow);
        }
        assertTrue(menuRow.contains("audio/music/menu.wav"), menuRow);
        assertFalse(rendered.contains("ui.click"),
                "a CC0 entry must not get a credits row: " + rendered);

        // The row is derived from the entry: retitle the entry and the row moves.
        hopCredit.addProperty("title", "hop-retitled-000");
        hopCredit.addProperty("author", "Someone Else");
        String retitledRow = row(Credits.render(fixture), "sfx.hop");
        assertTrue(retitledRow.contains("hop-retitled-000"), retitledRow);
        assertTrue(retitledRow.contains("Someone Else"), retitledRow);
        assertFalse(retitledRow.contains("impactSoft_medium_000"),
                "a generated row cannot keep a stale title: " + retitledRow);

        // A non-CC0 slot without its credit cannot be rendered into a blank row.
        hop.remove("credit");
        hop.addProperty("licence", "CC BY 4.0");
        assertThrows(IllegalArgumentException.class, () -> Credits.render(fixture),
                "a non-CC0 entry without a credit is refused");
    }

    /**
     * The data rows of the generated Audio table, in manifest order. owns
     * audio; the sprite table has its own section and is not counted
     * here, so a real non-CC0 sprite kit cannot break this check.
     */
    private static List<String> tableRows(String rendered) {
        List<String> rows = new ArrayList<>();
        boolean audio = false;
        for (String line : normalized(rendered).split("\n")) {
            if (line.equals("## Audio")) {
                audio = true;
            } else if (line.startsWith("## ")) {
                audio = false;
            } else if (audio && line.startsWith("| `")) {
                rows.add(line);
            }
        }
        return rows;
    }

    private static String row(String rendered, String id) {
        for (String line : tableRows(rendered)) {
            if (line.startsWith("| `" + id + "`")) {
                return line;
            }
        }
        return fail("no credits row for '" + id + "' in: " + rendered);
    }

    private static JsonObject credit(String title, String author, String licence,
                                     String source, String changes) {
        JsonObject credit = new JsonObject();
        credit.addProperty("title", title);
        credit.addProperty("author", author);
        credit.addProperty("licence", licence);
        credit.addProperty("source", source);
        credit.addProperty("changes", changes);
        return credit;
    }

    private static JsonObject audioEntry(JsonObject manifest, String id) {
        for (JsonElement element : manifest.getAsJsonArray("audio")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(id)) {
                return entry;
            }
        }
        throw new AssertionError("manifest has no audio entry '" + id + "'");
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
