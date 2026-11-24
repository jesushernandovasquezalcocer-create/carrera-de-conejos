package rabbitrace.game;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The same seed and the same input sequence
 * reproduce the {@code boardHash}, the die sequence and the winner across two
 * JVMs.
 *
 * <p>{@link MatchDeterminismProbe} plays a whole match per seed through the
 * public rules surface, with the input policy derived from the rule state. This
 * test runs that probe in this JVM and in two separate child JVMs, then requires
 * the three transcripts to be byte-identical and, field by field, that every
 * match kept its seed, board hash, a non-empty die sequence of real rolls and a
 * recorded winner. An empty or skipped child, a winner-less game, or a board
 * hash that merely repeats itself cannot satisfy that.
 *
 * <p>The whole transcript is compared rather than only the three named fields,
 * so a hidden field that drifted between JVMs is red too. The seeds are distinct
 * and the board hashes must be too, which keeps the equality from being carried
 * by a constant hash.
 */
class MatchDeterminismTest {

    private static final int CHILD_TIMEOUT_SECONDS = 120;

    @Test
    void theSameSeedAndInputsReproduceBoardDieSequenceAndWinnerAcrossTwoJvms() throws Exception {
        List<String> seeds = MatchDeterminismProbe.seeds();
        assertEquals(5, seeds.size(), "the probe's seeds");
        assertEquals(seeds.size(), new HashSet<>(seeds).size(), "seeds must be distinct");
        for (String seed : seeds) {
            assertEquals(seed, Seed.normalise(seed), "each seed is six Crockford characters");
        }

        String local = MatchDeterminismProbe.transcript(seeds);
        String first = playInSeparateJvm();
        String second = playInSeparateJvm();

        assertEquals(first, second,
                "two separate JVMs produced different transcripts for the same seeds");
        assertEquals(local, first,
                "a separate JVM differs from this JVM on the same seeds");

        Map<String, Map<String, String>> lines = parse(first);
        assertEquals(seeds, new ArrayList<>(lines.keySet()),
                "the transcript must carry one line per seed, in order");
        Set<String> hashes = new HashSet<>();
        for (String seed : seeds) {
            Map<String, String> line = lines.get(seed);
            assertEquals(seed, line.get("seed"), "a line is filed under the wrong seed");
            String hash = line.get("boardHash");
            assertTrue(hash != null && !hash.isBlank() && !"none".equals(hash),
                    "seed " + seed + " reports no boardHash: " + line);
            assertTrue(hashes.add(hash),
                    "seed " + seed + " repeats another seed's boardHash " + hash);

            String rolls = line.get("rolls");
            assertTrue(rolls != null && !"-".equals(rolls) && !rolls.isBlank(),
                    "seed " + seed + " consumed no die rolls: " + line);
            for (String roll : rolls.split(",")) {
                int value = Integer.parseInt(roll);
                assertTrue(value >= 1 && value <= 6,
                        "seed " + seed + " reports a die value outside 1..6: " + roll);
            }

            String winner = line.get("winner");
            assertTrue(Match.BLUE.equals(winner) || Match.BROWN.equals(winner),
                    "seed " + seed + " ended with winner=" + winner
                            + ", so the match has no reproducible winner: " + line);
        }
        assertEquals(seeds.size(), hashes.size(),
                "the board hashes are not one per seed, so the boardHash is not a real board");

        Map<String, String> winners = new LinkedHashMap<>();
        for (String seed : seeds) {
            winners.put(seed, lines.get(seed).get("winner"));
        }
        assertTrue(new HashSet<>(winners.values()).size() >= 1,
                "the winner field never names a colour");
        assertFalse(winners.values().contains("none"),
                "the winner is not reproducible when no match has one");
        assertNotEquals(local, "", "the in-JVM transcript is empty");
    }

    /** Runs the probe in a fresh JVM and returns exactly the bytes it wrote. */
    private static String playInSeparateJvm() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path exe = Path.of(java + ".exe");
        List<String> launch = new ArrayList<>();
        launch.add(Files.isRegularFile(exe) ? exe.toString() : java.toString());
        launch.add("-cp");
        launch.add(System.getProperty("java.class.path"));
        launch.add(MatchDeterminismProbe.class.getName());

        Process process = new ProcessBuilder(launch).redirectErrorStream(true).start();
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        if (!process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the child JVM did not finish within " + CHILD_TIMEOUT_SECONDS + " s");
        }
        assertEquals(0, process.exitValue(),
                "the child JVM exited " + process.exitValue() + ": "
                        + new String(output, StandardCharsets.UTF_8));
        return new String(output, StandardCharsets.UTF_8);
    }

    /** Parses {@code key=value} tokens into one ordered map per seed line. */
    private static Map<String, Map<String, String>> parse(String transcript) {
        Map<String, Map<String, String>> lines = new LinkedHashMap<>();
        for (String raw : transcript.split("\\R")) {
            if (raw.isBlank()) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String token : raw.trim().split("\\s+")) {
                int split = token.indexOf('=');
                assertTrue(split > 0, "the transcript carries a token without '=': " + token);
                fields.put(token.substring(0, split), token.substring(split + 1));
            }
            String seed = fields.get("seed");
            assertTrue(seed != null, "a transcript line carries no seed: " + raw);
            assertTrue(lines.put(seed, fields) == null,
                    "the transcript repeats seed " + seed);
        }
        return lines;
    }
}
