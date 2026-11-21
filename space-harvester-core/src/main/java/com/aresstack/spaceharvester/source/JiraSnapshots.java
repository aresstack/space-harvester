package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.source.SourceLocators;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.ResourceMetadataSource;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.StoredContent;
import com.aresstack.enterpriseai.source.api.StoredContentSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Jira im Core ({@link EacSourceAdapters#jira()}): wie die Kopie aus dem Enterprise AI Client, mit zwei Anpassungen an
 * den Abgleich des Space Harvester.
 *
 * <ul>
 *   <li>Abgelegt wird der Lesetext des Vorgangs ({@code load}: Beschreibung, Kopfdaten und alle Kommentare bis
 *   {@code maxComments}, auch über mehrere Seiten), nicht die rohe REST-Antwort. Nur dieser Text kommt in Volltextindex
 *   und Anreicherungen ({@link KnowledgeDocumentTextProjector}); die rohe Antwort enthielte zudem nur die erste
 *   Kommentarseite.</li>
 *   <li>Ohne eigene Startpunkte (Standard {@code project:*}) gilt die Auflistung je Projekt unverändert, wie bei
 *   Confluence ohne eigene Spaces; sonst durchsuchte der Core vor jeder Projektliste die ganze Jira.</li>
 * </ul>
 */
final class JiraSnapshots {

    /** Standard-Startpunkt der Jira-Quelle: alle Projekte außer den abgehakten. */
    static final String ALL_PROJECTS = "project:*";

    private JiraSnapshots() {
    }

    static SourceAdapter.OpenedSource adapt(SourceAdapter.OpenedSource opened) {
        SourceScope scope = opened.scope();
        boolean allProjects = scope != null && scope.startPoints().equals(List.of(ALL_PROJECTS));
        return new SourceAdapter.OpenedSource(textSnapshots(opened.port()), opened.delegated(),
                allProjects ? null : scope, opened.webPorts());
    }

    /**
     * Derselbe Port mit allen seinen Fähigkeiten; nur {@link StoredContentSource#storedContent} liefert den Lesetext
     * aus {@code load} statt der rohen Antwort.
     */
    static KnowledgeSourcePort textSnapshots(KnowledgeSourcePort port) {
        if (!(port instanceof StoredContentSource) || !(port instanceof ResourceMetadataSource)) {
            return port;
        }
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type = port.getClass(); type != null; type = type.getSuperclass()) {
            collect(type, interfaces);
        }
        return (KnowledgeSourcePort) Proxy.newProxyInstance(JiraSnapshots.class.getClassLoader(),
                interfaces.toArray(Class<?>[]::new), (proxy, method, args) -> {
                    if (method.getName().equals("equals") && method.getParameterCount() == 1
                            && method.getDeclaringClass() == Object.class) {
                        return proxy == args[0];
                    }
                    if (isStoredContent(method)) {
                        return text(port, (String) args[0]);
                    }
                    try {
                        return method.invoke(port, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void collect(Class<?> type, Set<Class<?>> interfaces) {
        for (Class<?> implemented : type.getInterfaces()) {
            if (interfaces.add(implemented)) {
                collect(implemented, interfaces);
            }
        }
    }

    /**
     * Die Ressourcen-ID zum Vorgangsschlüssel: {@code jira:<Locator der Basis-URL>/issue/<KEY>} wie in der Quelle, die
     * das selbst bestätigt ({@link StoredContentSource#localId}); sonst über die Kopfdaten des Vorgangs.
     */
    private static KnowledgeResourceId resourceId(KnowledgeSourcePort port, String localId)
            throws KnowledgeSourceException {
        try {
            KnowledgeResourceId candidate = KnowledgeResourceId.of("jira",
                    SourceLocators.url(port.sourceId().value()) + "/issue/" + localId);
            if (localId.equals(((StoredContentSource) port).localId(candidate))) {
                return candidate;
            }
        } catch (IllegalArgumentException e) {
            // unten über die Kopfdaten
        }
        return ((ResourceMetadataSource) port).resourceMetadata(localId).id();
    }

    private static boolean isStoredContent(Method method) {
        return method.getDeclaringClass() == StoredContentSource.class && method.getName().equals("storedContent");
    }

    private static StoredContent text(KnowledgeSourcePort port, String localId) throws KnowledgeSourceException {
        KnowledgeDocument document = port.load(resourceId(port, localId));
        return new StoredContent(document.resource(), document.text().getBytes(StandardCharsets.UTF_8),
                KnowledgeDocumentTextProjector.MEDIA_TYPE, KnowledgeDocumentTextProjector.NATIVE_FORMAT,
                Instant.now());
    }
}
