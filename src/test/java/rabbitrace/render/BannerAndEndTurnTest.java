package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Board;
import rabbitrace.game.Match;
import rabbitrace.automation.Commands;
import rabbitrace.automation.EventLog;
import rabbitrace.automation.Frames;
import rabbitrace.automation.Snapshot;
import rabbitrace.settings.Settings;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two presentation gaps as pure checks: the
 * banner copy selected from the phase alone, the {@code Terminar turno}
 * button's enabled rule, its specified HUD rectangle, and the
 * pointer path that accepts a click only through that same rule. The painter
 * reading the frame is exercised in pixels, so a plate that only exists in this
 * test cannot stand in for one the board draws.
 */
class BannerAndEndTurnTest {

    // ------------------------------------------------------------------
    // Banner selection.
    // ------------------------------------------------------------------

    @Test
    void bannerCopyIsSelectedByPhaseAndNamesTheNextPlayer() {
        assertEquals("Sin movimiento posible",
                BoardPainter.bannerText(Match.NO_LEGAL_MOVE, Match.BLUE),
                "no-move banner is exactly Sin movimiento posible");
        assertEquals("Turno de Azul", BoardPainter.bannerText(Match.HAND_OFF, Match.BLUE),
                "hand-off banner names the next player, not the one who just moved");
        assertEquals("Turno de Marrón", BoardPainter.bannerText(Match.HAND_OFF, Match.BROWN));

        for (String phase : new String[]{Match.RPS_SELECT, Match.RPS_REVEAL, Match.AWAIT_ROLL,
                Match.ROLLING, Match.AWAIT_MOVE, Match.MATCH_WON, null}) {
            assertNull(BoardPainter.bannerText(phase, Match.BLUE),
                    "phase " + phase + " must not paint a banner");
        }
    }

    /**
     * A real boxed board reaches both phases through the automation controller with
     * the defaults on: the settled die enters {@code NoLegalMove}, a skip
     * hands off to {@code HandOff}, and the two rendered plates differ in the
     * banner rectangle. That ties the phase selection above to painted pixels.
     */
    @Test
    void bothBannersArePaintedOverTheBoard() throws Exception {
        Match match = Match.start("ABC123");
        // Blue's start [2,8] is boxed in by its three in-board neighbours; the
        // south side is the board edge. The same setup scripts.
        match.setTile(1, 8, Board.ROCK);
        match.setTile(2, 7, Board.BUSH);
        match.setTile(3, 8, Board.FENCE);

        try (EventLog log = EventLog.open(null)) {
            Commands commands = new Commands(match, Settings.defaults(), log, () -> { },
                    Animations.defaults());
            commands.handle("rps 2 1");
            commands.handle("key W"); // skip the opening shake
            commands.handle("press tirar");
            assertEquals(Match.ROLLING, match.phase());
            commands.handle("key W"); // skip the tumble; no neighbour is free
            assertEquals(Match.NO_LEGAL_MOVE, match.phase());

            BufferedImage noMove = BoardPainter.render(Frames.of(match));
            commands.handle("key SPACE");
            assertEquals(Match.HAND_OFF, match.phase());
            BufferedImage handOff = BoardPainter.render(Frames.of(match));

            assertTrue(differentPixels(noMove, handOff, BoardPainter.BANNER) > 1000,
                    "the no-move and hand-off plates paint the same pixels, so the banner text is absent");
        }
    }

    // ------------------------------------------------------------------
    // Terminar turno: rectangle, enabled rule, hit test.
    // ------------------------------------------------------------------

    @Test
    void theEndTurnButtonIsTheSpecifiedHudRectLeftOfTheGear() {
        assertEquals(new Chrome.Rect(1270, 845, 140, 40), Chrome.END_TURN.rect(),
                "Terminar turno is 140x40 at (1270, 845)");
        assertTrue(Chrome.HUD.contains(Chrome.END_TURN), "the button is part of the HUD model");
        assertEquals(1430, Chrome.GEAR.rect().x(), "the gear rectangle must not move");
        assertTrue(Chrome.END_TURN.rect().right() <= Chrome.GEAR.rect().x(),
                "the button sits left of the gear");
        assertTrue(Chrome.END_TURN.rect().contains(1340, 865), "the button centre is inside its rect");
        assertFalse(Chrome.END_TURN.rect().contains(1269, 865), "the left edge is outside");
        assertFalse(Chrome.END_TURN.rect().contains(1410, 865), "the right edge is outside");
        assertFalse(Chrome.END_TURN.rect().contains(1340, 885), "the bottom edge is outside");
        assertFalse(Chrome.END_TURN.rect().coversCentre(14, 8),
                "the bottom band must clear the last tile centre");
    }

    @Test
    void theButtonIsEnabledOnlyAfterAStepInAwaitMove() {
        Match match = Match.start("ABC123");
        assertEquals(Match.BLUE, match.throwHands(2, 1));
        match.rollDie();
        assertEquals(Match.AWAIT_MOVE, match.phase());
        assertEquals(0, match.stepsThisTurn());
        assertFalse(match.canEndTurn(), "zero steps refuses Terminar turno");
        assertFalse(Frames.of(match).endTurnEnabled(),
                "the painter is handed the same disabled rule");

        BufferedImage disabled = BoardPainter.render(Frames.of(match));
        match.moveTo(2, 7);
        assertEquals(1, match.stepsThisTurn());
        assertTrue(match.canEndTurn(), "one step enables Terminar turno");
        assertTrue(Frames.of(match).endTurnEnabled());
        BufferedImage enabled = BoardPainter.render(Frames.of(match));
        assertTrue(differentPixels(disabled, enabled, Chrome.END_TURN.rect()) > 500,
                "the disabled and enabled plates look the same");

        match.endTurn();
        assertEquals(Match.AWAIT_ROLL, match.phase());
        assertFalse(match.canEndTurn(), "there is no button to press outside AwaitMove");
    }

    /**
     * The pointer path is the rule: a click on the dim button leaves every state
     * field untouched, and the same click after a step ends the turn. The click
     * is a virtual-pixel click, the path the window shell and the automation share.
     */
    @Test
    void clickingTheButtonIsInertUntilAStepThenEndsTheTurn() throws Exception {
        Match match = Match.start("ABC123");
        try (EventLog log = EventLog.open(null)) {
            Commands commands = new Commands(match, Settings.defaults(), log, () -> { });
            commands.handle("rps 2 1");
            commands.handle("press tirar");
            assertEquals(Match.AWAIT_MOVE, match.phase());

            String before = Snapshot.of(match);
            assertEquals("ok", commands.click(1340, 865),
                    "a click on the disabled button is swallowed, not refused");
            assertEquals(before, Snapshot.of(match),
                    "a zero-step click changed the match instead of being inert");

            commands.handle("key W");
            assertTrue(match.stepsThisTurn() > 0);
            assertTrue(commands.click(1340, 865).startsWith("ok"));
            assertEquals(Match.AWAIT_ROLL, match.phase(),
                    "the enabled button must end the turn through Match.endTurn()");
        }
    }

    private static int differentPixels(BufferedImage a, BufferedImage b, Chrome.Rect rect) {
        int differing = 0;
        for (int y = rect.y(); y < rect.bottom(); y++) {
            for (int x = rect.x(); x < rect.right(); x++) {
                if ((a.getRGB(x, y) & 0xffffff) != (b.getRGB(x, y) & 0xffffff)) {
                    differing++;
                }
            }
        }
        return differing;
    }
}
