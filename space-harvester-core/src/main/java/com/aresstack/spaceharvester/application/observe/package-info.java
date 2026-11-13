/**
 * Beobachter statt Abfragen (ADR 0027): Der Core meldet Änderungen, eine Oberfläche fragt nicht in Abständen.
 * Gemeinsame Bausteine aller Beobachter: {@link com.aresstack.spaceharvester.application.observe.Subscription} zum
 * Abmelden, {@link com.aresstack.spaceharvester.application.observe.Listeners} für die Empfänger und
 * {@link com.aresstack.spaceharvester.application.observe.Coalescer} für die gebündelte Zustellung auf einem eigenen
 * Thread.
 */
package com.aresstack.spaceharvester.application.observe;
