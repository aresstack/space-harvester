package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

/**
 * Callback, über den der Adapter Anmeldedaten erst im Moment des Logins anfordert. Die Composition Root
 * verdrahtet ihn mit dem Security-Port (z. B. KeePassRPC über {@code adyton}); weder Domain noch
 * Use Cases sehen die Anmeldedaten.
 */
public interface MediaWikiCredentialsProvider {

    /**
     * @return frische Anmeldedaten für die Site oder {@code null}, wenn anonym zugegriffen werden soll
     * @throws Exception wenn die Anmeldedaten nicht beschafft werden können (z. B. Tresor gesperrt); der
     *                   Adapter meldet das als {@code ACCESS_DENIED}, ohne die Ursache weiterzugeben
     */
    MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) throws Exception;

    /** Anonymer Zugriff. */
    static MediaWikiCredentialsProvider anonymous() {
        return new MediaWikiCredentialsProvider() {
            @Override
            public MediaWikiCredentials credentialsFor(MediaWikiSiteConfig site) {
                return null;
            }
        };
    }
}
