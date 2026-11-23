package com.aresstack.spaceharvester.app.speech;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Der neueste Text gewinnt, ein Stopp meldet das Ende, ein Fehler geht an die Statuszeile (wie im EAC). */
class ReadAloudBindingTest {

    @Test
    void aNewTextReplacesTheRunningOneAndEachReportsItsEnd() throws Exception {
        FakeReadAloud fake = new FakeReadAloud();
        ReadAloudBinding binding = new ReadAloudBinding(() -> fake, message -> { });
        CountDownLatch oldFinished = new CountDownLatch(1);
        CountDownLatch newFinished = new CountDownLatch(1);
        try {
            binding.speak("Alter Absatz über Nordlicht.", oldFinished::countDown);
            assertThat(fake.await(1)).isTrue();

            binding.speak("Neuer Absatz.", newFinished::countDown);
            assertThat(oldFinished.await(5, TimeUnit.SECONDS)).as("der abgelöste meldet sein Ende").isTrue();
            assertThat(fake.await(2)).isTrue();
            assertThat(fake.spoken).containsExactly("Alter Absatz über Nordlicht.", "Neuer Absatz.");

            binding.stop();
            assertThat(newFinished.await(5, TimeUnit.SECONDS)).as("ein Stopp meldet das Ende").isTrue();
        } finally {
            binding.close();
        }
    }

    @Test
    void aMissingSpeechServiceGoesToTheStatusLine() throws Exception {
        List<String> failures = new ArrayList<>();
        ReadAloudBinding binding = new ReadAloudBinding(() -> {
            throw new IllegalStateException("Der KI-Dienst bietet kein Sprachmodell (TTS) an");
        }, failures::add);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            binding.speak("Ein Absatz.", finished::countDown);
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> { });
            assertThat(failures).containsExactly("Der KI-Dienst bietet kein Sprachmodell (TTS) an");
        } finally {
            binding.close();
        }
    }
}
