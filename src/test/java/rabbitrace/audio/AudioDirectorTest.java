package rabbitrace.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.LineUnavailableException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The seam rests on two behaviours: a silent mode never asks the device for a line, and
 * a sounding mode asks exactly once. The scenario replay asserts the observable
 * ready-line status; this test asserts the call behind it, so a silent director
 * that merely reports silence cannot pass both.
 */
class AudioDirectorTest {

    @Test
    void aSilentModeNeverAsksForALine() {
        AtomicInteger asked = new AtomicInteger();
        AudioDirector.LineOpener opener = format -> {
            asked.incrementAndGet();
            throw new IllegalStateException("a silent run must not touch the mixer");
        };

        try (AudioDirector notRequested = AudioDirector.start(false, false, true, opener);
             AudioDirector headless = AudioDirector.start(true, true, true, opener);
             AudioDirector noAnimations = AudioDirector.start(true, false, false, opener)) {
            assertEquals(AudioDirector.Status.SILENT, notRequested.status());
            assertEquals(AudioDirector.Status.SILENT, headless.status());
            assertEquals(AudioDirector.Status.SILENT, noAnimations.status());
        }
        assertEquals(0, asked.get(), "a silent run must open no audio line at all");
    }

    @Test
    void aSoundingModeAsksOnceAndSurvivesAMissingDevice() {
        AtomicInteger asked = new AtomicInteger();
        AudioDirector.LineOpener opener = format -> {
            asked.incrementAndGet();
            throw new LineUnavailableException("no device in the test automation");
        };

        try (AudioDirector director = AudioDirector.start(true, false, true, opener)) {
            assertEquals(AudioDirector.Status.UNAVAILABLE, director.status());
        }
        assertEquals(1, asked.get(), "a sounding run must ask the device once");
    }
}
