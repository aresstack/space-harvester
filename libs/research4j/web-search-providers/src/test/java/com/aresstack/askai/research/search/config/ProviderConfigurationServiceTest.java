package com.aresstack.askai.research.search.config;

import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretArrays;
import com.aresstack.askai.research.search.security.SecretValueService;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ProviderConfigurationServiceTest {

    @Test
    public void saveEncryptedApiKeyWithoutPlainText()
            throws Exception {

        Path directory = Files.createTempDirectory(
                "askai-provider-test");
        ProviderConfigurationPaths paths =
                new ProviderConfigurationPaths(directory);
        SecretValueService secrets =
                new SecretValueService(
                        new AesGcmSecretCipher(
                                new FileSecretKeyProvider(
                                        paths.getSecretKeyFile())));
        ProviderConfigurationService service =
                new ProviderConfigurationService(
                        paths,
                        new GsonProviderConfigurationStore(),
                        secrets);

        BraveSearchConfiguration configuration =
                new BraveSearchConfiguration();
        configuration.setEnabled(true);
        char[] apiKey = "top-secret".toCharArray();
        service.saveBrave(configuration, apiKey);

        String json = new String(
                Files.readAllBytes(
                        paths.getBraveConfigurationFile()),
                StandardCharsets.UTF_8);

        assertFalse(json.contains("top-secret"));
        assertTrue(json.contains("cipherText"));
        assertTrue(Files.exists(paths.getSecretKeyFile()));

        BraveSearchConfiguration loaded =
                service.loadOrCreateBrave();
        char[] decrypted = secrets.decrypt(
                loaded.getApiKey());
        assertArrayEquals(
                "top-secret".toCharArray(),
                decrypted);
        SecretArrays.clear(decrypted);
    }
}
