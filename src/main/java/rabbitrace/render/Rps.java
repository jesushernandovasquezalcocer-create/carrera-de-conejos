package rabbitrace.render;

import rabbitrace.game.Match;

import java.util.List;

/**
 * The opening throw's panel, as pure
 * geometry and state so the painter, the pointer path and the tests share one
 * source of truth.
 *
 * <p>The controls are fixed but there is no rectangle for the RPS panel, so`r`n * this is the chosen layout: a parchment panel centred on
 * the 1600×900 canvas with no veil, P1's Rock/Paper/Scissors column on the left
 * and P2's on the right. Every hand is a 120×100 plate holding an 84×84 hand
 * sprite ({@code rps.hand.*}) with its label beneath.
 *
 * <p>The state is a column model, not a rule: {@link Column#CHOOSING} shows the
 * three plates, {@link Column#READY} hides a committed choice behind a neutral
 * {@code ¡Listo!} card, and {@link Column#REVEALED} shows the hands with the
 * chosen one marked. A choice is never visible before both sides committed
 *
 */
public final class Rps {

    /** The panel: 760×460 centred on the canvas at (420, 220). */
    public static final Chrome.Rect PANEL = new Chrome.Rect(420, 220, 760, 460);

    /** Baselines of the three text lines, in virtual pixels. */
    public static final int HEADING_BASELINE = 268;
    public static final int CAPTION_BASELINE = 294;
    public static final int OUTCOME_BASELINE = 664;

    /** One hand plate: 120×110, stacked three deep with a 6 px gap. */
    public static final int PLATE_W = 120;
    public static final int PLATE_H = 110;
    public static final int PLATE_GAP = 6;
    /** The 84×84 sprite slot drawn inside a plate. */
    public static final int SPRITE = 84;

    /** The left (P1) and right (P2) column origins. */
    public static final int LEFT_COLUMN_X = 540;
    public static final int RIGHT_COLUMN_X = 940;
    public static final int COLUMN_TOP = 306;
    public static final int COLUMN_HEIGHT = 3 * PLATE_H + 2 * PLATE_GAP;

    /** One clickable hand: the player, the hand number, and its plate. */
    public record Hand(String player, int hand, Chrome.Rect rect) {
    }

    /** The six plates, in paint order: P1's column then P2's. */
    public static final List<Hand> HANDS = List.of(
            plate(Match.BLUE, 1, LEFT_COLUMN_X, 0),
            plate(Match.BLUE, 2, LEFT_COLUMN_X, 1),
            plate(Match.BLUE, 3, LEFT_COLUMN_X, 2),
            plate(Match.BROWN, 1, RIGHT_COLUMN_X, 0),
            plate(Match.BROWN, 2, RIGHT_COLUMN_X, 1),
            plate(Match.BROWN, 3, RIGHT_COLUMN_X, 2));

    /** How one player's column is painted on the current phase. */
    public enum Column {
        /** No commitment yet: the three hands are live. */
        CHOOSING,
        /** Committed, choice still secret: the neutral {@code ¡Listo!} card. */
        READY,
        /** Both committed: the hands are shown and the chosen one is marked. */
        REVEALED
    }

    private Rps() {
    }

    private static Hand plate(String player, int hand, int x, int row) {
        return new Hand(player, hand,
                new Chrome.Rect(x, COLUMN_TOP + row * (PLATE_H + PLATE_GAP), PLATE_W, PLATE_H));
    }

    /** Whether the RPS surface is painted in this phase. */
    public static boolean shows(String phase) {
        return Match.RPS_SELECT.equals(phase) || Match.RPS_REVEAL.equals(phase);
    }

    /**
     * The column state for one player: choosing, ready, or revealed. {@code null}
     * outside the RPS phases, where the panel is not painted at all.
     */
    public static Column columnState(String phase, boolean committed) {
        if (Match.RPS_REVEAL.equals(phase)) {
            return Column.REVEALED;
        }
        if (Match.RPS_SELECT.equals(phase)) {
            return committed ? Column.READY : Column.CHOOSING;
        }
        return null;
    }

    /**
     * Whether a click on {@code player}'s column commits a hand
     * only while that player is still choosing.
     */
    public static boolean accepts(String phase, boolean committed) {
        return Match.RPS_SELECT.equals(phase) && !committed;
    }

    /** The hand under a virtual pixel, or {@code null}. */
    public static Hand handAt(int x, int y) {
        for (Hand hand : HANDS) {
            if (hand.rect().contains(x, y)) {
                return hand;
            }
        }
        return null;
    }

    /** The one column rectangle of a player, for the neutral ready card. */
    public static Chrome.Rect columnRect(String player) {
        int x = Match.BROWN.equals(player) ? RIGHT_COLUMN_X : LEFT_COLUMN_X;
        return new Chrome.Rect(x, COLUMN_TOP, PLATE_W, COLUMN_HEIGHT);
    }
}
