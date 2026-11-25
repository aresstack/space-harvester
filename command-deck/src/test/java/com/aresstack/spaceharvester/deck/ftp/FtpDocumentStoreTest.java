package com.aresstack.spaceharvester.deck.ftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.SaveResult;
import com.aresstack.spaceharvester.deck.ftp.commonsnet.CommonsNetFtpConnector;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FtpDocumentStoreTest {

    private FakeMvsFtpHost host;
    private ExecutorService pool;
    private FtpSession session;
    private FtpDocumentStore store;

    @BeforeEach
    void open() throws Exception {
        host = FakeMvsFtpHost.sample();
        pool = Executors.newCachedThreadPool();
        session = new FtpSessions(new CommonsNetFtpConnector(), pool).open(host.profile(), "Testhost",
                () -> new ServerCredentials(new ServerAccount("Testhost", host.host(), "KKR"), "geheim".toCharArray()));
        store = new FtpDocumentStore(session);
    }

    @AfterEach
    void close() throws Exception {
        session.disconnect();
        pool.shutdownNow();
        host.close();
    }

    @Test
    void loadsAMemberWithItsNameAndSavesItBack() throws Exception {
        EditorDocument loaded = store.load("'KKR.JCL(BUILD)'");
        assertThat(loaded.name()).isEqualTo("BUILD");
        assertThat(loaded.text()).startsWith("//BUILD    JOB");
        assertThat(loaded.lineEnding()).isEqualTo("\n");

        SaveResult result = store.save(loaded, loaded.text() + "\n//STEP2    EXEC PGM=IEFBR14   \n", false);
        assertThat(result).isInstanceOf(SaveResult.Saved.class);
        // Satzformat: rechts gekürzt, kein leerer letzter Satz.
        assertThat(host.text("KKR.JCL(BUILD)")).endsWith("//STEP2    EXEC PGM=IEFBR14");
        EditorDocument saved = ((SaveResult.Saved) result).document();
        assertThat(saved.hash()).isEqualTo(store.load("'KKR.JCL(BUILD)'").hash());
        // Der Stand ist der Editortext, sonst bliebe das Dokument nach dem Speichern „geändert“.
        assertThat(saved.text()).isEqualTo(loaded.text() + "\n//STEP2    EXEC PGM=IEFBR14   \n");
        assertThat(store.save(saved, saved.text(), false)).isInstanceOf(SaveResult.Saved.class);
    }

    @Test
    void refusesToOverwriteAChangeOnTheHostUnlessAsked() throws Exception {
        EditorDocument loaded = store.load("'KKR.JCL(STERNE)'");
        host.member("KKR.JCL", "STERNE", "//STERNE  JOB GEAENDERT");
        assertThat(store.save(loaded, "//MEINS", false)).isInstanceOf(SaveResult.Conflict.class);
        assertThat(host.text("KKR.JCL(STERNE)")).isEqualTo("//STERNE  JOB GEAENDERT");
        assertThat(store.save(loaded, "//MEINS", true)).isInstanceOf(SaveResult.Saved.class);
        assertThat(host.text("KKR.JCL(STERNE)")).isEqualTo("//MEINS");
    }

    @Test
    void resolvesCopybooksInConfiguredOrderAndQualifiedLibrary() throws Exception {
        host.member("KKR.CPY1", "OTHER", "       01 OTHER PIC X.");
        host.member("KKR.CPY2", "BOOK", "       01 SECOND-FIELD PIC X.");
        host.member("KKR.JCL", "BOOK", "       01 FALLBACK PIC X.");
        session.disconnect();
        session = new FtpSessions(new CommonsNetFtpConnector(), pool).open(
                host.profile().withSyslib(java.util.List.of("KKR.CPY1", "KKR.CPY2")), "Testhost",
                () -> new ServerCredentials(new ServerAccount("Testhost", host.host(), "KKR"), "geheim".toCharArray()));
        store = new FtpDocumentStore(session);
        assertThat(store.resolveCopybook("'KKR.JCL(BUILD)'", "BOOK", "").orElseThrow().text())
                .contains("SECOND-FIELD");
        assertThat(store.resolveCopybook("'KKR.JCL(BUILD)'", "BOOK", "KKR.CPY2")).isPresent();
        assertThat(store.resolveCopybook("'KKR.JCL(BUILD)'", "BOOK", "CPY2")).isPresent();
        assertThat(store.resolveCopybook("'KKR.JCL(BUILD)'", "MISSING", "")).isEmpty();
        assertThatThrownBy(() -> store.resolveCopybook("'KKR.JCL(BUILD)'", "BOOK", "OTHER.LIB"))
                .isInstanceOf(DocumentException.class);
    }

    @Test
    void normalizesOnlyMvsRecords() {
        assertThat(FtpDocumentStore.expected("A  \nB ", true)).isEqualTo("A\nB");
        assertThat(FtpDocumentStore.expected("a  \nb\n", false)).isEqualTo("a  \nb\n");
    }

    @Test
    void namesAndFailures() {
        assertThat(FtpDocumentStore.name("'KKR.DATA.LOG'")).isEqualTo("KKR.DATA.LOG");
        assertThat(FtpDocumentStore.name("/u/kkr/build.sh")).isEqualTo("build.sh");
        assertThatThrownBy(() -> store.load("'KKR.JCL(FEHLT)'")).isInstanceOf(DocumentException.class)
                .hasMessageContaining("FEHLT");
    }
}
