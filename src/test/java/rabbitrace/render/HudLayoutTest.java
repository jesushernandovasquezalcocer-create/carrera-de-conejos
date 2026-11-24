package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;
import rabbitrace.automation.Frames;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD geometry with the {@code Tirar} button in it:
 * every part stays in the margins and clear of all 135 tile centres,
 * and the button is painted ready only in {@code AwaitRoll}. The model is the
 * one the painter draws and the pointer path hit-tests, so this pins the same
 * rectangles the scenario reads from {@code layout}.
 */
class HudLayoutTest {

    /** claim, tile by tile: no HUD rectangle holds a tile centre. */
    @Test
    void everyHudPartStaysInTheMarginsClearOfTileCentres() {
        Chrome.Rect grid = Chrome.GRID;
        assertEquals(170, grid.x());
        assertEquals(90, grid.y());
        assertEquals(84 * 15, grid.w());
        assertEquals(80 * 9, grid.h());

        assertTrue(Chrome.HUD.contains(Chrome.TIRAR), "the Tirar button is part of the HUD");
        assertEquals(new Chrome.Rect(1270, 22, 140, 40), Chrome.TIRAR.rect(),
                "the Tirar button sits at the right end of the top band, 140x40 at (1270, 22)");
        for (String name : new String[]{"turn", "seed", "gear", "die", "tirar"}) {
            assertTrue(Chrome.HUD.stream().anyMatch(part -> part.name().equals(name)),
                    "the HUD model names '" + name + "'");
        }

        for (Chrome.Part part : Chrome.HUD) {
            Chrome.Rect r = part.rect();
            String what = "HUD " + part.name();
            assertTrue(r.x() >= 0 && r.y() >= 0 && r.right() <= BoardPainter.WIDTH
                            && r.bottom() <= BoardPainter.HEIGHT,
                    what + " leaves the 1600x900 canvas: " + r);
            // It is not in the grid at all: outside horizontally or vertically.
            boolean outsideX = r.right() <= grid.x() || r.x() >= grid.right();
            boolean outsideY = r.bottom() <= grid.y() || r.y() >= grid.bottom();
            assertTrue(outsideX || outsideY, what + " enters the grid rectangle: " + r);
            for (int row = 0; row < TileMap.ROWS; row++) {
                for (int col = 0; col < TileMap.COLS; col++) {
                    assertFalse(r.coversCentre(col, row),
                            what + " covers the centre of tile (" + col + "," + row + ")");
                }
            }
        }
    }

    /**
     * The button is live in {@code AwaitRoll} and drawn disabled in the phase
     * that follows, so its plate changes with the phase: the same predicate
     * ({@link Match#AWAIT_ROLL}) gates the pointer hit-test.
     */
    @Test
    void theTirarPlateReadsReadyOnlyInAwaitRoll() {
        Match match = Match.start("ABC123");
        assertEquals(Match.RPS_SELECT, match.phase());
        match.throwHands(2, 1); // blue wins paper over rock and is first
        assertEquals(Match.AWAIT_ROLL, match.phase());
        BufferedImage ready = BoardPainter.render(Frames.of(match));

        match.rollDie();
        assertEquals(Match.AWAIT_MOVE, match.phase());
        BufferedImage off = BoardPainter.render(Frames.of(match));

        Chrome.Rect r = Chrome.TIRAR.rect();
        int changed = 0;
        for (int y = r.y(); y < r.bottom(); y++) {
            for (int x = r.x(); x < r.right(); x++) {
                if ((ready.getRGB(x, y) & 0xffffff) != (off.getRGB(x, y) & 0xffffff)) {
                    changed++;
                }
            }
        }
        assertTrue(changed > 1000,
                "the Tirar plate looks the same in AwaitRoll and AwaitMove: only "
                        + changed + " of " + (r.w() * r.h()) + " pixels differ");
    }
}
