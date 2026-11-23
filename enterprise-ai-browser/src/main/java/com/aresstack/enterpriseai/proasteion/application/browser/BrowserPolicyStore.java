package com.aresstack.enterpriseai.proasteion.application.browser;

import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;

import java.io.IOException;

/**
 * Remembers a user's "always" answer by adding the exact host to the allow or block list of the browser profile
 * that was active for the navigation.
 */
public interface BrowserPolicyStore {

    void allow(BrowserProfile profile, String host) throws IOException;

    void block(BrowserProfile profile, String host) throws IOException;
}
