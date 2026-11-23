package com.aresstack.spaceharvester.deck.eakte.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebDavXmlTest {

    @Test
    void readsAbsoluteHrefsDisplayNamesAndSkipsTheFolderItself() throws IOException {
        String xml = "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">"
                + "<D:response><D:href>https://akte.example.org/fsc/webdav/</D:href><D:propstat><D:prop>"
                + "<D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status>"
                + "</D:propstat></D:response>"
                + "<D:response><D:href>/fsc/webdav/COO.1.2/</D:href><D:propstat><D:prop>"
                + "<D:displayname>Mappe Nord</D:displayname><D:resourcetype><D:collection/></D:resourcetype>"
                + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                + "<D:response><D:href>/fsc/webdav/Brief%20A.pdf</D:href><D:propstat><D:prop>"
                + "<D:getcontentlength>12</D:getcontentlength><D:resourcetype/></D:prop>"
                + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat><D:propstat><D:prop><D:displayname/></D:prop>"
                + "<D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response></D:multistatus>";
        List<EakteEntry> entries = WebDavXml.entries(xml.getBytes(StandardCharsets.UTF_8), "/fsc/webdav");
        assertThat(entries).extracting(EakteEntry::name).containsExactly("Mappe Nord", "Brief A.pdf");
        assertThat(entries.get(0).href()).isEqualTo("/fsc/webdav/COO.1.2/");
        assertThat(entries.get(1).size()).isEqualTo(12);
    }

    @Test
    void rejectsDoctypes() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<D:multistatus xmlns:D=\"DAV:\">&e;</D:multistatus>";
        assertThatThrownBy(() -> WebDavXml.entries(xml.getBytes(StandardCharsets.UTF_8), "/"))
                .isInstanceOf(IOException.class);
    }
}
