package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeObject;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeSystemFile;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectKind;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.PalTypeSystemFileFactory;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.IPalTransactions;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class NdvClientInventoryTest {

    @Test
    public void systemFilesAreFetchedOncePerConnection() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        IPalTypeSystemFile file = PalTypeSystemFileFactory.newInstance(11, 22, IPalTypeSystemFile.FUSER);
        IPalTransactions pal = (IPalTransactions) Proxy.newProxyInstance(
                IPalTransactions.class.getClassLoader(), new Class<?>[] {IPalTransactions.class},
                (proxy, method, args) -> {
                    if ("getSystemFiles".equals(method.getName())) {
                        requests.incrementAndGet();
                        return new IPalTypeSystemFile[] {file};
                    }
                    return null;
                });
        try (NdvClient client = new NdvClient(pal)) {
            client.connect("test", 8011, "USER", "");
            IPalTypeSystemFile[] first = client.getSystemFiles();
            first[0] = null;
            assertNotNull(client.getSystemFiles()[0]);
            assertEquals(1, requests.get());
            client.close();
            client.connect("test", 8011, "USER", "");
            assertNotNull(client.getSystemFiles()[0]);
            assertEquals(2, requests.get());
        }
    }

    @Test
    public void nonProgressingPaginationDoesNotReturnAnApparentlyCompleteInventory() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        IPalTransactions pal = (IPalTransactions) Proxy.newProxyInstance(
                IPalTransactions.class.getClassLoader(), new Class<?>[] {IPalTransactions.class},
                (proxy, method, args) -> {
                    if ("getObjectsFirst".equals(method.getName()) || "getObjectsNext".equals(method.getName())) {
                        requests.incrementAndGet();
                        return new IPalTypeObject[] {null};
                    }
                    return null;
                });
        try (NdvClient client = new NdvClient(pal)) {
            client.connect("test", 8011, "USER", "");
            try {
                client.listObjects(client.getDefaultSystemFile(), "LIB", "*", ObjectKind.SOURCE, 0);
                fail("Expected incomplete listing to fail");
            } catch (NdvException expected) {
                assertTrue(expected.getMessage().contains("no progress"));
                assertEquals(1, requests.get());
            }
        }
    }
}
