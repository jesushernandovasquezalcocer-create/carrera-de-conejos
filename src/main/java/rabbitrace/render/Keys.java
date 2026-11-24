package rabbitrace.render;

import rabbitrace.game.Match;

import java.util.Locale;

/**
 * The keyboard layer as a pure function (
 * ): {@code (screen, phase, key) → action}. It holds no Swing type
 * and no match state, so the whole key map is testable without a window; the
 * window shell translates a {@link java.awt.event.KeyEvent} into a {@link Key},
 * and the committed action is executed by the same controller the pointer and
 * the automation {@code press}/{@code key} commands use.
 *
 * <p>The map contains exactly the controls the game defines: the opening throw's
 * hand keys (P1 {@code 1}/{@code 2}/{@code 3}, P2 {@code 7}/{@code 8}/{@code 9}),
 * any-key skips for the skippable beats, {@code Space} to roll and to end a
 * turn, {@code WASD}/arrows for one orthogonal step, {@code Esc} for the
 * layer unwind, {@code Enter} for the Título seed field and Victoria's
 * {@code Revancha}, and {@code F11} for fullscreen. It invents
 * no focus ring, no tab order and no accelerator.
 */
public final class Keys {

    /** The physical keys the map understands; no AWT constants leak in. */
    public enum Kind {
        /** A printable character, carried by {@link Key#character()}. */
        CHARACTER,
        BACKSPACE,
        ENTER,
        ESCAPE,
        SPACE,
        F11,
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    /**
     * One key press. {@code CHARACTER} carries the typed character; every other
     * kind carries {@code '\0'} except {@link #space()}, which carries a space so
     * the Título field can type it.
     */
    public record Key(Kind kind, char character) {

        public static Key typed(char character) {
            return new Key(Kind.CHARACTER, character);
        }

        public static Key named(Kind kind) {
            return new Key(kind, '\0');
        }

        public static Key space() {
            return new Key(Kind.SPACE, ' ');
        }
    }

    /** What a key press means on the current surface. */
    public enum Action {
        /** Nothing on this screen or phase. */
        NONE,
        /** The Título seed field takes the character. */
        TYPE_CHARACTER,
        /** The Título seed field drops its last character. */
        BACKSPACE,
        /** {@code Enter} in the Título field activates {@code Nueva partida}. */
        NEW_MATCH,
        /** {@code Esc} in Partida freezes the match under Pausa. */
        PAUSE,
        /** {@code Esc} in Pausa resumes Partida. */
        RESUME,
        /** {@code Esc} in Ajustes returns to its opener. */
        CLOSE_AJUSTES,
        /** {@code Esc} in Confirmación is {@code Cancelar}. */
        CANCEL_CONFIRM,
        /** {@code Enter} on Victoria is {@code Revancha}. */
        REMATCH,
        /** The opening throw's P1 hand keys {@code 1}/{@code 2}/{@code 3}. */
        RPS_BLUE,
        /** The opening throw's P2 hand keys {@code 7}/{@code 8}/{@code 9}. */
        RPS_BROWN,
        /** {@code Space} in AwaitRoll rolls the die. */
        ROLL,
        /** {@code Space} in AwaitMove ends the turn. */
        END_TURN,
        MOVE_NORTH,
        MOVE_SOUTH,
        MOVE_EAST,
        MOVE_WEST,
        /** Any key that lands while a skippable beat is up finishes the beat. */
        SKIP_ANIMATION,
        /** {@code F11} toggles fullscreen from any screen. */
        FULLSCREEN
    }

    private Keys() {
    }

