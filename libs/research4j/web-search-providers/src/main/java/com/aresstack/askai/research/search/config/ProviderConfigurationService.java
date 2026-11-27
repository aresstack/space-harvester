package com.aresstack.askai.research.search.config;

import com.aresstack.askai.research.search.brave.BraveResultType;
import com.aresstack.askai.research.search.brave.BraveSearchConfiguration;
import com.aresstack.askai.research.search.brave.BraveSearchConfigurationValidator;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfiguration;
import com.aresstack.askai.research.search.brightdata.BrightDataSearchConfigurationValidator;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfigurationValidator;
import com.aresstack.askai.research.search.security.SecretArrays;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfiguration;
import com.aresstack.askai.research.search.serpapi.SerpApiSearchConfigurationValidator;
import com.aresstack.askai.research.search.security.SecretValueService;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProviderConfigurationService {

    private final ProviderConfigurationPaths paths;
    private final ProviderConfigurationStore store;
    private final SecretValueService secretValueService;
    private final BraveSearchConfigurationValidator braveValidator;
    private final BrightDataSearchConfigurationValidator brightDataValidator;
    private final DataForSeoSearchConfigurationValidator dataForSeoValidator;
    private final SerpApiSearchConfigurationValidator serpApiValidator;

    public ProviderConfigurationService(
            ProviderConfigurationPaths paths,
            ProviderConfigurationStore store,
            SecretValueService secretValueService) {

        this.paths = requireNonNull(paths, "paths");
        this.store = requireNonNull(store, "store");
        this.secretValueService = requireNonNull(
                secretValueService,
                "secretValueService");
        this.braveValidator =
                new BraveSearchConfigurationValidator();
        this.brightDataValidator =
                new BrightDataSearchConfigurationValidator();
        this.dataForSeoValidator =
                new DataForSeoSearchConfigurationValidator();
        this.serpApiValidator =
                new SerpApiSearchConfigurationValidator();
    }

    public ProviderConfigurationBundle loadOrCreateAll() {
        return new ProviderConfigurationBundle(
                loadOrCreateBrave(),
                loadOrCreateBrightData(),
                loadOrCreateDataForSeo(),
                loadOrCreateSerpApi());
    }

    public BraveSearchConfiguration loadOrCreateBrave() {
        Path file = paths.getBraveConfigurationFile();
        if (!Files.exists(file)) {
            BraveSearchConfiguration configuration =
                    createDefaultBraveConfiguration();
            saveBrave(configuration);
            return configuration;
        }
        BraveSearchConfiguration configuration = store.load(
                file,
                BraveSearchConfiguration.class);
        braveValidator.validate(configuration);
        return configuration;
    }

    public BrightDataSearchConfiguration loadOrCreateBrightData() {
        Path file = paths.getBrightDataConfigurationFile();
        if (!Files.exists(file)) {
            BrightDataSearchConfiguration configuration =
                    new BrightDataSearchConfiguration();
            saveBrightData(configuration);
            return configuration;
        }
        BrightDataSearchConfiguration configuration = store.load(
                file,
                BrightDataSearchConfiguration.class);
        brightDataValidator.validate(configuration);
        return configuration;
    }

    public DataForSeoSearchConfiguration loadOrCreateDataForSeo() {
        Path file = paths.getDataForSeoConfigurationFile();
        if (!Files.exists(file)) {
            DataForSeoSearchConfiguration configuration =
                    createDefaultDataForSeoConfiguration();
            saveDataForSeo(configuration);
            return configuration;
        }
        DataForSeoSearchConfiguration configuration = store.load(
                file,
                DataForSeoSearchConfiguration.class);
        if (configuration.isEnabled()
                && (configuration.getApiKey() == null || !configuration.getApiKey().isPresent())) {
            // A file from before the single API key (login/password) has no key: keep it editable, disabled,
            // so the settings dialog opens and the key can be entered once.
            configuration.setEnabled(false);
        }
        dataForSeoValidator.validate(configuration);
        return configuration;
    }

    public SerpApiSearchConfiguration loadOrCreateSerpApi() {
        Path file = paths.getSerpApiConfigurationFile();
        if (!Files.exists(file)) {
            SerpApiSearchConfiguration configuration =
                    new SerpApiSearchConfiguration();
            saveSerpApi(configuration);
            return configuration;
        }
        SerpApiSearchConfiguration configuration = store.load(
                file,
                SerpApiSearchConfiguration.class);
        serpApiValidator.validate(configuration);
        return configuration;
    }

    public void saveBrave(BraveSearchConfiguration configuration) {
        braveValidator.validate(configuration);
        store.save(paths.getBraveConfigurationFile(), configuration);
    }

    public void saveBrave(
            BraveSearchConfiguration configuration,
            char[] apiKey) {

        try {
            configuration.setApiKey(
                    secretValueService.encrypt(apiKey));
            saveBrave(configuration);
        } finally {
            SecretArrays.clear(apiKey);
        }
    }

    public void saveBrightData(
            BrightDataSearchConfiguration configuration) {

        brightDataValidator.validate(configuration);
        store.save(
                paths.getBrightDataConfigurationFile(),
                configuration);
    }

    public void saveBrightData(
            BrightDataSearchConfiguration configuration,
            char[] apiKey) {

        try {
            configuration.setApiKey(
                    secretValueService.encrypt(apiKey));
            saveBrightData(configuration);
        } finally {
            SecretArrays.clear(apiKey);
        }
    }

    public void saveDataForSeo(
            DataForSeoSearchConfiguration configuration) {

        dataForSeoValidator.validate(configuration);
        store.save(
                paths.getDataForSeoConfigurationFile(),
                configuration);
    }

    public void saveDataForSeo(
            DataForSeoSearchConfiguration configuration,
            char[] apiKey) {

        try {
            configuration.setApiKey(
                    secretValueService.encrypt(apiKey));
            saveDataForSeo(configuration);
        } finally {
            SecretArrays.clear(apiKey);
        }
    }

    public void saveSerpApi(
            SerpApiSearchConfiguration configuration) {

        serpApiValidator.validate(configuration);
        store.save(
                paths.getSerpApiConfigurationFile(),
                configuration);
    }

    public void saveSerpApi(
            SerpApiSearchConfiguration configuration,
            char[] apiKey) {

        try {
            configuration.setApiKey(
                    secretValueService.encrypt(apiKey));
            saveSerpApi(configuration);
        } finally {
            SecretArrays.clear(apiKey);
        }
    }

    public ProviderConfigurationPaths getPaths() {
        return paths;
    }

    private BraveSearchConfiguration
            createDefaultBraveConfiguration() {

        BraveSearchConfiguration configuration =
                new BraveSearchConfiguration();
        configuration.getResultFilter().add(
                BraveResultType.WEB);
        return configuration;
    }

    private DataForSeoSearchConfiguration
            createDefaultDataForSeoConfiguration() {

        DataForSeoSearchConfiguration configuration =
                new DataForSeoSearchConfiguration();
        configuration.getRemoveFromUrl().add("srsltid");
        return configuration;
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }
}
