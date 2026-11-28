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

        assertThat(flow).contains("Fake-KI Frage").contains("Fake-KI Ablauf");
        assertThat(flow).contains("EXT_SATZSW>\"SATZSW\\n(CALLNAT)\\nFake-KI Satz");
        assertThat(flow).contains("ruft: SCHREIB");
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

    private static String flow(boolean ai, boolean collapsed) {
        return SourceDiagrams.generate(PROGRAM, "VERTEIL.NSP", Optional.of(SourceLanguage.NATURAL),
                DiagramType.FLOWCHART, 0, collapsed, false, SOURCES::get, null, ai).mermaid().orElseThrow();
    }

    private static final class FakeAi implements BlockLabelSummarizer {
        private final boolean enabled;
        final List<String> codes = new ArrayList<>();
        final List<String> purposes = new ArrayList<>();

        FakeAi(boolean enabled) {
            this.enabled = enabled;
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
