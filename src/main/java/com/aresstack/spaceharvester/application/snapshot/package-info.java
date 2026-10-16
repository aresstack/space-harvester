/**
 * Quellenneutraler Vertrag für native Seiten-Snapshots (Raw-First, Strang B, Vertragsfenster 1 in
 * {@code docs/PARALLEL_WORKSTREAMS.md}): {@link com.aresstack.spaceharvester.application.snapshot.PageSnapshot} mit
 * unveränderter {@link com.aresstack.spaceharvester.application.snapshot.NativePayload} und Kopfdaten, sowie der
 * Speicher-Port {@link com.aresstack.spaceharvester.application.snapshot.SnapshotRepository}.
 *
 * <p>Frei von Lucene-, Spring- und Confluence-Typen. Persistenz (Lucene, #26), Synchronisierung (#16) und Abfragen
 * (#27) bauen gegen diese Typen; Änderungen am Vertrag gehen über den Strang B.
 */
package com.aresstack.spaceharvester.application.snapshot;
