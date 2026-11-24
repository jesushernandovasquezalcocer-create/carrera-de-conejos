package rabbitrace;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code game/} is the determinism boundary, and the rule is
 * enforced by a test rather than by convention. One test keeps the real tree
 * clean; 's test proves the scan is not vacuous by planting an AWT
 * import in the real directory and watching the scan fail.
 */
class ModuleRuleTest {

    private static final Path GAME = Path.of("src/main/java/rabbitrace/game");

    private static final List<String> BANNED = List.of(
            "import java.awt",
            "import javax.swing",
            "import rabbitrace.audio",
            "import rabbitrace.render",
            "import rabbitrace.assets",
            "import rabbitrace.automation");

    @Test
    void gameImportsNothingOutsideTheJdk() throws IOException {
        assertBoundaryClean();
    }

    @Test
    void theScanFailsWhenAnAwtImportIsAddedToTheGameModule() throws IOException {
        // The real tree is clean before the experiment, so a reported offence
        // below can only come from the import this test adds.
        assertBoundaryClean();

        Path leak = Files.createTempFile(GAME, "AwtLeak", ".java");
        try {
            Files.writeString(leak, """
                    package rabbitrace.game;

                    import java.awt.Color;

                    final class AwtLeak {
                        private Color colour;
                    }
                    """, StandardCharsets.UTF_8);

            List<String> offences = offences(GAME);
            assertEquals(1, offences.size(),
                    "the scan must reject the AWT import added to " + GAME
                            + ", offences=" + offences);
            assertTrue(offences.get(0).startsWith(leak.toString()),
                    "the offence must name the planted file: " + offences.get(0));
            assertTrue(offences.get(0).contains("import java.awt"),
                    "the offence must name the banned import: " + offences.get(0));
        } finally {
            Files.deleteIfExists(leak);
        }

        assertBoundaryClean();
    }

    private static void assertBoundaryClean() throws IOException {
        assertTrue(Files.isDirectory(GAME), "the game module must exist at " + GAME);
        List<String> offences = offences(GAME);
        assertTrue(offences.isEmpty(), "game/ must stay AWT-free: " + offences);
    }

    private static List<String> offences(Path game) throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(game)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(file -> {
                String source = read(file);
                for (String banned : BANNED) {
                    if (source.contains(banned)) {
                        offences.add(file + ": " + banned);
                    }
                }
            });
        }
        return offences;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
