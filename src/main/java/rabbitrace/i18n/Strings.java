package rabbitrace.i18n;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The one door to user-visible text.
 *
 * <p>Every visible string is Spanish (LatAm) and comes from
 * {@code rabbitrace/i18n/strings_es.properties}. A missing key renders as a visible
 * {@code !key!} — never a crash, and never the key standing in as the label — and a
 * test fails on any key used in code that is absent from the bundle.
 */
public final class Strings {

    public static final String BASE_NAME = "rabbitrace.i18n.strings";
    public static final String DEFAULT_LANG = "es";

    private static final ResourceBundle BUNDLE = load(DEFAULT_LANG);

    private Strings() {
    }

    private static ResourceBundle load(String lang) {
        try {
            return ResourceBundle.getBundle(BASE_NAME, Locale.forLanguageTag(lang));
        } catch (MissingResourceException e) {
            return null;
        }
    }

    /** The label for a key; {@code !key!} when the bundle has no such key. */
    public static String text(String key) {
        if (BUNDLE != null && BUNDLE.containsKey(key)) {
            return BUNDLE.getString(key);
        }
        return "!" + key + "!";
    }

    /** True when the bundle defines the key. Used by the bundle test. */
    public static boolean has(String key) {
        return BUNDLE != null && BUNDLE.containsKey(key);
    }
}
