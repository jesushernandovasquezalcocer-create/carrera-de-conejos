package rabbitrace.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code sha256} of
 * {@code assets/oracle/board-concept.png} equals the value pinned in
 * {@code assets/manifest.json}'s {@code oracle} object.
 *
 * <p>The Oracle is the style source every later generation is measured against,
 * so its bytes are pinned rather than merely present: the committed file is
 * hashed and compared with the manifest's pin. A second hash over a copy with
 * one flipped byte proves the comparison is over content, not size or presence,
 * so an edited Oracle cannot pass by keeping the manifest text.
 */
class OracleHashTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path MANIFEST = ROOT.resolve("assets/manifest.json");
    private static final String ORACLE = "oracle/board-concept.png";

    @TempDir
    static Path temp;

    @Test
    void oracleBytesMatchThePinnedSha256() throws IOException {
        JsonObject manifest = read(MANIFEST);
        JsonElement oracleElement = manifest.get("oracle");
        assertTrue(oracleElement != null && oracleElement.isJsonObject(),
                "assets/manifest.json has no 'oracle' object");
        JsonObject oracle = oracleElement.getAsJsonObject();

        assertEquals(ORACLE, string(oracle, "file"),
                "the manifest must pin the Oracle at '" + ORACLE + "'");

        String pinned = string(oracle, "sha256");
        assertTrue(pinned != null && pinned.matches("[0-9a-f]{64}"),
                "assets/manifest.json's oracle.sha256 must be a lowercase SHA-256: " + pinned);

        Path plate = ROOT.resolve("assets").resolve(ORACLE).normalize();
        assertTrue(Files.isRegularFile(plate),
                "the committed Oracle is missing: assets/" + ORACLE);

        String actual = sha256(plate);
        assertEquals(pinned, actual,
                "assets/" + ORACLE + " does not hash to the manifest's pinned value; "
                        + "the Oracle is hash-pinned and must not be edited");

        // A hash, not a presence test: flipping one byte must break the pin.
        byte[] bytes = Files.readAllBytes(plate);
        bytes[bytes.length / 2] ^= 0x01;
        Path mutated = Files.write(temp.resolve("board-concept.png"), bytes);
        assertNotEquals(pinned, sha256(mutated),
                "one flipped byte in the Oracle still hashed to the pin, so the check "
                        + "is not verifying content");
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static JsonObject read(Path manifest) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return fail("SHA-256 is required by the JDK", e);
        }
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }
}
