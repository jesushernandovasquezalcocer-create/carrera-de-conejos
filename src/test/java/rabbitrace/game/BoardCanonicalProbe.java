package rabbitrace.game;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Child-process entry point.
 *
 * <p>It prints the generator version and, for each of 50 fixed seeds, the seed
 * followed by its canonical serialisation. The overhead is deliberately zero:
 * no automation, no window, no state beyond {@link BoardGenerator}, so two runs are
 * as independent as two JVMs can be. The test launches this class twice and
 * compares the raw bytes.
 */
public final class BoardCanonicalProbe {

    private BoardCanonicalProbe() {
    }

    /**
     * 50 distinct six-character seeds across the Crockford alphabet, derived
     * deterministically from the index so both the probe and the test agree on
     * the set without sharing a file.
     */
    public static List<String> seeds() {
        List<String> seeds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            StringBuilder seed = new StringBuilder(Seed.LENGTH);
            int value = i;
            for (int position = 0; position < Seed.LENGTH; position++) {
                seed.insert(0, Seed.ALPHABET.charAt(value % Seed.ALPHABET.length()));
                value /= Seed.ALPHABET.length();
            }
            seeds.add(seed.toString());
        }
        return seeds;
    }

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        out.append("version ").append(BoardGenerator.VERSION).append('\n');
        for (String seed : seeds()) {
            out.append("seed ").append(seed).append('\n');
            out.append(BoardGenerator.generate(seed).canonical()).append('\n');
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        System.out.write(bytes, 0, bytes.length);
        System.out.flush();
    }
}
