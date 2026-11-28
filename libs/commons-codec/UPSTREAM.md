# Apache Commons Codec

Upstream: https://github.com/apache/commons-codec

Festgehaltener Stand: Tag `rel/commons-codec-1.22.1`, Commit `dc8f6c832a30f524bf12a5ae7ab013e4ad7c088f`
(Maven `commons-codec:commons-codec:1.22.1`). Lizenz: Apache License 2.0, siehe LICENSE.txt und NOTICE.txt; beide werden
unter `META-INF/licenses/commons-codec` mit ausgeliefert.

googleauth (`libs/googleauth`) braucht Base32/Base64 daraus. Maven Central ist keine verlässliche Quelle (#1648, CI-Läufe
scheitern mit 429 oder finden die Datei nicht), deshalb liegt die Bibliothek hier als unveränderte Quellkopie. Gradle
ersetzt `commons-codec:commons-codec` in allen Projekten durch das Projekt `:commons-codec` (Wurzel-`build.gradle`),
so steht sie nur einmal im Klassenpfad, auch wo andere Bibliotheken sie mitbringen. Im Server legt das
Spring-Boot-Abhängigkeitsmanagement die Fassung selbst fest; dort bleibt es wie bisher bei dessen Fassung.

Kopiert sind `LICENSE.txt`, `NOTICE.txt`, `src/main/java` und `src/main/resources` (ohne `src/main/javadoc`), gebaut wie
upstream für Java 8. `UPSTREAM.sha256` hält die Prüfsummen; `verifyUpstreamCopy` läuft vor `compileJava`. Für einen neuen
Stand den Tag klonen, dieselben Ordner kopieren, das Manifest neu schreiben und die Version im Wurzel-`build.gradle`
nachziehen.
