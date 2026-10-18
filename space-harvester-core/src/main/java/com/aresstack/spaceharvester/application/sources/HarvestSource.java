package com.aresstack.spaceharvester.application.sources;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Eine konfigurierte Wissensquelle aus Sicht der Anwendungsfälle, unabhängig von ihrem Typ (Confluence, später
 * weitere). Umgesetzt im Paket {@code source}.
 */
public interface HarvestSource {

    /** Quellen-ID aus der Konfiguration, z. B. {@code confluence}. */
    String id();

    /** Quellentyp, z. B. {@code confluence}. */
    String type();

    /** Anzeigename. */
    String title();

    /**
     * Was die Quelle kann (#18). Standard: Partitionen und beide Metadatenabfragen, Originale nur, wenn die Quelle
     * {@link #storedPage} überschreibt und das hier meldet.
     */
    default Set<SourceCapability> capabilities() {
        Set<SourceCapability> capabilities = EnumSet.of(SourceCapability.PARTITIONS, SourceCapability.PAGE_LISTING,
                SourceCapability.PAGE_METADATA);
        if (asyncStoredPages()) {
            capabilities.add(SourceCapability.ASYNC_STORED_PAGE);
        }
        return capabilities;
    }

    /**
     * Alle Partitionen der Quelle (bei Confluence: Spaces), die mit den konfigurierten Zugangsdaten sichtbar sind;
     * ungekürzt.
     *
     * @throws SourceAccessException wenn die Quelle nicht erreichbar ist oder den Zugriff verweigert
     */
    List<Partition> partitions();

