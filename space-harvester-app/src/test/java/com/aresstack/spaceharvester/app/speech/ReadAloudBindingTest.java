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
    void aPageWhoseAiReleaseWasWithdrawnIsNotSentToTheSpeechService() throws Exception {
        FakeReadAloud fake = new FakeReadAloud();
        List<String> failures = new ArrayList<>();
        ReadAloudBinding binding = new ReadAloudBinding(() -> fake, failures::add);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            binding.speak("Geheimer Absatz über Nordlicht.", () -> false, finished::countDown);
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> { });
            assertThat(fake.spoken).isEmpty();
            assertThat(failures).containsExactly(ReadAloudBinding.NOT_ALLOWED);
        } finally {
            binding.close();
        }
    }

    @Test
    void pausedWhileTheServiceWasStillStartingBeginsOnlyOnResume() throws Exception {
        FakeReadAloud fake = new FakeReadAloud();
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        ReadAloudBinding binding = new ReadAloudBinding(() -> {
            asked.countDown();
            try {
                answer.await(5, TimeUnit.SECONDS); // der Dienst nennt seine Modelle
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fake;
        }, message -> { });
        try {
            binding.speak("Ein Absatz.", () -> { });
            assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();
            binding.pause();
            answer.countDown();
            Thread.sleep(200);
            assertThat(fake.spoken).as("angehalten: noch nichts gesprochen").isEmpty();
            assertThat(binding.isPaused()).isTrue();

            binding.resume();
            assertThat(fake.await(1)).isTrue();
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
