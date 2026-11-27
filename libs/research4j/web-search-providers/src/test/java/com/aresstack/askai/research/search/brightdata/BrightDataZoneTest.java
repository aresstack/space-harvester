package com.aresstack.askai.research.search.brightdata;

import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretValueService;
import org.junit.Test;

import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The zone is the name of a zone in the user's own Bright Data account: no default, required when enabled. */
public class BrightDataZoneTest {

    @Test
    public void thereIsNoDefaultZoneAndAnEnabledProviderNeedsOne() throws Exception {
        BrightDataSearchConfiguration config = new BrightDataSearchConfiguration();
        assertEquals("", config.getZone());
        new BrightDataSearchConfigurationValidator().validate(config); // disabled: fine without a zone

        SecretValueService secrets = new SecretValueService(new AesGcmSecretCipher(
                new FileSecretKeyProvider(Files.createTempDirectory("bd-zone").resolve("key"))));
        config.setEnabled(true);
        config.setApiKey(secrets.encrypt("token".toCharArray()));
        try {
            new BrightDataSearchConfigurationValidator().validate(config);
            fail("an enabled Bright Data without a zone must not validate");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("zone"));
        }
        config.setZone("my-zone");
        new BrightDataSearchConfigurationValidator().validate(config);
    }
}
