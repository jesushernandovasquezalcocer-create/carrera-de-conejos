package rabbitrace.render;

import rabbitrace.game.Match;

import java.util.List;

/**
 * The surface geometry: every panel's rectangle and the
 * controls inside it, in virtual pixels.
 *
 * <p>The painter draws these rectangles and the pointer path hit-tests them, so a
 * panel that "lands on rectangle" is one source of truth rather than two
 * routines that happen to agree. Control names are the {@code press} names of
 * so a click and a named press reach the same action.
 */
public final class Screens {

    /** One focusable control: its action name and its rectangle. */
    public record Control(String name, Chrome.Rect rect) {
    }

    /** One screen surface: the panel it paints and its controls in paint order. */
    public record Panel(String screen, Chrome.Rect rect, List<Control> controls) {

        public Control control(String name) {
            for (Control control : controls) {
                if (control.name().equals(name)) {
                    return control;
                }
            }
            throw new IllegalArgumentException("screen " + screen + " has no control '" + name + "'");
        }
    }

    /** Título: `420, 150, 760, 600`. */
    public static final Panel TITULO = new Panel(Match.TITULO, new Chrome.Rect(420, 150, 760, 600),
            List.of(
                    new Control("field", new Chrome.Rect(560, 320, 180, 44)),
                    new Control("aleatoria", new Chrome.Rect(752, 320, 140, 44)),
                    new Control("jugar", new Chrome.Rect(620, 410, 360, 64)),
                    new Control("continuar", new Chrome.Rect(620, 490, 360, 64)),
                    new Control("ajustes", new Chrome.Rect(620, 570, 360, 64)),
                    new Control("salir", new Chrome.Rect(620, 650, 360, 64))));

    /** Ajustes: `360, 110, 880, 680`. */
    public static final Panel AJUSTES = new Panel(Match.AJUSTES, new Chrome.Rect(360, 110, 880, 680),
            List.of(
                    new Control("master", new Chrome.Rect(540, 248, 420, 24)),
                    new Control("music", new Chrome.Rect(540, 318, 420, 24)),
                    new Control("sfx", new Chrome.Rect(540, 388, 420, 24)),
                    new Control("muted", new Chrome.Rect(978, 457, 26, 26)),
                    new Control("fullscreen", new Chrome.Rect(978, 517, 26, 26)),
                    new Control("copiar", new Chrome.Rect(1000, 600, 120, 40)),
                    new Control("restablecer", new Chrome.Rect(400, 700, 200, 48)),
                    new Control("volver", new Chrome.Rect(1000, 700, 200, 48))));

    /** Pausa: `520, 240, 560, 420`. */
    public static final Panel PAUSA = new Panel(Match.PAUSA, new Chrome.Rect(520, 240, 560, 420),
            List.of(
                    new Control("reanudar", new Chrome.Rect(610, 360, 380, 56)),
                    new Control("ajustes", new Chrome.Rect(610, 432, 380, 56)),
                    new Control("menu", new Chrome.Rect(610, 504, 380, 56)),
                    new Control("salir", new Chrome.Rect(610, 576, 380, 56))));

    /** Victoria: `480, 260, 640, 380`. */
    public static final Panel VICTORIA = new Panel(Match.VICTORIA, new Chrome.Rect(480, 260, 640, 380),
            List.of(
                    new Control("revancha", new Chrome.Rect(500, 470, 260, 60)),
                    new Control("menu", new Chrome.Rect(840, 470, 260, 60)),
                    new Control("ajustes", new Chrome.Rect(700, 540, 200, 40))));

    /** Confirmación: `520, 320, 560, 260`. */
    public static final Panel CONFIRMACION = new Panel(Match.CONFIRMACION, new Chrome.Rect(520, 320, 560, 260),
            List.of(
                    new Control("cancelar", new Chrome.Rect(580, 500, 200, 48)),
                    new Control("confirmar", new Chrome.Rect(820, 500, 200, 48))));

    /** Every panel screen, in table order. */
    public static final List<Panel> PANELS = List.of(TITULO, AJUSTES, PAUSA, VICTORIA, CONFIRMACION);

    private Screens() {
    }

    /** The panel of a screen, or {@code null} for a screen with no panel. */
    public static Panel of(String screen) {
        for (Panel panel : PANELS) {
            if (panel.screen().equals(screen)) {
                return panel;
            }
        }
        return null;
    }

    /** The control under a virtual pixel of that screen, or {@code null}. */
    public static String controlAt(String screen, int x, int y) {
        Panel panel = of(screen);
        if (panel == null) {
            return null;
        }
        for (Control control : panel.controls()) {
            if (control.rect().contains(x, y)) {
                return control.name();
            }
        }
        return null;
    }
}
