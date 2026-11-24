package rabbitrace.render;

import rabbitrace.game.Board;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Fnv;
import rabbitrace.game.Match;
import rabbitrace.i18n.Strings;
import rabbitrace.settings.Settings;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure painter: same frame and same board seed produce the
 * same pixels, with no window, no clock, no thread and no file read. It draws in
 * virtual pixels only.
 *
 * <p>Placeholder art: the real slots come from the asset contract. Every label is
 * read from {@code strings_es.properties} — no user-visible text is written
 * here.
 */
public final class BoardPainter {

    public static final int WIDTH = TileMap.VIRTUAL_W;
    public static final int HEIGHT = TileMap.VIRTUAL_H;

    private static final Font HUD_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 22);
    /** The 140 px HUD action labels ({@code Terminar turno} is 14 characters). */
    private static final Font HUD_ACTION = new Font(Font.SANS_SERIF, Font.PLAIN, 18);
    /** The font ramp: 56 wordmark, 32 screen heading, 24 button, 18 body. */
    private static final Font WORDMARK = new Font(Font.SANS_SERIF, Font.BOLD, 56);
    private static final Font SCREEN_HEADING = new Font(Font.SANS_SERIF, Font.BOLD, 32);
    private static final Font BUTTON_LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
    private static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 18);

    private static final Color BORDER = new Color(0x24331d);
    private static final Color PANEL = new Color(20, 32, 15, 200);
    private static final Color TEXT = new Color(0xe9f2dd);
    /** The HUD {@code Tirar} plate when rolling is legal, and when it is not. */
    private static final Color TIRAR_READY = new Color(0x3f6b34);
    private static final Color TIRAR_OFF = new Color(0x2a3226);
    /** The Título panel: parchment-toned, 24 px corners, over a 45% veil. */
    private static final Color PARCHMENT = new Color(0xefe3c4);
    private static final Color PARCHMENT_EDGE = new Color(0x8a6a3a);
    private static final Color INK = new Color(0x3b2a17);
    private static final Color FIELD = new Color(0xf7efd9);
    private static final Color BUTTON_OFF = new Color(0xc9bda3);
    private static final Color INK_OFF = new Color(0x8a7f6a);
    private static final Color FOCUS = new Color(0x2f5fb8);
    /** inline field error, at y≈375; the copy comes from the bundle. */
    private static final Color ERROR = new Color(0xa83232);
    private static final Color VEIL_45 = new Color(0, 0, 0, 115);
    /** Pausa and Victoria dim the board 55%; Confirmación adds 35%. */
    private static final Color VEIL_55 = new Color(0, 0, 0, 140);
    private static final Color VEIL_35 = new Color(0, 0, 0, 89);

    /** Ajustes volume rows; the panel and buttons come from {@link Screens}. */
    private static final int AJUSTES_LABEL_X = 400;
    private static final int AJUSTES_SLIDER_X = 540;
    private static final int AJUSTES_SLIDER_W = 420;
    private static final int AJUSTES_VALUE_X = 980;
    private static final int[] AJUSTES_VOLUME_Y = {260, 330, 400};
    private static final String[] AJUSTES_VOLUME_KEYS = {
            "ajustes.master", "ajustes.music", "ajustes.sfx"};
    private static final Color GRASS = new Color(0x6f9440);
    private static final Color DIRT = new Color(0x8d6b3f);
    /** The two ground fields; the planner crops each. */
    private static final String GROUND_GRASS = "ground.grass.field";
    private static final String GROUND_DIRT = "ground.dirt.field";
    private static final Color OBSTACLE = new Color(0x2f4a2b);
    /** `bark` family: the placeholder fence's rails and knot. */
    private static final Color FENCE_WOOD = new Color(0x8a6a3a);
    private static final Color FENCE_KNOT = new Color(0x4f3e2b);
    private static final Color BLUE_RABBIT = new Color(0x3f6fd0);
    private static final Color BROWN_RABBIT = new Color(0x8a5a2b);
    private static final Color CARROT = new Color(0xe8722b);
    /** The veil on a tile a click cannot legally reach this turn. */
    private static final Color UNTARGETABLE = new Color(0x1c, 0x24, 0x18, 96);

    /**
     * The {@code HandOff} / {@code NoLegalMove} banner:
     * a parchment plate centred over the board, 560x120 at (520, 390). It is a
     * transient beat, so it covers tiles for its phase only; any key or click
     * finishes it through the same skip path as the other skippable beats.
     */
    static final Chrome.Rect BANNER = new Chrome.Rect(520, 390, 560, 120);

    /** The frame's plan while painting; buttons read their manifest 9-slice from it. */
    private RenderPlan renderPlan;

    /** Renders one frame offscreen: the headless path and the frame command's path. */
    public static java.awt.image.BufferedImage render(Frame f) {
        return render(f, false);
    }

    /**
     * The live window's pixels: the same board plate {@link #render} draws plus
     * the surfaces the window owns — currently the opening throw's panel
     * The panel is drawn by this class at virtual
     * coordinates, so the window scales it exactly like the board.
     *
     * <p>It is not part of {@link #render} on purpose: the offline {@code frame}
     * probe is the board evidence the ground and fence checks read pixels
     * from, and an opaque centred panel would cover
     * those very pixels. The probe therefore renders the plate, and the live
     * surface adds the panel. When the RPS surface gets its own check,
     * the probe and the panel should meet in one frame; that is a separate change,
     * not something this one may decide by weakening an existing check.
     */
    public static java.awt.image.BufferedImage renderScreen(Frame f) {
        return render(f, true);
    }

    private static java.awt.image.BufferedImage render(Frame f, boolean screen) {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(WIDTH, HEIGHT, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            new BoardPainter().paint(g, f, screen);
        } finally {
            g.dispose();
        }
        return image;
    }

    public void paint(Graphics2D g, Frame f) {
        paint(g, f, false);
    }

    /**
     * @param screen true for the live window, which also paints the surfaces the
     *     offline {@code frame} probe deliberately omits (see
     *     {@link #renderScreen})
     */
    private void paint(Graphics2D g, Frame f, boolean screen) {
        renderPlan = plan(f);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(BORDER);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        paintBorderProps(g);

        Board board = f.board();
        // every sprite is drawn inside the rectangle allows, and the
        // same list is what the dev-only `sprites` probe reports.
        List<SpriteDraw> sprites = spriteDraws(f);
        for (SpriteDraw sprite : sprites) {
            if (sprite.border()) {
                paintSprite(g, f, sprite);
            }
        }
        paintGround(g, f, board);
        paintGrid(g, board);
        if (plan(f).sprites()) {
            for (SpriteDraw sprite : sprites) {
                if (!sprite.border()) {
                    paintSprite(g, f, sprite);
                }
            }
        }

        // Título is the plate under a 45% veil with its own panel,
        // not the Partida HUD. Confirmación is a panel over Título, and
        // Ajustes is a panel over whichever screen opened it.
        String base = screenUnder(f);
        if (Match.TITULO.equals(base)) {
            paintTitulo(g, f);
        } else {
            paintUntargetable(g, f, board);
            paintHighlight(g, f);
            paintHud(g, f);
            // the hand-off and no-move banners sit above the
            // board and HUD but below Pausa, Victoria and Ajustes.
            paintBanner(g, f);
            // the opening throw's panel sits above the board and
            // HUD but below Pausa, Victoria and Ajustes, so those overlays still
            // cover it. Only the live surface draws it; see renderScreen.
            if (screen && Rps.shows(f.phase())) {
                paintRps(g, f);
            }

            // Pausa is the frozen Partida surface under a 55% veil, not the
            // bare board. The match underneath is unchanged, so the surface
            // is drawn over exactly the frame pause left behind.
            if (Match.PAUSA.equals(base)) {
                paintPausa(g);
            }
            // Victoria is the win overlay over the frozen board,
            // dimmed 55% like Pausa. It is also what Ajustes covers when opened
            // from the win screen.
            if (Match.VICTORIA.equals(base)) {
                paintVictoria(g, f);
            }
        }

        if (Match.CONFIRMACION.equals(f.screen())) {
            paintConfirmacion(g);
        }
        if (Match.AJUSTES.equals(f.screen())) {
            paintAjustes(g, f);
        }
    }

    /**
     * The screen painted under the current one. Confirmación always covers
     * Título; Ajustes covers the screen it was opened from.
     */
    private static String screenUnder(Frame f) {
        if (Match.CONFIRMACION.equals(f.screen())) {
            return Match.TITULO;
        }
        if (Match.AJUSTES.equals(f.screen())) {
            return f.opener() == null ? Match.PARTIDA : f.opener();
        }
        return f.screen();
    }

    /**
     * The Ajustes surface: its opener dimmed (45%
     * over Título, 55% over the board), then the 880×680 parchment panel with the
     * heading, the three volume rows, the two switches, the read-only seed and the
     * {@code Restablecer} / {@code Volver} pair. Initial focus is {@code Volver}
     * so it carries the 3 px focus ring. It is painted in the canvas —
     * bans native dialogs — so a captured frame carries the whole surface.
     */
    private void paintAjustes(Graphics2D g, Frame f) {
        g.setColor(Match.TITULO.equals(f.opener()) ? VEIL_45 : VEIL_55);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        Chrome.Rect panel = Screens.AJUSTES.rect();
        panel(g, panel);

        g.setFont(SCREEN_HEADING);
        g.setColor(INK);
        drawCentred(g, Strings.text("ajustes.heading"), panel.x(), panel.y() + 70, panel.w());

        Settings settings = f.settings() == null ? Settings.defaults() : f.settings();
        int[] values = {settings.master(), settings.music(), settings.sfx()};
        for (int i = 0; i < AJUSTES_VOLUME_Y.length; i++) {
            sliderRow(g, AJUSTES_VOLUME_KEYS[i], values[i], AJUSTES_VOLUME_Y[i]);
        }
        switchRow(g, "ajustes.mute", settings.muted(), 470);
        switchRow(g, "ajustes.fullscreen", settings.fullscreen(), 530);

        // Read-only seed and its generator version, with Copiar at x 1000.
        g.setFont(BODY);
        g.setColor(INK);
        g.drawString(Strings.text("ajustes.seed"), AJUSTES_LABEL_X, 628);
        g.drawString(f.seed() + "   ·   " + Strings.text("hud.generation") + " "
                + BoardGenerator.VERSION, 560, 628);
        button(g, Screens.AJUSTES.control("copiar").rect(), Strings.text("ajustes.copy"), true, false);

        button(g, Screens.AJUSTES.control("restablecer").rect(), Strings.text("ajustes.reset"), true, false);
        button(g, Screens.AJUSTES.control("volver").rect(), Strings.text("ajustes.back"), true, true);
    }

    /** One volume row: label, 420 px slider and the value beside it. */
    private void sliderRow(Graphics2D g, String labelKey, int value, int y) {
        g.setFont(BODY);
        g.setColor(INK);
        g.drawString(Strings.text(labelKey), AJUSTES_LABEL_X, y + 6);

        g.setColor(BUTTON_OFF);
        g.fillRoundRect(AJUSTES_SLIDER_X, y - 4, AJUSTES_SLIDER_W, 8, 8, 8);
        g.setColor(INK);
        g.fillRoundRect(AJUSTES_SLIDER_X, y - 4,
                Math.max(1, Math.round(AJUSTES_SLIDER_W * value / 100f)), 8, 8, 8);
        g.setColor(PARCHMENT_EDGE);
        int knobX = AJUSTES_SLIDER_X + Math.round((AJUSTES_SLIDER_W - 18) * value / 100f);
        g.fillOval(knobX, y - 9, 18, 18);

        g.setFont(BODY);
        g.setColor(INK);
        g.drawString(Integer.toString(value), AJUSTES_VALUE_X, y + 6);
    }

    /** One switch row: label with a checkbox at the value column. */
    private void switchRow(Graphics2D g, String labelKey, boolean on, int y) {
        g.setFont(BODY);
        g.setColor(INK);
        g.drawString(Strings.text(labelKey), AJUSTES_LABEL_X, y + 6);

        g.setColor(FIELD);
        g.fillRoundRect(AJUSTES_VALUE_X, y - 11, 22, 22, 6, 6);
        g.setColor(PARCHMENT_EDGE);
        g.drawRoundRect(AJUSTES_VALUE_X, y - 11, 22, 22, 6, 6);
        if (on) {
            g.setColor(INK);
            g.fillRoundRect(AJUSTES_VALUE_X + 5, y - 6, 12, 12, 3, 3);
        }
    }

    /**
     * The Pausa surface: the frozen board dimmed
     * 55%, then the 560×420 parchment panel with the heading and the four
     * buttons at their specified coordinates. Initial focus is {@code Reanudar}
     * so it carries the 3 px focus ring.
     */
    private void paintPausa(Graphics2D g) {
        g.setColor(VEIL_55);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        Chrome.Rect panel = Screens.PAUSA.rect();
        panel(g, panel);

        g.setFont(SCREEN_HEADING);
        g.setColor(INK);
        drawCentred(g, Strings.text("pausa.heading"), panel.x(), panel.y() + 70, panel.w());

        String[] names = {"reanudar", "ajustes", "menu", "salir"};
        String[] keys = {"pausa.resume", "pausa.settings", "pausa.menu", "pausa.quit"};
        for (int i = 0; i < names.length; i++) {
            button(g, Screens.PAUSA.control(names[i]).rect(), Strings.text(keys[i]), true, i == 0);
        }
    }

    /**
     * The Victoria surface: the frozen
     * board dimmed 55%, then the 640×380 parchment panel naming the winner, the
     * seed and generator version, the {@code Revancha} / {@code Menú} pair at
     * their specified coordinates, and the {@code Ajustes} text link. Initial
     * focus is {@code Revancha}, so it carries the 3 px focus ring.
     */
    private void paintVictoria(Graphics2D g, Frame f) {
        g.setColor(VEIL_55);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        Chrome.Rect panel = Screens.VICTORIA.rect();
        panel(g, panel);

        // The winner line at 32 px, then the seed and generator version.
        g.setFont(SCREEN_HEADING);
        g.setColor(INK);
        drawCentred(g, winnerLine(f.active()), panel.x(), panel.y() + 70, panel.w());

        g.setFont(BODY);
        drawCentred(g, Strings.text("ajustes.seed") + " " + f.seed()
                + "   ·   " + Strings.text("hud.generation") + " " + BoardGenerator.VERSION,
                panel.x(), panel.y() + 120, panel.w());

        button(g, Screens.VICTORIA.control("revancha").rect(),
                Strings.text("victoria.rematch"), true, true);
        button(g, Screens.VICTORIA.control("menu").rect(),
                Strings.text("victoria.menu"), true, false);

        Chrome.Rect link = Screens.VICTORIA.control("ajustes").rect();
        g.setFont(BODY);
        g.setColor(INK);
        drawCentred(g, Strings.text("victoria.settings"), link.x(), link.y() + 20, link.w());
    }

    /** the winner line names the player in Spanish. */
    private static String winnerLine(String active) {
        return "brown".equals(active)
                ? Strings.text("victoria.winBrown") : Strings.text("victoria.winBlue");
    }

    /**
     * The Confirmación surface: its opener dimmed
     * a further 35%, then the 560×260 parchment panel with the question line and
     * the two 200×48 buttons. Initial focus is {@code Cancelar}, so it
     * carries the 3 px focus ring. The opener is Título — makes
     * {@code Nueva partida} with a paused match the only way in — and
     * {@link #paint} has already drawn it.
     */
    private void paintConfirmacion(Graphics2D g) {
        g.setColor(VEIL_35);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        Chrome.Rect panel = Screens.CONFIRMACION.rect();
        panel(g, panel);

        // The question line at 18 px, y≈380.
        g.setFont(BODY);
        g.setColor(INK);
        drawCentred(g, Strings.text("confirmacion.question"), panel.x(), 380, panel.w());

        button(g, Screens.CONFIRMACION.control("cancelar").rect(),
                Strings.text("confirmacion.cancel"), true, true);
        button(g, Screens.CONFIRMACION.control("confirmar").rect(),
                Strings.text("confirmacion.confirm"), true, false);
    }

    /**
     * The Título surface: the plate dimmed 45%, then the
     * 760×600 parchment panel with the wordmark, the seed row and the four
     * buttons at their specified coordinates. {@code Continuar} is enabled only
     * while a paused match exists — on Título that is exactly when
     * {@code phase} is not null.
     */
    private void paintTitulo(Graphics2D g, Frame f) {
        g.setColor(VEIL_45);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        Chrome.Rect panel = Screens.TITULO.rect();
        panel(g, panel);

        // Wordmark at 56 px, y≈250.
        g.setFont(WORDMARK);
        g.setColor(INK);
        drawCentred(g, Strings.text("app.title"), panel.x(), panel.y() + 100, panel.w());

        // Seed row at y≈320: label, 180×44 field, 140×44 Aleatoria. An
        // invalid value is shown as typed, with the message at y≈375.
        Chrome.Rect field = Screens.TITULO.control("field").rect();
        g.setFont(BODY);
        g.setColor(INK);
        g.drawString(Strings.text("title.seed"), panel.x() + 50, field.y() + 28);
        field(g, field, f.seedField());
        button(g, Screens.TITULO.control("aleatoria").rect(),
                Strings.text("title.random"), true, false);
        if (!f.seedValid()) {
            g.setFont(BODY);
            g.setColor(ERROR);
            drawCentred(g, Strings.text("title.seedInvalid"), panel.x(), 392, panel.w());
        }

        // 360×64 buttons at x 620, y 410 / 490 / 570 / 650. Initial focus is
        // Nueva partida, so it carries the 3 px focus ring.
        // disables it until the field holds a valid seed.
        String[] names = {"jugar", "continuar", "ajustes", "salir"};
        String[] keys = {"title.play", "title.continue", "title.settings", "title.quit"};
        boolean continuarEnabled = f.phase() != null;
        for (int i = 0; i < names.length; i++) {
            boolean enabled = i == 0 ? f.seedValid() : i != 1 || continuarEnabled;
            button(g, Screens.TITULO.control(names[i]).rect(),
                    Strings.text(keys[i]), enabled, i == 0);
        }
    }

    /** A text field of the Título panel, 24 px label included. */
    private void field(Graphics2D g, Chrome.Rect r, String value) {
        g.setColor(FIELD);
        g.fill(new RoundRectangle2D.Double(r.x(), r.y(), r.w(), r.h(), 12, 12));
        g.setColor(PARCHMENT_EDGE);
        g.draw(new RoundRectangle2D.Double(r.x(), r.y(), r.w(), r.h(), 12, 12));
        g.setFont(BUTTON_LABEL);
        g.setColor(INK);
        g.drawString(value, r.x() + 16, r.y() + (r.h() + g.getFontMetrics().getAscent()) / 2 - 5);
    }

    /** The parchment plate: 24 px corners under a 2 px edge. */
    private static void panel(Graphics2D g, Chrome.Rect r) {
        g.setColor(PARCHMENT);
        g.fill(new RoundRectangle2D.Double(r.x(), r.y(), r.w(), r.h(), 24, 24));
        g.setColor(PARCHMENT_EDGE);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(r.x(), r.y(), r.w(), r.h(), 24, 24));
        g.setStroke(new BasicStroke(1f));
    }

    /**
     * A panel button; {@code focused} adds the 3 px focus ring of /.
     * The plate is the {@code ui.button.*} slot drawn through 9-slice
     * path, so its corners are 1:1 at any layout size and a disabled button is
     * the same slot's {@code ui.button.disabled} state.
     */
    private void button(Graphics2D g, Chrome.Rect r, String label, boolean enabled, boolean focused) {
        NineSlice.drawButton(g, renderPlan,
                enabled ? NineSlice.BUTTON_NORMAL : NineSlice.BUTTON_DISABLED,
                r.x(), r.y(), r.w(), r.h());
        g.setFont(BUTTON_LABEL);
        g.setColor(enabled ? INK : INK_OFF);
        drawCentred(g, label, r.x(), r.y() + (r.h() + g.getFontMetrics().getAscent()) / 2 - 5, r.w());
        if (focused) {
            g.setColor(FOCUS);
            g.setStroke(new BasicStroke(3f));
            g.draw(new RoundRectangle2D.Double(r.x() - 4, r.y() - 4, r.w() + 8, r.h() + 8, 20, 20));
            g.setStroke(new BasicStroke(1f));
        }
    }

    private static void drawCentred(Graphics2D g, String text, int x, int baselineY, int w) {
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, x + (w - width) / 2, baselineY);
    }

    /**
     * one crop origin per field, then every tile samples
     * the same continuous plate at {@code (ox + 168·col, oy + 160·row)} with the
     * field's declared {@code sample} window. The tone comes from the source
     * coordinates, never from the tile index, so adjacent tiles read neighbouring
     * points and the plate draws no lattice.: the stride is the sample
     * window, not the virtual tile, because the source is at 2×.
     */
    private void paintGround(Graphics2D g, Frame f, Board board) {
        List<GroundSample> samples = groundSamples(f);
        GroundSample grass = samples.get(0);
        GroundSample dirt = samples.get(1);
        RenderPlan plan = plan(f);
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                boolean isDirt = board.dirtAt(col, row);
                GroundSample field = isDirt ? dirt : grass;
                int level = RenderHash.shade(plan.slotSetVersion(), plan.genVersion(), f.seed(),
                        field.slot(),
                        field.originX() + field.strideX() * col,
                        field.originY() + field.strideY() * row);
                g.setColor(shift(isDirt ? DIRT : GRASS, level));
                g.fillRect(TileMap.tileX(col), TileMap.tileY(row), TileMap.TILE_W, TileMap.TILE_H);
            }
        }
    }

    /**
     * The two ground fields the board samples, with their declared source size,
     * declared per-tile {@code sample} window and chosen crop origin. The painter
     * reads the same values, so the dev-only {@code sprites} probe reports the
     * sampling the pixels come from.
     */
    public static List<GroundSample> groundSamples(Frame f) {
        RenderPlan plan = plan(f);
        List<GroundSample> samples = new ArrayList<>();
        for (String field : new String[]{GROUND_GRASS, GROUND_DIRT}) {
            int[] size = plan.size(field);
            int[] sample = plan.sample(field);
            int[] origin = crop(f, field);
            samples.add(new GroundSample(field,
                    size == null ? 0 : size[0],
                    size == null ? 0 : size[1],
                    sample == null ? 0 : sample[0],
                    sample == null ? 0 : sample[1],
                    origin[0], origin[1]));
        }
        return samples;
    }

    /** The field's one crop origin: the manifest's, or geometry without one. */
    private static int[] crop(Frame f, String fieldId) {
        RenderPlan plan = plan(f);
        int[] origin = plan.cropOrigin(fieldId);
        if (origin == null) {
            origin = RenderHash.cropOrigin(plan.slotSetVersion(), plan.genVersion(), f.seed(), fieldId,
                    RenderPlan.FIELD_WIDTH, RenderPlan.FIELD_HEIGHT);
        }
        // dev-only probe: the same sampling, moved by a source-pixel
        // offset, so a scenario can see the 168x160 stride in the pixels.
        return new int[]{origin[0] + plan.groundShiftX(), origin[1] + plan.groundShiftY()};
    }

    /**
     * How many {@code deco.*} sprites the painter would draw for this frame. The
     * dev-only {@code deco} probe reports it, so a scenario can prove the layer is
     * on or off without reading pixels.
     */
    public static int decorationCount(Frame f) {
        return decorations(f).size();
    }

    /**
     * The decoration layer: one render-plan choice per
     * eligible tile. keeps it off dirt and out of the three
     * clearings, at most one per tile, so the list is what the layer is.
     */
    private static List<Decoration> decorations(Frame f) {
        Board board = f.board();
        RenderPlan plan = plan(f);
        List<Decoration> chosen = new ArrayList<>();
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (board.dirtAt(col, row) || inClearing(col, row)) {
                    continue;
                }
                String slot = plan.decorationAt(col, row);
                if (slot != null) {
                    chosen.add(new Decoration(slot, TileMap.tileCentreX(col),
                            TileMap.tileY(row) + TileMap.TILE_H - 10));
                }
            }
        }
        return chosen;
    }

    /** One decoration and the point it stands on. */
    private record Decoration(String slot, int cx, int baseY) {
    }

    /** The 3×3 clearings of around both starts and the carrot. */
    private static boolean inClearing(int col, int row) {
        return near(col, row, BoardGenerator.BLUE_START)
                || near(col, row, BoardGenerator.BROWN_START)
                || near(col, row, new int[]{BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW});
    }

    private static boolean near(int col, int row, int[] cell) {
        return Math.abs(col - cell[0]) <= 1 && Math.abs(row - cell[1]) <= 1;
    }

    /** One placeholder decoration: the chosen variant is what the frame shows. */
    private static void paintDecoration(Graphics2D g, String slot, int cx, int baseY) {
        boolean second = slot.endsWith(".b");
        if (slot.startsWith("deco.flower")) {
            g.setColor(new Color(0x3d5c33));
            g.setStroke(new BasicStroke(2f));
            g.drawLine(cx, baseY, cx, baseY - 18);
            g.setColor(second ? new Color(0xd8a93a) : new Color(0xf2e2a8));
            double radius = second ? 4.0 : 3.4;
            g.fill(new Ellipse2D.Double(cx - radius - 3.5, baseY - 25, radius * 2, radius * 2));
            g.fill(new Ellipse2D.Double(cx + 3.5 - radius, baseY - 25, radius * 2, radius * 2));
            g.fill(new Ellipse2D.Double(cx - radius, baseY - 30, radius * 2, radius * 2));
            g.fill(new Ellipse2D.Double(cx - radius, baseY - 20, radius * 2, radius * 2));
            g.setStroke(new BasicStroke(1f));
        } else if (slot.startsWith("deco.tuft")) {
            g.setColor(second ? new Color(0x3d5c33) : new Color(0x4f7a38));
            g.setStroke(new BasicStroke(2f));
            int height = second ? 16 : 13;
            g.drawLine(cx - 5, baseY, cx - 9, baseY - height);
            g.drawLine(cx, baseY, cx, baseY - height - 3);
            g.drawLine(cx + 5, baseY, cx + 9, baseY - height + 2);
            g.setStroke(new BasicStroke(1f));
        } else {
            g.setColor(second ? new Color(0x8a8378) : new Color(0xa39a8c));
            g.fill(new Ellipse2D.Double(cx - 11, baseY - 7, 14, 7));
            g.fill(new Ellipse2D.Double(cx + 2, baseY - 4, 9, 5));
        }
    }

    private void paintGrid(Graphics2D g, Board board) {
        g.setColor(new Color(255, 255, 255, 26));
        for (int col = 0; col <= board.cols(); col++) {
            int x = TileMap.tileX(col);
            g.drawLine(x, TileMap.ORIGIN_Y, x, TileMap.ORIGIN_Y + TileMap.TILE_H * board.rows());
        }
        for (int row = 0; row <= board.rows(); row++) {
            int y = TileMap.tileY(row);
            g.drawLine(TileMap.ORIGIN_X, y, TileMap.ORIGIN_X + TileMap.TILE_W * board.cols(), y);
        }
    }

    /**
     * Every sprite the painter draws for this frame, in paint order, each with the
     * declared source size, the declared virtual footprint and the destination
     * rectangle it is clipped to.
     *
     * <p>The painter consumes this list, and the dev-only {@code sprites} probe
     * reports it, so the measured rectangle is the one the pixels came from. A
     * slot's source is 2× its footprint, so drawing at the footprint is the
     * {@code footprint / 2} source scale and no draw may exceed it.
     */
    public static List<SpriteDraw> spriteDraws(Frame f) {
        RenderPlan plan = plan(f);
        Board board = f.board();
        List<SpriteDraw> sprites = new ArrayList<>();
        sprites.add(topLeft(plan, "border.plate", 0, 0));
        for (Decoration decoration : decorations(f)) {
            sprites.add(bottomCentred(plan, decoration.slot(), decoration.cx(), decoration.baseY()));
        }
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (!board.blocking(col, row)) {
                    continue;
                }
                String slot = propSlot(plan, board, col, row);
                sprites.add(bottomCentred(plan, slot,
                        TileMap.tileCentreX(col), TileMap.tileY(row) + TileMap.TILE_H,
                        fenceTransforms(plan, board, col, row, slot)));
            }
        }
        int carrotX = TileMap.tileCentreX(f.carrot()[0]);
        int carrotBottom = TileMap.tileY(f.carrot()[1]) + TileMap.TILE_H;
        sprites.add(centred(plan, "goal.carrot.glow", carrotX, carrotBottom - TileMap.TILE_H / 2));
        sprites.add(bottomCentred(plan, "goal.carrot.base", carrotX, carrotBottom));
        sprites.add(bottomCentred(plan, rabbitSlot(f, Match.BLUE),
                TileMap.tileCentreX(f.blue()[0]), TileMap.tileY(f.blue()[1]) + TileMap.TILE_H));
        sprites.add(bottomCentred(plan, rabbitSlot(f, Match.BROWN),
                TileMap.tileCentreX(f.brown()[0]), TileMap.tileY(f.brown()[1]) + TileMap.TILE_H));
        return sprites;
    }

    /**
     * One drawn sprite. {@code w}/{@code h} are the destination rectangle in
     * virtual pixels: the footprint, never the 2× source. The scale
     * fields are the destination against the source, so {@code 0.5} is
     * one declared source scale.
     */
    public record SpriteDraw(String slot, int sizeW, int sizeH,
                             int footprintW, int footprintH,
                             int x, int y, int w, int h,
                             List<String> declared, List<String> applied) {

        /** True for the layer-1 border plate, painted before the ground. */
        public boolean border() {
            return "border.plate".equals(slot);
        }

        public double scaleX() {
            return sizeW <= 0 ? 0.0 : (double) w / sizeW;
        }

        public double scaleY() {
            return sizeH <= 0 ? 0.0 : (double) h / sizeH;
        }

        /** The quarter turns applied to the glyph. */
        public int angle() {
            return Transforms.angle(applied);
        }
    }

    /**
     * One ground field's sampling: its declared source size, its declared
     * per-tile {@code sample} window and the crop origin this match picked. The
     * declarations are reported as declared — {@code 0} when the field omits one
     * — while the painter's stride falls back to 2× the virtual tile, so a
     * missing declaration is visible to the probe.
     */
    public record GroundSample(String slot, int sizeW, int sizeH, int sampleW, int sampleH,
                               int originX, int originY) {

        /** The source-pixel stride from one tile to the next, in x. */
        public int strideX() {
            return sampleW > 0 ? sampleW : TileMap.TILE_W * 2;
        }

        /** The source-pixel stride from one tile to the next, in y. */
        public int strideY() {
            return sampleH > 0 ? sampleH : TileMap.TILE_H * 2;
        }

        public int tiles() {
            return TileMap.COLS * TileMap.ROWS;
        }

        public int lastX() {
            return originX + strideX() * (TileMap.COLS - 1);
        }

        public int lastY() {
            return originY + strideY() * (TileMap.ROWS - 1);
        }
    }

    /** A sprite placed at the top-left of its footprint ({@code anchor [0,0]}). */
    private static SpriteDraw topLeft(RenderPlan plan, String slot, int x, int y) {
        int[] footprint = footprint(plan, slot);
        int[] size = sourceSize(plan, slot, footprint);
        return new SpriteDraw(slot, size[0], size[1], footprint[0], footprint[1],
                x, y, footprint[0], footprint[1], plan.transforms(slot), List.of());
    }

    /** A sprite anchored bottom-centre on a tile ({@code anchor [0.5,1.0]}). */
    private static SpriteDraw bottomCentred(RenderPlan plan, String slot, int cx, int bottomY) {
        return bottomCentred(plan, slot, cx, bottomY, List.of());
    }

    /** The same, with the transforms lets the painter apply to this slot. */
    private static SpriteDraw bottomCentred(RenderPlan plan, String slot, int cx, int bottomY,
                                            List<String> applied) {
        int[] footprint = footprint(plan, slot);
        int[] size = sourceSize(plan, slot, footprint);
        return new SpriteDraw(slot, size[0], size[1], footprint[0], footprint[1],
                cx - footprint[0] / 2, bottomY - footprint[1], footprint[0], footprint[1],
                plan.transforms(slot), applied);
    }

    /** A sprite anchored at its centre ({@code anchor [0.5,0.5]}). */
    private static SpriteDraw centred(RenderPlan plan, String slot, int cx, int cy) {
        int[] footprint = footprint(plan, slot);
        int[] size = sourceSize(plan, slot, footprint);
        return new SpriteDraw(slot, size[0], size[1], footprint[0], footprint[1],
                cx - footprint[0] / 2, cy - footprint[1] / 2, footprint[0], footprint[1],
                plan.transforms(slot), List.of());
    }

    /**
     * The transforms applies to one prop: a fence segment in a column run
     * turns a quarter turn ({@code rot90}) and takes {@code flipX} for variety; a
     * horizontal run needs neither. Anything else is drawn as declared, with no
     * transform. Choosing by the neighbouring fence tiles, not by the mood of the
     * painter, is what makes "along a column" a fact the pixels can be read for
     *
     */
    private static List<String> fenceTransforms(RenderPlan plan, Board board, int col, int row,
                                                String slot) {
        if (!slot.startsWith("prop.fence.")) {
            return List.of();
        }
        boolean vertical = fenceAt(board, col, row - 1) || fenceAt(board, col, row + 1);
        if (!vertical) {
            return List.of();
        }
        List<String> applied = new ArrayList<>();
        applied.add(Transforms.ROT90);
        long h = RenderHash.variantHash(plan.slotSetVersion(), plan.genVersion(), plan.seed(),
                "prop.fence", col, row);
        if ((h & 1L) == 1L) {
            applied.add(Transforms.FLIP_X);
        }
        return List.copyOf(applied);
    }

    private static boolean fenceAt(Board board, int col, int row) {
        return board.inside(col, row) && board.kindAt(col, row) == Board.FENCE;
    }

    /**
     * The slot's declared footprint, or the placeholder geometry when the
     * run has no manifest. The fallback is the same size the placeholder draws.
     */
    private static int[] footprint(RenderPlan plan, String slot) {
        int[] declared = plan.footprint(slot);
        if (declared != null) {
            return declared;
        }
        if ("border.plate".equals(slot)) {
            return new int[]{WIDTH, HEIGHT};
        }
        if ("goal.carrot.glow".equals(slot)) {
            return new int[]{168, 168};
        }
        if ("goal.carrot.base".equals(slot)) {
            return new int[]{84, 120};
        }
        if ("shadow.rabbit".equals(slot)) {
            return new int[]{63, 36};
        }
        if ("shadow.prop".equals(slot)) {
            return new int[]{84, 48};
        }
        if (slot.startsWith("deco.tuft.")) {
            return new int[]{42, 36};
        }
        if (slot.startsWith("deco.pebble.")) {
            return new int[]{42, 30};
        }
        if (slot.startsWith("deco.")) {
            return new int[]{42, 42};
        }
        if (slot.startsWith("rabbit.")) {
            return new int[]{84, 96};
        }
        if ("prop.stump.a".equals(slot) || "prop.fence.post".equals(slot)) {
            return new int[]{84, 96};
        }
        if ("prop.fence.segment".equals(slot)) {
            return new int[]{84, 88};
        }
        return new int[]{84, 120};
    }

    /** The slot's declared source size, or 2× its footprint when it declares none. */
    private static int[] sourceSize(RenderPlan plan, String slot, int[] footprint) {
        int[] declared = plan.size(slot);
        return declared != null ? declared : new int[]{footprint[0] * 2, footprint[1] * 2};
    }

    /** The prop slot a blocking tile maps to. */
    private static String propSlot(RenderPlan plan, Board board, int col, int row) {
        return switch (board.kindAt(col, row)) {
            case Board.ROCK -> variantOr(plan, "prop.rock", col, row, "prop.rock.a");
            case Board.BUSH -> variantOr(plan, "prop.bush", col, row, "prop.bush.a");
            case Board.STUMP -> "prop.stump.a";
            case Board.FENCE -> "prop.fence.segment";
            default -> "prop.rock.a";
        };
    }

    private static String variantOr(RenderPlan plan, String group, int col, int row, String fallback) {
        String variant = plan.variant(group, col, row);
        return variant == null ? fallback : variant;
    }

    /** The rabbit slot for a player: idle, or its win/lose face. */
    private static String rabbitSlot(Frame f, String player) {
        String result = "idle.n";
        if (Match.MATCH_WON.equals(f.phase())) {
            result = player.equals(f.active()) ? "win" : "lose";
        }
        return "rabbit." + player + "." + result;
    }

    /**
     * Paints one sprite inside its declared footprint. The clip is the
     * destination rectangle {@link #spriteDraws} reports, so a silhouette that
     * outgrew its footprint would be cut rather than drawn larger.
     */
    private void paintSprite(Graphics2D g, Frame f, SpriteDraw sprite) {
        Graphics2D clipped = (Graphics2D) g.create();
        try {
            clipped.clipRect(sprite.x(), sprite.y(), sprite.w(), sprite.h());
            paintSpriteShape(clipped, f, sprite);
        } finally {
            clipped.dispose();
        }
    }

    private void paintSpriteShape(Graphics2D g, Frame f, SpriteDraw sprite) {
        String slot = sprite.slot();
        int cx = sprite.x() + sprite.w() / 2;
        int bottom = sprite.y() + sprite.h();
        if (sprite.border()) {
            paintBorderProps(g);
        } else if (slot.startsWith("deco.")) {
            paintDecoration(g, slot, cx, bottom);
        } else if ("goal.carrot.glow".equals(slot)) {
            paintCarrotGlow(g, cx, sprite.y() + sprite.h() / 2);
        } else if ("goal.carrot.base".equals(slot)) {
            paintCarrotBody(g, cx, bottom - TileMap.TILE_H / 2);
        } else if (slot.startsWith("rabbit.")) {
            paintRabbit(g, cx, bottom - TileMap.TILE_H / 2,
                    slot.contains(".brown.") ? BROWN_RABBIT : BLUE_RABBIT);
        } else if (slot.startsWith("prop.fence.")) {
            // a fence segment turns with rot90 for a column and stays as it
            // is for a row; the glyph is the same either way.
            paintFence(g, sprite.x(), sprite.y(), sprite.w(), sprite.h(), sprite.applied());
        } else if (slot.startsWith("prop.")) {
            paintObstacle(g, f, cx, bottom - TileMap.TILE_H / 2);
        }
    }

    /**
     * One fence glyph inside its destination rectangle, placed through the
     * sprite's declared transforms. The glyph is
     * two rails and a knot in a square centred in the rectangle; a quarter turn
     * swaps the rails' axes and the mirrors move the knot, so a column run reads
     * as a rail standing up and a row run as a rail lying down. The transforms
     * are applied by mapping integer rectangles, so the result is exact pixels,
     * not a rotated anti-aliased approximation.
     */
    public static void paintFence(Graphics2D g, int x, int y, int w, int h,
                                  List<String> applied) {
        boolean rot = applied != null && applied.contains(Transforms.ROT90);
        boolean flipX = applied != null && applied.contains(Transforms.FLIP_X);
        boolean flipY = applied != null && applied.contains(Transforms.FLIP_Y);
        int s = Math.min(w, h);
        int left = x + (w - s) / 2;
        int top = y + (h - s) / 2;

        g.setColor(FENCE_WOOD);
        fenceRail(g, left, top, s, rot, flipX, flipY, 2, 26, s - 4, 7);
        fenceRail(g, left, top, s, rot, flipX, flipY, 2, 52, s - 4, 7);
        g.setColor(FENCE_KNOT);
        fenceRail(g, left, top, s, rot, flipX, flipY, 8, 21, 12, 8);
    }

    /** One rectangle of the fence glyph, placed through the declared transforms. */
    private static void fenceRail(Graphics2D g, int left, int top, int s,
                                  boolean rot, boolean flipX, boolean flipY,
                                  int lx, int ly, int lw, int lh) {
        int gx = lx;
        int gy = ly;
        int gw = lw;
        int gh = lh;
        if (rot) {
            gx = s - ly - lh;
            gy = lx;
            gw = lh;
            gh = lw;
        }
        if (flipX) {
            gx = s - gx - gw;
        }
        if (flipY) {
            gy = s - gy - gh;
        }
        g.fillRect(left + gx, top + gy, gw, gh);
    }

    private void paintObstacle(Graphics2D g, Frame f, int cx, int cy) {
        long h = Fnv.hash64("rock:" + f.seed() + ":" + cx + ":" + cy);
        int jitter = (int) (h & 0x07);
        int size = 46 + jitter;
        g.setColor(new Color(0, 0, 0, 48));
        g.fill(new Ellipse2D.Double(cx - size / 2.0 + 4, cy - size / 2.0 + 6, size, size * 0.8));
        g.setColor(shift(OBSTACLE, jitter * 3));
        g.fill(new Ellipse2D.Double(cx - size / 2.0, cy - size / 2.0, size, size * 0.8));
        g.setColor(new Color(255, 255, 255, 24));
        g.fill(new Ellipse2D.Double(cx - size / 3.0, cy - size / 2.4, size / 2.0, size / 4.0));
    }

    /**
     * while a turn is in {@code AwaitMove}, a tile whose path
     * is missing, blocked or over budget renders greyed and ignores clicks. The
     * painter reads {@link Frame#targets()}, the same set the pointer path consults,
     * and veils every other tile except the one the active rabbit stands on.
     */
    private void paintUntargetable(Graphics2D g, Frame f, Board board) {
        if (!"AwaitMove".equals(f.phase())) {
            return;
        }
        int[] activeCell = "brown".equals(f.active()) ? f.brown() : f.blue();
        g.setColor(UNTARGETABLE);
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if ((col == activeCell[0] && row == activeCell[1]) || targetable(f, col, row)) {
                    continue;
                }
                g.fillRect(TileMap.tileX(col), TileMap.tileY(row), TileMap.TILE_W, TileMap.TILE_H);
            }
        }
    }

    private static boolean targetable(Frame f, int col, int row) {
        if (f.targets() == null) {
            return false;
        }
        for (int[] target : f.targets()) {
            if (target[0] == col && target[1] == row) {
                return true;
            }
        }
        return false;
    }

    private void paintHighlight(Graphics2D g, Frame f) {
        int[] cell = "brown".equals(f.active()) ? f.brown() : f.blue();
        g.setColor(new Color(0x4d8de8));
        g.setStroke(new BasicStroke(4f));
        g.draw(new RoundRectangle2D.Double(
                TileMap.tileX(cell[0]) + 4, TileMap.tileY(cell[1]) + 4,
                TileMap.TILE_W - 8, TileMap.TILE_H - 8, 16, 16));
        g.setStroke(new BasicStroke(1f));
    }

    private static void paintCarrotGlow(Graphics2D g, int cx, int cy) {
        g.setColor(new Color(255, 211, 77, 70));
        g.fill(new Ellipse2D.Double(cx - 40, cy - 40, 80, 80));
    }

    private static void paintCarrotBody(Graphics2D g, int cx, int cy) {
        Path2D body = new Path2D.Double();
        body.moveTo(cx - 14, cy - 16);
        body.lineTo(cx + 14, cy - 16);
        body.lineTo(cx, cy + 26);
        body.closePath();
        g.setColor(CARROT);
        g.fill(body);
        g.setColor(new Color(0x2f6b2f));
        g.fill(new Ellipse2D.Double(cx - 16, cy - 30, 12, 18));
        g.fill(new Ellipse2D.Double(cx + 4, cy - 30, 12, 18));
    }

    private static void paintRabbit(Graphics2D g, int cx, int cy, Color body) {
        g.setColor(new Color(0, 0, 0, 60));
        g.fill(new Ellipse2D.Double(cx - 24, cy + 8, 48, 14));
        g.setColor(body.darker());
        g.fill(new Ellipse2D.Double(cx - 9, cy - 30, 10, 26));
        g.fill(new Ellipse2D.Double(cx + 1, cy - 32, 10, 26));
        g.setColor(body);
        g.fill(new Ellipse2D.Double(cx - 20, cy - 8, 40, 30));
        g.fill(new Ellipse2D.Double(cx - 11, cy - 20, 22, 22));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(cx - 6, cy - 14, 5, 5));
        g.fill(new Ellipse2D.Double(cx + 2, cy - 14, 5, 5));
    }

    /**
     * The border scene, placeholder edition: a tree or rock per
     * {@link Chrome#BORDER_PROPS} footprint, all of it inside the margins. The
     * footprints are the geometry the automation checks, so nothing may spill out of
     * them.
     */
    private void paintBorderProps(Graphics2D g) {
        int index = 0;
        for (Chrome.Rect r : Chrome.BORDER_PROPS) {
            boolean rock = (index++ % 2) == 1;
            g.setColor(new Color(0, 0, 0, 48));
            g.fill(new RoundRectangle2D.Double(r.x(), r.y() + r.h() * 0.72,
                    r.w(), r.h() * 0.28, 12, 12));
            if (rock) {
                g.setColor(new Color(0x4b4b42));
                g.fill(new RoundRectangle2D.Double(r.x(), r.y(), r.w(), r.h() * 0.82, 18, 18));
                g.setColor(new Color(255, 255, 255, 22));
                g.fill(new RoundRectangle2D.Double(r.x() + r.w() * 0.18, r.y() + r.h() * 0.12,
                        r.w() * 0.34, r.h() * 0.26, 10, 10));
            } else {
                g.setColor(new Color(0x2f4a2b));
                g.fill(new RoundRectangle2D.Double(r.x(), r.y(), r.w(), r.h() * 0.74, 22, 22));
                g.setColor(new Color(0x3d5c33));
                g.fill(new RoundRectangle2D.Double(r.x() + r.w() * 0.12, r.y() + r.h() * 0.06,
                        r.w() * 0.44, r.h() * 0.30, 16, 16));
                g.setColor(new Color(0x24331d));
                g.fillRect(r.x() + (int) (r.w() * 0.42), r.y() + (int) (r.h() * 0.58),
                        Math.max(4, (int) (r.w() * 0.16)), (int) (r.h() * 0.42));
            }
        }
    }

    /**
     * The HUD: turn, roll and active player in the top band; the seed with
     * its generator version bottom-left; the Ajustes gear bottom-right. Every
     * rectangle comes from {@link Chrome#HUD}, the same geometry the automation
     * reports, so a HUD that moved over the grid would be caught.
     */
    private void paintHud(Graphics2D g, Frame f) {
        g.setColor(PANEL);
        for (Chrome.Part part : Chrome.HUD) {
            Chrome.Rect r = part.rect();
            g.fillRoundRect(r.x(), r.y(), r.w(), r.h(), 18, 18);
        }

        g.setColor(TEXT);
        g.setFont(HUD_SMALL);
        Chrome.Rect turn = Chrome.TURN.rect();
        String roll = f.roll() == null ? "-" : Integer.toString(f.roll());
        g.drawString(Strings.text("hud.turn") + " " + f.turn()
                + "   ·   " + Strings.text("hud.active") + " " + player(f.active())
                + "   ·   " + Strings.text("hud.roll") + " " + roll,
                turn.x() + 20, turn.y() + 44);

        Chrome.Rect seed = Chrome.SEED.rect();
        g.drawString(Strings.text("hud.seed") + " " + f.seed()
                + "   ·   " + Strings.text("hud.generation") + " " + BoardGenerator.VERSION,
                seed.x() + 20, seed.y() + 40);

        Chrome.Rect gear = Chrome.GEAR.rect();
        String gearLabel = Strings.text("hud.gear");
        int gearWidth = g.getFontMetrics().stringWidth(gearLabel);
        g.drawString(gearLabel, gear.x() + (gear.w() - gearWidth) / 2,
                gear.y() + (gear.h() + g.getFontMetrics().getAscent()) / 2 - 3);

        paintTirar(g, f);
        paintEndTurn(g, f);
    }

    /**
     * The HUD {@code Tirar} button: the same
     * margin plate as the gear, ready in {@code AwaitRoll} and dimmed in every
     * other phase, where disables it. The pointer path hit-tests the same
     * {@link Chrome#TIRAR} rectangle, so the drawn state and the accepted state
     * are one predicate, {@link Match#AWAIT_ROLL}.
     */
    private void paintTirar(Graphics2D g, Frame f) {
        hudAction(g, Chrome.TIRAR.rect(), Strings.text("hud.tirar"),
                Match.AWAIT_ROLL.equals(f.phase()), HUD_SMALL);
    }

    /**
     * The HUD {@code Terminar turno} button:
     * live in {@code AwaitMove} only after at least one step, dim in every other
     * state. It reads {@link Frame#endTurnEnabled()}, the same
     * {@link Match#canEndTurn()} rule the pointer path hit-tests, so the plate and
     * the accepted click cannot disagree.
     */
    private void paintEndTurn(Graphics2D g, Frame f) {
        hudAction(g, Chrome.END_TURN.rect(), Strings.text("hud.endTurn"),
                f.endTurnEnabled(), HUD_ACTION);
    }

    /** One 140 px HUD action plate: ready green or disabled, label centred. */
    private void hudAction(Graphics2D g, Chrome.Rect r, String label, boolean enabled, Font font) {
        g.setColor(enabled ? TIRAR_READY : TIRAR_OFF);
        g.fillRoundRect(r.x() + 2, r.y() + 2, r.w() - 4, r.h() - 4, 14, 14);
        g.setFont(font);
        g.setColor(enabled ? TEXT : INK_OFF);
        drawCentred(g, label, r.x(),
                r.y() + (r.h() + g.getFontMetrics().getAscent()) / 2 - 3, r.w());
    }

    /**
     * The two transient banners of as one parchment plate
     * over the board: {@code Turno de Azul}/{@code Turno de Marrón} while
     * {@code HandOff} names the next player, and exactly {@code Sin movimiento
     * posible} while {@code NoLegalMove} holds its safety net. The phase is the
     * duration {@link Animations} gives the beat, so there is no second clock here
     * and the plate is painted whenever the state exposes that phase.
     */
    private void paintBanner(Graphics2D g, Frame f) {
        String text = bannerText(f.phase(), f.active());
        if (text == null) {
            return;
        }
        Chrome.Rect r = BANNER;
        panel(g, r);
        g.setFont(SCREEN_HEADING);
        g.setColor(INK);
        drawCentred(g, text, r.x(),
                r.y() + (r.h() + g.getFontMetrics().getAscent()) / 2 - 6, r.w());
    }

    /**
     * The banner copy for a phase and the active player, or {@code null} when no
     * banner belongs to that phase. {@code HandOff}'s {@code active} is already
     * the next player, so the text comes from that field.
     */
    public static String bannerText(String phase, String active) {
        if (Match.NO_LEGAL_MOVE.equals(phase)) {
            return Strings.text("banner.noMove");
        }
        if (Match.HAND_OFF.equals(phase)) {
            return "brown".equals(active)
                    ? Strings.text("banner.handOffBrown")
                    : Strings.text("banner.handOffBlue");
        }
        return null;
    }

    /**
     * The opening throw's panel: a centred
     * parchment plate with the heading, P1's hand column on the left and P2's on
     * the right, and — during {@code RpsReveal} — the outcome line. The layout
     * and the hidden/ready/revealed model are {@link Rps}, the same geometry the
     * pointer path hit-tests, so a plate the human can click is a plate the
     * painter drew.
     *
     * <p>No veil: the board stays visible around the panel. A committed but not
     * yet revealed column is a neutral {@code ¡Listo!} card, so the two players
     * can pass the machine without the first choice becoming readable
     *
     */
    private void paintRps(Graphics2D g, Frame f) {
        Chrome.Rect panel = Rps.PANEL;
        panel(g, panel);

        g.setFont(SCREEN_HEADING);
        g.setColor(INK);
        drawCentred(g, Strings.text("rps.heading"), panel.x(), Rps.HEADING_BASELINE, panel.w());

        g.setFont(BODY);
        drawCentred(g, Strings.text("rps.player1"),
                Rps.LEFT_COLUMN_X, Rps.CAPTION_BASELINE, Rps.PLATE_W);
        drawCentred(g, Strings.text("rps.player2"),
                Rps.RIGHT_COLUMN_X, Rps.CAPTION_BASELINE, Rps.PLATE_W);

        rpsColumn(g, f, Match.BLUE, f.p1Committed(), f.p1Hand());
        rpsColumn(g, f, Match.BROWN, f.p2Committed(), f.p2Hand());

        if (Match.RPS_REVEAL.equals(f.phase())) {
            g.setFont(BUTTON_LABEL);
            g.setColor(INK);
            drawCentred(g, rpsOutcome(f.active()), panel.x(), Rps.OUTCOME_BASELINE, panel.w());
        }
    }

    /** One player's column: live hands, the neutral ready card, or the revealed hands. */
    private void rpsColumn(Graphics2D g, Frame f, String player, boolean committed, Integer chosen) {
        Rps.Column state = Rps.columnState(f.phase(), committed);
        if (state == Rps.Column.READY) {
            // The choice is secret: one neutral card over the whole column, never
            // a highlight on the plate that was picked.
            Chrome.Rect r = Rps.columnRect(player);
            g.setColor(BUTTON_OFF);
            g.fill(new RoundRectangle2D.Double(r.x() - 10, r.y(), r.w() + 20, r.h(), 20, 20));
            g.setColor(PARCHMENT_EDGE);
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Double(r.x() - 10, r.y(), r.w() + 20, r.h(), 20, 20));
            g.setStroke(new BasicStroke(1f));
            g.setFont(BUTTON_LABEL);
            g.setColor(INK_OFF);
            drawCentred(g, Strings.text("rps.ready"),
                    r.x() - 10, r.y() + r.h() / 2 + 8, r.w() + 20);
            return;
        }
        boolean revealed = state == Rps.Column.REVEALED;
        boolean live = state == Rps.Column.CHOOSING;
        for (Rps.Hand hand : Rps.HANDS) {
            if (!hand.player().equals(player)) {
                continue;
            }
            boolean selected = revealed && chosen != null && chosen == hand.hand();
            rpsPlate(g, hand, selected, live);
        }
    }

    /** One 120×110 hand plate: 9-slice plate, 84×84 hand glyph, label beneath. */
    private void rpsPlate(Graphics2D g, Rps.Hand hand, boolean selected, boolean live) {
        Chrome.Rect r = hand.rect();
        NineSlice.drawButton(g, renderPlan,
                selected || live ? NineSlice.BUTTON_NORMAL : NineSlice.BUTTON_DISABLED,
                r.x(), r.y(), r.w(), r.h());
        paintHandGlyph(g, hand.hand(), r.x() + (r.w() - Rps.SPRITE) / 2, r.y() + 3);
        g.setFont(BODY);
        g.setColor(selected || live ? INK : INK_OFF);
        drawCentred(g, rpsHandLabel(hand.hand()), r.x(), r.y() + r.h() - 5, r.w());
        if (selected) {
            g.setColor("brown".equals(hand.player()) ? BROWN_RABBIT : BLUE_RABBIT);
            g.setStroke(new BasicStroke(3f));
            g.draw(new RoundRectangle2D.Double(r.x() + 2, r.y() + 2, r.w() - 4, r.h() - 4, 16, 16));
            g.setStroke(new BasicStroke(1f));
        }
    }

    /** The placeholder hand glyphs: a rock, a flat palm, two crossed blades. */
    private static void paintHandGlyph(Graphics2D g, int hand, int x, int y) {
        int s = Rps.SPRITE;
        int cx = x + s / 2;
        int cy = y + s / 2;
        switch (hand) {
            case 1 -> {
                // Rock: a rounded stone with the upper-left light.
                g.setColor(new Color(0x6b6252));
                g.fill(new Ellipse2D.Double(x + 14, y + 20, s - 28, s - 32));
                g.setColor(new Color(0x8a8070));
                g.fill(new Ellipse2D.Double(x + 24, y + 28, s - 48, s - 52));
                g.setColor(new Color(255, 255, 255, 40));
                g.fill(new Ellipse2D.Double(x + 30, y + 30, s - 58, s - 60));
            }
            case 2 -> {
                // Paper: a flat palm with three finger bars.
                g.setColor(new Color(0xe8d9b4));
                g.fillRoundRect(x + 16, y + 24, s - 32, s - 40, 14, 14);
                g.setColor(new Color(0xc9b58c));
                for (int i = 0; i < 3; i++) {
                    g.fillRoundRect(x + 26 + i * 10, y + 18, 6, 16, 4, 4);
                }
                g.setColor(new Color(0x8a6a3a));
                g.drawRoundRect(x + 16, y + 24, s - 32, s - 40, 14, 14);
            }
            case 3 -> {
                // Scissors: two crossed blades with a central pin.
                g.setColor(new Color(0x9aa3ab));
                g.setStroke(new BasicStroke(9f));
                g.drawLine(x + 22, y + 18, x + s - 20, y + s - 20);
                g.drawLine(x + s - 22, y + 18, x + 20, y + s - 20);
                g.setStroke(new BasicStroke(1f));
                g.setColor(new Color(0x5a5f63));
                g.fillOval(cx - 7, cy - 7, 14, 14);
                g.setColor(new Color(255, 255, 255, 90));
                g.fillOval(cx - 3, cy - 3, 6, 6);
            }
            default -> {
            }
        }
    }

    private static String rpsHandLabel(int hand) {
        return switch (hand) {
            case 1 -> Strings.text("rps.rock");
            case 2 -> Strings.text("rps.paper");
            default -> Strings.text("rps.scissors");
        };
    }

    private static String rpsOutcome(String winner) {
        return "brown".equals(winner) ? Strings.text("rps.wonBrown") : Strings.text("rps.wonBlue");
    }

    /** The player's UI name (GLOSSARY: Azul, Marrón), never the state token. */
    private static String player(String active) {
        return "brown".equals(active) ? Strings.text("player.brown") : Strings.text("player.blue");
    }

    /** The match's render plan; a frame built without one still paints a plate. */
    private static RenderPlan plan(Frame f) {
        return f.plan() == null ? RenderPlan.none() : f.plan();
    }

    private static Color shift(Color c, int delta) {
        return new Color(
                clamp(c.getRed() + delta),
                clamp(c.getGreen() + delta),
                clamp(c.getBlue() + delta));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
