package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.ai.AiChat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Erfundene Stichpunkte. */
class BookFieldsTest {

    @Test
    void derPromptKenntGlossarUnbekannteUndFestgelegtesNieDasEigeneFeld() {
        Abbreviations.Result known = new Abbreviations.Result(
                List.of(new Abbreviations.Resolved("OGR", "Obstgehölzregister des Kreises")), List.of("XYZ"));
        List<AiChat.Message> messages = BookFields.messages(BookFields.Field.QUESTIONS,
                "Streuobstwiesen im Kreis pflegen, OGR, XYZ", known,
                Map.of(BookFields.Field.MISSION, "Pflege von Streuobstwiesen", BookFields.Field.QUESTIONS, "alt?"));

        String user = messages.get(1).text();
        assertThat(user).contains("Streuobstwiesen im Kreis pflegen, OGR, XYZ")
                .contains("- OGR = Obstgehölzregister des Kreises")
                .contains("unverändert").contains("XYZ")
                .contains("Schon festgelegt – Auftrag:\nPflege von Streuobstwiesen")
                .doesNotContain("alt?")
                .contains("Feld „Fragen an das Buch“");
    }

    @Test
    void listenOhneAufzaehlungszeichenUndFeldnamen() {
        assertThat(BookFields.clean(BookFields.Field.QUESTIONS, "Fragen an das Buch:\n1. Wann schneidet man?\n"
                + "- Welche Sorten?\n\n* Wer mäht?\n")).isEqualTo("Wann schneidet man?\nWelche Sorten?\nWer mäht?");
        assertThat(BookFields.clean(BookFields.Field.MISSION, "```\nAuftrag: Ein Buch über Streuobst.\n```"))
                .isEqualTo("Ein Buch über Streuobst.");
    }

    @Test
    void jedesFeldEineAnfrageHoechstensSovieleZugleichFehlerLassenDieAnderenWeiterlaufen() {
        AtomicInteger open = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        java.util.List<AiChat.Listener> pending = new java.util.concurrent.CopyOnWriteArrayList<>();
        AiChat chat = (request, listener) -> {
            most.accumulateAndGet(open.incrementAndGet(), Math::max);
            pending.add(listener);
            return () -> listener.cancelled();
        };
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        BookFieldFill fill = new BookFieldFill(chat, "modell", 2);
        Map<BookFields.Field, List<AiChat.Message>> requests = new java.util.LinkedHashMap<>();
        for (BookFields.Field field : BookFields.Field.values()) {
            requests.put(field, List.of(AiChat.Message.user(field.label())));
        }
        fill.start(requests, new BookFieldFill.Listener() {
            public void started(BookFields.Field field) { events.add("start " + field); }
            public void delta(BookFields.Field field, String text) { events.add("teil " + field); }
            public void completed(BookFields.Field field, String text) { events.add("fertig " + field + " " + text); }
            public void failed(BookFields.Field field, String reason) { events.add("fehler " + field); }
            public void finished() { events.add("ende"); }
        });
        assertThat(pending).hasSize(2);
        pending.get(0).delta("Ein ");
        open.decrementAndGet();
        pending.get(0).completed("- Buch");
        open.decrementAndGet();
        pending.get(1).failed(new com.aresstack.spaceharvester.ai.AiChatException(
                com.aresstack.spaceharvester.ai.AiChatException.Kind.values()[0], 500, "kaputt", null));
        assertThat(pending).hasSize(4);
        fill.cancel();

        assertThat(most.get()).isLessThanOrEqualTo(2);
        assertThat(events).contains("teil MISSION", "fertig MISSION - Buch", "fehler QUESTIONS", "fehler EXCLUDED")
                .endsWith("ende");
        assertThat(events.stream().filter("ende"::equals)).hasSize(1);
    }
}
