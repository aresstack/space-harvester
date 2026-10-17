/**
 * Objektzentrierte Auswertung einer UD-Analyse (#98): Erwähnungen von Nomen und Pronomen mit eigener lokaler
 * Identität, Aussagen je Prädikat mit Subjekt, Akkusativobjekt und ergänzenden Rollen, nominale Bezüge (Genitiv)
 * und eine priorisierte Sicht Subjekt → Prädikat → Akkusativobjekt. Rollen folgen dem Aussagenmodell aus
 * enterprise-ai-client und den Sprachanreicherungen aus #29 ({@code ACTOR}, {@code AFFECTED_OBJECT},
 * {@code RECIPIENT}, {@code INSTRUMENT}).
 * <p>
 * Regeln und Grenzen:
 * <ul>
 *   <li>Parserkanten sind die Grundlage ({@link com.aresstack.spaceharvester.nlp.extraction.Basis#PARSER}); der
 *       Kasus-Rückfall ({@link com.aresstack.spaceharvester.nlp.extraction.CaseFallback}) ergänzt nur, was fehlt,
 *       markiert sich als Heuristik und überschreibt keine Kante.</li>
 *   <li>Gleiche Wortform heißt nicht gleiche Entität: Erwähnungen werden nie zusammengeführt, Pronomen bleiben
 *       {@link com.aresstack.spaceharvester.nlp.extraction.Resolution#UNRESOLVED}.</li>
 *   <li>Ein Genitiv ist ein nominaler Bezug, keine Besitzaussage.</li>
 *   <li>Häufigkeiten ({@link com.aresstack.spaceharvester.nlp.extraction.MentionStatistics}) sind sprachliche
 *       Statistik, keine fachliche Wichtigkeit.</li>
 * </ul>
 */
package com.aresstack.spaceharvester.nlp.extraction;
