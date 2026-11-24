package rabbitrace.render;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The window shell: the only place that touches AWT peers or the
 * screen. It owns a {@link JFrame} and a panel whose {@code paintComponent} applies
 * the scale to a {@link BoardPainter} call — the painter itself never knows the
 * window size, so a headless run and a windowed run paint the same pixels.
 *
 * <p>Pointer input is converted window → virtual with inverse before it
 * reaches the same controller the automation {@code click} uses, so a click on a panel
 * control resolves identically at every window size. Keyboard input is translated
 * into the pure {@link Keys} map's key type and reaches the same controller the
 * automation {@code key} command does — no rules live in this class.
 */
public final class Shell {

    private final JFrame frame;
    private final JPanel canvas;
    private final Supplier<Frame> state;
    private final BiConsumer<Integer, Integer> pointer;
    /** The keyboard path: a mapped key in, the controller's reply out. */
    private final Function<Keys.Key, String> keys;
    /**
     * The window's bounds before it went fullscreen:
     * remembered for the session, never persisted, and what a second toggle
     * restores.
     */
    private Rectangle windowedBounds;
    private boolean fullscreen;

    public Shell(String title, Supplier<Frame> state, BiConsumer<Integer, Integer> pointer,
                 Function<Keys.Key, String> keys) {
        this.state = state;
        this.pointer = pointer;
        this.keys = keys;
        this.canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Frame frameState = Shell.this.state.get();
                if (frameState == null) {
                    return;
                }
                BufferedImage image = BoardPainter.renderScreen(frameState);
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.transform(TileMap.windowTransform(getWidth(), getHeight()));
                    g.drawImage(image, 0, 0, null);
                } finally {
                    g.dispose();
                }
            }
        };
        canvas.setBackground(Color.BLACK);
        canvas.setPreferredSize(new Dimension(TileMap.VIRTUAL_W, TileMap.VIRTUAL_H));
        // The canvas is the game's one focus target: it owns the key
        // listener, and a pointer press takes focus back if another window stole
        // it. Every key reaches the controller through the pure Keys map.
        canvas.setFocusable(true);
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                // window pixels → virtual pixels first.
                int[] virtual = TileMap.windowToVirtual(
                        e.getX(), e.getY(), canvas.getWidth(), canvas.getHeight());
                pointer.accept(virtual[0], virtual[1]);
            }
        });
        canvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                Keys.Key press = keyOf(e);
                if (press == null) {
                    return;
                }
                keys.apply(press);
                // every control repaints on change; the controller
                // already asks for the repaint an action causes, and this covers a
                // press that only changes focus-visible state.
                canvas.repaint();
            }
        });
        this.frame = new JFrame(title);
        frame.setContentPane(canvas);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(960, 540));
        frame.pack();
    }

    public JFrame frame() {
        return frame;
    }

    /** Shows the window. Call on the EDT. */
    public void show() {
        frame.setVisible(true);
        // The canvas holds the keyboard focus from the first frame, so Space
        // rolls the opening turn without a click to focus the board first.
        canvas.requestFocusInWindow();
    }

    public void repaint() {
        canvas.repaint();
    }

    public void close() {
        frame.dispose();
    }

    /**
     * fullscreen is a borderless maximized window on the
     * current monitor. The windowed size and position are remembered for the
     * session and restored on the next toggle; nothing here is persisted.
     *
     * <p>The frame is disposed around the decorated/undecorated switch because
     * AWT only allows it on an undisplayable frame. Callable from any thread; the
     * actual work runs on the EDT.
     */
    public void setFullscreen(boolean on) {
        runOnEdt(() -> {
            if (fullscreen == on) {
                return;
            }
            if (on) {
                windowedBounds = frame.getBounds();
                int[] area = maximizedArea();
                frame.dispose();
                frame.setUndecorated(true);
                frame.setBounds(area[0], area[1], area[2], area[3]);
                frame.setVisible(true);
            } else {
                Rectangle restore = windowedBounds;
                frame.dispose();
                frame.setUndecorated(false);
                if (restore != null) {
                    frame.setBounds(restore);
                }
                frame.setVisible(true);
            }
            fullscreen = on;
            canvas.revalidate();
            canvas.repaint();
            // A dispose/undecorate cycle can leave the canvas without focus;
            // F11 must not silently kill the keyboard for the rest of the match.
            canvas.requestFocusInWindow();
        });
    }

    /** True while the borderless maximized window is up. */
    public boolean fullscreen() {
        return callOnEdt(() -> fullscreen);
    }

    /** The window's outer bounds as {@code [x, y, w, h]}. */
    public int[] bounds() {
        return callOnEdt(() -> {
            Rectangle bounds = frame.getBounds();
            return new int[]{bounds.x, bounds.y, bounds.width, bounds.height};
        });
    }

    /**
     * The area a maximized window covers on the current monitor: the screen minus
     * the toolkit insets (the taskbar), as {@code [x, y, w, h]}.
     */
    public int[] screenBounds() {
        return callOnEdt(this::maximizedArea);
    }

    private int[] maximizedArea() {
        GraphicsConfiguration configuration = frame.getGraphicsConfiguration();
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new int[]{
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom};
    }

    /**
     * Translates a Swing key event into the pure map's key (
     * ). Letters and digits stay characters — the screen decides
     * that {@code W} moves in {@code AwaitMove} and types in the Título field —
     * while the named keys and the arrows get their own kinds. Ctrl, Alt and Meta
     * chords are not part of this layer and are ignored, so {@code Ctrl+V} never
     * types a {@code V} into the seed.
     *
     * @return the key, or {@code null} when the event carries no plain key
     */
    private static Keys.Key keyOf(KeyEvent e) {
        int modifiers = e.getModifiersEx()
                & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK);
        if (modifiers != 0) {
            return null;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE:
                return Keys.Key.named(Keys.Kind.ESCAPE);
            case KeyEvent.VK_F11:
                return Keys.Key.named(Keys.Kind.F11);
            case KeyEvent.VK_ENTER:
                return Keys.Key.named(Keys.Kind.ENTER);
            case KeyEvent.VK_SPACE:
                return Keys.Key.space();
            case KeyEvent.VK_BACK_SPACE:
                return Keys.Key.named(Keys.Kind.BACKSPACE);
            case KeyEvent.VK_UP:
                return Keys.Key.named(Keys.Kind.NORTH);
            case KeyEvent.VK_DOWN:
                return Keys.Key.named(Keys.Kind.SOUTH);
            case KeyEvent.VK_LEFT:
                return Keys.Key.named(Keys.Kind.WEST);
            case KeyEvent.VK_RIGHT:
                return Keys.Key.named(Keys.Kind.EAST);
            default:
                char character = e.getKeyChar();
                if (character == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(character)) {
                    return null;
                }
                return Keys.Key.typed(character);
        }
    }

    private void runOnEdt(Runnable task) {
        callOnEdt(() -> {
            task.run();
            return null;
        });
    }

    private <T> T callOnEdt(Supplier<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(task.get());
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the window", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("the window task failed", e);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
