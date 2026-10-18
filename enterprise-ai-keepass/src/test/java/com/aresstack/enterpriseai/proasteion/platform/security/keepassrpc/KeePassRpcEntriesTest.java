package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class KeePassRpcEntriesTest {

    @Test
    public void prefersDirectFieldsAndMatchesTitleExactly() {
        KeePassEntry entry = KeePassRpcEntries.exactTitleMatch(JsonParser.parseString("["
                + "{\"title\":\"Confluence Prod Archiv\",\"usernameValue\":\"old\",\"password\":\"old\"},"
                + "{\"title\":\" confluence prod \",\"usernameValue\":\"alice\",\"password\":\"pw\"}]"),
                "Confluence Prod");
        assertEquals("alice", entry.userName());
        assertArrayEquals("pw".toCharArray(), entry.password());
    }

    @Test
    public void fallsBackToFormFieldsAndUsernameField() {
        KeePassEntry entry = KeePassRpcEntries.exactTitleMatch(JsonParser.parseString("[{\"title\":\"Wiki\","
                + "\"formFieldList\":[{\"type\":\"FFTusername\",\"value\":\"bob\"},"
                + "{\"type\":\"FFTpassword\",\"value\":\"pw\"}]}]"), "Wiki");
        assertEquals("bob", entry.userName());
        assertArrayEquals("pw".toCharArray(), entry.password());

        KeePassEntry legacy = KeePassRpcEntries.exactTitleMatch(JsonParser.parseString(
                "[{\"title\":\"Wiki\",\"username\":\"carol\"}]"), "Wiki");
        assertEquals("carol", legacy.userName());
        assertEquals(0, legacy.password().length);
    }

    @Test
    public void loginDtoRoundTripsLikeMainframeMate() {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<String, String>();
        fields.put("MM_Category", "Confluence");
        fields.put("MM_CertAlias", "my-cert");
        KeePassEntry written = new KeePassEntry("Confluence", "alice", "pw".toCharArray(), null, fields, "", "",
                "https://wiki.example/");
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        list.add(KeePassRpcEntries.login(written));

        KeePassEntry read = KeePassRpcEntries.exactTitleMatch(list, "Confluence");
        assertEquals("alice", read.userName());
        assertArrayEquals("pw".toCharArray(), read.password());
        assertEquals("https://wiki.example/", read.url());
        assertEquals("my-cert", read.attributes().get("MM_CertAlias"));
        assertEquals("Confluence", read.attributes().get("MM_Category"));
    }

    @Test
    public void noMatchOrNoArrayGivesNull() {
        assertNull(KeePassRpcEntries.exactTitleMatch(JsonParser.parseString("[{\"title\":\"A\"}]"), "B"));
        assertNull(KeePassRpcEntries.exactTitleMatch(JsonParser.parseString("{}"), "B"));
        assertNull(KeePassRpcEntries.exactTitleMatch(null, "B"));
    }

    @Test
    public void closeWipesThePassword() {
        KeePassEntry entry = new KeePassEntry("A", "u", "pw".toCharArray());
        entry.close();
        assertArrayEquals(new char[] {'\0', '\0'}, entry.password());
        assertEquals("KeePassEntry[A, ***]", entry.toString());
    }

    @Test
    public void setsCertificateAliasAsMainframeMateFormFieldAndKeepsTheRest() {
        com.google.gson.JsonObject entry = JsonParser.parseString("{\"title\":\"Confluence\",\"uniqueID\":\"u1\","
                + "\"formFieldList\":[{\"type\":\"FFTusername\",\"value\":\"bob\"},"
                + "{\"type\":\"FFTpassword\",\"value\":\"pw\"}]}").getAsJsonObject();
        com.google.gson.JsonObject added = KeePassRpcEntries.withFormField(entry, "MM_CertAlias", "Max Muster");
        assertEquals("Max Muster", KeePassRpcEntries.attributes(added).get("MM_CertAlias"));
        assertEquals(3, added.getAsJsonArray("formFieldList").size());
        assertEquals(2, entry.getAsJsonArray("formFieldList").size());
        com.google.gson.JsonObject changed = KeePassRpcEntries.withFormField(added, "MM_CertAlias", "Neu");
        assertEquals("Neu", KeePassRpcEntries.attributes(changed).get("MM_CertAlias"));
        assertEquals(3, changed.getAsJsonArray("formFieldList").size());
        assertEquals("bob", KeePassRpcEntries.exactTitleMatch(JsonParser.parseString("[" + changed + "]"),
                "Confluence").userName());
    }
}
