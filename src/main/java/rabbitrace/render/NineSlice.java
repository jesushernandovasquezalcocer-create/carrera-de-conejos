package rabbitrace.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 9-slice draw path: a {@code ui.button.*} slot
 * declares {@code nineSlice [l,t,r,b]} in source pixels, and the painter draws
 * its corners at 1:1 while stretching only the edges and the centre. A button
 * therefore keeps its corners pixel-identical at every layout size the HUD
 * gives it, instead of being scaled like a sprite.
 *
 * <p>The source raster comes from the slot's declared {@code size}; while the
 * kit is procedural, last resort paints it from the slot id so every
 * clone draws the same pixels with no file on disk.
 */
public final class NineSlice {

    /** The four slots a button can be drawn from. */
    public static final String BUTTON_NORMAL = "ui.button.normal";
    public static final String BUTTON_HOVER = "ui.button.hover";
    public static final String BUTTON_PRESSED = "ui.button.pressed";
    public static final String BUTTON_DISABLED = "ui.button.disabled";

    /**
     * The corner block this draw path keeps identical across sizes.
     * Eight pixels sits inside the declared 32 px slice, inside the rim arc, and
     * inside the 96×40 layout's non-overlapping top and bottom corner bands.
     */
    public static final int CORNER = 8;

    private static final int FALLBACK_WIDTH = 240;
    private static final int FALLBACK_HEIGHT = 96;
    private static final int[] FALLBACK_BUTTON_SLICE = {32, 32, 32, 32};

    private static final Color BUTTON_FILL = new Color(0xd9c69a);
    private static final Color BUTTON_HOVER_FILL = new Color(0xe4d3a8);
    private static final Color BUTTON_PRESSED_FILL = new Color(0xc7b284);
    private static final Color BUTTON_OFF_FILL = new Color(0xc9bda3);
    private static final Color BUTTON_RIM = new Color(0x8a6a3a);
    private static final Color BUTTON_OFF_RIM = new Color(0xa89c85);
    private static final Color BUTTON_LIGHT = new Color(0xf3e7c9);

    private static final Map<String, BufferedImage> SOURCES = new ConcurrentHashMap<>();

    private NineSlice() {
    }

    /** One slot drawn at one size, in the probe image's coordinates. */
    public record ProbeDraw(String slot, int x, int y, int w, int h) {
    }

    /**
     * The slot's declared 9-slice, or the default for a {@code ui.button.*}
     * when the manifest is absent, or {@code null} for a slot that is not sliced.
     */
    public static int[] slices(RenderPlan plan, String slot) {
        int[] declared = plan == null ? null : plan.nineSlice(slot);
        if (declared != null) {
            return declared;
        }
        return slot != null && slot.startsWith("ui.button.")
                ? FALLBACK_BUTTON_SLICE.clone() : null;
    }

    /** The slot's source raster: its declared size, or the button default. */
    public static BufferedImage source(RenderPlan plan, String slot) {
        int[] size = plan == null ? null : plan.size(slot);
        int width = size != null ? size[0] : FALLBACK_WIDTH;
        int height = size != null ? size[1] : FALLBACK_HEIGHT;
        String key = slot + "/" + width + "x" + height;
        return SOURCES.computeIfAbsent(key, ignored -> paintSource(slot, width, height));
    }

    /** Draws a button slot into a layout rectangle with corners at 1:1. */
    public static void drawButton(Graphics2D g, RenderPlan plan, String slot,
                                  int x, int y, int w, int h) {
        draw(g, source(plan, slot), slices(plan, slot), x, y, w, h);
    }

