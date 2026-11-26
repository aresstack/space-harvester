package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.SourceScope;
import com.aresstack.enterpriseai.source.api.SourceSettingsReader;
import org.apache.commons.net.ftp.FTPFile;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FtpInventoryTest {

    @Test
    public void inventoriesMultipleQualifiersAndAllMembersWithoutTheFormerLimit() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("HLQ1", Arrays.asList(directory("HLQ1.SUB"), directory("HLQ1.SEQ")));
        session.children.put("HLQ1.SUB", Collections.singletonList(directory("HLQ1.SUB.SRC")));
        List<FtpClientSession.FtpEntry> members = new ArrayList<FtpClientSession.FtpEntry>();
        for (int i = 0; i < 2100; i++) {
            members.add(file("HLQ1.SUB.SRC(P" + i + ")"));
        }
        session.children.put("HLQ1.SUB.SRC", members);
        session.sequential.add("HLQ1.SEQ");
        session.children.put("HLQ2", Arrays.asList(directory("HLQ2.EMPTY"), directory("HLQ2.SRC")));
        session.children.put("HLQ2.SRC", Collections.singletonList(file("HLQ2.SRC(MAIN)")));
        SourceScope scope = SourceScope.builder().startPoints("HLQ1", "HLQ2", "HLQ1.SUB")
                .maxDepth(Integer.MAX_VALUE).maxResources(Integer.MAX_VALUE).build();
        List<KnowledgeResource> result = source(session).discover(scope);
        assertEquals(2102, result.size());
        assertTrue(result.stream().anyMatch(r -> r.id().value().endsWith("/HLQ1.SEQ")));
        assertFalse(result.stream().anyMatch(r -> r.id().value().endsWith("/HLQ2.EMPTY")));
        assertTrue(result.stream().anyMatch(r -> r.id().value().endsWith("/HLQ2.SRC(MAIN)")));
        assertEquals(1, Collections.frequency(session.listed, "HLQ1.SUB"));
    }

    @Test
    public void rejectsUnqualifiedWildcardBeforeListingAnyStartPoint() throws Exception {
        FakeSession session = new FakeSession();
        try {
            source(session).discover(SourceScope.of("HLQ1", "%"));
            fail("Reject a global wildcard");
        } catch (KnowledgeSourceException e) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, e.kind());
        }
        assertTrue(session.listed.isEmpty());
    }

    @Test
    public void distinguishesSequentialDatasetsFromMemberListsAndPartitionedDatasets() {
        FTPFile sequential = new FTPFile();
        sequential.setRawListing("VOL001 3390 2026/11/20 1 2 FB 80 3200 PS HLQ.SOURCE");
        FTPFile partitioned = new FTPFile();
        partitioned.setRawListing("VOL001 3390 2026/11/20 1 2 FB 80 3200 PO HLQ.SOURCE");
        FTPFile member = new FTPFile();
        member.setRawListing("SOURCE 01.01 2026/11/20 2026/11/20 12:00 2 2 0 USER");
        assertTrue(CommonsNetFtpSession.sequentialDataset(new FTPFile[]{sequential}, "HLQ.SOURCE"));
        assertFalse(CommonsNetFtpSession.sequentialDataset(new FTPFile[]{partitioned, member}, "HLQ.SOURCE"));
        assertFalse(CommonsNetFtpSession.sequentialDataset(new FTPFile[]{sequential}, "HLQ.OTHER"));
    }

    @Test
    public void optionalFilterRecognizesProgramSourcesWithoutDownloadingTheWholeDocument() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("HLQ.DATA", Arrays.asList(file("HLQ.DATA(ABC)"), file("HLQ.DATA(DEF)"),
                file("HLQ.DATA(GHI)"), file("HLQ.DATA(JKL)")));
        session.samples.put("HLQ.DATA(ABC)", "000100 IDENTIFICATION DIVISION.\n000200 PROGRAM-ID. SAMPLE.");
        session.samples.put("HLQ.DATA(DEF)", "//RUN JOB CLASS=A\n//STEP EXEC PGM=TEST");
        session.samples.put("HLQ.DATA(GHI)", "       01 CUSTOMER-RECORD.\n          05 CUSTOMER-ID PIC 9(8).");
        session.samples.put("HLQ.DATA(JKL)", "Ordinary documentation with the words PROGRAM-ID and JOB");
        List<KnowledgeResource> result = source(session, true).discover(SourceScope.of("HLQ.DATA"));
        assertEquals(3, result.size());
        assertFalse(result.stream().anyMatch(r -> r.id().value().endsWith("(JKL)")));
    }

    @Test
    public void directScanIncludesSequentialDatasetButDoesNotOpenChildDirectories() throws Exception {
        FakeSession session = new FakeSession();
        session.children.put("HLQ", Arrays.asList(directory("HLQ.SRC"), directory("HLQ.PDS")));
        session.sequential.add("HLQ.SRC");
        List<KnowledgeResource> result = source(session).discover(SourceScope.builder()
                .startPoints("HLQ").maxDepth(0).build());
        assertEquals(1, result.size());
        assertTrue(result.get(0).id().value().endsWith("/HLQ.SRC"));
        assertEquals(Collections.singletonList("HLQ"), session.listed);
    }

    private static FtpKnowledgeSource source(FakeSession session) {
        return source(session, false);
    }

    private static FtpKnowledgeSource source(FakeSession session, boolean programsOnly) {
        SourceAddress address = SourceAddress.of("ftp");
        FtpConnectionSettings settings = FtpSourceProvider.connection(new SourceSettingsReader(
                FtpSourceProvider.sourceType(), SourceSettings.empty().with("host", "localhost")));
        FtpSessionPool<FtpClientSession> pool = new FtpSessionPool<FtpClientSession>(address, settings,
                null, (ignored, user, password) -> session);
        return new FtpKnowledgeSource(address, settings, pool, programsOnly);
    }

    private static FtpClientSession.FtpEntry directory(String path) {
        return new FtpClientSession.FtpEntry(path, path, true, 0);
    }

    private static FtpClientSession.FtpEntry file(String path) {
        return new FtpClientSession.FtpEntry(path, path, false, 0);
    }

    private static final class FakeSession implements FtpClientSession {
        private final Map<String, List<FtpEntry>> children = new LinkedHashMap<String, List<FtpEntry>>();
        private final List<String> listed = new ArrayList<String>();
        private final List<String> sequential = new ArrayList<String>();
        private final Map<String, String> samples = new LinkedHashMap<String, String>();

        @Override
        public boolean mvs() {
            return true;
        }

        @Override
        public List<FtpEntry> list(String path) {
            listed.add(path);
            return children.getOrDefault(path, Collections.<FtpEntry>emptyList());
        }

        @Override
        public String sampleText(String path) {
            return samples.get(path);
        }

        @Override
        public String readText(String path) {
            throw new AssertionError("Do not download document content during inventory");
        }

        @Override
        public boolean isSequentialDataset(String path) {
            return sequential.contains(path);
        }

        @Override
        public void close() {
        }
    }
}