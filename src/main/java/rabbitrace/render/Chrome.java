package rabbitrace.render;

import java.util.List;

/**
 * The chrome around the board as geometry: the HUD
 * elements and the border props. The painter draws these exact rectangles and the
 * automation reports them, so "the HUD and border props never cover the centre of an
 * in-play tile" is a checkable property of one source of truth rather than a
 * coincidence of two drawing routines.
 *
 * <p>Everything here lives in the margins: the grid rectangle is
 * {@code (170, 90, 1260, 720)}, so the HUD's turn band sits above it, the seed
 * block below-left and the 140×40 gear at {@code (1430, 845)}. Border props are
 * placeholder trees and rocks, entirely outside the grid, so their overhang into
 * edge cells is 0 — the ceiling is 12 px.
 */
public final class Chrome {

    /** An axis-aligned rectangle in virtual pixels; {@code [x, x+w) × [y, y+h)}. */
    public record Rect(int x, int y, int w, int h) {

        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public boolean contains(int px, int py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        /** True when this rectangle covers the centre of tile {@code (col, row)}. */
        public boolean coversCentre(int col, int row) {
            return contains(TileMap.tileCentreX(col), TileMap.tileCentreY(row));
        }
    }

    /** A named HUD element: the painter draws it, the automation names it. */
    public record Part(String name, Rect rect) {
    }

    /** The board rectangle: 15×9 tiles of 84×80 at origin (170, 90). */
    public static final Rect GRID = new Rect(TileMap.ORIGIN_X, TileMap.ORIGIN_Y,
            TileMap.TILE_W * TileMap.COLS, TileMap.TILE_H * TileMap.ROWS);

    /** Turn, roll and active player: the top margin band. */
    public static final Part TURN = new Part("turn", new Rect(170, 12, 1260, 66));

    /** Seed with its generator version: bottom-left. */
    public static final Part SEED = new Part("seed", new Rect(170, 820, 900, 60));

    /** The 140×40 Ajustes gear at (1430, 845): bottom-right. */
    public static final Part GEAR = new Part("gear", new Rect(1430, 845, 140, 40));

    /**
     * The 140×40 {@code Terminar turno} button left of the gear, at
     * {@code (1270, 845)}: wholly in the 80 px bottom
     * margin, clear of the seed block and the gear. It is live only when
     * {@link rabbitrace.game.Match#canEndTurn()} is true.
     */
    public static final Part END_TURN = new Part("endTurn", new Rect(1270, 845, 140, 40));

    /**
     * The 140×40 {@code Tirar} button at the right end of the top band
     * wholly in the 90 px top margin, so it can
     * never cover a tile centre. It is live in {@code AwaitRoll} and
     * drawn disabled in every other phase.
     */
    public static final Part TIRAR = new Part("tirar", new Rect(1270, 22, 140, 40));

    /** Every HUD element, in paint order. */
    public static final List<Part> HUD = List.of(TURN, SEED, END_TURN, GEAR, TIRAR);

    /**
     * Placeholder border scene: trees and rocks filling the margins. Each rect is
     * the prop's footprint, and every footprint is wholly outside {@link #GRID}.
     * The seed does not move them, so the chrome is the same in every match.
     */
    public static final List<Rect> BORDER_PROPS = List.of(
            new Rect(24, 24, 96, 52),
            new Rect(1480, 18, 96, 60),
            new Rect(28, 380, 60, 48),
            new Rect(1520, 400, 56, 44),
            new Rect(30, 812, 90, 64),
            new Rect(1180, 826, 90, 54));

    private Chrome() {
    }
}