    /**
     * Draws {@code src} into {@code (x,y,w,h)} using {@code slice [l,t,r,b]}:
     * the four corners are copied 1:1, the edges and the centre are stretched.
     * When a layout is narrower or shorter than two opposite slices, the
     * corners overlap rather than shrink — keeping them 1:1 is the contract.
     */
    public static void draw(Graphics2D g, BufferedImage src, int[] slice,
                            int x, int y, int w, int h) {
        if (src == null || slice == null || slice.length < 4) {
            return;
        }
        int l = slice[0];
        int t = slice[1];
        int r = slice[2];
        int b = slice[3];
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (l <= 0 || t <= 0 || r <= 0 || b <= 0 || l + r >= sw || t + b >= sh) {
            g.drawImage(src, x, y, w, h, null);
            return;
        }
        Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        try {
            int middleW = w - l - r;
            int middleH = h - t - b;
            if (middleW > 0 && middleH > 0) {
                g.drawImage(src, x + l, y + t, x + l + middleW, y + t + middleH,
                        l, t, sw - r, sh - b, null);
            }
            if (middleW > 0) {
                g.drawImage(src, x + l, y, x + l + middleW, y + t,
                        l, 0, sw - r, t, null);
                g.drawImage(src, x + l, y + h - b, x + l + middleW, y + h,
                        l, sh - b, sw - r, sh, null);
            }
            if (middleH > 0) {
                g.drawImage(src, x, y + t, x + l, y + t + middleH,
                        0, t, l, sh - b, null);
                g.drawImage(src, x + w - r, y + t, x + w, y + t + middleH,
                        sw - r, t, sw, sh - b, null);
            }
            g.drawImage(src, x, y, x + l, y + t, 0, 0, l, t, null);
            g.drawImage(src, x + w - r, y, x + w, y + t, sw - r, 0, sw, t, null);
            g.drawImage(src, x, y + h - b, x + l, y + h, 0, sh - b, l, sh, null);
            g.drawImage(src, x + w - r, y + h - b, x + w, y + h,
                    sw - r, sh - b, sw, sh, null);
        } finally {
            if (interpolation == null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            }
        }
    }

    /**
     * probe layout: the slot at its native size and at the three sizes
     * the design names (96×40, 96×64, 96×120). The painter draws all four through
     * {@link #draw}, so identical corner blocks are the 9-slice being real.
     */
    public static List<ProbeDraw> probeDraws(RenderPlan plan, String slot) {
        int[] size = plan == null ? null : plan.size(slot);
        int width = size != null ? size[0] : FALLBACK_WIDTH;
        int height = size != null ? size[1] : FALLBACK_HEIGHT;
        return List.of(
                new ProbeDraw(slot, 80, 100, width, height),
                new ProbeDraw(slot, 420, 100, 96, 40),
                new ProbeDraw(slot, 580, 100, 96, 64),
                new ProbeDraw(slot, 740, 100, 96, 120));
    }

    /**
     * The procedural last resort: a parchment plate with
     * a bark rim whose arc lives inside the declared corner slice. The rim is
     * what makes a scaled draw visibly different from a 1:1 corner.
     */
    private static BufferedImage paintSource(String slot, int width, int height) {
        BufferedImage image = new BufferedImage(
                Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = fill(slot);
            g.setColor(fill);
            g.fillRect(0, 0, width, height);

            int radius = Math.min(width, height) / 4;
            g.setColor(rim(slot));
            g.setStroke(new BasicStroke(Math.max(2f, height / 32f)));
            g.draw(new RoundRectangle2D.Double(1.5, 1.5, width - 3, height - 3, radius, radius));

            // The upper-left light: a thin warm line inside the top rim.
            g.setColor(BUTTON_LIGHT);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(radius / 2, Math.max(4, height / 12),
                    width - radius / 2, Math.max(4, height / 12));
        } finally {
            g.dispose();
        }
        return image;
    }

    private static Color fill(String slot) {
        return switch (slot == null ? "" : slot) {
            case BUTTON_HOVER -> BUTTON_HOVER_FILL;
            case BUTTON_PRESSED -> BUTTON_PRESSED_FILL;
            case BUTTON_DISABLED -> BUTTON_OFF_FILL;
            default -> BUTTON_FILL;
        };
    }

    private static Color rim(String slot) {
        return BUTTON_DISABLED.equals(slot) ? BUTTON_OFF_RIM : BUTTON_RIM;
    }
}
