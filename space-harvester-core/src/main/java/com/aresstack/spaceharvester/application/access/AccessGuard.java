package com.aresstack.spaceharvester.application.access;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Einziger Einstieg für Rechteprüfungen in Anwendungsfällen und Controllern. Gibt nur frei, wenn die {@link
 * AccessPolicy} ausdrücklich {@link AccessDecision#ALLOW} liefert; ein fehlender Kontext, {@link
 * AccessDecision#UNDETERMINED} und Fehler der Richtlinie verweigern (fail closed).
 *
 * <p>Prüfen vor dem Lesen bei Quelle oder Cache, wo der Umfang vorher bekannt ist ({@link #require}); Listen, Bäume und
 * Suchtreffer nach dem Lesen mit {@link #filter} beschneiden, bevor etwas davon die API verlässt.
 */
public final class AccessGuard {

    private static final System.Logger LOG = System.getLogger(AccessGuard.class.getName());

    private final AccessPolicy policy;

    /** Quellen, die gerade niemandem gezeigt werden, unabhängig von der Richtlinie ({@link #withhold}). */
    private volatile java.util.function.Predicate<String> withheld = sourceId -> false;

    public AccessGuard(AccessPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Hält Quellen zurück, solange {@code withheld} sie nennt, etwa für quellenübergreifende Aufträge eine Quelle,
     * deren Daten gerade gelöscht werden (Fundstelle 22): {@link #permits} und {@link #permitsSourceWide} verweigern
     * dann, egal was die Richtlinie sagt.
     */
    public void withhold(java.util.function.Predicate<String> withheld) {
        this.withheld = Objects.requireNonNull(withheld, "withheld");
    }

    private boolean withheld(String sourceId) {
        try {
            return sourceId != null && withheld.test(sourceId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean permits(AccessContext context, ResourceScope scope, AccessKind kind) {
        if (context == null || scope == null || kind == null || withheld(scope.sourceId())) {
            return false;
        }
        try {
            return policy.decide(context, scope, kind) == AccessDecision.ALLOW;
        } catch (RuntimeException e) {
            // Nur der Ausnahmetyp: Meldungen einer delegierten Prüfung können Hosts oder Token-Fragmente enthalten.
            LOG.log(System.Logger.Level.WARNING, "Rechteprüfung für {0} fehlgeschlagen ({1}); Zugriff verweigert",
                    context.principal(), e.getClass().getName());
            return false;
        }
    }

    /**
     * Darf der Aufrufer alles lesen, was die Zugangsdaten der Quelle sehen ({@link AccessPolicy#decideSourceWide})?
     * Fail closed wie {@link #permits}.
     */
    public boolean permitsSourceWide(AccessContext context, String sourceId, AccessKind kind) {
        if (context == null || sourceId == null || kind == null || withheld(sourceId)) {
            return false;
        }
        try {
            return policy.decideSourceWide(context, sourceId, kind) == AccessDecision.ALLOW;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Rechteprüfung für {0} fehlgeschlagen ({1}); Zugriff verweigert",
                    context.principal(), e.getClass().getName());
            return false;
        }
    }

    /**
     * @throws AccessDeniedException wenn nicht freigegeben; die API antwortet mit 403
     */
    public void require(AccessContext context, ResourceScope scope, AccessKind kind) {
        if (!permits(context, scope, kind)) {
            throw new AccessDeniedException(false, scope == null ? null : scope.sourceId());
        }
    }

    /**
     * Wie {@link #require}, aber für Prüfungen nach dem Lesen bei der Quelle: Die API antwortet dann wie bei einem
     * unbekannten Objekt, damit eine Verweigerung nicht dessen Existenz verrät.
     *
     * @throws AccessDeniedException mit {@link AccessDeniedException#concealExistence()}
     */
    public void requireConcealed(AccessContext context, ResourceScope scope, AccessKind kind) {
        if (!permits(context, scope, kind)) {
            throw new AccessDeniedException(true, scope == null ? null : scope.sourceId());
        }
    }

    /**
     * Darf der Aufrufer den Inhalt dieser Seite lesen? Für Korpusfreigaben entscheidet der Space; die delegierte Prüfung
     * (#24) fragt die Quelle nach der Seite selbst.
     *
     * @param spaceKey Space der Seite, wenn bekannt; ohne ihn geben Korpusfreigaben nur bei Freigabe aller Spaces frei
     */
    public boolean canRead(AccessContext context, String sourceId, String spaceKey, String pageId) {
        return permits(context, ResourceScope.page(sourceId, spaceKey, pageId), AccessKind.CONTENT);
    }

    /** Die Elemente, die der Aufrufer sehen darf, in unveränderter Reihenfolge. */
    public <T> List<T> filter(AccessContext context, Collection<T> items, AccessKind kind,
                              Function<? super T, ResourceScope> scope) {
        return items.stream().filter(item -> permits(context, scope.apply(item), kind)).toList();
    }
}
