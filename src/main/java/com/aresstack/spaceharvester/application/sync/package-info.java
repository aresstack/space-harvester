/**
 * Synchronisierung eines Space mit dem Cache (#16): vollständige Inventur der Seitenmetadaten ({@link
 * com.aresstack.spaceharvester.application.sync.InventoryCollector}), Abgleich mit der zuletzt bestätigten Generation
 * ({@link com.aresstack.spaceharvester.application.sync.SyncPlanner}), Prüfung fehlender Seiten ({@link
 * com.aresstack.spaceharvester.application.sync.RemovalCheck}) und Bestätigung der nächsten Generation ({@link
 * com.aresstack.spaceharvester.application.sync.SpaceSync}).
 *
 * <p>Grundregeln: Nur eine vollständig und widerspruchsfrei gelistete Inventur kann eine Generation bestätigen und
 * Seiten entfernen. {@code complete=true} am letzten Cursor heißt nur, dass die Paginierung zu Ende ist, nicht, dass
 * der Bestand ein atomarer Schnappschuss der Quelle ist. Eine fehlende Seite wird erst entfernt, wenn die Quelle sie
 * einzeln als nicht vorhanden oder verschoben bestätigt. Persistiert wird über den Port {@link
 * com.aresstack.spaceharvester.application.sync.SyncBaselineStore}; Inhalte lädt diese Schicht nicht.
 */
package com.aresstack.spaceharvester.application.sync;
