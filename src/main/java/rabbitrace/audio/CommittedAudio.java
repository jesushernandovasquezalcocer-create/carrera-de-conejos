package rabbitrace.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The committed-file half:
 * every WAV under {@code assets/} is 16-bit PCM, mono, at 44.1 kHz — 22.05 kHz
 * for an ambience slot — and no OGG or MP3 is committed at all.
 *
 * <p>This is a property of the tree, not of playback: {@link AudioDirector}
 * still asks {@link AudioSystem} for a line and the manifest still decides which
 * file a slot uses. The scan reads each file's header, so a WAV that only has
 * the right extension is still red.
 */
public final class CommittedAudio {

    /** SFX and music are 44.1 kHz. */
    public static final int SFX_MUSIC_RATE = 44_100;

    /** ambience is 22.05 kHz. */
    public static final int AMBIENCE_RATE = 22_050;

    /** The JDK reads PCM WAV; these containers need a decoder the project may not add. */
    private static final Set<String> FORBIDDEN = Set.of("ogg", "mp3");

    /** One file that breaks: the path relative to {@code assets/}, and why. */
    public record Violation(String file, String message) {
    }

    /**
     * Every problem under {@code assetsRoot}, in path order.
     *
     * @param ratesByFile a path relative to {@code assetsRoot} (with {@code /})
     *                    mapped to the rate its slot requires; a WAV not named
     *                    there is held to 44.1 kHz, the non-ambience default
     */
    public static List<Violation> violations(Path assetsRoot, Map<String, Integer> ratesByFile)
            throws IOException {
        if (!Files.isDirectory(assetsRoot)) {
            return List.of(new Violation(".", "assets root is not a directory: " + assetsRoot));
        }
        List<Violation> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(assetsRoot)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String relative = assetsRoot.relativize(file).toString().replace('\\', '/');
                String extension = extension(relative);
                if (FORBIDDEN.contains(extension)) {
                    found.add(new Violation(relative,
                            "is a ." + extension + "; only 16-bit PCM WAV is committed"));
                    continue;
                }
                if (!extension.equals("wav")) {
                    continue;
                }
                int expectedRate = ratesByFile.getOrDefault(relative, SFX_MUSIC_RATE);
                String problem = problem(file, expectedRate);
                if (problem != null) {
                    found.add(new Violation(relative, problem));
                }
            }
        }
        return List.copyOf(found);
    }

    private static String problem(Path file, int expectedRate) {
        AudioFormat format;
        try {
            format = AudioSystem.getAudioFileFormat(file.toFile()).getFormat();
        } catch (UnsupportedAudioFileException | IOException e) {
            return "is not a readable WAV: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        List<String> problems = new ArrayList<>();
        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            problems.add("encoding is " + format.getEncoding());
        }
        if (format.getSampleSizeInBits() != 16) {
            problems.add("sample size is " + format.getSampleSizeInBits() + " bits, not 16");
        }
        if (format.getChannels() != 1) {
            problems.add("channels = " + format.getChannels() + ", not mono");
        }
        if (Float.compare(format.getSampleRate(), expectedRate) != 0) {
            problems.add("sample rate is " + format.getSampleRate() + " Hz, not " + expectedRate);
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private CommittedAudio() {
    }
}
