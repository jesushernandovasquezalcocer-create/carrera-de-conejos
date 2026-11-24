package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;
import rabbitrace.automation.Frames;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The opening throw's panel (Â, Â, Â): the pure geometry and
 * state model in {@link Rps}, plus the two things the pixels must prove â€” a
 * committed hand stays secret until both sides are in, and the
 * panel exists only while the throw is unresolved.
 */
class RpsTest {

    // ------------------------------------------------------------------
    // Geometry and hit-testing.
    // ------------------------------------------------------------------

    @Test
    void thePanelIsCentredAndTheSixHandRectsTileTheTwoColumns() {
        assertEquals(800, Rps.PANEL.x() + Rps.PANEL.w() / 2, "the panel is horizontally centred");
        assertEquals(450, Rps.PANEL.y() + Rps.PANEL.h() / 2, "the panel is vertically centred");
        assertEquals(new Chrome.Rect(420, 220, 760, 460), Rps.PANEL);
        assertEquals(6, Rps.HANDS.size(), "P1's three hands and P2's three hands");

        for (int i = 0; i < 3; i++) {
            Rps.Hand blue = Rps.HANDS.get(i);
            Rps.Hand brown = Rps.HANDS.get(i + 3);
            assertEquals(Match.BLUE, blue.player());
            assertEquals(Match.BROWN, brown.player());
            assertEquals(i + 1, blue.hand(), "P1's column is Rock, Paper, Scissors top to bottom");
            assertEquals(i + 1, brown.hand(), "P2's column is Rock, Paper, Scissors top to bottom");
            assertEquals(Rps.LEFT_COLUMN_X, blue.rect().x());
            assertEquals(Rps.RIGHT_COLUMN_X, brown.rect().x());
            assertEquals(Rps.COLUMN_TOP + i * (Rps.PLATE_H + Rps.PLATE_GAP), blue.rect().y());
            assertEquals(Rps.PLATE_W, blue.rect().w());
            assertEquals(Rps.PLATE_H, blue.rect().h());
            // The plates never overlap, and stay inside the panel.
            assertTrue(Rps.PANEL.contains(blue.rect().x(), blue.rect().y()));
            assertTrue(Rps.PANEL.contains(blue.rect().right() - 1, blue.rect().bottom() - 1));
            assertFalse(blue.rect().contains(blue.rect().x(), blue.rect().bottom()),
                    "the bottom edge is exclusive");
        }
        assertTrue(Rps.HANDS.get(0).rect().bottom() < Rps.HANDS.get(1).rect().y());
        assertTrue(Rps.LEFT_COLUMN_X + Rps.PLATE_W < Rps.RIGHT_COLUMN_X,
                "the two columns do not touch");

        // Every plate's centre resolves to that plate; a point one pixel outside
        // the plate and outside every other plate resolves to nothing.
        for (Rps.Hand hand : Rps.HANDS) {
            Chrome.Rect r = hand.rect();
            assertEquals(hand, Rps.handAt(r.x() + r.w() / 2, r.y() + r.h() / 2),
                    "the centre of " + hand.player() + " hand " + hand.hand() + " is that hand");
        }
        assertNull(Rps.handAt(Rps.PANEL.x() + 10, Rps.PANEL.y() + 10),
                "the heading area is not a hand");
        assertNull(Rps.handAt(Rps.LEFT_COLUMN_X - 1, Rps.COLUMN_TOP),
                "one pixel left of the column is not a hand");
        assertNull(Rps.handAt(Rps.LEFT_COLUMN_X, Rps.COLUMN_TOP - 1),
                "one pixel above the column is not a hand");
    }

    @Test
    void theColumnStateHidesTheChoiceUntilBothPlayersCommit() {
        assertEquals(Rps.Column.CHOOSING, Rps.columnState(Match.RPS_SELECT, false));
        assertEquals(Rps.Column.READY, Rps.columnState(Match.RPS_SELECT, true));
        assertEquals(Rps.Column.REVEALED, Rps.columnState(Match.RPS_REVEAL, true));
        assertNull(Rps.columnState(Match.AWAIT_ROLL, true), "the panel is gone after the throw");
        assertNull(Rps.columnState(null, false));

        assertTrue(Rps.shows(Match.RPS_SELECT));
        assertTrue(Rps.shows(Match.RPS_REVEAL));
        assertFalse(Rps.shows(Match.AWAIT_ROLL));
        assertFalse(Rps.shows(null));

        assertTrue(Rps.accepts(Match.RPS_SELECT, false), "a pending player's column takes a choice");
        assertFalse(Rps.accepts(Match.RPS_SELECT, true), "a committed column is disabled");
        assertFalse(Rps.accepts(Match.RPS_REVEAL, true), "the reveal takes no new choice");
    }

    // ------------------------------------------------------------------
    // The pixels.
    // ------------------------------------------------------------------

