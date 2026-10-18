package com.aresstack.spaceharvester.application.reading;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Was die Quelle beim Rendern einer Seite aus anderen Seiten oder Systemen einbindet, erkannt am Storage-Format
 * ({@code body.storage}). Confluence rendert Makros wie {@code include}, {@code excerpt-include}, {@code children},
 * {@code pagetree} oder {@code jira} mit den Zugangsdaten der Quelle; die gerenderte Ansicht kann also Inhalte zeigen,
 * die der Aufrufer selbst nicht lesen darf.
 *
 * <p>Fail closed: Als eigener Inhalt gelten nur Makros einer festen Liste, die allein Inhalte der Seite selbst
 * darstellen. Jedes andere Makro, ein Makroparameter mit Verweis auf eine andere Seite oder einen Space (auch
 * {@code pageId} einer anderen Seite, etwa bei draw.io), ein Anhang einer anderen Seite und ein Verweis über die
 * Content-ID zählen als fremd.
 *
 * @param macros     Namen der fremden Makros in Dokumentreihenfolge, ohne Doppelte
 * @param references ob Parameter, Bilder oder Links auf Inhalte anderer Seiten verweisen
 */
public record ForeignContent(List<String> macros, boolean references) {

    /** Makros, die nur Inhalte der Seite selbst darstellen. */
    static final Set<String> LOCAL_MACROS = Set.of("anchor", "code", "column", "details", "drawio", "excerpt",
            "expand", "info", "mermaid", "mermaid-cloud", "mermaid-macro", "multimedia", "noformat", "note", "panel",
            "plantuml", "section", "status", "tip", "toc", "toc-zone", "unmigrated-wiki-markup", "view-file",
            "viewdoc", "viewpdf", "viewppt", "viewxls", "warning");

    public static final ForeignContent NONE = new ForeignContent(List.of(), false);

    private static final Pattern MACRO = Pattern.compile("<ac:(?:structured-)?macro\\b[^>]*\\bac:name=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAMETER = Pattern.compile("<ac:parameter\\b[^>]*>(.*?)</ac:parameter>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_CONTENT = Pattern.compile("<ri:(?:page|blog-post|space|content-entity)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Ein Anhang, der zu einer anderen Seite gehört: {@code <ri:attachment …><ri:page …/></ri:attachment>}. */
    private static final Pattern FOREIGN_ATTACHMENT = Pattern.compile(
            "<ri:attachment\\b[^>]*[^/]>\\s*<ri:(?:page|blog-post|content-entity)\\b", Pattern.CASE_INSENSITIVE);
    /** Verweis über die Content-ID: Confluence setzt dafür Titel der Zielseite ein. */
    private static final Pattern CONTENT_ENTITY = Pattern.compile("<ri:content-entity\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_ID_PARAMETER = Pattern.compile(
            "<ac:parameter\\b[^>]*\\bac:name=\"pageId\"[^>]*>\\s*([^<\\s]*)\\s*</ac:parameter>",
            Pattern.CASE_INSENSITIVE);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ForeignContent {
        macros = List.copyOf(macros);
    }

    /** Ob die gerenderte Ansicht Inhalte außerhalb der Seite enthalten kann. */
    public boolean present() {
        return !macros.isEmpty() || references;
    }

    /**
     * Aus dem Storage-Format; {@code null} oder leer ergibt {@link #NONE}.
     *
     * @param pageId die Seite selbst; ein Makroparameter {@code pageId} mit anderem Wert zählt als fremd
     */
    public static ForeignContent of(String storage, String pageId) {
        if (storage == null || storage.isEmpty()) {
            return NONE;
        }
        Set<String> foreign = new LinkedHashSet<>();
        Matcher macro = MACRO.matcher(storage);
        while (macro.find()) {
            String name = macro.group(1).toLowerCase(Locale.ROOT);
            if (!LOCAL_MACROS.contains(name)) {
                foreign.add(name);
            }
        }
        boolean references = FOREIGN_ATTACHMENT.matcher(storage).find() || CONTENT_ENTITY.matcher(storage).find();
        Matcher parameter = PARAMETER.matcher(storage);
        while (!references && parameter.find()) {
            references = OTHER_CONTENT.matcher(parameter.group(1)).find();
        }
        Matcher page = PAGE_ID_PARAMETER.matcher(storage);
        while (!references && page.find()) {
            references = !page.group(1).isEmpty() && !page.group(1).equals(pageId);
        }
        return new ForeignContent(List.copyOf(foreign), references);
    }

    /** Aus dem Snapshot: bei Confluence-JSON {@code body.storage.value}, sonst der Inhalt als Text. */
    public static ForeignContent of(PageSnapshot snapshot) {
        return of(storage(snapshot), snapshot.key().pageId());
    }

    /** Das Storage-Format einer Seite oder {@code null}, wenn die Payload keines trägt. */
    public static String storage(PageSnapshot snapshot) {
        byte[] bytes = snapshot.payload().bytes();
        if (!"application/json".equals(snapshot.payload().mediaType())) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            JsonNode value = JSON.readTree(bytes).path("body").path("storage").path("value");
            return value.isString() ? value.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
