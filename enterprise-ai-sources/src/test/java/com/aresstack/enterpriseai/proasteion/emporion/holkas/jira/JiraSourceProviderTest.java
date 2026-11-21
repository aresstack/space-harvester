package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.enterpriseai.astu.source.SourceSettings;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.SourceScope;
import org.junit.Test;

import javax.net.ssl.SSLSocketFactory;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class JiraSourceProviderTest {

    private final SSLSocketFactory[] requested = new SSLSocketFactory[1];
    private final JiraSourceProvider provider = new JiraSourceProvider(new RecordingSecretProvider(), null, null,
            "EAC-Test", (alias, keyStoreFile, passwordRef) -> {
                requested[0] = (SSLSocketFactory) SSLSocketFactory.getDefault();
                return requested[0];
            });

    private static SourceSettings settings(String... pairs) {
        SourceSettings settings = SourceSettings.empty();
        for (int i = 0; i < pairs.length; i += 2) {
            settings = settings.with(pairs[i], pairs[i + 1]);
        }
        return settings;
    }

    @Test
    public void typeDescribesJiraWithDialogFieldsOnly() {
        KnowledgeSourceType type = provider.type();
        assertEquals("jira", type.id());
        assertEquals("jira", type.scheme());
        assertEquals("Jira", type.displayName());
        assertEquals("baseUrl", type.summaryKey());
        assertEquals("title", type.titleKey());
        assertEquals("Projekte", type.partitionsLabel());
        assertEquals(5, type.fields().size());
        assertTrue(type.field("baseUrl").isRequired());
        assertEquals(SourceSettingField.VaultValue.LOCATION, type.field("baseUrl").vaultValue());
        assertEquals(SourceSettingField.Kind.SECRET_REF, type.field("credentialRef").kind());
        assertEquals(SourceSettingField.Kind.CLIENT_CERTIFICATE, type.field("clientCertificate.alias").kind());
        assertTrue(type.field("jql").isAdvanced());
        assertEquals("Jira", type.field("title").defaultValue());
        assertEquals("jira://jira.intern/jira", type.addressFor(provider.locator(settings("baseUrl", "https://jira.intern/jira/"))));
    }

    @Test
    public void validateReportsProblemsWithoutValues() {
        assertTrue(provider.validate(settings("baseUrl", "https://jira.intern/jira")).isEmpty());
        List<String> problems = provider.validate(settings("baseUrl", "ftp://jira", "credentialRef", "???",
                "maxComments", "abc", "clientCertificate.keyStoreFile", "/x.p12", "jql", "a ORDER BY b"));
        assertFalse(problems.isEmpty());
        for (String problem : problems) {
            assertFalse(problem, problem.contains("???"));
        }
        assertTrue(problems.toString(), problems.toString().contains("Basis-URL"));
        assertTrue(problems.toString(), problems.toString().contains("Zertifikat"));
        assertTrue(problems.toString(), problems.toString().contains("maxComments"));
        assertTrue(provider.validate(settings("baseUrl", "http://jira", "credentialRef", "keepass:Jira")).toString()
                .contains("https"));
        assertTrue(provider.validate(settings("baseUrl", "http://jira", "credentialRef", "keepass:Jira",
                "allowInsecureHttp", "true")).isEmpty());
    }

    @Test
    public void scopeDefaultsToAllProjectsWithoutLimit() {
        SourceScope scope = provider.scope(settings("baseUrl", "https://jira.intern"));
        assertEquals("project:*", scope.startPoints().get(0));
        assertEquals(1, scope.maxDepth());
        assertEquals(1000000, scope.maxResources());
        assertEquals("project:DEMO", provider.scope(settings("baseUrl", "https://jira.intern", "startPoints",
                "project:DEMO")).startPoints().get(0));
        SourceScope jql = provider.scope(settings("baseUrl", "https://jira.intern", "startPoints",
                "jql:project in (DEMO, OPS), OPS-7", "maxDepth", "2", "maxResources", "500"));
        assertEquals(java.util.Arrays.asList("jql:project in (DEMO, OPS)", "OPS-7"), jql.startPoints());
        assertEquals(2, jql.maxDepth());
        assertEquals(500, jql.maxResources());
        assertTrue(provider.validate(settings("baseUrl", "https://jira.intern", "startPoints",
                "jql:project in (DEMO, OPS)")).isEmpty());
        assertFalse(provider.validate(settings("baseUrl", "https://jira.intern", "startPoints", "irgendwas")).isEmpty());
        assertFalse(provider.validate(settings("baseUrl", "https://jira.intern", "maxDepth", "x")).isEmpty());
    }

    @Test
    public void openBuildsAReadingSourceAndUsesTheCertificateWhenConfigured() {
        KnowledgeSourcePort port = provider.open(SourceAddress.of("jira", "jira.intern/jira"),
                settings("baseUrl", "https://jira.intern/jira", "credentialRef", "keepass:Jira"));
        assertTrue(port instanceof JiraKnowledgeSource);
        assertEquals("jira://jira.intern/jira", port.sourceId().value());
        assertTrue(requested[0] == null);
        provider.open(SourceAddress.of("jira", "jira.intern/jira"),
                settings("baseUrl", "https://jira.intern/jira", "clientCertificate.alias", "mein-zertifikat"));
        assertNotNull(requested[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void openRejectsBrokenSettings() {
        provider.open(SourceAddress.of("jira", "x"), settings("baseUrl", "nicht-eine-url"));
    }
}
