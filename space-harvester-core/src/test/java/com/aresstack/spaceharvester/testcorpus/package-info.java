/**
 * Testkorpus zur ADR 0026 (Anhänge und enthaltene Dateien als Dokumente), Paket TK: Erzeuger für Prüfdateien, die erst
 * im Test entstehen. Im Repository liegen keine großen oder fremden Testdateien; alles hier ist Java mit den
 * Bibliotheken, die der Core schon hat (PDFBox, POI, JDK).
 *
 * <table>
 *   <caption>Fälle und Abnahmekriterien der ADR 0026</caption>
 *   <tr><th>Abnahme</th><th>Erzeuger</th></tr>
 *   <tr><td>3 Kennung</td><td>{@link Corpus#duplicateNames(boolean)} (gleiche Namen, Pfadzeichen, gleicher Inhalt,
 *       vertauschte Reihenfolge), {@link Archives#sameNameInFolders()}</td></tr>
 *   <tr><td>4 Grenzen</td><td>{@link Limits#around} mit {@link Archives#many}, {@link Corpus#manyAttachments},
 *       {@link Archives#nested}, {@link Corpus#nestedMails}, {@link Archives#packed} (Verhältnis) und
 *       {@link Limits#stalling} (Laufzeit ohne Fortschritt)</td></tr>
 *   <tr><td>5 Speicher</td><td>{@link LargeFiles#pdfWithImages} mit {@link LargeFiles#PDF_OVER_300_MIB_PAGES},
 *       {@link LargeFiles#xlsx} mit {@link LargeFiles#XLSX_ROWS}, {@link Corpus#largeAttachmentMail},
 *       {@link Corpus#packedArchiveMail} (Messung: 150 MiB)</td></tr>
 *   <tr><td>6 Vollständigkeit</td><td>Marken je Seite, Zeile und Absatz in {@link LargeFiles}</td></tr>
 *   <tr><td>7 Status</td><td>{@link Corpus#damaged()} mit {@link DamagedFiles} (gekürzt, ohne Sinn, leer,
 *       verschlüsselt) und {@link Archives#partlyEncrypted()}</td></tr>
 *   <tr><td>11 Mails</td><td>{@link Corpus#zipInMail()}, {@link Corpus#mailInMail()},
 *       {@link Corpus#outlookMailInMail()}, {@link Corpus#damaged()}, {@link Corpus#duplicateNames(boolean)}</td></tr>
 * </table>
 *
 * <p>Große Dateien entstehen in einer Datei (meist {@code @TempDir}) und nie ganz im Speicher. Ihre Testgruppe mit
 * kleinem Heap baut Paket AP3; normale Tests rufen dieselben Erzeuger mit kleinem Umfang. Eine Postfachdatei
 * ({@code .pst}) lässt sich ohne neue Bibliothek nicht erzeugen; den Integrationstest der Outlook-Quelle führt Paket
 * OL2.
 */
package com.aresstack.spaceharvester.testcorpus;
