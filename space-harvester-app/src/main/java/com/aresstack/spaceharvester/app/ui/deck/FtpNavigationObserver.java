package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;

import java.util.List;

/** Receive direct entries after successful FTP navigation. */
public interface FtpNavigationObserver {

    FtpNavigationObserver NONE = new FtpNavigationObserver() {
        public boolean enabled(FtpSession session) { return false; }
        public void listed(FtpSession session, List<RemoteEntry> entries) { }
    };

    boolean enabled(FtpSession session);
    void listed(FtpSession session, List<RemoteEntry> entries);
}