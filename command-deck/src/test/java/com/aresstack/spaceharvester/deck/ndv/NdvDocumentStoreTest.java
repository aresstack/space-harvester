package com.aresstack.spaceharvester.deck.ndv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.SaveResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Natural-Quellen im gemeinsamen Editor: Laden, Speichern, Konflikt wie writeIfUnchanged in MainframeMate. */
class NdvDocumentStoreTest {

    private final FakeNdvServer server = FakeNdvServer.sample();
    private final NdvSessions sessions = new NdvSessions(server, Runnable::run);
    private final NdvSession session = sessions.open(new NdvProfile("10.0.0.7", 2700), "",
            () -> new ServerCredentials(new ServerAccount("Host", "10.0.0.7", "PILOT"), "geheim".toCharArray()));
    private final NdvDocumentStore store = new NdvDocumentStore(session);

    private NdvObject object(String library, String name) throws Exception {
        List<NdvObject> found = new ArrayList<>();
        session.objects(library, name, (page, total) -> found.addAll(page));
        return found.getFirst();
    }

    @Test
    void loadsTheSourceUnderItsPath() throws Exception {
        String location = store.register(object("LAGER", "LAGER001"));

        EditorDocument document = store.load(location);
        assertThat(location).isEqualTo("LAGER/LAGER001.NSP");
        assertThat(document.name()).isEqualTo("LAGER001.NSP");
        assertThat(document.text()).contains("READ ARTIKEL BY NUMMER");
        assertThat(server.writes()).isEmpty();
    }

    @Test
    void savesWhenTheServerStillHasTheLoadedText() throws Exception {
        String location = store.register(object("KASSE", "KASSE001"));
        EditorDocument loaded = store.load(location);

        SaveResult result = store.save(loaded, "* geändert\nEND", false);
        assertThat(result).isInstanceOf(SaveResult.Saved.class);
        assertThat(server.source("KASSE", "KASSE001")).isEqualTo("* geändert\nEND");
        assertThat(((SaveResult.Saved) result).document().hash()).isNotEqualTo(loaded.hash());
    }

    @Test
    void reportsAConflictWhenSomeoneElseChangedIt() throws Exception {
        NdvObject object = object("KASSE", "KASSE001");
        String location = store.register(object);
        EditorDocument loaded = store.load(location);
        session.write(object, "* von jemand anderem");

        SaveResult result = store.save(loaded, "* meins", false);
        assertThat(result).isInstanceOf(SaveResult.Conflict.class);
        assertThat(server.source("KASSE", "KASSE001")).isEqualTo("* von jemand anderem");

        assertThat(store.save(loaded, "* meins", true)).isInstanceOf(SaveResult.Saved.class);
        assertThat(server.source("KASSE", "KASSE001")).isEqualTo("* meins");
    }

    @Test
    void anUnknownPathOrAFailedReadHasAReason() throws Exception {
        assertThatThrownBy(() -> store.load("X/Y.NSP")).isInstanceOf(DocumentException.class)
                .hasMessageContaining("Unbekannt");
        String location = store.register(object("LAGER", "LAGER001"));
        session.disconnect();
        assertThatThrownBy(() -> store.load(location)).isInstanceOf(DocumentException.class)
                .hasMessageContaining("getrennt");
    }
}
