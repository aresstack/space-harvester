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

/** Chat-Werkzeuge im FTP-Reiter gegen den erfundenen MVS-Host: Datasets, Members, Jobs, Spool lesen. */
class FtpChatToolsTest {

    @Test
    void listsAndReadsDatasetsJobsAndSpool() throws Exception {
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
            String jobs = tools.run(new AiChat.ToolCall("3", FtpChatTools.JOBS, "{}"));
            String spool = tools.run(new AiChat.ToolCall("4", FtpChatTools.SPOOL, "{\"job_id\":\"JOB04700\"}"));

            System.out.println(members + "\n" + jobs + "\n" + spool);
            assertThat(members).contains("BUILD", "STERNE");
            assertThat(member).contains("//BUILD    JOB");
            assertThat(jobs).contains("JOB04700", "BUILD", "JOB04701");
            assertThat(spool).startsWith("Spool JOB04700");
            session.disconnect();
        } finally {
            pool.shutdownNow();
        }
    }
}
