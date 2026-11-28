package com.aresstack.spaceharvester.mainframe.diagram;

import com.aresstack.spaceharvester.mainframe.diagram.OutlineToMermaidConverter.DiagramType;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Flussdiagramm für Menschen: Zweige an den Pfeilen, aufgelöste Aufrufziele, KI-Beschreibungen mit Rückfall. */
class FlowLabelsTest {

    /** Erfundenes Natural-Programm: liest Sätze und verteilt sie je Satzart auf Unterprogramme. */
    private static final String PROGRAM = """
            DEFINE DATA LOCAL
            1 #SATZART (A2)
            1 #ZAEHLER (N5)
            END-DEFINE
            READ WORK FILE 1 #SATZ
              MOVE SUBSTR(#SATZ,1,2) TO #SATZART
              ADD 1 TO #ZAEHLER
              DECIDE ON FIRST VALUE OF #SATZART
                VALUE 'SW'
                  CALLNAT 'SATZSW' #SATZ
                VALUE 'DT'
                  CALLNAT 'SATZDT' #SATZ
                NONE
                  CALLNAT 'FEHLER' #SATZART
              END-DECIDE
            END-WORK
            IF #ZAEHLER = 0
              CALLNAT 'LEERLAUF'
            END-IF
            END
            """;

    private static final Map<String, String> SOURCES = Map.of(
            "SATZSW", "DEFINE DATA PARAMETER\n1 #SATZ (A80)\nEND-DEFINE\nCALLNAT 'SCHREIB' #SATZ\nEND\n",
            "SATZDT", "DEFINE DATA PARAMETER\n1 #SATZ (A80)\nEND-DEFINE\nWRITE #SATZ\nEND\n");

    @AfterEach
    void reset() {
        OutlineToMermaidConverter.blockLabelSummarizer(null);
    }

    @Test
    void withoutAiTheArrowsShowTheBranchAndCalleesShowWhatTheyCall() {
        String flow = flow(false, true);

        assertThat(flow).contains("\"Wert von #SATZART?\"");
        assertThat(flow).contains("==>|\"#SATZART = 'SW'\"| EXT_SATZSW")
                .contains("==>|\"#SATZART = 'DT'\"| EXT_SATZDT").contains("==>|\"sonst\"| EXT_FEHLER")
                // die Bedingung steht am Pfeil, auch wenn er nicht an der eigenen Raute beginnt
                .contains("==>|\"wenn #ZAEHLER = 0\"| EXT_LEERLAUF");
        // Name in der ersten Zeile (Doppelklick öffnet ihn), darunter die eigenen Aufrufe des geladenen Programms
        assertThat(flow).contains("EXT_SATZSW>\"SATZSW\\n(CALLNAT)\\nruft: SCHREIB\"]");
        assertThat(flow).contains("EXT_FEHLER>\"FEHLER\\n(CALLNAT)\"]");
        assertThat(flow).doesNotContain("Fake-KI");
    }

    @Test
    void withAiBlocksDecisionsAndCalleesAreExplainedFromTheFullSource() {
        FakeAi ai = new FakeAi(true);
        OutlineToMermaidConverter.blockLabelSummarizer(ai);

        String flow = flow(true, true);

        // Im Knoten nur der kurze Titel mit Nummer, die Erklärung steht unter derselben Nummer in der Erklärbox
        assertThat(flow).contains("BLOCK[\"① Fake-KI Frage\"]");
        assertThat(flow).contains("EXT_SATZSW>\"SATZSW\\n(CALLNAT)\\n② Fake-KI Frage\\nruft: SCHREIB");
        assertThat(flow).contains("subgraph erklaerungen [\"Erklärungen\"]")
                .contains("erklaerung_1[\"① Fake-KI Ablauf\"]:::erklaerung")
                .contains("erklaerung_2[\"② Fake-KI Ablauf\"]:::erklaerung");
        // Freistehend: keine Linie von der Box zu einem Knoten, nur die unsichtbare Reihenfolge in der Box
        assertThat(flow.lines().filter(line -> line.contains("erklaerung_") && line.contains("-->")))
                .isEmpty();
        // Der Block nach READ bekam alle Zeilen, auch MOVE und ADD, die die Gliederung nicht kennt
        assertThat(ai.codes).anySatisfy(code -> assertThat(code).contains("MOVE SUBSTR").contains("ADD 1 TO"));
        // Die Frage sah die ganze Verzweigung mit allen Werten
        assertThat(ai.codes).anySatisfy(code -> assertThat(code).contains("VALUE 'SW'").contains("VALUE 'DT'")
                .contains("END-DECIDE"));
        // Der Zweck des Unterprogramms kennt Aufrufstelle und Zweig
        assertThat(ai.purposes).anySatisfy(purpose -> assertThat(purpose).contains("SATZSW")
                .contains("CALLNAT 'SATZSW' #SATZ").contains("#SATZART = 'SW'"));
    }

    @Test
    void theQuestionsOfADiagramRunInParallelEachOnlyOnce() {
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger most = new java.util.concurrent.atomic.AtomicInteger();
        List<String> asked = java.util.Collections.synchronizedList(new ArrayList<>());
        OutlineToMermaidConverter.blockLabelSummarizer(new BlockLabelSummarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public int parallel() {
                return 3;
            }

            @Override
            public String summarize(String code, int maxLength, String purpose) {
                return answer("Titel", code, purpose);
            }

            @Override
            public String describe(String code, int maxLength, String purpose) {
                return answer("Erklärung", code, purpose);
            }

            private String answer(String kind, String code, String purpose) {
                asked.add(kind + code + purpose);
                most.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
                return kind;
            }
        });

