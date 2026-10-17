/**
 * Seitenhierarchie als reine ID-Projektion: aus flachen Einträgen {@code pageId + parentPageId} einer Quelle wird ein
 * Baum mit {@code roots[]} gebildet, ohne Seiteninhalte. Robust gegen mehrere Wurzeln, fehlende Eltern und Zyklen;
 * jede Seite erscheint genau einmal. Frameworkneutral und ohne Kenntnis des Caches.
 */
package com.aresstack.spaceharvester.application.tree;
