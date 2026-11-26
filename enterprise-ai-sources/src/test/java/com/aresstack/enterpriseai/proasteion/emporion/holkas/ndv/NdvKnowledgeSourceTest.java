package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeSystemFile;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectKind;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectType;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.PalTypeSystemFileFactory;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.SourceScope;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class NdvKnowledgeSourceTest {

    private final AtomicLong clock = new AtomicLong();
    private final FakeClient first = new FakeClient();
    private final FakeClient second = new FakeClient();
    private int connections;

    private final NdvKnowledgeSource source = new NdvKnowledgeSource(SourceAddress.of("ndv", "host:8011"),
            "host", 8011, SecretRef.of("test"), ref -> new SecretMaterial(ref, "user", new char[0]),
            (host, port, user, password) -> connections++ == 0 ? first : second, clock::get);

    @Test
    public void inventoryMetadataEliminatesPerObjectListingsAndLogons() throws Exception {
        for (int i = 0; i < 100; i++) {
            first.objects.add(object("P" + i, "NSP", "2026-11-25", 20));
        }
        List<KnowledgeResource> inventory = source.discover(SourceScope.of("LIB"));
        assertEquals(100, inventory.size());
        for (KnowledgeResource resource : inventory) {
            KnowledgeDocument document = source.load(resource.id());
            assertEquals(resource.revision(), document.resource().revision());
        }
        assertEquals(1, first.logons);
        assertEquals(1, first.systemFiles);
        assertEquals(1, first.listings);
        assertEquals(100, first.downloads);
        assertEquals(1, connections);
    }

    @Test
    public void metadataIsConsumedOnceAndRefreshedOnDirectRead() throws Exception {
        first.objects.add(object("BOOK", "NSP", "date", 20));
        KnowledgeResource listed = source.discover(SourceScope.of("LIB")).get(0);
        source.load(listed.id());
        first.objects.set(0, object("BOOK", "NSP", "date", 21));
        KnowledgeDocument refreshed = source.load(listed.id());
        assertNotEquals(listed.revision(), refreshed.resource().revision());
        assertEquals(2, first.listings);
        assertEquals(1, first.logons);
    }

    @Test
    public void expiredMetadataIsNotUsed() throws Exception {
        first.objects.add(object("BOOK", "NSP", "date", 20));
        KnowledgeResource listed = source.discover(SourceScope.of("LIB")).get(0);
        first.objects.set(0, object("BOOK", "NSP", "new-date", 20));
        clock.addAndGet(TimeUnit.MINUTES.toNanos(6));
        assertNotEquals(listed.revision(), source.load(listed.id()).resource().revision());
        assertEquals(2, first.listings);
    }

    @Test
    public void reconnectDiscardsMetadataAndSystemFileSelection() throws Exception {
        first.objects.add(object("BOOK", "NSP", "old-date", 20));
        KnowledgeResource listed = source.discover(SourceScope.of("LIB")).get(0);
        first.failDownload = true;
        second.objects.add(object("BOOK", "NSP", "new-date", 20));
        assertNotEquals(listed.revision(), source.load(listed.id()).resource().revision());
        assertEquals(2, connections);
        assertEquals(1, second.listings);
        assertEquals(1, second.systemFiles);
        assertEquals(1, second.logons);
    }

    @Test
    public void sameNamedTypesRetainTheirOwnMetadata() throws Exception {
        first.objects.add(object("BOOK", "NSP", "date", 20));
        first.objects.add(object("BOOK", "NSM", "date", 30));
        List<KnowledgeResource> resources = source.discover(SourceScope.of("LIB"));
        for (KnowledgeResource resource : resources) {
            KnowledgeDocument doc = source.load(resource.id());
            assertTrue(doc.text().contains(resource.title()));
        }
        assertEquals(Arrays.asList("BOOK.NSP", "BOOK.NSM"), first.read);
        assertEquals(1, first.listings);
    }

    @Test
    public void missingDateDoesNotTurnSizeIntoARevision() throws Exception {
        first.objects.add(object("BOOK", "NSP", "", 20));
        KnowledgeResource listed = source.discover(SourceScope.of("LIB")).get(0);
        assertFalse(listed.revision().isKnown());
        KnowledgeDocument old = source.load(listed.id());
        first.contentSuffix = "different";
        KnowledgeResource again = source.discover(SourceScope.of("LIB")).get(0);
        assertFalse(again.revision().isKnown());
        assertNotEquals(old.resource().revision(), source.load(again.id()).resource().revision());
    }

    @Test
    public void exactLimitIsCompleteButOverflowFailsInsteadOfTruncating() throws Exception {
        first.objects.add(object("ONE", "NSP", "date", 20));
        first.objects.add(object("TWO", "NSP", "date", 20));
        assertEquals(2, source.discover(SourceScope.builder().startPoint("LIB").maxResources(2).build()).size());
        try {
            source.discover(SourceScope.builder().startPoint("LIB").maxResources(1).build());
            fail("Expected incomplete inventory to fail");
        } catch (KnowledgeSourceException expected) {
            assertTrue(expected.getMessage().contains("maxResources"));
        }
    }

    @Test
    public void ddmsRequireExplicitScopeAndUseLongNameAndSystemFile() throws Exception {
        first.objects.add(object("BOOK", "NSP", "date", 20));
        first.ddms.add(new NdvObjectInfo("SHORT", "LONG-ACCOUNT-NAME", ObjectKind.SOURCE,
                ObjectType.DDM, "DDM", "NSD", 15, "USER", "date", 33, 44));
        assertEquals(1, source.discover(SourceScope.of("LIB")).size());
        try {
            source.discover(SourceScope.of("FDDM:"));
            fail("DDM scope was not configured");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.ACCESS_DENIED, expected.kind());
        }
        NdvKnowledgeSource optedIn = new NdvKnowledgeSource(SourceAddress.of("ndv", "host:8011"),
                "host", 8011, SecretRef.of("test"), ref -> new SecretMaterial(ref, "user", new char[0]),
                (host, port, user, password) -> first, true, clock::get);
        List<KnowledgeResource> resources = optedIn.discover(SourceScope.of("LIB", "FDDM:"));
        assertEquals(2, resources.size());
        KnowledgeResource ddm = resources.get(1);
        assertTrue(ddm.id().value().endsWith("/FDDM:33-44/LONG-ACCOUNT-NAME.NSD"));
        assertEquals("LONG-ACCOUNT-NAME.NSD", ddm.title());
        int before = first.listings;
        assertTrue(optedIn.load(ddm.id()).text().contains("LONG-ACCOUNT-NAME"));
        assertEquals(before, first.listings);
        assertTrue(optedIn.load(ddm.id()).text().contains("LONG-ACCOUNT-NAME"));
        assertEquals(before + 1, first.listings);
    }

    @Test
    public void failedLibraryLogonIsNotAnEmptyInventory() throws Exception {
        first.failLogon = true;
        try {
            source.discover(SourceScope.of("LIB"));
            fail("Expected failure");
        } catch (KnowledgeSourceException expected) {
            assertEquals(KnowledgeSourceException.Kind.INVALID_RESPONSE, expected.kind());
        }
    }

    private static NdvObjectInfo object(String name, String extension, String date, int size) {
        return new NdvObjectInfo(name, "", ObjectKind.SOURCE, 1, extension, extension, size,
                "USER", date, 11, 22);
    }

    private static final class FakeClient extends NdvClient {
        private final List<NdvObjectInfo> objects = new ArrayList<>();
        private final List<NdvObjectInfo> ddms = new ArrayList<>();
        private final List<String> read = new ArrayList<>();
        private String library;
        private int logons;
        private int systemFiles;
        private int listings;
        private int downloads;
        private boolean connected = true;
        private boolean failDownload;
        private boolean failLogon;
        private String contentSuffix = "";

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public String getCurrentLibrary() {
            return library;
        }

        @Override
        public void logon(String library) throws NdvException {
            logons++;
            if (failLogon) {
                throw new NdvException("denied");
            }
            this.library = library;
        }

        @Override
        public IPalTypeSystemFile[] getSystemFiles() {
            systemFiles++;
            return new IPalTypeSystemFile[] {
                    PalTypeSystemFileFactory.newInstance(11, 22, IPalTypeSystemFile.FUSER),
                    PalTypeSystemFileFactory.newInstance(33, 44, IPalTypeSystemFile.FDDM)
            };
        }

        @Override
        public List<NdvObjectInfo> listObjects(IPalTypeSystemFile sysFile, String library,
                                               String filter, int kind, int type) {
            listings++;
            List<NdvObjectInfo> result = new ArrayList<>();
            for (NdvObjectInfo object : type == ObjectType.DDM ? ddms : objects) {
                if ("*".equals(filter) || object.getName().equals(filter)) {
                    result.add(object);
                }
            }
            return result;
        }

        @Override
        public String readSource(String library, NdvObjectInfo object) throws IOException {
            downloads++;
            if (failDownload) {
                throw new IOException("disconnected");
            }
            boolean ddm = object.getType() == ObjectType.DDM;
            assertEquals(ddm ? 33 : 11, object.getDatabaseId());
            assertEquals(ddm ? 44 : 22, object.getFileNumber());
            if (ddm) {
                assertEquals("", library);
            }
            String identity = object.getEffectiveName() + "." + object.getTypeExtension();
            read.add(identity);
            return identity + contentSuffix;
        }

        @Override
        public void close() {
            connected = false;
        }
    }
}
