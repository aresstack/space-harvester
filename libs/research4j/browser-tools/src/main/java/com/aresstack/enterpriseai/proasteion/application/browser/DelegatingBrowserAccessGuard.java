package com.aresstack.enterpriseai.proasteion.application.browser;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserAccessGuard;
import com.aresstack.enterpriseai.browser.api.BrowserException;

/**
 * The guard handed to the browser runtime when the adapters are assembled, before the composition root (with the
 * settings and the approval prompt) exists. Until {@link #delegateTo} is called every address is refused.
 */
public final class DelegatingBrowserAccessGuard implements BrowserAccessGuard {

    private volatile BrowserAccessGuard delegate;

    public void delegateTo(BrowserAccessGuard guard) {
        this.delegate = guard;
    }

    @Override
    public void admit(ChatConversationId chat, String url) throws BrowserException {
        BrowserAccessGuard current = delegate;
        if (current == null) {
            throw new BrowserException(BrowserException.Kind.BLOCKED,
                    "Die Browser-Richtlinie ist noch nicht geladen; " + url + " wurde nicht geöffnet.");
        }
        current.admit(chat, url);
    }
}
