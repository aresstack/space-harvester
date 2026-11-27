package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * What the structured capture actually SAW of a result page, in one log line — the counts that tell a
 * flattened or empty capture apart from a page the analysis misread — plus an opt-in dump of the rendered
 * page and its capture for building a regression case from a real SERP. The dump is off unless the system
 * property {@value #SNAPSHOT_DIR_PROPERTY} (or the environment variable {@value #SNAPSHOT_DIR_ENV}) names
 * a directory; it never contains cookies or storage, and the page's scripts are removed.
 */
final class SerpCaptureDiagnostics {

    static final String SNAPSHOT_DIR_PROPERTY = "askai.serp.snapshotDir";
    static final String SNAPSHOT_DIR_ENV = "ASKAI_SERP_SNAPSHOT_DIR";

    /** Elements a dump drops with their content (inline script text carries session values, never results). */
    private static final java.util.regex.Pattern NOT_PAGE_CONTENT = java.util.regex.Pattern.compile(
            "<(script|noscript|iframe)\\b[^>]*>.*?</\\1\\s*>|<(script|iframe)\\b[^>]*/>",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /**
     * The serialized DOM ({@code page.content()}, read by Playwright, no script in the page) without scripts,
     * noscript and iframes — stripped here in the JVM.
     */
    static String sanitizedHtml(String content) {
        return content == null ? "" : NOT_PAGE_CONTENT.matcher(content).replaceAll("");
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SerpCaptureDiagnostics() {
    }

    static String summary(RenderedPageDocument document) {
        int visible = 0;
        int visibleExternal = 0;
        for (RenderedLinkDescriptor link : document.links) {
            if (!link.visible) {
                continue;
            }
            visible++;
            if (link.domainClassification == DomainClassification.EXTERNAL_DOMAIN
                    && link.resolvedTargetUrl.startsWith("http")) {
                visibleExternal++;
            }
        }
        return "[serp-capture] snapshot=" + document.snapshotId
                + " url=" + document.pageUrl
                + " containers=" + document.containers.size()
                + " links=" + document.links.size()
                + " visibleLinks=" + visible
                + " visibleExternalLinks=" + visibleExternal
                + " truncated=" + document.captureTruncated
                + " documentHeight=" + document.viewport.documentHeight
                + (document.captureWarnings.isEmpty() ? "" : " warnings=" + document.captureWarnings);
    }

    /** The configured dump directory, or null when dumping is off. */
    static Path snapshotDirectory() {
        String configured = System.getProperty(SNAPSHOT_DIR_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv(SNAPSHOT_DIR_ENV);
        }
        return configured == null || configured.trim().isEmpty() ? null : Paths.get(configured.trim());
    }

    /** Writes {@code <snapshot>.html} and {@code <snapshot>.capture.json}; returns the html path. */
    static Path dump(Path directory, RenderedPageDocument document, String sanitizedHtml)
            throws IOException {
        Files.createDirectories(directory);
        String base = document.snapshotId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path html = directory.resolve(base + ".html");
        Files.write(html, (sanitizedHtml == null ? "" : sanitizedHtml)
                .getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve(base + ".capture.json"),
                GSON.toJson(document).getBytes(StandardCharsets.UTF_8));
        return html;
    }
}
