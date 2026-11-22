package com.aresstack.spaceharvester.application.background.services;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Beschreibung eines Analysedienstes für den {@link ServicePlanner} (D1, Nachtrag ADR 0025).
 *
 * @param id            Kennung, auf die sich Vorstufen beziehen
 * @param title         Titel für Auftragsliste und Meldungen, etwa „Bilder beschreiben“
 * @param start         Starttyp, zur Laufzeit gelesen, damit Einstellungen wie {@code images.automatic} gelten
 * @param prerequisites Kennungen der Vorstufen
 * @param kind          die {@link com.aresstack.spaceharvester.application.background.BackgroundKind} der Arbeit
 *                      oder {@code null}, solange es nur die Beschreibung gibt; dann legt der Dienst nichts an
 * @param missing       Seiten ohne aktuelles Ergebnis, mit den Rechten des Aufrufers
 * @param parameters    weitere Angaben jedes Teils, beim Start des Planers einmal je Dienst festgelegt (etwa der
 *                      Eigentümer und der eingestellte Analysator); {@link #PAGES} setzt immer der Planer
 */
public record AnalysisService(String id, String title, Supplier<ServiceStart> start, List<String> prerequisites,
                              String kind, Missing missing, Parameters parameters) {

    /** Angabe jedes Unterauftrags: seine Seiten-IDs, durch Zeilenumbruch getrennt. */
    public static final String PAGES = "pages";

    public AnalysisService {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id darf nicht leer sein");
        }
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(start, "start");
        prerequisites = List.copyOf(prerequisites);
        Objects.requireNonNull(missing, "missing");
        Objects.requireNonNull(parameters, "parameters");
    }

    /** Ohne weitere Angaben: die Teile tragen nur {@link #PAGES}. */
    public AnalysisService(String id, String title, Supplier<ServiceStart> start, List<String> prerequisites,
                           String kind, Missing missing) {
        this(id, title, start, prerequisites, kind, missing, Parameters.NONE);
    }

    /** Legt die weiteren Angaben der Teile beim Start fest. */
    @FunctionalInterface
    public interface Parameters {

        /** Keine. */
        Parameters NONE = caller -> Map.of();

        /**
         * @param caller der Aufrufer von {@link ServicePlanner#start}
         * @return Angaben für alle Teile dieses Dienstes in diesem Start; nie mit {@link #PAGES}
         */
        Map<String, String> of(AccessContext caller);
    }

    /** Ermittelt, was fehlt. */
    @FunctionalInterface
    public interface Missing {

        /** Nie fehlend: für Dienste, die es nur als Beschreibung gibt. */
        Missing NOTHING = (caller, pages) -> MissingPages.NONE;

        /**
         * @param caller der Aufrufer; nur Seiten, die er sehen darf, zählen als fehlend
         * @param pages  die Seitenmenge des Auftrags
         */
        MissingPages of(AccessContext caller, PageSet pages);
    }
}
