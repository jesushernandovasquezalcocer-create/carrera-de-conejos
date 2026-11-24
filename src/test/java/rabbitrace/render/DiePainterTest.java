package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.assets.AssetSource;
import rabbitrace.automation.Frames;
import rabbitrace.game.Match;
import rabbitrace.settings.Settings;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The die is pixels, not only a pose: the painter draws the
 * parchment face and its ink pips inside {@link Chrome#DIE} while a die is in
 * hand, dims it in {@code AwaitRoll}, animates it only when the live frame
 * carries a tumble fraction, and paints no HUD at all on Título.
 */
class DiePainterTest {

    private static final int PARCHMENT_RGB = 0xefe3c4;
    private static final int INK_RGB = 0x3b2a17;

    @Test
    void theDieIsDrawnWhileADieIsInHand() {
        Match match = openingMatch();
        match.startRoll();
        assertEquals(Match.ROLLING, match.phase());

        // The offline probe passes no fraction: one fixed face, the rolled value.
        BufferedImage rolling = BoardPainter.render(Frames.of(match));
        assertTrue(parchmentPixels(rolling) > 1000,
                "the die's parchment face is not in the Rolling frame: "
                        + parchmentPixels(rolling));
        assertTrue(inkPixels(rolling) > 60,
                "the die's pips are not in the Rolling frame: " + inkPixels(rolling));
        int roll = match.roll();
        assertEquals(roll, pipBlobs(rolling),
                "the face shows " + pipBlobs(rolling) + " pips but the die rolled " + roll);

        match.settleRoll();
        assertEquals(Match.AWAIT_MOVE, match.phase());
        BufferedImage settled = BoardPainter.render(Frames.of(match));
        assertTrue(parchmentPixels(settled) > 1000, "the settled face is missing");
        assertTrue(inkPixels(settled) > 60, "the settled pips are missing");
        assertEquals(roll, pipBlobs(settled), "the settled face is not the rolled value");
        // Same rolled value, same rest pose: the offline Rolling frame and the
        // settled frame both show the value, so the probe is deterministic.
        assertTrue(differentPixels(rolling, settled) == 0,
                "the offline Rolling frame and the settled frame disagree on the die");
    }

    /** All six faces: exactly as many separate pips as the value. */
    @Test
    void everyFaceDrawsExactlyThatManyPips() {
        Match match = openingMatch();
        match.rollDie();
        Frame base = Frames.of(match);
        for (int face = 1; face <= 6; face++) {
            BufferedImage image = BoardPainter.render(withRoll(base, face));
            assertEquals(face, pipBlobs(image),
                    "face " + face + " does not draw " + face + " pips");
        }
    }

    /** A live fraction moves the die: two points in the tumble paint differently. */
    @Test
    void theLiveTumbleMovesAndCyclesInsideTheDieRect() {
        Match match = openingMatch();
        match.startRoll();
        BufferedImage early = live(match, 0.05);
        BufferedImage later = live(match, 0.30);
        BufferedImage nearSettle = live(match, 0.90);

        assertTrue(differentPixels(early, later) > 500,
                "the tumble's tilt, bob or face did not move between 30 ms and 180 ms");
        assertTrue(differentPixels(later, nearSettle) > 500,
                "the tumble looks the same at 180 ms and 540 ms");
    }

    /** AwaitRoll has no die in hand, so its face is dimmer than the settled one. */
    @Test
    void theFaceIsDimInAwaitRollAndLitInAwaitMove() {
        Match match = openingMatch();
        assertEquals(Match.AWAIT_ROLL, match.phase());
        BufferedImage waiting = BoardPainter.render(Frames.of(match));

        match.rollDie();
        assertEquals(Match.AWAIT_MOVE, match.phase());
        BufferedImage settled = BoardPainter.render(Frames.of(match));

        assertTrue(luminance(waiting) < luminance(settled),
                "the AwaitRoll die is not dimmer than the settled die: "
                        + luminance(waiting) + " vs " + luminance(settled));
        assertTrue(parchmentPixels(settled) > 1000,
                "the lit face should paint the parchment at full strength");
        assertTrue(parchmentPixels(waiting) == 0,
                "a dim face should be blended, not the opaque parchment colour");
    }

    /** Título paints no Partida HUD, so the die rect is the bare border plate. */
    @Test
    void theDieIsAbsentOnTitulo() {
        Match idle = Match.idle("ABC123");
        assertEquals(Match.TITULO, idle.screen());
        BufferedImage titulo = BoardPainter.render(Frames.of(idle));
        Chrome.Rect r = Chrome.DIE.rect();
        int first = titulo.getRGB(r.x(), r.y()) & 0xffffff;
        for (int y = r.y(); y < r.bottom(); y++) {
            for (int x = r.x(); x < r.right(); x++) {
                assertEquals(first, titulo.getRGB(x, y) & 0xffffff,
                        "the die's rect is not bare on Título at (" + x + "," + y + ")");
            }
        }
        assertEquals(0, parchmentPixels(titulo));
        assertEquals(0, inkPixels(titulo));
    }

    /** The geometry: inside the top band, left of Tirar, clear of the turn text. */
    @Test
    void theDieSitsInTheTopBandLeftOfTirar() {
        assertTrue(Chrome.HUD.contains(Chrome.DIE), "the die is part of the HUD model");
        Chrome.Rect die = Chrome.DIE.rect();
        Chrome.Rect band = Chrome.TURN.rect();
        assertEquals(64, die.w());
        assertEquals(64, die.h());
        assertTrue(die.x() >= band.x() && die.right() <= band.right(),
                "the die leaves the top band horizontally: " + die);
        assertTrue(die.y() >= band.y() && die.bottom() <= band.bottom(),
                "the die leaves the top band vertically: " + die);
        assertTrue(die.right() < Chrome.TIRAR.rect().x(),
                "the die does not sit left of Tirar: " + die);
    }

    // ------------------------------------------------------------------
    // Fixtures and pixel helpers.
    // ------------------------------------------------------------------

    private static Match openingMatch() {
        Match match = Match.start("ABC123");
        assertEquals(Match.BLUE, match.throwHands(2, 1),
                "the fixture's opening throw is decisive for blue");
        return match;
    }

    private static BufferedImage live(Match match, double fraction) {
        return BoardPainter.render(Frames.of(match, Settings.defaults(), AssetSource.placeholder(),
                true, true, 0, 0, fraction));
    }

    /** The same frame with one chosen die value, so all six faces can be painted. */
    private static Frame withRoll(Frame f, int roll) {
        return new Frame(f.board(), f.blue(), f.brown(), f.carrot(), f.screen(), f.phase(),
                f.p1Hand(), f.p2Hand(), f.p1Committed(), f.p2Committed(), f.active(), f.turn(),
                roll, f.endTurnEnabled(), f.seed(), f.rngCursor(), f.boardHash(), f.targets(),
                f.opener(), f.settings(), f.seedField(), f.seedValid(), f.plan(), f.dieTumble());
    }

    private static int parchmentPixels(BufferedImage image) {
        return count(image, PARCHMENT_RGB);
    }

    private static int inkPixels(BufferedImage image) {
        return count(image, INK_RGB);
    }

    /**
     * The number of separate ink pips on the face: a flood fill over the exact
     * ink pixels, ignoring specks too small to be a pip. A 5 px radius pip leaves
     * dozens of core pixels, and 14 px spacing keeps neighbours apart.
     */
    private static int pipBlobs(BufferedImage image) {
        Chrome.Rect r = Chrome.DIE.rect();
        boolean[][] ink = new boolean[r.w()][r.h()];
        for (int y = 0; y < r.h(); y++) {
            for (int x = 0; x < r.w(); x++) {
                ink[x][y] = (image.getRGB(r.x() + x, r.y() + y) & 0xffffff) == INK_RGB;
            }
        }
        int blobs = 0;
        for (int y = 0; y < r.h(); y++) {
            for (int x = 0; x < r.w(); x++) {
                if (!ink[x][y]) {
                    continue;
                }
                int size = 0;
                java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
                open.push(new int[]{x, y});
                ink[x][y] = false;
                while (!open.isEmpty()) {
                    int[] cell = open.pop();
                    size++;
                    int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] step : neighbours) {
                        int nx = cell[0] + step[0];
                        int ny = cell[1] + step[1];
                        if (nx >= 0 && ny >= 0 && nx < r.w() && ny < r.h() && ink[nx][ny]) {
                            ink[nx][ny] = false;
                            open.push(new int[]{nx, ny});
                        }
                    }
                }
                if (size >= 10) {
                    blobs++;
                }
            }
        }
        return blobs;
    }

    private static int count(BufferedImage image, int rgb) {
        Chrome.Rect r = Chrome.DIE.rect();
        int total = 0;
        for (int y = r.y(); y < r.bottom(); y++) {
            for (int x = r.x(); x < r.right(); x++) {
                if ((image.getRGB(x, y) & 0xffffff) == rgb) {
                    total++;
                }
            }
        }
        return total;
    }

    private static int differentPixels(BufferedImage a, BufferedImage b) {
        Chrome.Rect r = Chrome.DIE.rect();
        int total = 0;
        for (int y = r.y(); y < r.bottom(); y++) {
            for (int x = r.x(); x < r.right(); x++) {
                if ((a.getRGB(x, y) & 0xffffff) != (b.getRGB(x, y) & 0xffffff)) {
                    total++;
                }
            }
        }
        return total;
    }

    private static long luminance(BufferedImage image) {
        Chrome.Rect r = Chrome.DIE.rect();
        long total = 0;
        for (int y = r.y(); y < r.bottom(); y++) {
            for (int x = r.x(); x < r.right(); x++) {
                int rgb = image.getRGB(x, y);
                total += ((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff);
            }
        }
        return total;
    }
}
