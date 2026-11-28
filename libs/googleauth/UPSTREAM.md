# GoogleAuth (TOTP)

Upstream: https://github.com/wstrange/GoogleAuth

Festgehaltener Stand: Tag `1.5.0`, Commit `d6dfa1191137aa6c9bf7398058830eaf8ee5eb01` (Maven `com.warrenstrange:googleauth:1.5.0`).
Lizenz: BSD 3-Clause, siehe LICENSE; die Lizenz wird unter `META-INF/licenses/googleauth` mit ausgeliefert.

Dieselbe Bibliothek wie im `TotpService` von WD4J. Maven Central ist für neue Abhängigkeiten des Space Harvester keine
verlässliche Quelle (#1648, CI-Läufe scheitern mit 429), deshalb liegt sie hier als unveränderte Quellkopie und wird
als Gradle-Projekt `:googleauth` gebaut.

Kopiert sind `LICENSE` und `src/main/java` ohne `GoogleAuthenticatorQRGenerator.java`: Der QR-Code-Erzeuger braucht
Apache HttpClient, den der Space Harvester nicht verwendet. Laufzeitabhängigkeit bleibt nur commons-codec.

`UPSTREAM.sha256` hält die Prüfsummen der kopierten Dateien; `verifyUpstreamCopy` läuft vor `compileJava` und meldet
geänderte, fehlende oder zusätzliche Dateien. Für einen neuen Stand den Tag klonen, dieselben Dateien kopieren, die
Quellen prüfen und das Manifest neu schreiben.
