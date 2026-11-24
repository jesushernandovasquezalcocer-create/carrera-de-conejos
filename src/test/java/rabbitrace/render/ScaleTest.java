package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Match;
import rabbitrace.automation.Frames;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screen metrics and the window law
 * one uniform scale, letterboxed and centred,
 * with a marker's tile still resolving to that tile after the window → virtual
 * step.
 */
class ScaleTest {

    private static final int[][] WINDOWS = {{1600, 900}, {1280, 720}, {2560, 1440}};

    @Test
    void tilesLandOnTheMetricsAndEveryWindowResolvesItsMarkers() {
        // 84x80 virtual pixels per tile at origin (170, 90), so the grid is
        // 1260x720 and the margins are 170 left/right and 90 top/bottom.
        assertEquals(84, TileMap.TILE_W);
        assertEquals(80, TileMap.TILE_H);
        assertEquals(170, TileMap.ORIGIN_X);
        assertEquals(90, TileMap.ORIGIN_Y);
        assertEquals(15, TileMap.COLS);
        assertEquals(9, TileMap.ROWS);
        assertEquals(1600, TileMap.VIRTUAL_W);
        assertEquals(900, TileMap.VIRTUAL_H);
        assertEquals(1260, TileMap.TILE_W * TileMap.COLS);
        assertEquals(720, TileMap.TILE_H * TileMap.ROWS);
        assertEquals(170, TileMap.tileX(0));
        assertEquals(1430, TileMap.tileX(TileMap.COLS));
        assertEquals(90, TileMap.tileY(0));
        assertEquals(810, TileMap.tileY(TileMap.ROWS));
        assertEquals(212, TileMap.tileCentreX(0));
        assertEquals(130, TileMap.tileCentreY(0));
        assertEquals(380, TileMap.tileCentreX(2));
        assertEquals(770, TileMap.tileCentreY(8));
        assertEquals(1220, TileMap.tileCentreX(12));
        assertEquals(800, TileMap.tileCentreX(BoardGenerator.CARROT_COL));
        assertEquals(130, TileMap.tileCentreY(BoardGenerator.CARROT_ROW));

        Match match = Match.start("ABC123");
        assertArrayEquals(new int[]{2, 8}, match.blue());
        assertArrayEquals(new int[]{12, 8}, match.brown());

        // The 1600x900 canvas itself: the painter puts board pixels exactly on the
        // grid rectangle and the margins stay border.
        BufferedImage virtual = BoardPainter.render(Frames.of(match));
        assertEquals(1600, virtual.getWidth());
        assertEquals(900, virtual.getHeight());
        int border = virtual.getRGB(100, 450) & 0xffffff;
        assertEquals(border, virtual.getRGB(1440, 450) & 0xffffff);
        assertEquals(border, virtual.getRGB(800, 85) & 0xffffff);
        assertEquals(border, virtual.getRGB(160, 770) & 0xffffff);
        assertNotEquals(border, virtual.getRGB(212, 130) & 0xffffff);
        assertNotEquals(border, virtual.getRGB(800, 130) & 0xffffff);
        assertNotEquals(border, virtual.getRGB(380, 770) & 0xffffff);
        assertNotEquals(border, virtual.getRGB(1220, 770) & 0xffffff);
        assertNotEquals(border, virtual.getRGB(1388, 770) & 0xffffff);

        for (int[] window : WINDOWS) {
            int w = window[0];
            int h = window[1];
            double s = TileMap.scale(w, h);
            assertEquals(Math.min(w / 1600.0, h / 900.0), s, 0.0);
            assertTrue(s > 0);

            // One scale for both axes: the canvas is reduced, never stretched.
            AffineTransform transform = TileMap.windowTransform(w, h);
            assertEquals(s, transform.getScaleX(), 0.0);
            assertEquals(s, transform.getScaleY(), 0.0);
            assertEquals(0.0, transform.getShearX(), 0.0);
            assertEquals(0.0, transform.getShearY(), 0.0);
            assertEquals(TileMap.letterboxX(w, h), transform.getTranslateX(), 0.0);
            assertEquals(TileMap.letterboxY(w, h), transform.getTranslateY(), 0.0);
            if (w == TileMap.VIRTUAL_W && h == TileMap.VIRTUAL_H) {
                assertTrue(transform.isIdentity());
            }

            // Centred: equal margins within the half-pixel the rounded offset can
            // consume, and the scaled canvas fits inside the window.
            double left = TileMap.letterboxX(w, h);
            double top = TileMap.letterboxY(w, h);
            double right = w - TileMap.VIRTUAL_W * s - left;
            double bottom = h - TileMap.VIRTUAL_H * s - top;
            assertEquals(left, right, 1.0);
            assertEquals(top, bottom, 1.0);
            assertEquals(0.0, left, 1.0);
            assertEquals(0.0, top, 1.0);

            // The grid rectangle keeps its size under the uniform scale.
            Point2D gridTopLeft = transform.transform(
                    new Point2D.Double(TileMap.ORIGIN_X, TileMap.ORIGIN_Y), null);
            Point2D gridBottomRight = transform.transform(
                    new Point2D.Double(TileMap.ORIGIN_X + TileMap.TILE_W * TileMap.COLS,
                            TileMap.ORIGIN_Y + TileMap.TILE_H * TileMap.ROWS), null);
            assertEquals(1260 * s, gridBottomRight.getX() - gridTopLeft.getX(), 1e-9);
            assertEquals(720 * s, gridBottomRight.getY() - gridTopLeft.getY(), 1e-9);

            // Every tile's centre, and a point a few pixels inside two of its
            // corners, resolves back to that tile from window pixels.
            for (int row = 0; row < TileMap.ROWS; row++) {
                for (int col = 0; col < TileMap.COLS; col++) {
                    assertResolves(transform, w, h, col, row,
                            TileMap.tileCentreX(col), TileMap.tileCentreY(row));
                    assertResolves(transform, w, h, col, row,
                            TileMap.tileX(col) + 2, TileMap.tileY(row) + 2);
                    assertResolves(transform, w, h, col, row,
                            TileMap.tileX(col) + TileMap.TILE_W - 3,
                            TileMap.tileY(row) + TileMap.TILE_H - 3);
                }
            }

            // names the markers: blue's and brown's tiles and the carrot.
            for (int[] cell : new int[][]{match.blue(), match.brown(),
                    {BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW}}) {
                assertResolves(transform, w, h, cell[0], cell[1],
                        TileMap.tileCentreX(cell[0]), TileMap.tileCentreY(cell[1]));
            }

            // A click in the margin is outside the grid, whatever the window size.
            assertNull(TileMap.windowTileAt(device(transform, 100, 450)[0],
                    device(transform, 100, 450)[1], w, h));

            // The painter's plate, scaled with the same transform the shell uses,
            // covers the window with no letterbox and keeps margins and tiles in
            // their metric positions.
            BufferedImage painted = paintWindow(virtual, w, h);
            assertEquals(w, painted.getWidth());
            assertEquals(h, painted.getHeight());
            assertEquals(border, painted.getRGB(0, 0) & 0xffffff);
            assertEquals(border, painted.getRGB(w - 1, h - 1) & 0xffffff);
            int[] margin = device(transform, 100, 450);
            assertEquals(border, painted.getRGB(margin[0], margin[1]) & 0xffffff);
            int[] tile = device(transform, 212, 130);
            assertNotEquals(border, painted.getRGB(tile[0], tile[1]) & 0xffffff);
        }
    }

    private static void assertResolves(AffineTransform transform, int w, int h,
                                       int col, int row, int virtualX, int virtualY) {
        int[] point = device(transform, virtualX, virtualY);
        assertArrayEquals(new int[]{col, row},
                TileMap.windowTileAt(point[0], point[1], w, h),
                "window " + w + "x" + h + " at " + point[0] + "," + point[1]
                        + " should resolve to " + col + "," + row);
    }

    private static int[] device(AffineTransform transform, int virtualX, int virtualY) {
        Point2D point = transform.transform(new Point2D.Double(virtualX, virtualY), null);
        return new int[]{(int) Math.round(point.getX()), (int) Math.round(point.getY())};
    }

    private static BufferedImage paintWindow(BufferedImage virtual, int w, int h) {
        BufferedImage window = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = window.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            g.transform(TileMap.windowTransform(w, h));
            g.drawImage(virtual, 0, 0, null);
        } finally {
            g.dispose();
        }
        return window;
    }
}
