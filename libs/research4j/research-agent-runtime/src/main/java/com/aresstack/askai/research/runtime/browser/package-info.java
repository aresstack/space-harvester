/**
 * The research workers' browser isolation contract, free of any browser-backend type. Terms follow
 * WebDriver BiDi: a research worker holds one <b>user context</b> ({@code browser.createUserContext}: its own
 * cookies, local storage and sign-ins) and drives one or more <b>browsing contexts</b> (tabs, created with
 * {@code browsingContext.create} in that user context) inside it. A user context belongs to exactly one chat
 * and is never shared between workers; the research RESULTS are not isolated — every page a worker reads is
 * recorded into the session's one capture store and accepted through the session's one source lifecycle.
 *
 * <p>{@link com.aresstack.askai.research.runtime.browser.BrowserUserContextPool} is the seam the task
 * executor uses: acquire a lease per task attempt, hand {@code lease.browser()} to the acquisition (the same
 * {@code web_*} tool contract the session browser serves), close the lease in a {@code finally}.</p>
 */
package com.aresstack.askai.research.runtime.browser;
