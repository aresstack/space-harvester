package com.aresstack.spaceharvester.application.marks;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.Describable;

import java.util.Objects;

/**
 * Eine Marke eines Videos mit ihrem Standbild, für die Bildanalyse: beschrieben mit einem eigenen Prompt für Folien und
 * Bildschirmaufzeichnungen statt dem der Bildbeschreibung.
 *
 * @param millis Position der Marke im Video
 * @param frame  das abgelegte Standbild der Marke
 */
public record VideoMark(long millis, Assets.AssetContent frame) implements Describable {

    /** Der Prompt für das Standbild einer Marke. */
    public static final String INSTRUCTION = "Dies ist ein Standbild aus einer Videoaufzeichnung an einem "
            + "Folienwechsel (Präsentation oder Bildschirmfreigabe). Beschreibe sachlich auf Deutsch in höchstens fünf "
            + "Sätzen, welche Anwendung oder Folie zu sehen ist, ihren Titel bzw. die sichtbare Überschrift und die "
            + "wichtigsten lesbaren Inhalte. Gib lesbare Texte wörtlich wieder; erfinde keine Kennungen, Nummern oder "
            + "Adressen, die nicht klar lesbar sind.";

    public VideoMark {
        Objects.requireNonNull(frame, "frame");
    }

    @Override
    public String id() {
        return frame.asset().attachmentId();
    }

    @Override
    public String version() {
        return frame.asset().version();
    }

    @Override
    public String title() {
        return Assets.markTitle(millis);
    }

    @Override
    public String filename() {
        return frame.asset().title();
    }

    @Override
    public String mediaType() {
        return frame.asset().mediaType();
    }

    @Override
    public String instruction() {
        return INSTRUCTION;
    }

    @Override
    public Assets.AssetContent content() {
        return frame;
    }
}
