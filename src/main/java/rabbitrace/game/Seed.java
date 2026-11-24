package rabbitrace.game;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * A match seed: Crockford base32, exactly six characters, normalised per the rules.
 * This class is AWT-free and holds no wall clock.
 */
public final class Seed {

    /** Crockford base32: no I, L, O or U. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static final int LENGTH = 6;

    private Seed() {
    }

    /**
     * Uppercase, drop whitespace, map I/L→1 and O→0, then validate. Whitespace
     * may separate the characters as well as pad them, because accepts
     * {@code A B C 1 2 3} as {@code ABC123}.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("seed is null");
        }
        String s = typing(raw);
        if (s.length() != LENGTH) {
            throw new IllegalArgumentException("seed must be " + LENGTH + " characters: " + raw);
        }
        for (int i = 0; i < s.length(); i++) {
            if (ALPHABET.indexOf(s.charAt(i)) < 0) {
                throw new IllegalArgumentException("seed has an illegal character: " + raw);
            }
        }
        return s;
    }

    /**
     * The live normalisation: uppercase, drop
     * whitespace, map {@code I}/{@code L}→{@code 1} and {@code O}→{@code 0}.
     * Unlike {@link #normalise}, it never judges the result, because the Título
     * field must be able to hold a value that is not yet valid (five characters,
     * a stray {@code U}) and show the message instead of throwing.
     */
    public static String typing(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isWhitespace(c)) {
                kept.append(c);
            }
        }
        return kept.toString()
                .toUpperCase(Locale.ROOT)
                .replace('I', '1')
                .replace('L', '1')
                .replace('O', '0');
    }

    /** True when the value is exactly six Crockford characters. */
    public static boolean isValid(String raw) {
        String s = typing(raw);
        if (s.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (ALPHABET.indexOf(s.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * A fresh seed for a human-facing launch. The seed draw is the only place a
     * random source other than the match stream appears, and it never enters game
     * state: the seed it returns does.
     */
    public static String random() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
