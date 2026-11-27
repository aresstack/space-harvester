package com.aresstack.askai.browser.search;

/** Validates a complete settings object. See {@link DefaultLegacyBrowserSearchSettingsValidator}. */
public interface LegacyBrowserSearchSettingsValidator {

    SettingsValidationResult validate(LegacyBrowserSearchSettings settings);
}
