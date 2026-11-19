package com.aresstack.spaceharvester.application.assets;

/**
 * Etwas, das das Vision-Modell beschreiben kann (ADR 0021): ein Bild einer Seite, eine Marke eines Videos oder künftig
 * andere Medien. Die Bildanalyse sucht nur nach {@code Describable}s und fragt jedes selbst nach seinen Bytes, seinem
 * Prompt und ob es übersprungen wird; Medienarten kennt sie nicht.
 */
public interface Describable {

    /** Anhangs-ID, unter der die Bytes abliegen, oder {@code null} (dann bestimmt die Ablage sie). */
    String id();

    /** Fassung des Anhangs oder {@code null}. */
    String version();

    /** Anzeigename. */
    String title();

    /** Dateiname. */
    String filename();

    /** Medientyp, soweit bekannt. */
    String mediaType();

    /**
     * Der Prompt für das Vision-Modell; {@code null} heißt der der Bildbeschreibung des Enterprise AI Client
     * ({@code SourceImageAnalysisUseCase}). Beschreibungen gelten je Bildbytes, Modell und Prompt.
     */
    default String instruction() {
        return null;
    }

    /** Warum es nicht beschrieben wird (etwa {@code vector-source}), oder {@code null}. */
    default String skipReason() {
        return null;
    }

    /**
     * Die Bytes aus der Ablage oder über die Anmeldung der Quelle.
     *
     * @throws com.aresstack.spaceharvester.application.sources.SourceAccessException wenn die Quelle nicht liefert
     * @throws AssetTooLargeException                                                  über der Grenze der Ablage
     */
    Assets.AssetContent content();
}
