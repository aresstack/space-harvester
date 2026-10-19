package com.aresstack.enterpriseai.proasteion.application.security;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import com.aresstack.enterpriseai.adyton.SecretFunction;
import com.aresstack.enterpriseai.adyton.SecretMaterial;
import com.aresstack.enterpriseai.adyton.SecretProvider;
import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.MediaWikiCredentials;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.MediaWikiCredentialsProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki.MediaWikiSiteConfig;

import java.util.Arrays;

/**
 * {@link MediaWikiCredentialsProvider} über den Security-Port: Benutzername und Passwort des KeePass-Eintrags
 * werden erst beim Login geholt; die {@link MediaWikiCredentials} löscht der Adapter nach dem Login selbst.
 */
public final class SecretBackedMediaWikiCredentialsProvider implements MediaWikiCredentialsProvider {

    private final SecretProvider secrets;
    private final SecretRef ref;

    public SecretBackedMediaWikiCredentialsProvider(SecretProvider secrets, SecretRef ref) {
        if (secrets == null || ref == null) {
            throw new IllegalArgumentException("secrets and ref must not be null");
        }
        this.secrets = secrets;
        this.ref = ref;
    }

    public SecretRef ref() {
        return ref;
    }

    @Override
    public MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) throws SecretUnavailableException {
        return secrets.<MediaWikiCredentials, SecretUnavailableException>withSecret(ref,
                new SecretFunction<MediaWikiCredentials, SecretUnavailableException>() {
                    @Override
                    public MediaWikiCredentials apply(SecretMaterial material) throws SecretUnavailableException {
                        if (!material.hasPrincipal()) {
                            throw new SecretUnavailableException(SecretUnavailableException.Reason.NOT_FOUND, ref,
                                    "KeePass-Eintrag hat keinen Benutzernamen");
                        }
                        char[] password = material.copySecret();
                        try {
                            return new MediaWikiCredentials(material.principal(), password);
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                    }
                });
    }

    @Override
    public String toString() {
        return "SecretBackedMediaWikiCredentialsProvider[" + ref + "]";
    }
}
