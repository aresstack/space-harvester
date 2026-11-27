package com.aresstack.askai.research.search.config;

import java.nio.file.Path;

public interface ProviderConfigurationStore {

    <T> T load(Path file, Class<T> configurationType);

    void save(Path file, Object configuration);
}
