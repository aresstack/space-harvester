package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.document.SvgDrawing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagramComposerTest {

    static final String PLAN = "Gern: {\"title\":\"Nachtlauf Nordlicht\",\"form\":\"flow\",\"elements\":["
            + "{\"label\":\"Prüfung\",\"note\":\"vor dem Start\",\"icon\":\"a magnifying glass\"},"
            + "{\"label\":\"Spool frei\",\"note\":\"\",\"icon\":\"a box\"},"
            + "{\"label\":\"Lauf\",\"note\":\"ohne Abbruch\",\"icon\":\"a moon\"}]}";
    static final String ICON = "```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
            + "<circle cx=\"50\" cy=\"50\" r=\"38\" fill=\"#2F6FDE\" stroke=\"#2B2D42\" stroke-width=\"3\"/></svg>\n```";

    /** Plant und antwortet je Symbol; die Anweisung unterscheidet Plan und Symbol. */
    static StepModel model(String plan, String icon, AtomicInteger calls) {
        return new StepModel() {
            @Override
            public String version() {
                return "test";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                calls.incrementAndGet();
                return instructions.equals(DiagramComposer.PLAN_SYSTEM) ? plan : icon;
            }
        };
    }

    @Test
    void composesADrawableDiagramFromPlanAndParts() {
        AtomicInteger calls = new AtomicInteger();
        DiagramComposer composer = new DiagramComposer(model(PLAN, ICON, calls), Runnable::run, attempt -> { });
        DiagramComposer.Diagram diagram = composer.compose("Nordlicht", "erfunden", DiagramComposer.Memory.NONE)
                .orElseThrow();

        assertThat(calls).hasValue(4);
        assertThat(diagram.repairs()).isEmpty();
        assertThat(diagram.plan().form()).isEqualTo(DiagramComposer.Form.FLOW);
        assertThat(diagram.svg()).contains("Prüfung", "marker-end", "viewBox=\"0 0 100 100\"");
        assertThat(diagram.svg()).doesNotContain("```");
    }

    @Test
    void reusesStoredPlanAndIcons() {
        Map<String, String> stored = new HashMap<>();
        DiagramComposer.Memory memory = new DiagramComposer.Memory() {
            @Override
            public String load(String name) {
                return stored.get(name);
            }

            @Override
            public void save(String name, String content) {
                stored.put(name, content);
            }
        };
        AtomicInteger calls = new AtomicInteger();
        new DiagramComposer(model(PLAN, ICON, calls), Runnable::run, attempt -> { }).compose("t", "m", memory);
        assertThat(stored).containsKeys("plan.json", "icon-00.svg", "icon-02.svg");
        AtomicInteger again = new AtomicInteger();
        new DiagramComposer(model(PLAN, ICON, again), Runnable::run, attempt -> { }).compose("t", "m", memory)
                .orElseThrow();
        assertThat(again).hasValue(0);
    }

    @Test
    void anUnusableIconBecomesAProgramDrawnDotAndTheDiagramStillAppears() {
        AtomicInteger calls = new AtomicInteger();
        DiagramComposer composer = new DiagramComposer(model(PLAN, "kein Bild", calls), Runnable::run, a -> { });
        DiagramComposer.Diagram diagram = composer.compose("t", "m", DiagramComposer.Memory.NONE).orElseThrow();
        assertThat(calls).hasValue(1 + 3 * DiagramComposer.ATTEMPTS);
        assertThat(diagram.repairs()).hasSize(3).allMatch(repair -> repair.contains("ohne Modell"));
        assertThat(diagram.svg()).contains("<circle");
    }

    @Test
    void noUsablePlanMeansNoDiagram() {
        AtomicInteger calls = new AtomicInteger();
        DiagramComposer composer = new DiagramComposer(model("{\"elements\":[]}", ICON, calls), Runnable::run,
                a -> { });
        assertThat(composer.compose("t", "m", DiagramComposer.Memory.NONE)).isEmpty();
        assertThat(calls).hasValue(DiagramComposer.ATTEMPTS);
    }

    @Test
    void cleaningRemovesEverythingThatMustNotRun() throws Exception {
        String svg = DiagramComposer.clean("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"x()\">"
                + "<script>alert(1)</script><image href=\"http://example.test/a.png\"/>"
                + "<text>A</text><rect width=\"10\" height=\"10\" style=\"fill:url(http://example.test)\"/>"
                + "<use href=\"http://example.test/#a\"/></svg>");
        assertThat(svg).doesNotContain("script", "onload", "image", "<text", "example.test", "style");
        assertThat(svg).contains("<rect", "viewBox=\"0 0 100 100\"");
        assertThatThrownBy(() -> DiagramComposer.clean("<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>"))
                .isInstanceOf(DiagramComposer.Unusable.class);
    }

    @Test
    void anIconMustShowSomethingWithoutBackground() throws Exception {
        assertThat(DiagramComposer.iconProblem(DiagramComposer.clean(ICON))).isNull();
        assertThat(DiagramComposer.iconProblem(DiagramComposer.clean("<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "viewBox=\"0 0 100 100\"><rect width=\"100\" height=\"100\" fill=\"#fff\"/></svg>")))
                .isNotNull(); // SvgDrawing entfernt flächenfüllende Hintergründe selbst; übrig bleibt nichts
        assertThat(DiagramComposer.iconProblem(DiagramComposer.clean("<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "viewBox=\"0 0 100 100\"></svg>"))).isNotNull();
    }

    @Test
    void everyFormAndSizeIsDrawable() throws Exception {
        for (DiagramComposer.Form form : DiagramComposer.Form.values()) {
            for (int count = 2; count <= DiagramComposer.MAX_ELEMENTS; count++) {
                List<DiagramComposer.Element> elements = new ArrayList<>();
                List<String> icons = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    elements.add(new DiagramComposer.Element("Schritt <" + i + "> & mehr Wörter hier",
                            "eine kurze Notiz mit sechs Wörtern", "x"));
                    icons.add(i % 2 == 0 ? DiagramComposer.clean(ICON) : null);
                }
                String svg = DiagramComposer.assemble(new DiagramComposer.Plan("Titel", form, elements), icons);
                SvgDrawing drawing = SvgDrawing.parse(svg);
                assertThat(drawing.width()).isEqualTo(DiagramComposer.WIDTH);
                for (DiagramComposer.Slot slot : DiagramComposer.layout(form, count)) {
                    assertThat(slot.x()).isGreaterThanOrEqualTo(0);
                    assertThat(slot.x() + slot.width()).isLessThanOrEqualTo(DiagramComposer.WIDTH);
                    assertThat(slot.y()).isGreaterThanOrEqualTo(100);
                    assertThat(slot.y() + slot.height()).isLessThanOrEqualTo(DiagramComposer.HEIGHT);
                }
            }
        }
    }
}
