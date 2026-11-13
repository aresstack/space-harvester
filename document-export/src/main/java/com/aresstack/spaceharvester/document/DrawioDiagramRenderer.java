package com.aresstack.spaceharvester.document;

import com.aresstack.drawio.DrawioRenderer;
import com.aresstack.drawio.api.PageSelector;
import com.aresstack.drawio.api.RenderException;
import com.aresstack.drawio.api.RenderLimits;
import com.aresstack.drawio.api.RenderOptions;
import com.aresstack.drawio.api.RenderResult;
import com.aresstack.drawio.api.ResourcePolicy;
import com.aresstack.drawio.api.UnsupportedFeaturePolicy;

import java.time.Duration;

/**
 * Zeichnet ein draw.io-Diagramm ({@code mxfile}-Quelldaten aus dem Datenbestand) ohne Browser und ohne Netz mit
 * drawio-java (Original-draw.io-JavaScript auf GraalJS, gezeichnet mit Java2D) als SVG, wie der Enterprise AI Client
 * ({@code source-confluence}); Aufrufer ist {@code Diagrams} im Core.
 *
 * <p>Was drawio-java nicht originalgetreu zeichnen kann (unbekannte Formen und Schablonen, Bilder, die nicht als
 * {@code data:}-URI eingebettet sind, nicht lesbare Daten, Zeit- und Größengrenzen), macht das Diagramm „nicht
 * vollständig darstellbar“ ({@link Unsupported}); dann nimmt der Export die abgelegte PNG-Vorschau. Der Renderer
 * ruft nie eine Adresse ab. Die Meldung nennt nur Fehlercode und Fähigkeit (etwa
 * {@code stencil:mxgraph.aws4.lambda}), nie Beschriftungen, IDs oder XML.</p>
 */
public final class DrawioDiagramRenderer {

    /** Das Diagramm ist nicht vollständig darstellbar; die Meldung nennt nur Code und Fähigkeit, keine Inhalte. */
    public static final class Unsupported extends Exception {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    /** Erste Seite, nur eingebettete Bilder, nichts stillschweigend weglassen, höchstens 30 s je Diagramm (Export). */
    private static final RenderOptions OPTIONS = RenderOptions.builder()
            .page(PageSelector.first())
            .resourcePolicy(ResourcePolicy.dataUrisOnly())
            .unsupportedFeaturePolicy(UnsupportedFeaturePolicy.FAIL)
            .limits(RenderLimits.builder().timeout(Duration.ofSeconds(30)).build())
            .build();

    /**
     * Ein Renderer je JVM, beim ersten Diagramm angelegt; seine Threads sind Daemon-Threads. Vorgaben von drawio-java:
     * min(4, CPUs) gleichzeitig, 64 wartend, danach {@code OVERLOADED}.
     */
    private static final class Holder {
        static final DrawioRenderer RENDERER = DrawioRenderer.create();
    }

    private DrawioDiagramRenderer() {
    }

    /**
     * @param xml {@code mxfile} (erste Seite; komprimiert oder nicht) oder ein {@code mxGraphModel}
     * @return SVG-Dokument als Text
     * @throws Unsupported wenn das Diagramm nicht lesbar oder nicht vollständig darstellbar ist
     */
    public static String toSvg(String xml) throws Unsupported {
        if (xml == null || xml.trim().isEmpty()) {
            throw new Unsupported("Diagrammdaten fehlen");
        }
        RenderResult<String> result;
        try {
            result = Holder.RENDERER.renderSvg(xml, OPTIONS);
        } catch (RenderException e) {
            throw new Unsupported(reason(e));
        }
        if (result.getWidth() <= 1 && result.getHeight() <= 1) {
            // Leere Seite: drawio-java liefert ein 1×1-SVG; wie bisher „nicht darstellbar“.
            throw new Unsupported("Diagramm ohne Elemente");
        }
        return result.getOutput();
    }

    /** Nur Code und Fähigkeit; {@link RenderException#getMessage()} wird bewusst nicht übernommen (nennt Zell-IDs). */
    static String reason(RenderException e) {
        String capability = e.getCapabilityId();
        switch (e.getCode()) {
            case UNSUPPORTED_STENCIL:
            case UNSUPPORTED_FEATURE:
            case MISSING_RESOURCE:
                return "nicht unterstützt: " + (capability == null ? e.getCode().name() : capability);
            default:
                return e.getCode().name() + (capability == null ? "" : " (" + capability + ")");
        }
    }
}
