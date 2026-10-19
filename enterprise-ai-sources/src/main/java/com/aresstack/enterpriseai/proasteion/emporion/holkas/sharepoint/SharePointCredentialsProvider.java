package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

/**
 * Callback, über den der Adapter Anmeldedaten erst im Moment des Zugriffs anfordert. Die Composition Root
 * verdrahtet ihn mit dem Security-Port (KeePass); weder Domain noch Use Cases sehen die Anmeldedaten.
 */
public interface SharePointCredentialsProvider {

    /**
     * @return frische Anmeldedaten oder {@code null}, wenn ohne eigene Anmeldung zugegriffen werden soll
     *         (Windows-Anmeldung des JDK)
     * @throws Exception wenn die Anmeldedaten nicht beschafft werden können (z. B. Tresor gesperrt); der
     *                   Adapter meldet das als {@code ACCESS_DENIED}, ohne die Ursache weiterzugeben
     */
    SharePointCredentials credentials() throws Exception;

    /** Ohne eigene Anmeldedaten. */
    static SharePointCredentialsProvider none() {
        return new SharePointCredentialsProvider() {
            @Override
            public SharePointCredentials credentials() {
                return null;
            }
        };
    }
}
