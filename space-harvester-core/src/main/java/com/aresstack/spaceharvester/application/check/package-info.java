/**
 * Prüfschritt (ADR 0032, FL4): Das Chat-Modell beurteilt Aussagen gegen ihren Originalsatz (stimmt, unvollständig,
 * falsch) und darf unvollständige mit einem einfachen Hauptsatz aus Wörtern des Satzes korrigieren. Nur auf
 * ausdrücklichen Auftrag, nur für Seiten mit KI-Freigabe; ein Urteil senkt höchstens die wirksame Stufe, nie die
 * gespeicherte Stufe des Parsers.
 */
package com.aresstack.spaceharvester.application.check;
