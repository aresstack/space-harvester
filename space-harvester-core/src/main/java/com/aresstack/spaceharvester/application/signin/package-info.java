/**
 * Zusätzliche Anmeldung während eines Auftrags (ADR 0018): verlangt eine Quelle einen Einmalcode, wartet der Auftrag
 * an derselben Stelle auf {@link com.aresstack.spaceharvester.application.signin.SignInRequests}; die Oberfläche fragt
 * den Code ab und sendet ihn über dieselbe Klasse. Der Core zeigt nichts an und speichert keinen Code.
 */
package com.aresstack.spaceharvester.application.signin;
