package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceAsyncHttpTransport;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceAsyncHttpTransportContract;
import java.time.Duration;
import org.junit.After;

/** Der Upstream-Vertrag von {@link ConfluenceAsyncHttpTransport} für {@link WebClientConfluenceTransport} (JUnit 4). */
public class WebClientConfluenceTransportContractTest extends ConfluenceAsyncHttpTransportContract {

    private WebClientConfluenceTransport transport;

    @Override
    protected ConfluenceAsyncHttpTransport transport() {
        if (transport == null) {
            transport = WebClientConfluenceTransport.builder().readTimeout(Duration.ofSeconds(5)).build();
        }
        return transport;
    }

    @After
    public void closeTransport() {
        if (transport != null) {
            transport.close();
        }
    }
}
