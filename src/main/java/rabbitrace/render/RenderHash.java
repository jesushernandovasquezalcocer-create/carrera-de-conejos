package rabbitrace.render;

import rabbitrace.game.Fnv;

/**
 * The render-only hash: decoration variants and the
 * ground crop are chosen here, never from the match-seed stream, so the
 * decoration layer changes pixels only.
 *
 * <p>The key carries the slot-set version on purpose. requires a manifest
 * reorder to leave every choice alone while a {@code slotSetVersion} bump is what
 * moves them: the version is the manifest's identity, and the group membership
 * is ordered by id in {@link RenderPlan}, so reordering the JSON cannot re-map a
 * variant. The rest of the key — generator version, seed, group or field id,
 * column and row — is.
 */
public final class RenderHash {

    /** the board is the 15×9 grid at 2×, 2520×1440 source pixels. */
    public static final int BOARD_WIDTH = 2520;
    public static final int BOARD_HEIGHT = 1440;

    private RenderHash() {
    }

    /**
     * variant key, with the slot-set version in the namespace.
     */
    public static long variantHash(int slotSetVersion, int genVersion, String seed, String group,
                                   int col, int row) {
        return Fnv.hash64("rabbit-race/render/" + slotSetVersion + "/" + genVersion + "/" + seed
                + "/" + group + "/" + col + "," + row);
    }

    /** {@code group[(int) Long.remainderUnsigned(h, group.size())]}. */
    public static int variantIndex(int slotSetVersion, int genVersion, String seed, String group,
                                   int col, int row, int members) {
        if (members <= 0) {
            throw new IllegalArgumentException("a variant group needs at least one member: " + group);
        }
        return (int) Long.remainderUnsigned(variantHash(slotSetVersion, genVersion, seed, group,
                col, row), members);
    }

    /** crop key, with the slot-set version in the namespace. */
    public static long cropHash(int slotSetVersion, int genVersion, String seed, String fieldId) {
        return Fnv.hash64("rabbit-race/ground/" + slotSetVersion + "/" + genVersion + "/" + seed
                + "/" + fieldId);
    }

    /**
     * The one crop origin a field gets per match, as
     * {@code [ox, oy]} in source pixels. A field is the board plus one tile of
     * margin, and the origin is confined so the 2520×1440 board window always
     * fits inside it.
     */
    public static int[] cropOrigin(int slotSetVersion, int genVersion, String seed, String fieldId,
                                   int fieldWidth, int fieldHeight) {
        long h = cropHash(slotSetVersion, genVersion, seed, fieldId);
        int spanX = fieldWidth - BOARD_WIDTH + 1;
        int spanY = fieldHeight - BOARD_HEIGHT + 1;
        if (spanX < 1 || spanY < 1) {
            throw new IllegalArgumentException("field '" + fieldId + "' " + fieldWidth + "×"
                    + fieldHeight + " is smaller than the " + BOARD_WIDTH + "×" + BOARD_HEIGHT
                    + " board window");
        }
        int ox = (int) Long.remainderUnsigned(h, spanX);
        int oy = (int) Long.remainderUnsigned(h >>> 32, spanY);
        return new int[]{ox, oy};
    }

    /**
     * The placeholder ground's tone at one source pixel, in −8..8 around its base
     * colour. It is smooth value noise over an 84×80 lattice, sampled at the crop
     * coordinates the painter uses, so neighbouring tiles read nearby points of
     * one continuous field instead of repeating a per-tile value.
     */
    public static int shade(int slotSetVersion, int genVersion, String seed, String fieldId,
                            int sourceX, int sourceY) {
        double gx = sourceX / (double) TileMap.TILE_W;
        double gy = sourceY / (double) TileMap.TILE_H;
        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        double fx = gx - x0;
        double fy = gy - y0;
        double top = mix(value(slotSetVersion, genVersion, seed, fieldId, x0, y0),
                value(slotSetVersion, genVersion, seed, fieldId, x0 + 1, y0), fx);
        double bottom = mix(value(slotSetVersion, genVersion, seed, fieldId, x0, y0 + 1),
                value(slotSetVersion, genVersion, seed, fieldId, x0 + 1, y0 + 1), fx);
        return (int) Math.round(mix(top, bottom, fy));
    }

    private static double value(int slotSetVersion, int genVersion, String seed, String fieldId,
                                int x, int y) {
        long h = Fnv.hash64("rabbit-race/field/" + slotSetVersion + "/" + genVersion + "/" + seed
                + "/" + fieldId + "/" + x + "," + y);
        return ((h >>> 40) & 0x3ff) / 1023.0 * 16.0 - 8.0;
    }

    private static double mix(double a, double b, double amount) {
        return a + (b - a) * amount;
    }
}
