package com.aresstack.spaceharvester.deck.ndv.pal;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.IPalTransactions;
import com.aresstack.spaceharvester.deck.ndv.NdvConnection;
import com.aresstack.spaceharvester.deck.ndv.NdvConnector;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * Adapter: NDV-Verbindungen über das PAL-Protokoll (NATSPOD) wie in MainframeMate. Die Protokollschicht stammt aus
 * {@code enterprise-ai-sources} (dieselbe Kopie aus MainframeMate, die auch die NDV-Quelle des Harvesters nutzt).
 */
public final class PalNdvConnector implements NdvConnector {

    private final Supplier<NdvClient> clients;

    public PalNdvConnector() {
        this(NdvClient::new);
    }

    /** Mit einer anderen PAL-Schicht je Verbindung (Tests). */
    static PalNdvConnector over(Supplier<IPalTransactions> pal) {
        return new PalNdvConnector(() -> new NdvClient(pal.get()));
    }

    private PalNdvConnector(Supplier<NdvClient> clients) {
        this.clients = clients;
    }

    @Override
    public NdvConnection connect(NdvProfile profile, String user, char[] password) throws IOException {
        NdvClient client = clients.get();
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
