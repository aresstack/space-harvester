package com.aresstack.spaceharvester.application.marks;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.Describable;

import java.util.Objects;

/**
 * Eine Marke eines Videos mit ihrem Standbild, für die Bildanalyse: beschrieben mit einem eigenen Prompt für Folien und
 * Bildschirmaufzeichnungen statt dem der Bildbeschreibung.
 *
 * @param millis  Position der Marke im Video
 * @param id      Kennung ihres virtuellen Anhangs
 * @param version Fassung des Videos
 * @param frame   das abgelegte Standbild der Marke oder {@code null}, solange es nicht gezogen ist
 */
public record VideoMark(long millis, String id, String version, Assets.AssetContent frame) implements Describable {

    /** Der Prompt für das Standbild einer Marke. */
    public static final String INSTRUCTION = "Dies ist ein Standbild aus einer Videoaufzeichnung an einem "
            + "Folienwechsel (Präsentation oder Bildschirmfreigabe). Beschreibe sachlich auf Deutsch in höchstens fünf "
            + "Sätzen, welche Anwendung oder Folie zu sehen ist, ihren Titel bzw. die sichtbare Überschrift und die "
            + "wichtigsten lesbaren Inhalte. Gib lesbare Texte wörtlich wieder; erfinde keine Kennungen, Nummern oder "
            + "Adressen, die nicht klar lesbar sind.";

    public VideoMark {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public boolean ready() {
        return frame != null;
    }

    @Override
    public String title() {
        return Assets.markTitle(millis);
    }

    @Override
    public String filename() {
        return Assets.markFileName(millis);
    }

    @Override
    public String mediaType() {
        return "image/png";
    }

    @Override
    public String instruction() {
        return INSTRUCTION;
    }

    @Override
    public Assets.AssetContent content() {
        if (frame == null) {
            throw new IllegalStateException("Standbild der Marke noch nicht gezogen");
        }
        return frame;
    }
}
