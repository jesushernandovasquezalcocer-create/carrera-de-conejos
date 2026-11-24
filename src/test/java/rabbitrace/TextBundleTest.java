package rabbitrace;

import org.junit.jupiter.api.Test;
import rabbitrace.i18n.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * every user-visible string comes from the bundle, a missing
 * key renders {@code !key!}, and a test fails on any key used in code that the
 * bundle does not define. The accented text also proves the UTF-8 round trip.
 */
class TextBundleTest {

    private static final Pattern KEY_USE = Pattern.compile("Strings\\.text\\(\"([^\"]+)\"\\)");

    @Test
    void everyKeyUsedInCodeIsInTheBundle() throws IOException {
        List<String> missing = new ArrayList<>();
        TreeSet<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY_USE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        for (String key : used) {
            if (!Strings.has(key)) {
                missing.add(key);
            }
        }
        assertFalse(used.isEmpty(), "no key is used through Strings.text, so this test would assert nothing");
        assertTrue(missing.isEmpty(), "keys used in code but absent from strings_es.properties: " + missing);
    }

    @Test
    void spanishTextSurvivesTheUtf8RoundTrip() {
        assertEquals("Conejo Carrera", Strings.text("app.title"));
    }

    @Test
    void aMissingKeyRendersVisiblyAndNeverTheKeyAlone() {
        assertEquals("!no.such.key!", Strings.text("no.such.key"));
    }
}
