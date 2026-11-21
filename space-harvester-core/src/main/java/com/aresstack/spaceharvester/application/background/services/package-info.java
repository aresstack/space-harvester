/**
 * Der Dienst-Planer (D1, ADR 0025 Nachtrag „Dienst-Planer“): Analysedienste mit Starttyp wie Windows-Dienste und ihren
 * Vorstufen; ein ausdrücklich gestartetes Ziel legt die fehlenden Vorstufen als eine Hintergrundgruppe an, je Stufe und
 * Space ein Unterauftrag, der je Space auf seine Vorstufen wartet.
 */
package com.aresstack.spaceharvester.application.background.services;
