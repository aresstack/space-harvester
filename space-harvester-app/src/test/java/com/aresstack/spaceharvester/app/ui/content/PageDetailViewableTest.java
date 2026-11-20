package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Die Original-Ansicht bleibt nicht leer, wenn dem Kopf die Art der Ressource fehlt. */
class PageDetailViewableTest {

    @Test
    void attributesMissingInTheHeaderComeFromTheSnapshot() {
        TreeMap<String, String> snapshotAttributes = new TreeMap<>();
        snapshotAttributes.put(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND);
        snapshotAttributes.put(PageMetadata.REMOTE_MEDIA, "true");
        snapshotAttributes.put("dav.contentLength", "50000000");
        TreeMap<String, String> headerAttributes = new TreeMap<>();
        headerAttributes.put("dav.contentLength", "60000000");
        PageMetadata header = new PageMetadata("p", "r", "KickOff.mp4", "bscw", null, "2", null, null,
                headerAttributes);
        PageMetadata snapshot = new PageMetadata("p", "r", "KickOff.mp4", "bscw", null, "2", null,
                URI.create("https://bscw.example/KickOff.mp4"), snapshotAttributes);

        PageMetadata shown = PageDetailPanel.viewable(header, snapshot);

        assertThat(shown.attributes()).containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND)
                .containsEntry(PageMetadata.REMOTE_MEDIA, "true").containsEntry("dav.contentLength", "60000000");
        assertThat(shown.url()).isEqualTo(URI.create("https://bscw.example/KickOff.mp4"));
        assertThat(PageDetailPanel.viewable(snapshot, header)).isSameAs(snapshot);
    }
}
