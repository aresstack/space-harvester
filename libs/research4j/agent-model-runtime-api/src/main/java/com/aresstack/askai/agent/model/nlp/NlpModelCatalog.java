package com.aresstack.askai.agent.model.nlp;

import java.util.List;

/**
 * The neutral host port listing the INSTALLED, usable NLP models for a (capability, language), for the EXPLICIT
 * selection in the settings UI — the NLP counterpart of {@code RerankerModelCatalog}. It never installs or
 * downloads; it only reports what is already deployed in the NLP model store.
 */
public interface NlpModelCatalog {

    /** The virtual model ids of installed models for the capability + language (possibly empty), stable order. */
    List<String> listInstalledModels(NlpCapability capability, String languageCode);
}
