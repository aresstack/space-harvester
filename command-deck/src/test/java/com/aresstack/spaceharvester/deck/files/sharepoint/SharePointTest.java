package com.aresstack.spaceharvester.deck.files.sharepoint;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SharePoint-Adressen wie in MainframeMate und die Anmeldung je Rechner. */
class SharePointTest {

    @Test
    void mapsWebAddressesToWebDavPathsAndBack() {
        assertThat(SharePointPaths.toUncPath("https://intranet.example.org/sites/Team Raum/Freigegebene Dokumente"))
                .contains("\\\\intranet.example.org@SSL\\DavWWWRoot\\sites\\Team Raum\\Freigegebene Dokumente");
        assertThat(SharePointPaths.toUncPath("http://wiki.example.org:8080/sites/A%20B/"))
                .contains("\\\\wiki.example.org@8080\\DavWWWRoot\\sites\\A B");
        assertThat(SharePointPaths.toUncPath("C:\\Daten")).isEmpty();
        assertThat(SharePointPaths.toWebUrl("\\\\intranet.example.org@SSL\\DavWWWRoot\\sites\\Team"))
                .isEqualTo("https://intranet.example.org/sites/Team");
        assertThat(SharePointPaths.displayName("https://intranet.example.org/sites/Projekt-Alpha/Dokumente"))
                .isEqualTo("Projekt Alpha");
        assertThat(SharePointPaths.isWebDav("\\\\h@SSL\\DavWWWRoot\\x")).isTrue();
        assertThat(new SharePointSite(null, "https://h.example.org/sites/Raum").name()).isEqualTo("Raum");
    }

    @Test
    void asksOncePerHostAndAcceptsCredentials() {
        List<String> calls = new ArrayList<>();
        WebDavLogin login = new WebDavLogin() {
            @Override
            public Outcome connect(String uncPath) {
                calls.add("ohne");
                return Outcome.credentialsNeeded("Die Windows-Anmeldung reicht nicht.");
            }

            @Override
            public Outcome connect(String uncPath, String user, char[] password) {
                calls.add(user + ":" + new String(password));
                return "geheim".equals(new String(password)) ? Outcome.ok() : Outcome.credentialsNeeded("abgelehnt");
            }
        };
        SharePointAccess access = new SharePointAccess(login, Runnable::run);
        String unc = "\\\\h.example.org@SSL\\DavWWWRoot\\sites\\Raum";
        List<SharePointAccess.Result> results = new ArrayList<>();

        assertThat(access.needsLogin(unc)).isTrue();
        access.connect(unc, results::add);
        access.connect(unc, "anna", "falsch".toCharArray(), results::add);
        access.connect(unc, "anna", "geheim".toCharArray(), results::add);

        assertThat(results).extracting(SharePointAccess.Result::connected).containsExactly(false, false, true);
        assertThat(results.get(0).outcome().needsCredentials()).isTrue();
        assertThat(access.needsLogin(unc + "\\Unterordner")).isFalse();
        access.connect(unc, results::add);
        assertThat(calls).containsExactly("ohne", "anna:falsch", "anna:geheim");
        assertThat(access.needsLogin("C:\\Daten")).isFalse();
    }
}
