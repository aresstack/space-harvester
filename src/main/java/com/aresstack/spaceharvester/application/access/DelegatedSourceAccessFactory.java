package com.aresstack.spaceharvester.application.access;

import java.util.Optional;

/** Liefert die {@link DelegatedSourceAccess} einer Quelle, wenn deren Art eine delegierte Prüfung kennt. */
@FunctionalInterface
public interface DelegatedSourceAccessFactory {

    Optional<DelegatedSourceAccess> forSource(String sourceId);
}
