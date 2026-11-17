package com.aresstack.spaceharvester.app.credentials;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretVault;
import com.aresstack.enterpriseai.adyton.VaultEntry;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wiederholt lesende Zugriffe auf KeePass, wenn KeePassRPC vorübergehend nicht antwortet
 * ({@link SecretUnavailableException.Reason#NOT_AVAILABLE}: Zeitüberschreitung, kurz abgebrochene Verbindung), wie
 * {@code RetryingSecretProvider} im Enterprise AI Client ({@code security.keepass.retryCount} = 2,
 * {@code retryDelayMillis} = 300: Pausen 300 ms, dann 750 ms). Ein fehlender Eintrag, ein abgelehnter Zugriff und ein
 * abgebrochenes Pairing werden nie wiederholt; schreibende Zugriffe laufen genau einmal.
 *
 * <p>Nach jedem gelungenen Zugriff meldet er {@code reachable}: Daran erkennt die App, dass KeePass wieder antwortet,
 * und holt beim Start ausgebliebene Zugangsdaten nach (Observer, kein Polling). Hält selbst kein Geheimnis.
 */
final class RetryingVault implements SecretVault {

    private static final Logger LOG = LoggerFactory.getLogger(RetryingVault.class);
    /** Pausen vor den Wiederholungen wie im EAC (300 ms, dann ×2,5). */
    static final long[] DELAYS_MILLIS = {300, 750};

    /** Wartet zwischen zwei Versuchen; in Tests ohne echte Pause. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final SecretVault delegate;
    private final long[] delays;
    private final Sleeper sleeper;
    private final Runnable reachable;

    RetryingVault(SecretVault delegate, long[] delays, Sleeper sleeper, Runnable reachable) {
        this.delegate = delegate;
        this.delays = delays.clone();
        this.sleeper = sleeper;
        this.reachable = reachable;
    }

    /** Nur „nicht erreichbar“ ist vorübergehend. */
    static boolean isTransient(SecretUnavailableException failure) {
        return failure.reason() == SecretUnavailableException.Reason.NOT_AVAILABLE;
    }

    @Override
    public List<VaultEntry> list() throws SecretUnavailableException {
        return retrying(delegate::list);
    }

    @Override
    public VaultEntryDraft read(String title) throws SecretUnavailableException {
        return retrying(() -> delegate.read(title));
    }

    @Override
    public void save(VaultEntryDraft entry) throws SecretUnavailableException {
        delegate.save(entry);
        reachable.run();
    }

    @Override
    public void create(VaultEntryDraft entry) throws SecretUnavailableException {
        delegate.create(entry);
        reachable.run();
    }

    @Override
    public void remove(String title) throws SecretUnavailableException {
        delegate.remove(title);
        reachable.run();
    }

    private interface Read<T> {
        T call() throws SecretUnavailableException;
    }

    private <T> T retrying(Read<T> read) throws SecretUnavailableException {
        for (int attempt = 0; ; attempt++) {
            T result;
            try {
                result = read.call();
            } catch (SecretUnavailableException e) {
                if (!isTransient(e) || attempt >= delays.length) {
                    throw e;
                }
                long delay = delays[attempt];
                // Nur Versuch und Pause, nie Eintrag oder Wert.
                LOG.info("KeePass nicht erreichbar; Wiederholung {} von {} in {} ms", attempt + 1, delays.length,
                        delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                continue;
            }
            reachable.run();
            return result;
        }
    }
}
