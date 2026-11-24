package rabbitrace.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * seed normalisation accepts {@code abc123},
 * a whitespace-separated {@code A B C 1 2 3}, and maps {@code I}/{@code L}→
 * {@code 1} and {@code O}→{@code 0}; 5-character, 7-character and
 * {@code U}-containing seeds are rejected. Every case goes through the public
 * entry point and is compared with a literal, so a normaliser that accepted
 * everything, or rejected everything, cannot pass.
 */
class SeedTest {

    @Test
    void seedNormalisationAcceptsSixCrockfordCharactersAndRejectsTheRest() {
        assertNormalises("abc123", "ABC123");
        assertNormalises("A B C 1 2 3", "ABC123");
        assertNormalises("  A B C 1 2 3  ", "ABC123");
        assertNormalises("labc23", "1ABC23");
        assertNormalises("oabc23", "0ABC23");
        assertNormalises("1I2L3O", "112130");

        assertRejected("ABC12", "five characters");
        assertRejected("ABC1234", "seven characters");
        assertRejected("ABC12U", "U is not in the Crockford alphabet");
        assertRejected("abc12u", "lowercase u is not in the Crockford alphabet");
        assertRejected("ABC12!", "punctuation is outside the alphabet");
        assertRejected("", "an empty seed is not six characters");
    }

    /** The value normalises to {@code expected}, stays six legal characters, and is stable. */
    private static void assertNormalises(String raw, String expected) {
        String normalised = Seed.normalise(raw);
        assertEquals(expected, normalised, "normalising \"" + raw + "\"");
        assertEquals(Seed.LENGTH, normalised.length(),
                "\"" + raw + "\" must normalise to exactly " + Seed.LENGTH + " characters");
        for (int i = 0; i < normalised.length(); i++) {
            char c = normalised.charAt(i);
            assertTrue(Seed.ALPHABET.indexOf(c) >= 0,
                    "\"" + raw + "\" normalised to an illegal character '" + c + "'");
        }
        assertEquals(normalised, Seed.normalise(normalised),
                "normalisation must be stable: normalising \"" + normalised + "\" again");
    }

    /** The value is refused with an explanation rather than silently repaired. */
    private static void assertRejected(String raw, String why) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Seed.normalise(raw), "\"" + raw + "\" must be rejected: " + why);
        assertNotNull(error.getMessage(), "\"" + raw + "\" was rejected without an explanation");
    }
}
