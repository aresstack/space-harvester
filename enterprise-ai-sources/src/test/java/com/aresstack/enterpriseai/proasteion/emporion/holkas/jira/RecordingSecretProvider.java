package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.astu.security.SecretRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-Tresor: liefert Benutzer/Passwort oder ein Token und zählt die Aufrufe. */
final class RecordingSecretProvider implements SecretProvider {

    static final SecretRef REF = SecretRef.of("keepass:Jira");

    final List<SecretRef> resolved = new CopyOnWriteArrayList<SecretRef>();
    final Map<String, String> attributes = new LinkedHashMap<String, String>();
    String principal = "alice";
    char[] secret = "geheim".toCharArray();
    SecretUnavailableException failure;

    @Override
    public SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException {
        resolved.add(ref);
        if (failure != null) {
            throw failure;
        }
        return new SecretMaterial(ref, principal, secret);
    }

    @Override
    public Map<String, String> attributesOf(SecretRef ref) {
        return attributes;
    }
}
