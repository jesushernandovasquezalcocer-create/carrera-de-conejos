package rabbitrace.render;

import java.util.List;

/**
 * The transform vocabulary: a painter may apply only
 * transforms a slot declares, and only in 90° steps and mirrors.
 *
 * <p>A sprite is drawn in the same destination rectangle either way; the
 * transform turns the glyph inside it. That keeps footprint cap
 * intact: {@code rot90} never swaps the rectangle, so a 84×88 fence segment
 * stays inside its 84×88 footprint.
 */
public final class Transforms {

    /** Mirror around the sprite's vertical centre. */
    public static final String FLIP_X = "flipX";

    /** Mirror around the sprite's horizontal centre. */
    public static final String FLIP_Y = "flipY";

    /** One quarter turn, clockwise. */
    public static final String ROT90 = "rot90";

    private Transforms() {
    }

    /** True when {@code token} is a transform allows a sprite to declare. */
    public static boolean declared(String token) {
        return FLIP_X.equals(token) || FLIP_Y.equals(token) || ROT90.equals(token);
    }

    /**
     * True when every applied transform is one the slot declares. An undeclared
     * flip or rotation is a contract violation.
     */
    public static boolean allowed(List<String> declared, List<String> applied) {
        if (applied == null || applied.isEmpty()) {
            return true;
        }
        if (declared == null) {
            return false;
        }
        for (String token : applied) {
            if (!declared.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The sprite's rotation in degrees, taken from the applied transforms: only
     * quarter turns exist, so this is 0 or 90. A value that is not a multiple of
     * 90 cannot happen through this vocabulary, which is what asserts.
     */
    public static int angle(List<String> applied) {
        return applied != null && applied.contains(ROT90) ? 90 : 0;
    }
}
