/**
 * Zugriffsschutz der eigenen HTTP-API: Anmeldung technischer Clients per Bearer-Token, Korpusfreigabe je Client und der
 * lokale Entwicklungsmodus. Liefert den {@link com.aresstack.spaceharvester.application.access.AccessContext} an
 * Controller und verdrahtet {@link com.aresstack.spaceharvester.application.access.AccessGuard} und
 * {@link com.aresstack.spaceharvester.application.access.AccessPolicy}.
 *
 * <p>Unabhängig davon, wie sich der Harvester bei Quellen anmeldet ({@code source}); die Rechte des Erntekontos spielen
 * hier keine Rolle. Betrieb und Konfiguration: {@code docs/SECURITY.md}.
 */
package com.aresstack.spaceharvester.security;
