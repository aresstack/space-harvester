package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class JiraAuthorizationTest {

    @Test
    public void basicWithUserBearerWithoutAndNothingWithoutBoth() {
        assertEquals("Basic YWxpY2U6Z2VoZWlt", JiraAuthorization.header(
                new SecretMaterial(RecordingSecretProvider.REF, "alice", "geheim".toCharArray())));
        assertEquals("Bearer tok3n", JiraAuthorization.header(
                new SecretMaterial(RecordingSecretProvider.REF, "", "tok3n".toCharArray())));
        assertNull(JiraAuthorization.header(new SecretMaterial(RecordingSecretProvider.REF, null, new char[0])));
    }
}
