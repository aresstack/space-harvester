/**
 * Firefox adapter of {@link com.aresstack.enterpriseai.browser.api.engine.BrowserEngine} (#141).
 *
 * <p>Starts a visible Firefox on the chat profile with {@code --remote-debugging-port} on a free loopback port and
 * talks WebDriver BiDi over a direct WebSocket to {@code ws://127.0.0.1:<port>/session}, without geckodriver. The
 * protocol layer is WD4J ({@code de.bund.zrb}, vendored unchanged, see VENDORED.md); no WD4J type leaves this
 * package.
 *
 * <p>Firefox is never started with {@code -silent}: a cold start with {@code -silent} does not open the BiDi port.
 */
package com.aresstack.enterpriseai.browser.firefox;
