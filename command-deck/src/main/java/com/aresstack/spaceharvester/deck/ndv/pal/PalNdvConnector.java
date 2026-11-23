package com.aresstack.spaceharvester.deck.ndv.pal;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.IPalTransactions;
import com.aresstack.spaceharvester.deck.ndv.NdvConnection;
import com.aresstack.spaceharvester.deck.ndv.NdvConnector;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import java.io.IOException;
import java.net.Proxy;
import java.util.function.Supplier;

/**
 * Adapter: NDV-Verbindungen über das PAL-Protokoll (NATSPOD) wie in MainframeMate. Die Protokollschicht stammt aus
 * {@code enterprise-ai-sources} (dieselbe Kopie aus MainframeMate, die auch die NDV-Quelle des Harvesters nutzt).
 */
public final class PalNdvConnector implements NdvConnector {

    private final Supplier<NdvClient> clients;
    private final Route route;

    /** Der Weg zu einem NDV-Server: direkt oder über einen Proxy. */
    @FunctionalInterface
    public interface Route {
        /** Direkt ist {@link Proxy#NO_PROXY}; ohne gültigen Weg (etwa PAC gescheitert) eine {@link IOException}. */
        Proxy to(NdvProfile profile) throws IOException;
    }

    /** Immer direkt (Tests). */
    public PalNdvConnector() {
        this(profile -> Proxy.NO_PROXY);
    }

    /** @param route Weg je Profil; nur für Profile mit {@link NdvProfile#useProxy()} gefragt, sonst direkt */
    public PalNdvConnector(Route route) {
        this(NdvClient::new, route);
    }

    /** Mit einer anderen PAL-Schicht je Verbindung (Tests). */
    static PalNdvConnector over(Supplier<IPalTransactions> pal) {
        return over(pal, profile -> Proxy.NO_PROXY);
    }

    /** Mit einer anderen PAL-Schicht und einem Weg (Tests). */
    static PalNdvConnector over(Supplier<IPalTransactions> pal, Route route) {
        return new PalNdvConnector(() -> new NdvClient(pal.get()), route);
    }

    private PalNdvConnector(Supplier<NdvClient> clients, Route route) {
        this.clients = clients;
        this.route = route;
    }

    @Override
    public NdvConnection connect(NdvProfile profile, String user, char[] password) throws IOException {
        // Vorgabe direkt wie MainframeMate; den Proxy nur, wenn das Profil ihn verlangt.
        Proxy proxy = profile.useProxy() ? route.to(profile) : Proxy.NO_PROXY;
        NdvClient client = clients.get();
        client.proxy(proxy == null ? Proxy.NO_PROXY : proxy);
        try {
            // NdvClient erwartet das Passwort als String (PAL-Schnittstelle von MainframeMate).
            client.connect(profile.host(), profile.port(), user, new String(password));
        } catch (NdvException e) {
            client.close();
            throw new IOException(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }
        return new PalNdvConnection(client);
    }
}
