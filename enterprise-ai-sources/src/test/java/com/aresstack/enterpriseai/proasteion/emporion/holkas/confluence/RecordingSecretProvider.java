package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;

import java.util.ArrayList;
import java.util.List;

/** Secret-Provider für Tests: liefert festes Material, zählt Auflösungen und merkt sich ausgegebenes Material. */
final class RecordingSecretProvider implements SecretProvider {

    static final SecretRef REF = SecretRef.of("keepass:confluence");

    final List<SecretMaterial> issued = new ArrayList<SecretMaterial>();
    String principal = "alice";
    String secret = "s3cr3t-Pässwort";
    SecretUnavailableException.Reason failWith;

    @Override
    public SecretMaterial resolve(SecretRef ref) throws SecretUnavailableException {
        if (failWith != null) {
            throw new SecretUnavailableException(failWith, ref, "Testfehler");
        }
        if (!REF.equals(ref)) {
            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref, "unbekannt");
        }
        SecretMaterial material = new SecretMaterial(ref, principal, secret.toCharArray());
        issued.add(material);
        return material;
    }
}
