package com.aresstack.enterpriseai.browser.api;

import java.util.List;

/**
 * The browser of one chat. Operations act on the current tab unless a chat-local tab id is given. Reading
 * operations may be retried once after a reconnect; tab creation and closing are never repeated blindly.
 */
public interface ChatBrowser {

    /** All tabs of this chat's browser; exactly one is marked current once a tab exists. */
    List<BrowserTab> tabs() throws BrowserException;

    /** Makes {@code tabId} the current tab (and brings it to the front). */
    BrowserTab selectTab(String tabId) throws BrowserException;

    /** Opens a new tab, makes it current and navigates it to {@code url} if not {@code null}. */
    BrowserTab newTab(String url) throws BrowserException;

    /** Closes {@code tabId}; if it was current, another tab becomes current. */
    void closeTab(String tabId) throws BrowserException;

    /** Navigates {@code tabId} (or the current tab if {@code null}) and returns the loaded page. */
    BrowserPage navigate(String tabId, String url) throws BrowserException;

    /** Reads {@code tabId} (or the current tab if {@code null}) without navigating. */
    BrowserPage read(String tabId) throws BrowserException;

    /** Upper bound for the tabs {@link #openMany} loads at the same time. */
    int MAX_PARALLEL_TABS = 8;

    /** Upper bound for the random wait before each tab of {@link #openMany}. */
    int MAX_TAB_DELAY_MILLIS = 10000;

    /**
     * Opens {@code urls} in fresh tabs, at most {@code parallel} (1 to {@link #MAX_PARALLEL_TABS}) loading at the same
     * time, reads each page and closes its tab again; before
     * each new tab it waits a random time from 0 to {@code maxDelayMillis} (0: no wait), so a site does not see a burst; the current tab stays as it was. Every host passes the access
     * guard once per call (an unknown host is asked about at most once), every final address after redirects again.
     * Addresses whose path names a document (PDF, Office file) are also downloaded inside their tab, at most
     * {@link BrowserDocument#MAX_BYTES}. Duplicates and non-http(s) addresses are skipped. One entry per requested
     * address, in order; a failing address never fails the others.
     */
    List<BrowserBatchPage> openMany(List<String> urls, int parallel, int maxDelayMillis) throws BrowserException;

    /**
     * The links of {@code tabId} (or the current tab if {@code null}) as a new snapshot: only real anchors of the
     * rendered page, absolute http(s) addresses, de-duplicated, and when {@code filter} is not {@code null} only links
     * whose text, label or address contains it (ignoring case), at most {@code maxLinks}. Replaces the previous
     * snapshot of the tab.
     */
    BrowserLinks links(String tabId, int maxLinks, String filter) throws BrowserException;

    /**
     * Opens the link {@code linkId} of the tab's current link snapshot. The address passes the access guard like any
     * navigation. Fails with {@code INVALID_REQUEST} when the id is unknown or the tab left the snapshot's page.
     */
    BrowserPage follow(String tabId, String linkId) throws BrowserException;

    /**
     * Downloads the document behind link {@code linkId} of the tab's link snapshot (PDF, Office file) inside the page,
     * with the chat's cookies and logins, without leaving the page; at most {@link BrowserDocument#MAX_BYTES}. The
     * address passes the access guard before the download and the address reached after redirects again. Only
     * addresses the page's own origin may fetch work (no cross-origin downloads); others are opened with follow.
     */
    BrowserDocument download(String tabId, String linkId) throws BrowserException;

    /**
     * Returns to the page the tab showed before its last navigation through this browser. The address passes the
     * access guard before it is opened and again after redirects.
     */
    BrowserPage back(String tabId) throws BrowserException;

    /**
     * The controls of {@code tabId} (or the current tab if {@code null}) as a new snapshot: search and text fields,
     * buttons, select boxes, menu entries, tabs and toggles that are visible and enabled; search fields first. When
     * {@code filter} is not {@code null} only controls whose label, kind or value contains it (ignoring case), at most
     * {@code maxControls}. Replaces the previous control snapshot of the tab.
     */
    BrowserControls controls(String tabId, int maxControls, String filter) throws BrowserException;

    /**
     * Performs {@code action} on the control {@code controlId} of the tab's control snapshot and returns the page once
     * it settled. A form's declared target passes the access guard before submitting, and the address reached
     * afterwards passes it again. Fails with {@code INVALID_REQUEST} when the id is unknown, a new document was loaded
     * since the snapshot, or the element is gone.
     */
    BrowserActResult act(String tabId, String controlId, BrowserAction action, String value) throws BrowserException;

    /**
     * Performs {@code action} on the control the page names {@code locator}, without a control list: {@code
     * role=button name="Speichern"}, {@code label="Betrag"} or a plain visible text. Exact matches win over partial
     * ones; the same guards apply as for {@link #act}. Fails with {@code INVALID_REQUEST} when nothing matches or
     * several controls match equally well (their labels are named).
     */
    BrowserActResult actLocated(String tabId, String locator, BrowserAction action, String value)
            throws BrowserException;

    /**
     * A picture of {@code tabId} (or the current tab if {@code null}): the visible part of the page, or with
     * {@code controlId} the region around that control of the control snapshot (scrolled into view). With
     * {@code marks} the ids of the snapshot's controls visible in the picture are drawn next to them; without a valid
     * snapshot a new one is taken first, which replaces the previous one. The current address passes the access
     * guard before the picture is taken; password fields are masked. Does not navigate.
     */
    BrowserScreenshot screenshot(String tabId, String controlId, boolean marks) throws BrowserException;

    /**
     * Looks for what keeps the content of {@code tabId} (or the current tab if {@code null}) out of reach: a consent
     * banner or a challenge (captcha, login) the user has to solve in the visible browser. Does not change the page.
     */
    BrowserProbe probe(String tabId) throws BrowserException;

    /**
     * Ends this chat's browser process and connection; the profile stays. The next operation starts the browser
     * again with the same profile. Does nothing when no browser runs.
     */
    void closeBrowser();

    /**
     * Switches Human Emulation of this chat's browser from the next operation on: {@code keys} uneven keystrokes,
     * scrolling and reading pauses, {@code mouse} curved mouse paths with tremor. Both act only inside the browser.
     */
    void setHumanEmulation(boolean keys, boolean mouse);

    /** Whether a browser process of this chat runs right now (as far as the runtime knows). */
    boolean isRunning();
}
