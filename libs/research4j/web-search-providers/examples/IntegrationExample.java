package com.aresstack.askai.research.search.example;

import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.application.WebSearchProvidersModule;

import java.util.concurrent.CompletableFuture;

public final class IntegrationExample {

    private IntegrationExample() {
    }

    public static void main(String[] arguments) {
        final WebSearchProvidersModule module =
                WebSearchProvidersModule.openUserHome();

        WebSearchProvider provider =
                module.getProviderRegistry()
                        .require(SearchProviderId.BRAVE);

        WebSearchRequest request =
                WebSearchRequest.builder(
                        "Wearables Forschung Deutschland")
                        .countryCode("DE")
                        .languageCode("de")
                        .maximumResults(20)
                        .build();

        CompletableFuture<WebSearchResult> result =
                provider.search(request);

        result.whenComplete((searchResult, failure) -> {
            try {
                if (failure != null) {
                    failure.printStackTrace();
                    return;
                }

                searchResult.getHits().forEach(hit ->
                        System.out.println(
                                hit.getTitle()
                                        + " -> "
                                        + hit.getUrl()));
            } finally {
                module.close();
            }
        });
    }
}
