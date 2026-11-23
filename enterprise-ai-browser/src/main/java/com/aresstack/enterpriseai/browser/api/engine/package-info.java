/**
 * Engine SPI behind {@link com.aresstack.enterpriseai.browser.api.ChatBrowserPort}: what a browser adapter (for
 * example Firefox over WebDriver BiDi via WD4J) provides. The runtime owns lifecycle, locking, recovery and tab ids;
 * the adapter only starts a process on a given profile folder and speaks the protocol. Raw context ids stay between
 * runtime and adapter and are never shown to the model.
 */
package com.aresstack.enterpriseai.browser.api.engine;
