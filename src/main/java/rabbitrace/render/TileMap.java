package rabbitrace.render;

import java.awt.geom.AffineTransform;

/**
 * The coordinate law: board cells, the 1600x900 virtual canvas, and the
 * window. Orthographic, fixed camera, one scale factor that this class owns.
 */
public final class TileMap {

    public static final int TILE_W = 84;
    public static final int TILE_H = 80;
    public static final int ORIGIN_X = 170;
    public static final int ORIGIN_Y = 90;
    public static final int COLS = 15;
    public static final int ROWS = 9;

    public static final int VIRTUAL_W = 1600;
    public static final int VIRTUAL_H = 900;

    private TileMap() {
    }

    /** Board cell containing a virtual pixel, or {@code null} outside the grid. */
    public static int[] tileAt(int x, int y) {
        int col = Math.floorDiv(x - ORIGIN_X, TILE_W);
        int row = Math.floorDiv(y - ORIGIN_Y, TILE_H);
        if (x < ORIGIN_X || y < ORIGIN_Y || col < 0 || row < 0 || col >= COLS || row >= ROWS) {
            return null;
        }
        return new int[]{col, row};
    }

    public static int tileX(int col) {
        return ORIGIN_X + TILE_W * col;
    }

    public static int tileY(int row) {
        return ORIGIN_Y + TILE_H * row;
    }

    public static int tileCentreX(int col) {
        return tileX(col) + TILE_W / 2;
    }

    public static int tileCentreY(int row) {
        return tileY(row) + TILE_H / 2;
    }

    /** {@code s = min(clientW / 1600, clientH / 900)}: the only scale in the app. */
    public static double scale(int clientW, int clientH) {
        return Math.min(clientW / (double) VIRTUAL_W, clientH / (double) VIRTUAL_H);
    }

    /** Horizontal letterbox offset in device pixels. */
    public static int letterboxX(int clientW, int clientH) {
        return (int) Math.round((clientW - VIRTUAL_W * scale(clientW, clientH)) / 2.0);
    }

    /** Vertical letterbox offset in device pixels. */
    public static int letterboxY(int clientW, int clientH) {
        return (int) Math.round((clientH - VIRTUAL_H * scale(clientW, clientH)) / 2.0);
    }

    /** Window pixels to virtual pixels, the inverse. */
    public static int[] windowToVirtual(int x, int y, int clientW, int clientH) {
        double s = scale(clientW, clientH);
        return new int[]{
                (int) Math.round((x - letterboxX(clientW, clientH)) / s),
                (int) Math.round((y - letterboxY(clientW, clientH)) / s)
        };
    }

    /**
     * The single window transform: letterbox offset, then one uniform
     * scale. The shell paints with it and the inverse maps pointers back, so no
     * layer or hit-test ever carries its own scale.
     */
    public static AffineTransform windowTransform(int clientW, int clientH) {
        AffineTransform transform = new AffineTransform();
        transform.translate(letterboxX(clientW, clientH), letterboxY(clientW, clientH));
        transform.scale(scale(clientW, clientH), scale(clientW, clientH));
        return transform;
    }

    /**
     * The board cell under a window pixel: window →
     * virtual with the inverse, then {@link #tileAt}. {@code null} outside
     * the grid.
     */
    public static int[] windowTileAt(int x, int y, int clientW, int clientH) {
        int[] virtual = windowToVirtual(x, y, clientW, clientH);
        return tileAt(virtual[0], virtual[1]);
    }
}
