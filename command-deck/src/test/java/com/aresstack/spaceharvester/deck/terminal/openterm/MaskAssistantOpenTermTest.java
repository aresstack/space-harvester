package com.aresstack.spaceharvester.deck.terminal.openterm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.terminal.CursorMove;
import com.aresstack.spaceharvester.deck.terminal.MaskAssistant;
import com.aresstack.spaceharvester.deck.terminal.MaskRefused;
import com.aresstack.spaceharvester.deck.terminal.MaskSnapshot;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalKeyboard;
import com.aresstack.spaceharvester.deck.terminal.TerminalMask;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Der Maskenassistent an einer echten OpenTerm-Sitzung gegen den Schein-Host (#1151): eine Maske „Neues Dataset
 * anlegen“ wie ISPF 3.2 ausfüllen, ohne dass ein einziges Byte an den Host geht; erst die ENTER-Taste des Menschen
 * schickt ab, danach gilt die alte Maske nicht mehr.
 */
class MaskAssistantOpenTermTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    static final List<FakeTn3270Host.Line> ALLOCATE = List.of(
            new FakeTn3270Host.Line(0, 2, "ALLOCATE NEW DATA SET", false),
            new FakeTn3270Host.Line(2, 2, "Data Set Name . . .", false),
            FakeTn3270Host.Line.input(2, 24, 44, false, false),
            new FakeTn3270Host.Line(4, 2, "Volume serial . . .", false),
            FakeTn3270Host.Line.input(4, 24, 6, false, false),
            new FakeTn3270Host.Line(5, 2, "Space units . . . .", false),
            FakeTn3270Host.Line.input(5, 24, 9, false, false),
            new FakeTn3270Host.Line(6, 2, "Primary quantity  .", false),
            FakeTn3270Host.Line.input(6, 24, 5, true, false),
            new FakeTn3270Host.Line(7, 2, "Record format . . .", false),
            FakeTn3270Host.Line.input(7, 24, 2, false, false),
            new FakeTn3270Host.Line(8, 2, "Record length . . .", false),
            FakeTn3270Host.Line.input(8, 24, 5, true, false),
            new FakeTn3270Host.Line(10, 2, "Password  . . . . .", false),
            FakeTn3270Host.Line.input(10, 24, 8, false, true),
            new FakeTn3270Host.Line(22, 2, "Enter = Anlegen   F3 = Ende", false));

    static final List<FakeTn3270Host.Line> ALLOCATED = List.of(
            new FakeTn3270Host.Line(0, 2, "DATA SET ALLOCATED", false),
            new FakeTn3270Host.Line(2, 2, "Command ===>", false),
            FakeTn3270Host.Line.input(2, 16, 40, false, false));

    @Test
    void theAssistantPreparesTheDatasetMaskLocallyAndOnlyTheHumanSubmitsIt() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(ALLOCATE, "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable));
            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()));
            TerminalView view = session.view(TerminalView.class).orElseThrow();
            await(() -> session.state() == SessionState.CONNECTED && view.text().contains("ALLOCATE NEW DATA SET")
                    && !view.keyboardLocked());
            MaskAssistant assistant = session.assistant();

            MaskSnapshot mask = assistant.read();

            assertThat(mask.fields()).extracting(MaskSnapshot.InputField::label).containsExactly("Data Set Name",
                    "Volume serial", "Space units", "Primary quantity", "Record format", "Record length",
                    "Password");
            assertThat(mask.fields().get(0)).satisfies(field -> {
                assertThat(field.row()).isEqualTo(3);
                assertThat(field.column()).isEqualTo(26);
                assertThat(field.length()).isEqualTo(44);
            });
            assertThat(mask.fields().get(3).numeric()).isTrue();
            assertThat(mask.fields().get(6).sensitive()).isTrue();
            int sentBefore = host.received().length;

            assistant.fill(mask.revision(), List.of(new MaskAssistant.FieldValue(1, "USER.TEST.DATA"),
                    new MaskAssistant.FieldValue(3, "TRACKS"), new MaskAssistant.FieldValue(4, "10"),
                    new MaskAssistant.FieldValue(5, "FB"), new MaskAssistant.FieldValue(6, "80")));
            assistant.moveCursor(mask.revision(), CursorMove.TAB, 0);
            assistant.moveCursor(mask.revision(), CursorMove.NEW_LINE, 0);
            Thread.sleep(300);

            assertThat(host.received().length).as("kein Byte an den Host").isEqualTo(sentBefore);
            assertThat(host.inputs()).isZero();
            String[] lines = view.text().split("\n");
            assertThat(lines[2]).contains("USER.TEST.DATA");
            assertThat(lines[5]).contains("TRACKS");
            assertThat(lines[6]).contains("10");
            assertThat(lines[8]).contains("80");
            assertThatThrownBy(() -> assistant.fill(mask.revision(),
                    List.of(new MaskAssistant.FieldValue(7, "geheim")))).isInstanceOf(MaskRefused.class);
            assertThat(assistant.edits()).hasSize(5);
            MaskSnapshot again = assistant.read();
            assertThat(again.revision()).isEqualTo(mask.revision());
            assertThat(again.fields().get(0).value()).isEqualTo("USER.TEST.DATA");
            assertThat(again.fields().get(0).byAssistant()).isTrue();

            // Der Mensch schickt ab: seine ENTER-Taste, nicht der Assistent.
            host.nextScreen(ALLOCATED);
            session.view(TerminalKeyboard.class).orElseThrow().enter();
            await(() -> host.inputs() == 1 && view.text().contains("DATA SET ALLOCATED") && !view.keyboardLocked());

            assertThat(host.aids()).containsExactly(0x7D);
            assertThat(new String(host.received(), Charset.forName("Cp273"))).contains("USER.TEST.DATA");
            assertThatThrownBy(() -> assistant.fill(mask.revision(),
                    List.of(new MaskAssistant.FieldValue(1, "NOCH.EINMAL")))).isInstanceOf(MaskRefused.class)
                    .hasMessageContaining("geändert");
            assertThat(assistant.edits()).isEmpty();
            MaskSnapshot next = assistant.read();
            assertThat(next.revision()).isNotEqualTo(mask.revision());
            assertThat(next.lines().get(0)).contains("DATA SET ALLOCATED");
            assertThat(next.fields()).singleElement().satisfies(field -> assertThat(field.label())
                    .isEqualTo("Command"));
            session.disconnect();
            assertThatThrownBy(assistant::read).isInstanceOf(MaskRefused.class);
        }
    }

    @Test
    void theMaskPortIsNotTheKeyboard() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(ALLOCATE, "Cp273")) {
            var connection = new OpenTermConnector().create(TerminalProfile.of("127.0.0.1", host.port()),
                    new com.aresstack.spaceharvester.deck.terminal.TerminalConnection.Listener() {
                        @Override
                        public void connected() {
                        }

                        @Override
                        public void closed(String reason) {
                        }
                    });
            TerminalMask mask = connection.view(TerminalMask.class).orElseThrow();
            assertThat(mask).isNotInstanceOf(TerminalKeyboard.class).isNotInstanceOf(TerminalView.class);
            connection.disconnect();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("Zeitüberschreitung");
            }
            Thread.sleep(50);
        }
    }
}
