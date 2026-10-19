package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

/**
 * Callback, über den der Adapter Anmeldedaten erst im Moment der Anmeldung anfordert. Die Composition Root
 * verdrahtet ihn mit dem Security-Port (KeePass); weder Domain noch Use Cases sehen die Anmeldedaten.
 */
public interface BetaViewCredentialsProvider {

    /**
     * @return frische Anmeldedaten, nie {@code null}
     * @throws Exception wenn die Anmeldedaten nicht beschafft werden können (z. B. Tresor gesperrt); der
     *                   Adapter meldet das als {@code ACCESS_DENIED}, ohne die Ursache weiterzugeben
     */
    BetaViewCredentials credentials() throws Exception;
}
