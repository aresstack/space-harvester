package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Die Sicherheitsgrenze des KI-Maskenassistenten (#1151) gegen eine Maske im Speicher. */
class MaskAssistantTest {

    /** Eine Maske 4 × 40: Titel, „Name ===>“ (10), „Menge ...“ (4, numerisch), „Kennwort“ (8, verdeckt). */
    static final class MemoryMask implements TerminalMask {
        char[] cells = new char[4 * 40];
        List<Field> fields = new ArrayList<>();
        int cursor;
        boolean locked;
        long generation;
        final List<String> calls = new ArrayList<>();

        MemoryMask() {
            Arrays.fill(cells, ' ');
            put(0, "NEUES DATASET ANLEGEN");
            put(40, "Name ===>");
            fields.add(new Field(39, 1, true, false, false, false));
            fields.add(new Field(50, 10, false, false, false, false));
            fields.add(new Field(60, 1, true, false, false, false));
            put(80, "Menge ...");
            fields.add(new Field(90, 4, false, true, false, false));
            fields.add(new Field(94, 1, true, false, false, false));
            put(120, "Kennwort");
            fields.add(new Field(129, 8, false, false, true, false));
            fields.add(new Field(137, 1, true, false, false, false));
            put(129, "GEHEIM12");
            cursor = 50;
        }

        void put(int at, String text) {
            text.getChars(0, text.length(), cells, at);
        }

        @Override
        public ScreenImage capture() {
            return new ScreenImage(4, 40, cursor, locked, cells.clone(), fields, generation);
        }

        /** Läuft vor der Prüfung im Schreibzug, wie eine Taste des Menschen kurz vorher. */
        Runnable beforeWrite = () -> { };

        @Override
        public void write(long expectedGeneration, List<FieldWrite> writes) {
            beforeWrite.run();
            if (expectedGeneration != generation) {
                throw new MaskRefused("neues Bild");
            }
            for (FieldWrite write : writes) {
                if (!MaskAssistant.trimEnd(new String(cells, write.start(), write.length())).equals(write.expected())) {
                    throw new MaskRefused("von Hand geändert");
                }
            }
            for (FieldWrite write : writes) {
                calls.add("write " + write.start() + " " + write.value());
                for (int i = 0; i < write.length(); i++) {
                    cells[write.start() + i] = i < write.value().length() ? write.value().charAt(i) : ' ';
                }
            }
        }

        @Override
        public void moveCursor(int position) {
            calls.add("cursor " + position);
            cursor = position;
        }
    }

    private MemoryMask mask;
    private TerminalSession session;
    private MaskAssistant assistant;

    @BeforeEach
    void setUp() {
        mask = new MemoryMask();
        session = new TerminalSession("terminal-1", TerminalProfile.of("host.example", 23));
        session.change(SessionState.CONNECTED, null);
        assistant = new MaskAssistant(session, () -> Optional.of(mask));
    }

    @Test
    void readingListsTheInputFieldsWithLabelsAndBlanksHiddenOnes() {
        MaskSnapshot snapshot = assistant.read();

        assertThat(snapshot.fields()).hasSize(3);
        assertThat(snapshot.fields().get(0)).satisfies(field -> {
            assertThat(field.label()).isEqualTo("Name");
            assertThat(field.row()).isEqualTo(2);
            assertThat(field.column()).isEqualTo(11);
            assertThat(field.length()).isEqualTo(10);
        });
        assertThat(snapshot.fields().get(1).label()).isEqualTo("Menge");
        assertThat(snapshot.fields().get(1).numeric()).isTrue();
        assertThat(snapshot.fields().get(2).sensitive()).isTrue();
        assertThat(snapshot.fields().get(2).value()).isEqualTo(MaskSnapshot.MASKED);
        assertThat(String.join("\n", snapshot.lines())).contains("NEUES DATASET ANLEGEN").doesNotContain("GEHEIM12");
        assertThat(snapshot.cursorRow()).isEqualTo(2);
    }

    @Test
    void fillingWritesLocallyAndRemembersTheEditsForReview() {
        String revision = assistant.read().revision();

        List<MaskAssistant.Edit> edits = assistant.fill(revision, List.of(
                new MaskAssistant.FieldValue(1, "USER.DATA"), new MaskAssistant.FieldValue(2, "15")));

        assertThat(mask.calls).containsExactly("write 50 USER.DATA", "write 90 15");
        assertThat(edits).hasSize(2);
        assertThat(assistant.edits()).hasSize(2);
        assertThat(assistant.read().fields().get(0).byAssistant()).isTrue();
    }

    @Test
    void aValueLongerThanTheFieldIsRefusedAndNothingIsWritten() {
        String revision = assistant.read().revision();

        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(2, "12"),
                new MaskAssistant.FieldValue(1, "VIEL.ZU.LANGER.NAME"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("10 Zeichen");
        assertThat(mask.calls).isEmpty();
    }

    @Test
    void lettersInANumericFieldAreRefused() {
        String revision = assistant.read().revision();
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(2, "zehn"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("Ziffern");
    }

    @Test
    void hiddenAndPasswordFieldsAreNeverFilledByTheAssistant() {
        String revision = assistant.read().revision();
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(3, "x"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("Kennwortfeld");
        assertThat(mask.calls).isEmpty();
    }

    @Test
    void aFieldLabelledAsPasswordCountsAsSensitiveEvenIfTheHostShowsIt() {
        mask.fields.set(5, new TerminalMask.Field(129, 8, false, false, false, false));
        MaskSnapshot snapshot = assistant.read();
        assertThat(snapshot.fields().get(2).sensitive()).isTrue();
        assertThat(String.join("\n", snapshot.lines())).doesNotContain("GEHEIM12");
        assertThatThrownBy(() -> assistant.fill(snapshot.revision(), List.of(new MaskAssistant.FieldValue(3, "x"))))
                .isInstanceOf(MaskRefused.class);
    }

    @Test
    void protectedTextIsNoFieldAndUnknownNumbersAreRefused() {
        String revision = assistant.read().revision();
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(4, "x"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("gibt es auf dieser Maske nicht");
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(0, "x"))))
                .isInstanceOf(MaskRefused.class);
    }

    @Test
    void aNewScreenFromTheHostInvalidatesTheRevisionAndTheEdits() {
        String revision = assistant.read().revision();
        assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "A.B")));
        mask.put(0, "DATASET ANGELEGT     ");

        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "C.D"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("geändert");
        assertThat(assistant.edits()).isEmpty();
        assertThat(assistant.read().revision()).isNotEqualTo(revision);
    }

    @Test
    void typingIntoInputFieldsKeepsTheRevision() {
        String revision = assistant.read().revision();
        mask.put(90, "7");
        assertThat(MaskAssistant.revision(mask.capture())).isEqualTo(revision);
    }

    @Test
    void aFieldTheUserChangedSinceReadingWinsOverTheAssistant() {
        String revision = assistant.read().revision();
        mask.put(50, "VON.HAND");

        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "KI.WERT"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("von Hand");
        assertThat(mask.calls).isEmpty();
    }

    @Test
    void fillingWithoutReadingFirstIsRefused() {
        assertThatThrownBy(() -> assistant.fill(MaskAssistant.revision(mask.capture()),
                List.of(new MaskAssistant.FieldValue(1, "A")))).isInstanceOf(MaskRefused.class)
                .hasMessageContaining("Erst die Maske lesen");
    }

    @Test
    void aLockedKeyboardOrADisconnectedSessionRefusesEverything() {
        String revision = assistant.read().revision();
        mask.locked = true;
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "A"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("X-Wait");
        mask.locked = false;
        session.disconnect();
        assertThatThrownBy(assistant::read).isInstanceOf(MaskRefused.class).hasMessageContaining("nicht verbunden");
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "A"))))
                .isInstanceOf(MaskRefused.class);
        assertThat(mask.calls).isEmpty();
    }

    @Test
    void lineBreaksAreNoWayToSubmit() {
        String revision = assistant.read().revision();
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "A\n"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("Steuerzeichen");
        assertThat(mask.calls).isEmpty();
    }

    @Test
    void undoRestoresOnlyFieldsThatStillHoldTheAssistantsValue() {
        mask.put(90, "3");
        String revision = assistant.read().revision();
        assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "KI.EINS"),
                new MaskAssistant.FieldValue(2, "12")));
        assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "KI.ZWEI")));
        mask.put(90, "99  ");
        mask.calls.clear();

        int restored = assistant.undo();

        assertThat(restored).isEqualTo(1);
        assertThat(mask.calls).containsExactly("write 50 ");
        assertThat(new String(mask.cells, 90, 4)).isEqualTo("99  ");
        assertThat(assistant.edits()).isEmpty();
    }

    @Test
    void cursorMovesStayLocalAndFollowTheInputFields() {
        String revision = assistant.read().revision();
        assertThat(assistant.moveCursor(revision, CursorMove.TAB, 0)).containsExactly(3, 11);
        assertThat(assistant.moveCursor(revision, CursorMove.NEW_LINE, 0)).containsExactly(4, 10);
        assertThat(assistant.moveCursor(revision, CursorMove.TAB, 0)).containsExactly(2, 11);
        assertThat(assistant.moveCursor(revision, CursorMove.BACKTAB, 0)).containsExactly(4, 10);
        assertThat(assistant.moveCursor(revision, CursorMove.FIELD, 2)).containsExactly(3, 11);
        assertThat(assistant.moveCursor(revision, CursorMove.HOME, 0)).containsExactly(2, 11);
        assertThat(mask.calls).allMatch(call -> call.startsWith("cursor "));
    }

    @Test
    void observersHearAboutEditsAndUndo() {
        List<String> heard = new ArrayList<>();
        assistant.observe(() -> heard.add("x"));
        String revision = assistant.read().revision();
        assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "A")));
        assistant.undo();
        assertThat(heard).hasSize(2);
    }

    @Test
    void aKeystrokeBetweenCheckAndWriteWinsAndNothingIsWritten() {
        String revision = assistant.read().revision();
        mask.beforeWrite = () -> mask.put(90, "5");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assistant.fill(revision, List.of(
                new MaskAssistant.FieldValue(1, "A.B"), new MaskAssistant.FieldValue(2, "12"))))
                .isInstanceOf(MaskRefused.class);
        assertThat(mask.calls).isEmpty();
        assertThat(assistant.edits()).isEmpty();
    }

    @Test
    void anIdenticalNewScreenFromTheHostIsStillANewMask() {
        String revision = assistant.read().revision();
        assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "A.B")));
        mask.generation++;

        assertThat(assistant.edits()).isEmpty();
        assertThatThrownBy(() -> assistant.fill(revision, List.of(new MaskAssistant.FieldValue(1, "C.D"))))
                .isInstanceOf(MaskRefused.class).hasMessageContaining("geändert");
        assertThat(assistant.undo()).isZero();
    }

    @Test
    void hiddenProtectedTextNeverBecomesALabel() {
        mask.put(40, "XYZ4711  ");
        mask.fields.set(0, new TerminalMask.Field(40, 9, true, false, true, false));
        MaskSnapshot snapshot = assistant.read();
        assertThat(snapshot.fields().get(0).label()).doesNotContain("XYZ4711");
        assertThat(String.join("\n", snapshot.lines())).doesNotContain("XYZ4711");
    }
}
