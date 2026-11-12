package com.aresstack.spaceharvester.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConsoleOutput;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import com.aresstack.spaceharvester.app.ui.AppContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Terminal-Ausgabe (Angelo 12.11.2026): Das Terminal zeigt nur Meldungen ab der gewählten Stufe, Standard aus; die
 * Protokolldatei bekommt alles, auch die Meldungen aus {@code java.util.logging}, die vorher getrennt auf stderr
 * liefen.
 */
class ConsoleLoggingTest {

    @TempDir
    Path home;

    private Handler[] julHandlers;

    @BeforeEach
    void remember() {
        julHandlers = julRoot().getHandlers();
        ConsoleLogging.reset();
    }

    @AfterEach
    void restore() {
        java.util.logging.Logger root = julRoot();
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        for (Handler handler : julHandlers) {
            root.addHandler(handler);
        }
        ConsoleLogging.reset();
    }

    @Test
    void eachLevelShowsItsOwnMessagesAndTheSevereOnes() {
        assertThat(ConsoleThreshold.shows(ConsoleOutput.OFF, Level.ERROR)).isFalse();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.ERROR, Level.ERROR)).isTrue();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.ERROR, Level.WARN)).isFalse();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.WARN, Level.ERROR)).isTrue();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.WARN, Level.WARN)).isTrue();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.WARN, Level.INFO)).isFalse();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.INFO, Level.INFO)).isTrue();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.INFO, Level.DEBUG)).isFalse();
        assertThat(ConsoleThreshold.shows(ConsoleOutput.ALL, Level.TRACE)).isTrue();
        // Ohne install() (Tests, andere Einstiege) filtert der Filter nichts.
        assertThat(ConsoleThreshold.shows(null, Level.DEBUG)).isTrue();
    }

    @Test
    void installRoutesJavaUtilLoggingThroughSlf4jAndStartsSilent() {
        ConsoleLogging.apply(ConsoleOutput.INFO);
        assertThat(ConsoleLogging.current()).as("vor install() ohne Wirkung").isNull();

        ConsoleLogging.install();

        assertThat(ConsoleLogging.current()).isEqualTo(ConsoleOutput.OFF);
        assertThat(julRoot().getHandlers()).noneMatch(ConsoleHandler.class::isInstance)
                .anyMatch(JulBridge.class::isInstance);
        ConsoleLogging.apply(ConsoleOutput.WARN);
        assertThat(ConsoleLogging.current()).isEqualTo(ConsoleOutput.WARN);
        ConsoleLogging.install();
        assertThat(ConsoleLogging.current()).as("ein zweites install() ändert nichts").isEqualTo(ConsoleOutput.WARN);
        assertThat(julRoot().getHandlers()).filteredOn(JulBridge.class::isInstance).hasSize(1);
    }

    @Test
    void javaUtilLoggingArrivesInLogbackWithTextLevelAndException() {
        String name = "test.jul." + System.nanoTime();
        ch.qos.logback.classic.Logger target = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> seen = new ListAppender<>();
        seen.start();
        target.addAppender(seen);
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(name);
        jul.setUseParentHandlers(false);
        JulBridge bridge = new JulBridge();
        jul.addHandler(bridge);
        try {
            IllegalStateException failure = new IllegalStateException("kaputt");
            jul.log(java.util.logging.Level.WARNING, "Quelle {0} antwortet nicht", "wiki");
            jul.log(java.util.logging.Level.SEVERE, "Abbruch", failure);
            jul.info("Hinweis {ohne Parameter}");
            jul.fine("unter der Stufe von java.util.logging");

            assertThat(seen.list).extracting(ILoggingEvent::getLevel)
                    .containsExactly(Level.WARN, Level.ERROR, Level.INFO);
            assertThat(seen.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Quelle wiki antwortet nicht", "Abbruch", "Hinweis {ohne Parameter}");
            assertThat(seen.list.get(1).getThrowableProxy().getMessage()).isEqualTo("kaputt");
            assertThat(seen.list).extracting(ILoggingEvent::getLoggerName).containsOnly(name);
        } finally {
            jul.removeHandler(bridge);
            jul.setUseParentHandlers(true);
            target.detachAppender(seen);
        }
    }

    @Test
    void theFileGetsEverythingWhileTheTerminalFollowsTheSetting() throws Exception {
        LoggerContext context = new LoggerContext();
        // Sonst setzt der SLF4J-Anbieter den MDC-Adapter; ohne ihn scheitert jedes Schreiben in die Datei.
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        Path logs = home.resolve("logs");
        context.putProperty("SPACE_HARVESTER_LOG_DIR", logs.toString());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(ConsoleLoggingTest.class.getResource("/logback.xml"));
        try {
            ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Appender<ILoggingEvent> console = root.getAppender("CONSOLE");
            Appender<ILoggingEvent> file = root.getAppender("FILE");
            assertThat(console.getCopyOfAttachedFiltersList()).singleElement().isInstanceOf(ConsoleThreshold.class);
            assertThat(file.getCopyOfAttachedFiltersList()).as("die Datei filtert nichts").isEmpty();

            ConsoleLogging.install();
            LoggingEvent warning = new LoggingEvent(getClass().getName(), root, Level.WARN, "Warnung", null, null);
            LoggingEvent info = new LoggingEvent(getClass().getName(), root, Level.INFO, "Hinweis", null, null);
            assertThat(console.getFilterChainDecision(warning)).as("Standard aus").isEqualTo(FilterReply.DENY);
            ConsoleLogging.apply(ConsoleOutput.WARN);
            assertThat(console.getFilterChainDecision(warning)).isEqualTo(FilterReply.NEUTRAL);
            assertThat(console.getFilterChainDecision(info)).isEqualTo(FilterReply.DENY);
            ConsoleLogging.apply(ConsoleOutput.ALL);
            assertThat(console.getFilterChainDecision(info)).isEqualTo(FilterReply.NEUTRAL);

            ConsoleLogging.apply(ConsoleOutput.OFF);
            root.info("Steht in der Datei, nicht im Terminal");
        } finally {
            context.stop();
        }
        assertThat(Files.readString(logs.resolve("space-harvester-app.log"), StandardCharsets.UTF_8))
                .contains("Steht in der Datei, nicht im Terminal");
    }

    @Test
    void theAppAppliesTheFileAtOnceAndAChoiceBeforeItIsSaved() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                logging:
                  threshold:
                    console: warn
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data").toString().replace('\\', '/')));
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        AppContext app = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test",
                vault);
        ConsoleLogging.install();
        try {
            CountDownLatch started = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> app.reload(started::countDown));
            assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(ConsoleLogging.current()).isEqualTo(ConsoleOutput.WARN);

            CountDownLatch saved = new CountDownLatch(1);
            Throwable[] failure = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> {
                app.consoleOutput(ConsoleOutput.ERROR, saved::countDown, error -> {
                    failure[0] = error;
                    saved.countDown();
                });
                assertThat(ConsoleLogging.current()).as("sofort, noch vor dem Speichern").isEqualTo(ConsoleOutput.ERROR);
            });
            assertThat(saved.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(failure[0]).isNull();
            assertThat(app.settings().consoleOutput()).isEqualTo(ConsoleOutput.ERROR);
            assertThat(Files.readString(config, StandardCharsets.UTF_8)).contains("    console: \"error\"\n")
                    .doesNotContain("console: warn");
        } finally {
            app.shutdown();
        }
    }

    private static java.util.logging.Logger julRoot() {
        return LogManager.getLogManager().getLogger("");
    }
}
