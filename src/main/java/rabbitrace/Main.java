package rabbitrace;

import rabbitrace.audio.AudioDirector;
import rabbitrace.audio.Mixing;
import rabbitrace.assets.AssetSource;
import rabbitrace.game.Match;
import rabbitrace.game.Seed;
import rabbitrace.automation.Commands;
import rabbitrace.automation.DevHook;
import rabbitrace.automation.EventLog;
import rabbitrace.automation.Frames;
import rabbitrace.i18n.Strings;
import rabbitrace.render.Animations;
import rabbitrace.render.Shell;
import rabbitrace.settings.Settings;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point, flag parsing and launch mode.
 *
 * <pre>
 *   java -Dautomation.headless=true -Dautomation.port=7777 -Dgame.seed=ABC123 \
 *        -Dautomation.log=shots\run.jsonl -cp ... rabbitrace.Main
 * </pre>
 *
 * <p>No {@code -Dautomation.*} flag means no socket, no log and no behavioural
 * difference. The hook is opt-in and loopback-only.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        boolean headless = Boolean.parseBoolean(System.getProperty("automation.headless", "false"));
        if (headless) {
            // Before any AWT class is touched: a headless run creates no peer.
            System.setProperty("java.awt.headless", "true");
        }

        String seedText = System.getProperty("game.seed");
        // the title field is preselected, but no match is
        // live until the seed is started, so phase is null at a bare launch.
        Match match = seedText == null || seedText.isBlank()
                ? Match.idle(Seed.random())
                : Match.start(seedText);

        // a missing file is defaults, and a bad one never stops the
        // launch. -Dsettings.path points an automation run at a scratch file.
        Settings settings = Settings.load(Settings.defaultPath());

        AtomicReference<Shell> shell = new AtomicReference<>();
        Runnable repaint = () -> {
            Shell s = shell.get();
            if (s != null) {
                s.repaint();
            }
        };

        // silent unless -Dautomation.audio=1, and silent
        // regardless when headless or when the animation table is zeroed. The
        // director never enters state, so a sounding run dumps the same state.
        boolean audioRequested = flag("automation.audio");
        // The one tunable table, zeroed by -Dautomation.animations=0.
        Animations animations = Animations.fromFlag(flagValue("automation.animations"));

        // whether the run draws the kit or the final files. The
        // root is overridable so an automation run can present a kit-less assets tree
        // the way -Dsettings.path presents a scratch settings file.
        Path assetsRoot = Path.of(System.getProperty("assets.root", "assets"));
        AssetSource assets = AssetSource.load(assetsRoot);

        try (AudioDirector sound = AudioDirector.start(audioRequested, headless, animations.enabled(),
                assetsRoot.resolve("manifest.json"));
             EventLog log = EventLog.open(System.getProperty("automation.log"))) {
            // the line opens at the stored sliders and mute, so a
            // launch with Silenciar on is silent without waiting for a change.
            sound.apply(levels(settings));
            // the launch screen chooses the first bed. A bare
            // launch is Título with the menu loop; a seeded launch is Partida
            // with the ambience already running.
            sound.screen(match.screen());
            log.event("started", match.board().rngCursor(), Frames.fields(match));

            // The controller exists before the window so a pointer event can
            // reach the same command path the automation socket uses.
            Commands commands = new Commands(match, settings, log, repaint, animations,
                    System::nanoTime, sound::apply, sound::screen, sound, assets);

            if (!headless) {
                SwingUtilities.invokeAndWait(() -> {
                    Shell s = new Shell(Strings.text("app.window.title"), () -> Frames.of(match, settings, assets,
                            commands.decorations(), commands.sprites(),
                            commands.groundShiftX(), commands.groundShiftY(),
                            commands.dieTumbleFraction()),
                            (x, y) -> {
                                // the shell already converted the
                                // window pixel; this is the single virtual-pixel path.
                                if ("ok bye".equals(commands.click(x, y))) {
                                    System.exit(0);
                                }
                            },
                            key -> {
                                // the shell mapped a real key event
                                // through Keys; this is the same controller method the
                                // automation `key` command reaches.
                                if ("ok bye".equals(commands.key(key))) {
                                    System.exit(0);
                                }
                                return "ok";
                            });
                    s.frame().addWindowListener(new java.awt.event.WindowAdapter() {
                        @Override
                        public void windowClosing(java.awt.event.WindowEvent e) {
                            // settings save on app close too.
                            settings.saveQuietly();
                            System.exit(0);
                        }
                    });
                    s.show();
                    shell.set(s);
                });
            }

            // the controller is what toggles the window, so F11 and
            // the Ajustes switch apply the setting to the same JFrame the player
            // sees. A headless run attached no window, and applies nothing.
            commands.attachWindow(new Commands.WindowHandle() {
                @Override
                public int[] bounds() {
                    Shell s = shell.get();
                    return s == null ? null : s.bounds();
                }

                @Override
                public int[] screenBounds() {
                    Shell s = shell.get();
                    return s == null ? null : s.screenBounds();
                }

                @Override
                public boolean fullscreen() {
                    Shell s = shell.get();
                    return s != null && s.fullscreen();
                }

                @Override
                public void setFullscreen(boolean fullscreen) {
                    Shell s = shell.get();
                    if (s != null) {
                        s.setFullscreen(fullscreen);
                    }
                }
            });

            // the win beat after the winning hop is not skippable,
            // so the app itself must advance it; a real launch has no command
            // traffic to do it. Presentation only: no rule waits on a clock.
            commands.startClock();
            DevHook.startIfEnabled(commands::handle, sound::close);

            // The readiness line the automation scripts and the scenario replay wait on.
            System.out.println("ready headless=" + GraphicsEnvironment.isHeadless()
                    + " seed=" + match.board().seed()
                    + " cursor=" + match.board().rngCursor()
                    + " boardHash=" + match.board().hash()
                    + " audio=" + sound.token());
            System.out.flush();

            new CountDownLatch(1).await();
        }
    }

    /** The slider snapshot the formula reads. */
    private static Mixing.Levels levels(Settings settings) {
        return new Mixing.Levels(settings.master(), settings.music(),
                settings.sfx(), settings.muted());
    }

    /** A boolean automation switch: {@code 1} or {@code true} turns it on. */
    private static boolean flag(String name) {
        String value = flagValue(name);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /** The trimmed value of a system property, or null when it is absent. */
    private static String flagValue(String name) {
        String value = System.getProperty(name);
        return value == null ? null : value.trim();
    }
}
