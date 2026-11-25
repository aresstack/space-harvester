package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ftp.FakeMvsFtpHost;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.FtpSessions;
import com.aresstack.spaceharvester.deck.ftp.commonsnet.CommonsNetFtpConnector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Chat-Werkzeuge im FTP-Reiter gegen den erfundenen MVS-Host: Datasets und Members lesen. */
class FtpChatToolsTest {

    @Test
    void listsAndReadsDatasetsButDoesNotExposeJes() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (FakeMvsFtpHost host = FakeMvsFtpHost.sample()) {
            FtpSession session = new FtpSessions(new CommonsNetFtpConnector(), pool).open(host.profile(), "Testhost",
                    () -> new ServerCredentials(new ServerAccount("Testhost", host.host(), "KKR"),
                            "geheim".toCharArray()));
            session.connect().get();
            FtpChatTools tools = new FtpChatTools(session, () -> session);

            String members = tools.run(new AiChat.ToolCall("1", FtpChatTools.LIST, "{\"pfad\":\"'KKR.JCL'\"}"));
            String member = tools.run(new AiChat.ToolCall("2", FtpChatTools.READ,
                    "{\"pfad\":\"'KKR.JCL(BUILD)'\"}"));
            assertThat(members).contains("BUILD", "STERNE");
            assertThat(member).contains("//BUILD    JOB");
            assertThat(tools.definitions()).extracting(AiChat.Tool::name)
                    .containsExactly(FtpChatTools.LIST, FtpChatTools.READ);
            assertThat(tools.run(new AiChat.ToolCall("3", "jobs_auflisten", "{}"))).contains("Abgelehnt");
            assertThat(tools.run(new AiChat.ToolCall("4", "spool_lesen", "{}"))).contains("Abgelehnt");
            session.disconnect();
        } finally {
            pool.shutdownNow();
        }
    }
}
