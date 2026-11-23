package com.aresstack.enterpriseai.browser.firefox;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;

import java.util.concurrent.TimeUnit;

/** One running Firefox of one chat profile; holds the profile lock until it is stopped or gone. */
final class FirefoxInstance implements BrowserInstance {

    private final Process process;
    private final int port;
    private final FirefoxEngine.ProfileLock lock;
    private boolean released;

    FirefoxInstance(Process process, int port, FirefoxEngine.ProfileLock lock) {
        this.process = process;
        this.port = port;
        this.lock = lock;
    }

    @Override
    public synchronized boolean isAlive() {
        boolean alive = process.isAlive();
        if (!alive) {
            releaseLock();
        }
        return alive;
    }

    @Override
    public BrowserConnection connect() throws BrowserException {
        if (!isAlive()) {
            throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Firefox ist beendet");
        }
        return BidiBrowserConnection.open(port, this);
    }

    @Override
    public synchronized void stop() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        releaseLock();
    }

    private void releaseLock() {
        if (!released) {
            released = true;
            lock.release();
        }
    }
}