        String flow = flow(true, true);

        assertThat(most.get()).isBetween(2, 3);
        assertThat(asked).doesNotHaveDuplicates();
        assertThat(flow).contains("① Titel").contains("erklaerung_1[\"① Erklärung\"]");
    }

    @Test
    void theFirstPictureAsksNoAiAndASwitchedOffAiIsNeverAsked() {
        FakeAi on = new FakeAi(true);
        OutlineToMermaidConverter.blockLabelSummarizer(on);
        flow(false, true);
        assertThat(on.codes).isEmpty();

        FakeAi off = new FakeAi(false);
        OutlineToMermaidConverter.blockLabelSummarizer(off);
        String flow = flow(true, true);
        assertThat(off.codes).isEmpty();
        assertThat(flow).contains("\"Wert von #SATZART?\"");
    }

    @Test
    void aFailingAiKeepsTheSimpleLabels() {
        OutlineToMermaidConverter.blockLabelSummarizer(new BlockLabelSummarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String summarize(String code, int maxLength, String purpose) {
                throw new IllegalStateException("Dienst weg");
            }
        });

        String flow = flow(true, true);

        assertThat(flow).contains("\"Wert von #SATZART?\"").contains("ruft: SCHREIB");
    }

    @Test
    void ifConditionsAreShownAsQuestionAndLongTextIsWrapped() {
        FlowLabels labels = FlowLabels.of(PROGRAM, null, null, new FlowLabels.Layout(90, 300, 10));
        assertThat(labels.wrap("Liest den nächsten Satz und prüft das Datum"))
                .isEqualTo("Liest den\nnächsten\nSatz und\nprüft das\nDatum");
        var element = new com.aresstack.spaceharvester.mainframe.model.JclElement(
                com.aresstack.spaceharvester.mainframe.model.JclElementType.NAT_IF_BLOCK, "IF", 17, "IF #ZAEHLER = 0");
        assertThat(labels.decisionFact(element)).isEqualTo("#ZAEHLER = 0?");
        assertThat(labels.branch(10)).isEqualTo("#SATZART = 'SW'");
        assertThat(labels.branch(6)).as("im READ steht kein Zweig").isNull();
    }

    @Test
    void aCallInsideALoopKeepsTheBranchAroundTheLoop() {
        String program = """
                DECIDE ON FIRST VALUE OF #TYP
                  VALUE 'A'
                    FOR #I = 1 TO 3
                      CALLNAT 'SUB' #I
                    END-FOR
                  NONE
                    IGNORE
                END-DECIDE
                END
                """;
        FlowLabels labels = FlowLabels.of(program, null, null, FlowLabels.Layout.DEFAULT);
        assertThat(labels.branch(4)).isEqualTo("#TYP = 'A'");
    }

    @Test
    void statementsBeforeTheProgramEndAreStillDescribed() {
        String program = """
                DEFINE DATA LOCAL
                1 #X (N1)
                END-DEFINE
                CALLNAT 'INIT'
                MOVE 1 TO #X
                WRITE #X
                END
                """;
        FakeAi ai = new FakeAi(true);
        OutlineToMermaidConverter.blockLabelSummarizer(ai);

        SourceDiagrams.generate(program, "ENDE.NSP", Optional.of(SourceLanguage.NATURAL), DiagramType.FLOWCHART, 0,
                true, false, null, null, true);

        assertThat(ai.codes).anySatisfy(code -> assertThat(code).contains("MOVE 1 TO #X").contains("WRITE #X"));
    }

    @Test
    void switchingTheAiOffWhileDescribingStopsFurtherRequests() {
        int[] askedBeforeOff = {-1};
        FakeAi ai = new FakeAi(true) {
            @Override
            public String describe(String code, int maxLength, String purpose) {
                String answer = super.describe(code, maxLength, purpose);
                askedBeforeOff[0] = codes.size();
                switchOff();
                return answer;
            }
        };
        OutlineToMermaidConverter.blockLabelSummarizer(ai);

        flow(true, true);

        assertThat(askedBeforeOff[0]).isPositive();
        assertThat(ai.codes).hasSize(askedBeforeOff[0]);
    }

    private static String flow(boolean ai, boolean collapsed) {
        return SourceDiagrams.generate(PROGRAM, "VERTEIL.NSP", Optional.of(SourceLanguage.NATURAL),
                DiagramType.FLOWCHART, 0, collapsed, false, SOURCES::get, null, ai).mermaid().orElseThrow();
    }

    private static class FakeAi implements BlockLabelSummarizer {
        private boolean enabled;
        final List<String> codes = new ArrayList<>();
        final List<String> purposes = new ArrayList<>();

        FakeAi(boolean enabled) {
            this.enabled = enabled;
        }

        void switchOff() {
            enabled = false;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String summarize(String code, int maxLength, String purpose) {
            codes.add(code);
            purposes.add(purpose);
            return "Fake-KI Frage";
        }

        @Override
        public String explain(String code, int maxLength, String purpose) {
            codes.add(code);
            purposes.add(purpose);
            return "Fake-KI Satz";
        }

        @Override
        public String describe(String code, int maxLength, String purpose) {
            codes.add(code);
            purposes.add(purpose);
            return "Fake-KI Ablauf";
        }
    }
}
