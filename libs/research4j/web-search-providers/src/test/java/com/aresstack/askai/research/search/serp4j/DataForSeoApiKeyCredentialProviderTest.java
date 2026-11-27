package com.aresstack.askai.research.search.serp4j;

import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretValueService;
import com.aresstack.serp4j.core.port.credential.CredentialKey;
import com.aresstack.serp4j.core.port.credential.SecretValue;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DataForSeoApiKeyCredentialProviderTest {

    private static final CredentialKey LOGIN = CredentialKey.of("dataforseo.login");
    private static final CredentialKey PASSWORD = CredentialKey.of("dataforseo.password");

    private static SecretValueService secrets() throws Exception {
        return new SecretValueService(new AesGcmSecretCipher(
                new FileSecretKeyProvider(Files.createTempDirectory("dfs-key").resolve("key"))));
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void splitsTheBase64ApiKeyIntoLoginAndPassword() throws Exception {
        SecretValueService secrets = secrets();
        DataForSeoApiKeyCredentialProvider provider = new DataForSeoApiKeyCredentialProvider(
                secrets, secrets.encrypt(b64("user@example.test:pa:ss").toCharArray()), LOGIN, PASSWORD);
        assertEquals("user@example.test", new String(provider.resolve(LOGIN).get().getChars()));
        assertEquals("pa:ss", new String(provider.resolve(PASSWORD).get().getChars()));
        assertFalse(provider.resolve(CredentialKey.of("other")).isPresent());
        assertFalse(provider.toString().contains("user"));
    }

    @Test
    public void aValueThatIsNotBase64OfLoginColonPasswordYieldsNoCredentials() throws Exception {
        SecretValueService secrets = secrets();
        DataForSeoApiKeyCredentialProvider notBase64 = new DataForSeoApiKeyCredentialProvider(
                secrets, secrets.encrypt("not base64 !".toCharArray()), LOGIN, PASSWORD);
        DataForSeoApiKeyCredentialProvider noColon = new DataForSeoApiKeyCredentialProvider(
                secrets, secrets.encrypt(b64("justapassword").toCharArray()), LOGIN, PASSWORD);
        Optional<SecretValue> a = notBase64.resolve(LOGIN);
        Optional<SecretValue> b = noColon.resolve(PASSWORD);
        assertFalse(a.isPresent());
        assertFalse(b.isPresent());
    }
}
