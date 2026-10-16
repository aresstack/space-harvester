/**
 * Eingebetteter Lucene-Cache für native Seiten-Snapshots (#26): {@link
 * com.aresstack.spaceharvester.persistence.lucene.LucenePageCache} setzt den Port {@link
 * com.aresstack.spaceharvester.application.snapshot.SnapshotRepository} um und bietet darüber hinaus Kopfdaten-,
 * Space- und Elternabfragen ohne Payload, Revisionsverlauf und atomare Schreibläufe mit Space-Synchronisierungsstand.
 * Feldschema und Lebenszyklus: {@link com.aresstack.spaceharvester.persistence.lucene.PageCacheSchema}.
 */
package com.aresstack.spaceharvester.persistence.lucene;
