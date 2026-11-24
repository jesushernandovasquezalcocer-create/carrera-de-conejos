package rabbitrace.game;

import java.nio.charset.StandardCharsets;

/** FNV-1a 64-bit, the only hash the contract uses. */
public final class Fnv {

    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private Fnv() {
    }

    public static long hash64(String text) {
        long h = OFFSET;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= PRIME;
        }
        return h;
    }

    /** The state-dump form: {@code fnv1a64:<16 lowercase hex digits>}. */
    public static String hex(long value) {
        return "fnv1a64:" + String.format("%016x", value);
    }
}