    /**
     * The action a key press takes on {@code screen} in {@code phase}
     * (state table and screens). {@code phase} is
     * {@code null} when no match is live.
     */
    public static Action action(String screen, String phase, Key key) {
        if (key == null || key.kind() == null) {
            return Action.NONE;
        }
        // Esc always unwinds one layer. It is read before the skip rule
        // so pausing is never swallowed as a skipped animation (skips a
        // running beat to completion as part of the pause, not instead of it).
        if (key.kind() == Kind.ESCAPE) {
            if (Match.PARTIDA.equals(screen)) {
                return Action.PAUSE;
            }
            if (Match.PAUSA.equals(screen)) {
                return Action.RESUME;
            }
            if (Match.AJUSTES.equals(screen)) {
                return Action.CLOSE_AJUSTES;
            }
            if (Match.CONFIRMACION.equals(screen)) {
                return Action.CANCEL_CONFIRM;
            }
            return Action.NONE;
        }
        // F11 toggles fullscreen from any screen.
        if (key.kind() == Kind.F11) {
            return Action.FULLSCREEN;
        }
        // the seed field is Título's input target. It consumes
        // typing and Backspace, and Enter in the field starts the match.
        if (Match.TITULO.equals(screen)) {
            if (key.kind() == Kind.BACKSPACE) {
                return Action.BACKSPACE;
            }
            if (key.kind() == Kind.ENTER) {
                return Action.NEW_MATCH;
            }
            if (key.kind() == Kind.SPACE) {
                return Action.TYPE_CHARACTER;
            }
            if (key.kind() == Kind.CHARACTER && isPrintable(key.character())) {
                return Action.TYPE_CHARACTER;
            }
            return Action.NONE;
        }
        // Enter on the visible win screen is Revancha. No other key acts
        // on Victoria: Menú and Ajustes are pointer controls.
        if (Match.VICTORIA.equals(screen)) {
            return key.kind() == Kind.ENTER ? Action.REMATCH : Action.NONE;
        }
        // a key consumed on an overlay never falls through to the match.
        // Only Esc acts on Pausa, Ajustes and Confirmación in this layer.
        if (!Match.PARTIDA.equals(screen)) {
            return Action.NONE;
        }

        // while a skippable beat is up, any key finishes it and is
        // consumed, so it never also acts on the state. The winning
        // hop is skippable too; the 400 ms beat that follows it is not, and is
        // advanced by the controller's clock rather than by a key.
        if (Match.RPS_REVEAL.equals(phase)
                || Match.ROLLING.equals(phase)
                || Match.NO_LEGAL_MOVE.equals(phase)
                || Match.HAND_OFF.equals(phase)
                || Match.MATCH_WON.equals(phase)) {
            return Action.SKIP_ANIMATION;
        }
        // the opening throw's hand keys are split by player and side.
        if (Match.RPS_SELECT.equals(phase)) {
            if (key.kind() != Kind.CHARACTER) {
                return Action.NONE;
            }
            int digit = key.character() - '0';
            if (digit >= 1 && digit <= 3) {
                return Action.RPS_BLUE;
            }
            if (digit >= 7 && digit <= 9) {
                return Action.RPS_BROWN;
            }
            return Action.NONE;
        }
        if (Match.AWAIT_ROLL.equals(phase)) {
            // the roll is the Tirar button or Space, never automatic.
            return key.kind() == Kind.SPACE ? Action.ROLL : Action.NONE;
        }
        if (Match.AWAIT_MOVE.equals(phase)) {
            // Space ends the turn after at least one step.
            if (key.kind() == Kind.SPACE) {
                return Action.END_TURN;
            }
            // WASD and the arrows take one orthogonal step each.
            return movement(key);
        }
        return Action.NONE;
    }

    /** The step a movement key names, or {@link Action#NONE}. */
    private static Action movement(Key key) {
        return switch (key.kind()) {
            case NORTH -> Action.MOVE_NORTH;
            case SOUTH -> Action.MOVE_SOUTH;
            case EAST -> Action.MOVE_EAST;
            case WEST -> Action.MOVE_WEST;
            case CHARACTER -> switch (Character.toUpperCase(key.character())) {
                case 'W' -> Action.MOVE_NORTH;
                case 'S' -> Action.MOVE_SOUTH;
                case 'A' -> Action.MOVE_WEST;
                case 'D' -> Action.MOVE_EAST;
                default -> Action.NONE;
            };
            default -> Action.NONE;
        };
    }

    /**
     * The key an automation {@code key <chord>} names:
     * {@code W A S D}, the arrows, {@code SPACE}, {@code ENTER}, {@code ESC},
     * {@code F11}, {@code BACKSPACE}, or a single printable character. Letters
     * stay characters rather than becoming movement here, because the screen
     * decides their meaning: {@code W} moves in AwaitMove and types on Título.
     *
     * @return the key, or {@code null} when the chord is not one the map names
     */
    public static Key chord(String chord) {
        if (chord == null) {
            return null;
        }
        String trimmed = chord.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String name = trimmed.toUpperCase(Locale.ROOT);
        switch (name) {
            case "SPACE":
                return Key.space();
            case "ENTER":
            case "RETURN":
                return Key.named(Kind.ENTER);
            case "ESC":
            case "ESCAPE":
                return Key.named(Kind.ESCAPE);
            case "BACKSPACE":
            case "BKSP":
                return Key.named(Kind.BACKSPACE);
            case "F11":
                return Key.named(Kind.F11);
            case "UP":
            case "ARROWUP":
            case "ARROW_UP":
                return Key.named(Kind.NORTH);
            case "DOWN":
            case "ARROWDOWN":
            case "ARROW_DOWN":
                return Key.named(Kind.SOUTH);
            case "LEFT":
            case "ARROWLEFT":
            case "ARROW_LEFT":
                return Key.named(Kind.WEST);
            case "RIGHT":
            case "ARROWRIGHT":
            case "ARROW_RIGHT":
                return Key.named(Kind.EAST);
            default:
                return trimmed.length() == 1 ? Key.typed(trimmed.charAt(0)) : null;
        }
    }

    private static boolean isPrintable(char character) {
        return character != '\0' && !Character.isISOControl(character);
    }
}
