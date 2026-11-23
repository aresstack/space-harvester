# Herkunft

Kopie des Moduls `mermaid-renderer` aus [MainframeMate](https://github.com/Miguel0888/MainframeMate) (Stand 6640380),
vollständig mit Grammatiken, Ressourcen (Mermaid 11.4.1, Browser-Shim), JS-Bundle-Skripten und Tests.

Angepasst:

- Pakete `de.bund.zrb.mermaid` und `com.aresstack.mermaid.*` → `com.aresstack.spaceharvester.mermaid.*`, Ressourcen
  `/mermaid/` → `/spaceharvester-mermaid/`. Grund: die App hat über `com.aresstack:mermaid-java` (Markdown-Ausgabe,
  document-export) bereits gleichnamige Klassen im Klassenpfad.
- `SvgRenderer` (in MainframeMate in wiki-integration, im Modul nur als Testkopie) liegt hier im Hauptquelltext, weil
  die Diagramm-Ansicht und der Export ihn brauchen.
- Java 21, GraalJS 21.3.20.0.3 (wie drawio-java), Batik 1.20 (von der Wurzel erzwungen), ANTLR 4.9.3 wie im Original.
