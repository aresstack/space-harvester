package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SecretVaultTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Antwortet KeePass beim Start nicht, wartet die Quelle auf ihre Zugangsdaten. Sobald KeePass wieder antwortet, holt
 * die App sie nach und startet die Quellen neu, ohne dass die App neu zu starten ist.
 */
class KeePassRecoveryTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void aSourceWaitingForKeePassRecoversOnceKeePassAnswersAgain() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                  source-keepass-entries:
                    wiki: PAT_Confluence
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Firmenwiki
                      base-url: https://wiki.example.invalid
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                """.formatted(home.resolve("data")));
        SecretVault vault = new SecretVault(home.resolve("secrets"), new SecretVaultTest.FakeDpapi());
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test", vault);
        Sleepy keePass = new Sleepy();
        keePass.entries.put("PAT_Confluence", new String[] {"https://wiki.example.invalid", "pat-aus-keepass"});
        context.keePass(new KeePassLink(keePass, () -> true));

        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(context.runtime().waitingForSecret()).as("KeePass antwortete beim Start nicht").contains("wiki");
        assertThat(context.secrets().has("wiki")).isFalse();

        // KeePass antwortet wieder: Ein beliebiger gelungener Zugriff meldet das, die Quelle startet mit dem Token.
        keePass.asleep = false;
        context.keePass().titles();
        SettingDialogsTest.await(() -> context.runtime() != null && context.runtime().waitingForSecret().isEmpty());
        assertThat(context.secrets().has("wiki")).isTrue();
        assertThat(Files.readString(config)).doesNotContain("pat-aus-keepass");
    }

    /** Wie {@link SettingDialogsTest.FakeKeePass}, antwortet aber nicht, solange {@code asleep}. */
    private static final class Sleepy extends SettingDialogsTest.FakeKeePass {
        volatile boolean asleep = true;

        @Override
        public com.aresstack.enterpriseai.adyton.VaultEntryDraft read(String title) throws SecretUnavailableException {
            if (asleep) {
                throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_AVAILABLE, null,
                        "KeePassRPC nicht verfügbar");
            }
            return super.read(title);
        }
    }
}
