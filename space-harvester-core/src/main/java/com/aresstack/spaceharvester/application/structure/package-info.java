/**
 * Strukturgraph (Paket G1 des Fahrplans „Analyse-Reiter“, 21.11.2026): die Zusammenhänge einer Seite für Navigation
 * und Recherche, abgeleitet bei der Abfrage aus Hierarchie, Verweisen, bestätigten Begriffsbeziehungen und (abschaltbar)
 * ähnlichen Abschnitten. Einstieg ist {@link com.aresstack.spaceharvester.application.structure.PageConnections}.
 *
 * <p><b>Abgrenzung (Vorgabe Angelo, 21.11.2026):</b> Der Strukturgraph ergänzt nur. Er ersetzt nicht den
 * Wissensgraphen der objektorientierten Analyse ({@code application.knowledge}: {@code KnowledgeGraph},
 * {@code PageGraph}, {@code GraphNeighbourhood}), der aus fachlichen Aussagen Struktur- und Verhaltenssicht einer
 * Domäne erschließt. Beide Graphen teilen keine Klassen; eine spätere Verknüpfung ist möglich, aber nicht Teil von G1.
 *
 * <p>Grundsätze:
 * <ul>
 *   <li>Ohne Aussagen, ohne Modell, ohne KI-Dienst. Nichts wird gespeichert, kein Kantenspeicher, kein Polling.</li>
 *   <li>Jede Kante trägt Art ({@link com.aresstack.spaceharvester.application.structure.ConnectionKind}), Grundlage
 *       ({@link com.aresstack.spaceharvester.application.structure.Basis}) und Herkunft
 *       ({@link com.aresstack.spaceharvester.application.structure.Provenance}). Eine ausdrückliche Verlinkung oder
 *       Hierarchie ({@code EXPLICIT}) sieht nie aus wie eine bestätigte Begriffsbeziehung ({@code CONFIRMED}) oder eine
 *       berechnete Ähnlichkeit ({@code COMPUTED}).</li>
 *   <li>Nur lokal gespeicherte Seiten, mit den Rechten des Aufrufers je Seite. Was er nicht sehen darf, fehlt ohne
 *       Spur, auch in Zählern und Kennzeichen.</li>
 *   <li>Begrenzt: je Art und insgesamt; gekürzte Arten stehen in der Antwort.</li>
 * </ul>
 */
package com.aresstack.spaceharvester.application.structure;
