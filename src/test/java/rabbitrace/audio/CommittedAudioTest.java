package rabbitrace.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rabbitrace.assets.AudioAsset;
import rabbitrace.assets.AudioManifest;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * every committed WAV is 16-bit PCM, mono,
 * 44.1 kHz — 22.05 kHz for an ambience slot — and no {@code.ogg} or
 * {@code.mp3} exists under {@code assets/}.
 *
 * <p>The accepted test reads the real {@code assets/} tree, so the day a real
 * file is committed it is weighed against rather than trusted by its
 * extension. The boot kit is synthesised in memory and has no files, so
 * the run also adds two WAVs to a scratch tree: a compliant one must stay
 * green, and a stereo one must be named. The smaller tests pin each rule the
 * accepted test relies on, so a scanner that returns a constant cannot pass it.
 */
class CommittedAudioTest {

    private static final Path ROOT =
            Path.of(System.getProperty("test.root", ".")).toAbsolutePath().normalize();
    private static final Path ASSETS = ROOT.resolve("assets");

    /** A field, not a method parameter: the report records the test as {@code Class#method}. */
    @TempDir
    Path temp;

    @Test
    void everyCommittedWavIsTheRuntimeFormat() throws IOException {
        AudioManifest manifest = AudioManifest.load(ASSETS.resolve("manifest.json"));

        // The manifest's groups decide which rate a referenced file owes; an
        // unreferenced WAV is held to the non-ambience rate by default.
        Map<String, Integer> rates = new LinkedHashMap<>();
        for (AudioAsset asset : manifest.entries()) {
            if (asset.usesFile()) {
                rates.put(asset.file(), "ambience".equals(asset.group())
                        ? CommittedAudio.AMBIENCE_RATE
                        : CommittedAudio.SFX_MUSIC_RATE);
            }
        }

        assertEquals(List.of(), CommittedAudio.violations(ASSETS, rates),
                "every committed WAV must be 16-bit PCM mono at its slot's rate, "
                        + "and no .ogg or .mp3 may exist under assets/");

        // The rule must be live on this tree, not merely true of an empty one:
        // the same scan accepts a compliant WAV and names a stereo one.
        Path probe = Files.createDirectories(temp.resolve("assets"));
        writeWav(probe.resolve("audio/sfx/hop.wav"), 44_100, 1, 16);
        assertEquals(List.of(), CommittedAudio.violations(probe, Map.of()),
                "a 16-bit PCM mono 44.1 kHz WAV is the committed format");

        writeWav(probe.resolve("audio/sfx/stereo.wav"), 44_100, 2, 16);
        List<CommittedAudio.Violation> bad = CommittedAudio.violations(probe, Map.of());
        assertEquals(1, bad.size(), bad.toString());
        assertEquals("audio/sfx/stereo.wav", bad.get(0).file());
        assertTrue(bad.get(0).message().contains("channels = 2"), bad.toString());
    }

    @Test
    void aCompliantSfxAndAmbienceFilePass() throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        writeWav(assets.resolve("audio/sfx/hop.wav"), 44_100, 1, 16);
        writeWav(assets.resolve("audio/ambience/nature-loop.wav"), 22_050, 1, 16);
        Files.writeString(assets.resolve("notes.txt"), "not audio", StandardCharsets.UTF_8);

        Map<String, Integer> rates = Map.of(
                "audio/ambience/nature-loop.wav", CommittedAudio.AMBIENCE_RATE);

        assertEquals(List.of(), CommittedAudio.violations(assets, rates));
    }

    @Test
    void anAmbienceFileMustUseTwentyTwoKilohertz() throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        writeWav(assets.resolve("audio/ambience/nature-loop.wav"), 44_100, 1, 16);

        Map<String, Integer> rates = Map.of(
                "audio/ambience/nature-loop.wav", CommittedAudio.AMBIENCE_RATE);

        List<CommittedAudio.Violation> bad = CommittedAudio.violations(assets, rates);
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).message().contains("not 22050"), bad.toString());
    }

    @Test
    void aStereoFileIsRejected() throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        writeWav(assets.resolve("audio/sfx/stereo.wav"), 44_100, 2, 16);

        List<CommittedAudio.Violation> bad = CommittedAudio.violations(assets, Map.of());
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).message().contains("not mono"), bad.toString());
    }

    @Test
    void anEightBitFileIsRejected() throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        writeWav(assets.resolve("audio/sfx/eight-bit.wav"), 44_100, 1, 8);

        List<CommittedAudio.Violation> bad = CommittedAudio.violations(assets, Map.of());
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).message().contains("8 bits"), bad.toString());
    }

    @Test
    void aFortyEightKilohertzFileIsRejected() throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        writeWav(assets.resolve("audio/sfx/fast.wav"), 48_000, 1, 16);

        List<CommittedAudio.Violation> bad = CommittedAudio.violations(assets, Map.of());
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).message().contains("48000"), bad.toString());
    }

    @Test
    void aWavHeaderThatCannotBeReadIsRejected() throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Path fake = assets.resolve("audio/sfx/fake.wav");
        Files.createDirectories(fake.getParent());
        Files.writeString(fake, "RIFF but not really a WAV", StandardCharsets.UTF_8);

        List<CommittedAudio.Violation> bad = CommittedAudio.violations(assets, Map.of());
        assertEquals(1, bad.size(), bad.toString());
        assertTrue(bad.get(0).message().contains("not a readable WAV"), bad.toString());
    }

    @Test
    void oggAndMp3AreRejectedWhateverTheirCase() throws IOException {
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Path ogg = assets.resolve("audio/sfx/loop.OGG");
        Path mp3 = assets.resolve("audio/music/track.mp3");
        Files.createDirectories(ogg.getParent());
        Files.createDirectories(mp3.getParent());
        Files.write(ogg, new byte[] {1, 2, 3});
        Files.write(mp3, new byte[] {4, 5, 6});

        List<CommittedAudio.Violation> bad = CommittedAudio.violations(assets, Map.of());
        assertEquals(List.of("audio/music/track.mp3", "audio/sfx/loop.OGG"),
                bad.stream().map(CommittedAudio.Violation::file).toList());
        assertTrue(bad.stream().allMatch(v -> v.message().contains("only 16-bit PCM WAV is committed")),
                bad.toString());
    }

    /** Writes a silent WAV in the given shape, so the scan sees a real header. */
    private static void writeWav(Path file, int rate, int channels, int bits) throws IOException {
        Files.createDirectories(file.getParent());
        AudioFormat.Encoding encoding = bits == 8
                ? AudioFormat.Encoding.PCM_UNSIGNED
                : AudioFormat.Encoding.PCM_SIGNED;
        int frameSize = channels * bits / 8;
        AudioFormat format = new AudioFormat(encoding, rate, bits, channels, frameSize, rate, false);
        int frames = rate / 10;
        byte[] pcm = new byte[frames * frameSize];
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }
}
