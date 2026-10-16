package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/** mTLS-Hilfe gegen einen Test-KeyStore (selbstsigniertes Testzertifikat, kein echtes Material). */
public class ClientCertificatesTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private static KeyStore testStore() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = ClientCertificatesTest.class.getResourceAsStream("test-client.p12")) {
            store.load(in, PASSWORD);
        }
        return store;
    }

    private static X509KeyManager delegate() throws Exception {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(testStore(), PASSWORD);
        return (X509KeyManager) factory.getKeyManagers()[0];
    }

    @Test
    public void buildsASocketFactoryForAnExistingAlias() throws Exception {
        SSLSocketFactory factory = ClientCertificates.fromKeyStore(testStore(), PASSWORD, " test-client ");
        assertNotNull(factory);
    }

    @Test
    public void buildsAContextForEngineBasedTransportsWithTheSameChecks() throws Exception {
        javax.net.ssl.SSLContext context = ClientCertificates.contextFromKeyStore(testStore(), PASSWORD,
                " test-client ", null);

        assertNotNull(context.createSSLEngine("confluence.test", 443));
        try {
            ClientCertificates.contextFromKeyStore(testStore(), PASSWORD, "abgelaufen", null);
            fail();
        } catch (GeneralSecurityException expected) {
            assertEquals("keine Zertifikatskette für Alias 'abgelaufen'", expected.getMessage());
        }
    }

    @Test
    public void acceptsAnOwnTrustManagerForServerCertificates() throws Exception {
        X509TrustManager trust = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        assertNotNull(ClientCertificates.fromKeyStore(testStore(), PASSWORD, "test-client", trust));
    }

    @Test
    public void forcedManagerAlwaysOffersTheConfiguredAlias() throws Exception {
        X509KeyManager forced = ClientCertificates.forceAlias(delegate(), "test-client");

        assertEquals("test-client", forced.chooseClientAlias(new String[] {"RSA", "EC"}, null, null));
        // SSLEngine-Weg (Netty, JDK HttpClient): ohne diese Methode sähe der Server kein Zertifikat
        assertEquals("test-client", ((javax.net.ssl.X509ExtendedKeyManager) forced)
                .chooseEngineClientAlias(new String[] {"RSA", "EC"}, null, null));
        assertArrayEquals(new String[] {"test-client"}, forced.getClientAliases("RSA", null));
        assertNotNull(forced.getPrivateKey("test-client"));
        assertEquals(1, forced.getCertificateChain("test-client").length);
    }

    @Test
    public void unknownAliasIsRejectedUpFront() throws Exception {
        try {
            ClientCertificates.fromKeyStore(testStore(), PASSWORD, "abgelaufen");
            fail();
        } catch (GeneralSecurityException expected) {
            assertEquals("keine Zertifikatskette für Alias 'abgelaufen'", expected.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aliasIsRequired() throws Exception {
        ClientCertificates.fromKeyStore(testStore(), PASSWORD, " ");
    }
}
