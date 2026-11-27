package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Site leases with deadlines over every worker and chat of the pool. The key is the host without a leading
 * {@code www.}; every search-engine operation shares the one key {@value #SEARCH_KEY}.
 *
 * <ul>
 *   <li>Each site has a number of permits (the pool setting, or an override of {@link SiteLeasePolicy}) and a
 *       minimum spacing between two lease starts (the override's, else the policy's default spacing), so two
 *       tabs never hit the same site at the same moment.</li>
 *   <li>Waiters are served in FIFO order per site; a wait is bounded by the caller's timeout and ends typed
 *       {@value #SITE_BUSY}.</li>
 *   <li>A lease past its hold deadline is expired. A waiter that waited longer than the wait deadline marks the
 *       oldest holder expired. An expired holder keeps its permit until its running operation returns (two
 *       operations never overlap beyond the permits); then the permit goes to the head waiter and the holder's
 *       next call on that site fails typed {@value #SITE_LEASE_EXPIRED} (see {@link WorkerBrowsingContext}).</li>
 *   <li>A site in cool-down (CAPTCHA, block page) admits nobody until the cool-down ends: 30 s doubling to
 *       10 min, reset by the next clean operation after it.</li>
 * </ul>
 * No wait is unbounded unless the caller passes timeout 0, so a hung holder can never deadlock a waiter.
 */
final class SiteConcurrencyGate {

    static final String SEARCH_KEY = "search-engines";
    static final String SITE_BUSY = "SITE_BUSY";
    static final String SITE_LEASE_EXPIRED = "SITE_LEASE_EXPIRED";
    static final long COOL_DOWN_MIN_MILLIS = 30_000L;
    static final long COOL_DOWN_MAX_MILLIS = 600_000L;

    /** Time source; tests inject one and call {@link #wakeUp()} after moving it. */
    interface Clock {
        long nowMillis();
    }

    static final Clock SYSTEM_CLOCK = new Clock() {
        public long nowMillis() {
            return System.nanoTime() / 1_000_000L;
        }
    };

    /** One granted place on a site. */
    static final class Lease {
        final String key;
        final long acquiredAt;
        private boolean expired;
        private String expiredBecause;
        private boolean released;

        Lease(String key, long acquiredAt) {
            this.key = key;
            this.acquiredAt = acquiredAt;
        }
    }

    private static final class Site {
        final List<Lease> holders = new ArrayList<Lease>();
        final Deque<Object> waiters = new ArrayDeque<Object>();
        long lastStart = Long.MIN_VALUE / 2;
        long coolUntil = Long.MIN_VALUE / 2;
        int strikes;
    }

    private final Clock clock;
    private final Map<String, Site> sites = new HashMap<String, Site>();
    private int limit;
    private SiteLeasePolicy policy;

    SiteConcurrencyGate(int limit) {
        this(limit, SiteLeasePolicy.defaults(), SYSTEM_CLOCK);
    }

    SiteConcurrencyGate(int limit, SiteLeasePolicy policy, Clock clock) {
        this.limit = Math.max(1, limit);
        this.policy = policy == null ? SiteLeasePolicy.defaults() : policy;
        this.clock = clock == null ? SYSTEM_CLOCK : clock;
    }

    synchronized void setLimit(int limit) {
        this.limit = Math.max(1, limit);
        notifyAll();
    }

    synchronized void setPolicy(SiteLeasePolicy policy) {
        this.policy = policy == null ? SiteLeasePolicy.defaults() : policy;
        notifyAll();
    }

    /** Re-evaluates every waiter, e.g. after an injected clock moved. */
    synchronized void wakeUp() {
        notifyAll();
    }

    /** The gate key of a URL, or null when it has none. */
    static String siteOf(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String host = PublicSuffixDomainKeyResolver.hostOf(url.trim());
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /**
     * Takes a place on the site; waits FIFO up to {@code timeoutMillis} (0 = unbounded) and then fails with
     * {@value #SITE_BUSY}. Null key = no gate, returns null.
     */
    Lease enter(String key, long timeoutMillis) throws BrowserException, InterruptedException {
        if (key == null) {
            return null;
        }
        synchronized (this) {
            long start = clock.nowMillis();
            long deadline = timeoutMillis > 0 ? start + timeoutMillis : Long.MAX_VALUE;
            Object me = new Object();
            Site site = site(key);
            site.waiters.addLast(me);
            boolean tookOver = false;
            try {
                while (true) {
                    long now = clock.nowMillis();
                    expireOverdue(site, now);
                    int permits = permits(key);
                    long spacing = spacing(key);
                    if (site.waiters.peekFirst() == me && site.holders.size() < permits
                            && now >= site.lastStart + spacing && now >= site.coolUntil) {
                        site.waiters.removeFirst();
                        Lease lease = new Lease(key, now);
                        site.holders.add(lease);
                        site.lastStart = now;
                        notifyAll(); // the next waiter may fit a free permit as well
                        return lease;
                    }
                    if (!tookOver && now - start >= policy.waitDeadlineMillis && site.holders.size() >= permits) {
                        tookOver = true;
                        Lease oldest = oldestLive(site);
                        if (oldest != null) {
                            oldest.expired = true;
                            oldest.expiredBecause = "another worker waited " + (now - start) / 1000L + " s";
                        }
                    }
                    if (now >= deadline) {
                        throw new BrowserException(SITE_BUSY + " — " + key + ": no free place after "
                                + (now - start) / 1000L + " s (" + site.holders.size() + "/" + permits
                                + " in use, " + (site.waiters.size() - 1) + " ahead or behind"
                                + (now < site.coolUntil ? ", cooling down" : "") + "). Retry later.");
                    }
                    long next = deadline;
                    if (!tookOver && now < start + policy.waitDeadlineMillis) {
                        next = Math.min(next, start + policy.waitDeadlineMillis);
                    }
                    if (now < site.lastStart + spacing) {
                        next = Math.min(next, site.lastStart + spacing);
                    }
                    if (now < site.coolUntil) {
                        next = Math.min(next, site.coolUntil);
                    }
                    Lease oldest = oldestLive(site);
                    if (oldest != null) {
                        next = Math.min(next, oldest.acquiredAt + policy.holdDeadlineMillis);
                    }
                    long sleep = next == Long.MAX_VALUE ? 0L : Math.max(1L, next - now);
                    wait(sleep);
                }
            } finally {
                if (site.waiters.remove(me)) {
                    notifyAll(); // the next waiter may now be at the head
                }
                forgetIfIdle(key, site);
            }
        }
    }

    /** Gives the place back; returns true when the lease had expired (its holder lost the site). */
    synchronized boolean leave(Lease lease) {
        if (lease == null || lease.released) {
            return false;
        }
        lease.released = true;
        Site site = sites.get(lease.key);
        if (site != null) {
            site.holders.remove(lease);
            notifyAll();
            forgetIfIdle(lease.key, site);
        }
        if (!lease.expired && clock.nowMillis() - lease.acquiredAt >= policy.holdDeadlineMillis) {
            lease.expired = true;
            lease.expiredBecause = "the hold deadline of " + policy.holdDeadlineMillis / 1000L + " s passed";
        }
        return lease.expired;
    }

    synchronized String expiredBecause(Lease lease) {
        return lease == null ? null : lease.expiredBecause;
    }

    /** The site showed a CAPTCHA or block page: nobody enters it for a growing cool-down. */
    synchronized void coolDown(String key) {
        if (key == null) {
            return;
        }
        Site site = site(key);
        long length = COOL_DOWN_MIN_MILLIS << Math.min(site.strikes, 5);
        site.strikes++;
        site.coolUntil = clock.nowMillis() + Math.min(length, COOL_DOWN_MAX_MILLIS);
        notifyAll();
    }

    /** A clean operation after the cool-down resets the cool-down ladder. */
    synchronized void healthy(String key) {
        Site site = key == null ? null : sites.get(key);
        if (site != null && clock.nowMillis() >= site.coolUntil) {
            site.strikes = 0;
            forgetIfIdle(key, site);
        }
    }

    synchronized boolean coolingDown(String key) {
        Site site = key == null ? null : sites.get(key);
        return site != null && clock.nowMillis() < site.coolUntil;
    }

    synchronized int active(String key) {
        Site site = sites.get(key);
        return site == null ? 0 : site.holders.size();
    }

    synchronized int waiting(String key) {
        Site site = sites.get(key);
        return site == null ? 0 : site.waiters.size();
    }

    /** Holders, waiters and cool-downs per site, for the technical log. */
    synchronized String snapshot() {
        long now = clock.nowMillis();
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Site> entry : new TreeMap<String, Site>(sites).entrySet()) {
            Site site = entry.getValue();
            int expired = 0;
            for (Lease lease : site.holders) {
                if (lease.expired) {
                    expired++;
                }
            }
            text.append(entry.getKey()).append(": ").append(site.holders.size()).append('/')
                    .append(permits(entry.getKey())).append(" held");
            if (expired > 0) {
                text.append(" (").append(expired).append(" expired)");
            }
            text.append(", ").append(site.waiters.size()).append(" waiting");
            if (now < site.coolUntil) {
                text.append(", cool-down ").append((site.coolUntil - now + 999L) / 1000L).append(" s");
            }
            text.append('\n');
        }
        return text.toString();
    }

    private int permits(String key) {
        SiteLeasePolicy.SiteOverride override = SEARCH_KEY.equals(key) ? null : policy.overrideFor(key);
        return override == null ? limit : override.permits;
    }

    private long spacing(String key) {
        SiteLeasePolicy.SiteOverride override = SEARCH_KEY.equals(key) ? null : policy.overrideFor(key);
        if (SEARCH_KEY.equals(key)) {
            return 0L; // the search browser keeps the engines' own delay
        }
        return override == null ? policy.defaultSpacingMillis : override.spacingMillis;
    }

    private Site site(String key) {
        Site site = sites.get(key);
        if (site == null) {
            site = new Site();
            sites.put(key, site);
        }
        return site;
    }

    private void expireOverdue(Site site, long now) {
        for (Lease lease : site.holders) {
            if (!lease.expired && now - lease.acquiredAt >= policy.holdDeadlineMillis) {
                lease.expired = true;
                lease.expiredBecause = "the hold deadline of " + policy.holdDeadlineMillis / 1000L + " s passed";
            }
        }
    }

    private static Lease oldestLive(Site site) {
        Lease oldest = null;
        for (Iterator<Lease> it = site.holders.iterator(); it.hasNext(); ) {
            Lease lease = it.next();
            if (!lease.expired && (oldest == null || lease.acquiredAt < oldest.acquiredAt)) {
                oldest = lease;
            }
        }
        return oldest;
    }

    private void forgetIfIdle(String key, Site site) {
        long now = clock.nowMillis();
        if (site.holders.isEmpty() && site.waiters.isEmpty() && site.strikes == 0 && now >= site.coolUntil
                && now >= site.lastStart + spacing(key)) {
            sites.remove(key);
        }
    }
}
