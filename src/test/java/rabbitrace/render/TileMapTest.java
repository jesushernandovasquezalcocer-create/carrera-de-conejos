package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;
import rabbitrace.automation.Commands;
import rabbitrace.automation.EventLog;
import rabbitrace.automation.Snapshot;
import rabbitrace.settings.Settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the coordinate law: board cells, virtual
 * pixels and the window letterbox, plus the two automation commands that must be
 * the same pointer event.
 */
class TileMapTest {

    @Test
    void tileAndClickAreTheSamePointerEvent() throws Exception {
        // the exact pair the coordinate check names.
        assertArrayEquals(new int[]{2, 8}, TileMap.tileAt(380, 770));
        assertArrayEquals(new int[]{380, 770},
                new int[]{TileMap.tileCentreX(2), TileMap.tileCentreY(8)});

        // Outside the grid rectangle resolves to no tile, not a clamped edge cell.
        assertNull(TileMap.tileAt(169, 770));
        assertNull(TileMap.tileAt(1430, 770));
        assertNull(TileMap.tileAt(380, 50));
        assertNull(TileMap.tileAt(380, 810));

        // with a 1200×700 client area: s = min(0.75, 0.777…) = 0.75, so the
        // 1200×675 canvas is centred with 25 px of vertical letterbox (12.5 each
        // side, rounded up to the top 13 px).
        assertEquals(0.75, TileMap.scale(1200, 700), 0.0);
        assertEquals(0, TileMap.letterboxX(1200, 700));
        assertEquals(13, TileMap.letterboxY(1200, 700));
        assertArrayEquals(new int[]{0, 0}, TileMap.windowToVirtual(0, 13, 1200, 700));
        assertArrayEquals(new int[]{1600, 900}, TileMap.windowToVirtual(1200, 688, 1200, 700));
        // The row above the letterbox is not part of the virtual canvas.
        assertArrayEquals(new int[]{0, -1}, TileMap.windowToVirtual(0, 12, 1200, 700));
        // A device click on the tile's centre still resolves to tile (2, 8).
        int[] device = {TileMap.letterboxX(1200, 700) + (int) (380 * 0.75),
                TileMap.letterboxY(1200, 700) + (int) (770 * 0.75)};
        int[] virtual = TileMap.windowToVirtual(device[0], device[1], 1200, 700);
        assertArrayEquals(new int[]{2, 8}, TileMap.tileAt(virtual[0], virtual[1]));

        // `tile 2 8` is sugar for `click 380 770`; both reach one
        // pointer path with the same virtual point and the same board cell.
        try (EventLog log = EventLog.open(null)) {
            Match match = Match.start("ABC123");
            Commands commands = new Commands(match, Settings.defaults(), log, () -> { });
            String before = Snapshot.of(match);

            assertEquals("ok", commands.handle("tile 2 8"));
            Commands.Click byTile = commands.lastClick();
            assertEquals("ok", commands.handle("click 380 770"));
            Commands.Click byClick = commands.lastClick();

            assertEquals(new Commands.Click(380, 770, 2, 8), byTile);
            assertEquals(byTile, byClick);
            assertEquals(before, Snapshot.of(match));

            // A click outside the grid is ignored, and only that one.
            assertEquals("ok", commands.handle("click 0 0"));
            assertTrue(commands.lastClick().outside());
            assertEquals(before, Snapshot.of(match));

            // Bad arguments are rejected and change nothing.
            assertThrows(IllegalArgumentException.class, () -> commands.handle("click 380"));
            assertThrows(IllegalArgumentException.class, () -> commands.handle("tile 15 0"));
            assertEquals(new Commands.Click(0, 0, -1, -1), commands.lastClick());
        }
    }
}