    /**
     * before the second commitment the two frames differ only in
     * which secret hand P1 stored, so they must be pixel-identical. A build that
     * highlighted the picked plate would change pixels here.
     */
    @Test
    void aCommittedHandNeverShowsInTheFrameBeforeTheReveal() {
        BufferedImage rock = BoardPainter.renderScreen(Frames.of(committed("ABC123", 1)));
        BufferedImage scissors = BoardPainter.renderScreen(Frames.of(committed("ABC123", 3)));
        assertImagesEqual(rock, scissors, "a committed hand is secret until both sides are in");
    }

    /** After both commit, the chosen plate is painted, so the frame differs. */
    @Test
    void theRevealShowsTheChosenHand() {
        Match withRock = revealed("ABC123", 1, 2);
        Match withScissors = revealed("ABC123", 3, 2);
        assertEquals(1, withRock.revealedHand(Match.BLUE));
        assertEquals(3, withScissors.revealedHand(Match.BLUE));

        BufferedImage rock = BoardPainter.renderScreen(Frames.of(withRock));
        BufferedImage scissors = BoardPainter.renderScreen(Frames.of(withScissors));
        Chrome.Rect blue = Rps.columnRect(Match.BLUE);
        assertTrue(differingPixels(rock, scissors, blue) > 500,
                "the revealed blue column must show which hand was played");
        Chrome.Rect brown = Rps.columnRect(Match.BROWN);
        assertEquals(0, differingPixels(rock, scissors, brown),
                "P2's hand did not change, so its column must be identical");
    }

    /** The panel is painted during the throw and gone once the match is rolling. */
    @Test
    void thePanelExistsOnlyWhileTheThrowIsUnresolved() {
        Match match = committed("ABC123", 1);
        BufferedImage panel = BoardPainter.renderScreen(Frames.of(match));

        match.commitHand(Match.BROWN, 2); // paper beats rock: blue wins, AwaitRoll
        assertEquals(Match.AWAIT_ROLL, match.phase());
        assertNotNull(match.openingWinner());
        BufferedImage board = BoardPainter.renderScreen(Frames.of(match));

        int x = Rps.PANEL.x() + 10;
        int y = Rps.PANEL.y() + 10;
        assertTrue(luminance(panel.getRGB(x, y)) > luminance(board.getRGB(x, y)),
                "the parchment panel must cover the board during RpsSelect and be gone after it");
    }

    /**
     * The offline {@code frame} probe stays the board, so 's ground
     * stride and 's fence orientations remain readable; the live
     * surface adds the panel. This pins the split documented on
     * {@link BoardPainter#renderScreen}.
     */
    @Test
    void theOfflineProbeRendersTheBoardWithoutThePanel() {
        Match match = committed("ABC123", 1);
        BufferedImage probe = BoardPainter.render(Frames.of(match));
        BufferedImage screen = BoardPainter.renderScreen(Frames.of(match));
        int x = Rps.PANEL.x() + 10;
        int y = Rps.PANEL.y() + 10;
        assertNotEquals(probe.getRGB(x, y), screen.getRGB(x, y),
                "the live surface must add the panel the probe leaves out");
        assertEquals(probe.getRGB(Rps.PANEL.x() - 10, Rps.PANEL.y() - 10),
                screen.getRGB(Rps.PANEL.x() - 10, Rps.PANEL.y() - 10),
                "outside the panel the two renders are the same pixels");
    }

    // ------------------------------------------------------------------

    private static Match committed(String seed, int blueHand) {
        Match match = Match.start(seed);
        match.commitHand(Match.BLUE, blueHand);
        assertEquals(Match.RPS_SELECT, match.phase());
        return match;
    }

    private static Match revealed(String seed, int blueHand, int brownHand) {
        Match match = Match.start(seed);
        match.throwHands(blueHand, brownHand);
        assertEquals(Match.AWAIT_ROLL, match.phase());
        // Hold the shake so the reveal frame exists; a zero duration would go
        // straight to AwaitRoll and the panel would already be gone.
        match.beginReveal();
        assertEquals(Match.RPS_REVEAL, match.phase());
        return match;
    }

    private static void assertImagesEqual(BufferedImage a, BufferedImage b, String message) {
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight());
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    throw new AssertionError(message + ": pixel (" + x + "," + y + ") is "
                            + Integer.toHexString(a.getRGB(x, y)) + " for one hand and "
                            + Integer.toHexString(b.getRGB(x, y)) + " for the other");
                }
            }
        }
    }

    private static int differingPixels(BufferedImage a, BufferedImage b, Chrome.Rect rect) {
        int changed = 0;
        for (int y = rect.y(); y < rect.bottom(); y++) {
            for (int x = rect.x(); x < rect.right(); x++) {
                if ((a.getRGB(x, y) & 0xffffff) != (b.getRGB(x, y) & 0xffffff)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static int luminance(int rgb) {
        return ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
    }
}