    /**
     * Ein Stapel Metadaten aller Seiten einer Partition, ohne Inhalte, vollständig über den Cursor (auch Seiten
     * außerhalb des Baums der Startseite, ohne Gesamtgrenze).
     *
     * @param partitionKey Schlüssel der Partition (Space-Key)
     * @param cursor       {@code null} für den ersten Stapel, sonst {@link PageMetadataBatch#nextCursor()}
     * @param limit        größter gewünschter Stapel, mindestens 1; die Quelle darf weniger liefern
     * @throws SourceAccessException {@code NOT_FOUND} für eine unbekannte Partition, {@code UNSUPPORTED} für einen
     *                               fremden Cursor oder eine Quelle ohne diese Fähigkeit, sonst wie
     *                               {@link #partitions()}
     */
    PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit);

    /**
     * Metadaten einer einzelnen Seite ohne Inhalt über ihre ID in der Quelle, unabhängig vom Space (eine verschobene
     * Seite behält ihre ID).
     *
     * @param pageId ID der Seite in der Quelle (bei Confluence die Content-ID)
     * @throws SourceAccessException {@code NOT_FOUND} für eine unbekannte oder nicht sichtbare Seite,
     *                               {@code UNSUPPORTED} für eine ID, die die Quelle nicht kennt, oder eine Quelle ohne
     *                               diese Fähigkeit, sonst wie {@link #partitions()}
     */
    PageMetadata pageMetadata(String pageId);

    /**
     * Die native Antwort der Quelle für eine Seite Byte für Byte, mit den Kopfdaten aus derselben Antwort (Revision und
     * Inhalt gehören zusammen). Bei Confluence die REST-Antwort mit {@code body.storage} (ADR 0002).
     *
     * @param pageId ID der Seite in der Quelle (bei Confluence die Content-ID)
     * @throws SourceAccessException {@code NOT_FOUND} für eine unbekannte oder nicht sichtbare Seite,
     *                               {@code INVALID_RESPONSE} auch für eine Antwort über der Größengrenze der Quelle (sie
     *                               wird nie gekürzt), {@code UNSUPPORTED} für eine fremde ID oder eine Quelle ohne
     *                               diese Fähigkeit, sonst wie {@link #partitions()}
     */
    default StoredPage storedPage(String pageId) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann native Seitenantworten nicht liefern", null);
    }

    /**
     * {@code true}, wenn {@link #storedPageAsync} nicht blockierend arbeitet, also die Quelle einen asynchronen
     * HTTP-Client hat ({@code space-harvester.network.async-transport}, #47).
     */
    default boolean asyncStoredPages() {
        return false;
    }

    /**
     * Wie {@link #storedPage}, aber ohne den Aufrufer-Thread bis zur Antwort zu belegen. Die Stufe endet mit derselben
     * Seite oder ausnahmsweise mit derselben {@link SourceAccessException}; ein Abbruch der Stufe bricht die Anfrage
     * ab. Ohne {@link #asyncStoredPages()} endet sie sofort mit {@code UNSUPPORTED}.
     */
    default CompletionStage<StoredPage> storedPageAsync(String pageId) {
        return CompletableFuture.failedFuture(new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann native Seitenantworten nicht asynchron liefern", null));
    }

    /**
     * Volltextsuche in der Quelle selbst, über Titel und Inhalt (Confluence: CQL {@code siteSearch}, wie die
     * Websuche), mit den Zugangsdaten der Quelle. Liefert die Treffer mit Titel, Textauszug, Adresse und Version in der
     * Rangfolge der Quelle, ohne Seiten zu laden; Rechte je Seite klärt der Aufrufer. Nur mit
     * {@link SourceCapability#LIVE_SEARCH}.
     *
     * @param text  Suchtext, nicht leer
     * @param limit höchstens so viele Treffer ab dem ersten, mindestens 1
     * @throws SourceAccessException {@code UNSUPPORTED} für eine Quelle ohne diese Fähigkeit, sonst wie
     *                               {@link #partitions()}
     */
    default List<SourceSearchMatch> searchPages(String text, int limit) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann nicht selbst suchen", null);
    }

    /**
     * Ob die Eingabe eine Adresse dieser Quelle sein kann (Muster der Quelle, ohne Abfrage). Nur dann lohnt
     * {@link #resolveAddress}; eine passende Adresse kann trotzdem auf keine Seite zeigen, etwa eine andere Instanz.
     */
    default boolean addressable(String address) {
        return false;
    }

    /**
     * Die Seiten-ID hinter einer Adresse dieser Quelle (#178): Seitenadressen mit und ohne ID, bei Confluence auch
     * {@code display/KEY/Titel}, {@code viewpage.action?spaceKey=…&title=…}, die Startseite {@code display/KEY},
     * Bookmarks {@code confluence://…} und Ressourcen-IDs der Quelle. Titel und Startseite fragt die Quelle mit ihren
     * eigenen Zugangsdaten nach; Rechte je Seite klärt der Aufrufer am Ergebnis. Nur mit
     * {@link SourceCapability#ADDRESSES}.
     *
     * @return die Seiten-ID oder leer, wenn die Adresse nicht zu dieser Quelle gehört oder keine Seite bezeichnet
     *         (auch eine Seite, die die Quelle nicht kennt oder nicht zeigt)
     * @throws SourceAccessException {@code UNSUPPORTED} für eine Quelle ohne diese Fähigkeit, sonst wie
     *                               {@link #partitions()}
     */
    default Optional<String> resolveAddress(String address) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann Adressen nicht auflösen", null);
    }

    /**
     * Alle Anhänge einer Seite als Metadaten, auch nicht eingebettete, mit den nativen Listenantworten (#9). Lädt keine
     * Dateien. Eine Liste, die die Quelle nicht vollständig liefern kann, scheitert, statt kürzer zurückzukommen;
     * einzelne unbrauchbare Einträge stehen in {@link PageAttachments#warnings()}.
     *
     * @param pageId ID der Seite in der Quelle
     * @throws SourceAccessException wie {@link #storedPage(String)}
     */
    default PageAttachments attachments(String pageId) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann Anhangslisten nicht liefern", null);
    }

    /**
     * Lädt einen Anhang aus {@link #attachments(String)} über die Anmeldung und den Netzweg der Quelle herunter (#19).
     * Nur Anhänge der Quelle selbst, nie fremde URLs. Eine Datei über {@code maxBytes} oder der Größengrenze der Quelle
     * wird nicht weiter gelesen und nie gekürzt.
     *
     * @param maxBytes größte Datei, die der Aufrufer annimmt
     * @throws AttachmentTooLargeException über {@code maxBytes} oder der Grenze der Quelle
     * @throws SourceAccessException       wie {@link #storedPage(String)}; {@code NOT_FOUND} auch für einen gelöschten
     *                                     Anhang
     */
    default AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle kann Anhänge nicht herunterladen", null);
    }

    /**
     * Die Bilder der Lese-Ansicht einer Seite, so wie die Quelle sie darstellt; auch Bilder, die nicht als Anhang
     * im Storage-Format stehen (Makros, gerenderte Diagramme). Lädt keine Bytes.
     *
     * @throws SourceAccessException {@code UNSUPPORTED} ohne Lese-Ansicht, sonst wie {@link #storedPage(String)}
     */
    default List<ViewImage> viewImages(String pageId) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle hat keine Lese-Ansicht", null);
    }

    /**
     * Lädt ein Bild aus {@link #viewImages(String)} über die Anmeldung und den Netzweg der Quelle; nur Adressen der
     * Quelle selbst, alles andere {@code UNSUPPORTED}. Nie gekürzt.
     *
     * @throws AttachmentTooLargeException über {@code maxBytes}
     */
    default byte[] viewImage(ViewImage image, long maxBytes) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle hat keine Lese-Ansicht", null);
    }

    /**
     * Die Lese-Ansicht einer Seite, wie die Quelle sie rendert (bei Confluence {@code body.export_view} mit
     * ausgeführten Makros), dazu der Lesetext für Leser und KI und die Bilder der Ansicht (#176). Lädt keine Bilder.
     * Die Quelle rendert mit ihren eigenen Zugangsdaten; was der Aufrufer davon sehen darf, entscheidet der
     * Anwendungsfall.
     *
     * @throws SourceAccessException {@code UNSUPPORTED} ohne Lese-Ansicht, sonst wie {@link #storedPage(String)}
     */
    default ReadingPage readingPage(String pageId) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle hat keine Lese-Ansicht", null);
    }

    /**
     * Direkte Kindseiten eines Knotens live, in der Reihenfolge der Quelle, stapelweise (#177). Die Quelle wählt die
     * Stapelgröße; jeder Folgestapel kommt mit {@link ChildPages#nextCursor()}, eine Obergrenze gibt es nicht.
     *
     * @param partitionKey Partition (bei Confluence der Space), deren Wurzelseiten gemeint sind, wenn
     *                     {@code parentPageId} {@code null} ist; sonst ohne Bedeutung
     * @param parentPageId Seite, deren Kinder gemeint sind, oder {@code null} für die Wurzelseiten der Partition
     * @param cursor       {@code null} für den ersten Stapel, sonst {@link ChildPages#nextCursor()} desselben Knotens
     * @throws SourceAccessException {@code UNSUPPORTED} ohne Hierarchie, {@code NOT_FOUND} für unbekannte Knoten,
     *                               sonst wie {@link #pageMetadata(String)}
     */
    default ChildPages children(String partitionKey, String parentPageId, String cursor) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle hat keinen Seitenbaum", null);
    }

    /**
     * Wo eine Seite im Baum steht: Partition mit Namen, Vorfahren von oben nach unten und die Seite selbst (#177).
     *
     * @throws SourceAccessException wie {@link #children}
     */
    default PagePosition position(String pageId) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle hat keinen Seitenbaum", null);
    }

    /**
     * Verweise einer Seite auf andere Seiten dieser Quelle (ausgehend, jede einmal, ohne Anhänge und ohne die Seite
     * selbst) und, nur wenn die Quelle sie vollständig kennt, die eingehenden (#177). Die Quelle liest dazu den Inhalt
     * mit ihren eigenen Zugangsdaten; was der Aufrufer davon sieht, entscheidet der Anwendungsfall.
     *
     * @throws SourceAccessException {@code UNSUPPORTED} ohne Verweise, sonst wie {@link #pageMetadata(String)}
     */
    default PageReferences references(String pageId) {
        throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED,
                "Quelle liefert keine Querverweise", null);
    }

    /**
     * Ein Knoten der Navigation.
     *
     * @param pageId       Seiten-ID
     * @param title        Titel der Seite
     * @param partitionKey Partition der Seite, wenn bekannt, sonst {@code null}
     * @param hasChildren  ob die Seite Kinder hat, soweit die Quelle das ohne Laden weiß; {@code null} unbekannt
     */
    record PageNode(String pageId, String title, String partitionKey, Boolean hasChildren) {

        public PageNode {
            Objects.requireNonNull(pageId, "pageId");
            title = title == null || title.isBlank() ? pageId : title;
        }
    }

    /**
     * Ein Stapel Kindseiten.
     *
     * @param partitionKey Partition der Kinder (bei Confluence der Space des Elternknotens)
     * @param nextCursor   Cursor des nächsten Stapels oder {@code null}, wenn alle geliefert sind
     */
    record ChildPages(String partitionKey, List<PageNode> pages, String nextCursor) {

        public ChildPages {
            Objects.requireNonNull(partitionKey, "partitionKey");
            pages = List.copyOf(pages);
            nextCursor = nextCursor == null || nextCursor.isEmpty() ? null : nextCursor;
        }
    }

    /**
     * Die Lage einer Seite im Baum.
     *
     * @param partitionName Name der Partition (bei Confluence {@code space.name}), sonst der Schlüssel
     * @param ancestors     Seiten zwischen Partition und Seite, von oben nach unten; leer für Wurzelseiten
     */
    record PagePosition(String partitionKey, String partitionName, List<PageNode> ancestors, PageNode page) {

        public PagePosition {
            Objects.requireNonNull(partitionKey, "partitionKey");
            partitionName = partitionName == null || partitionName.isBlank() ? partitionKey : partitionName;
            ancestors = List.copyOf(ancestors);
            Objects.requireNonNull(page, "page");
        }
    }

    /**
     * Querverweise einer Seite.
     *
     * @param outgoing Seiten, auf die die Seite verweist, in Reihenfolge der Seite
     * @param incoming Seiten, die auf sie verweisen, oder {@code null}, wenn die Quelle sie nicht vollständig kennt
     */
    record PageReferences(List<PageNode> outgoing, List<PageNode> incoming) {

        public PageReferences {
            outgoing = List.copyOf(outgoing);
            incoming = incoming == null ? null : List.copyOf(incoming);
        }
    }

    /**
     * Die Lese-Ansicht einer Seite.
     *
     * @param version Version der Seite, aus der die Ansicht stammt, oder {@code null}, wenn die Quelle sie nicht
     *                eindeutig nennt (etwa weil sich die Seite während des Lesens änderte)
     * @param html    bereinigter HTML-Körper ohne Skripte und eingebettete Objekte
     * @param baseUri Basis für relative Adressen
     * @param text    Lesetext mit Markdown-Struktur, wie ihn die Quelle für Index und KI liefert
     * @param images  Bilder der Ansicht in Dokumentreihenfolge
     */
    record ReadingPage(String version, String html, String baseUri, String text, List<ViewImage> images) {

        public ReadingPage {
            html = html == null ? "" : html;
            baseUri = baseUri == null ? "" : baseUri;
            text = text == null ? "" : text;
            images = List.copyOf(images);
        }
    }

    /**
     * Ein Bild der Lese-Ansicht.
     *
     * @param src          aufgelöste Adresse aus dem {@code <img>}
     * @param title        Alternativtext oder Titel, leer ohne
     * @param attachmentId ID des Anhangs, wenn das Bild einer ist, sonst {@code null}
     * @param mediaType    Medientyp laut Quelle oder {@code null}
     * @param version      Version laut Quelle oder {@code null}
     */
    record ViewImage(String src, String title, String attachmentId, String mediaType, String version) {

        public ViewImage {
            if (src == null || src.isBlank()) {
                throw new IllegalArgumentException("src fehlt");
            }
            title = title == null ? "" : title;
        }
    }
}
