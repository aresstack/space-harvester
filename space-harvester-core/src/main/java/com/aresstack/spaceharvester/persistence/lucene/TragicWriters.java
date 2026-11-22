package com.aresstack.spaceharvester.persistence.lucene;

import java.io.IOException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.AlreadyClosedException;

/**
 * Schließen einer Ablage, deren {@link IndexWriter} ein tragisches Ereignis schon geschlossen hat (roter
 * develop-Build 22.11.2026, {@code HarvesterGitSourceTest}): Wird ein Thread mitten in der Datei-Ein-/Ausgabe von Lucene
 * unterbrochen ({@code ClosedByInterruptException}), schließt Lucene den Writer sofort; jedes spätere {@code commit}
 * wirft {@link AlreadyClosedException}. Beim Schließen der Ablage sind die nicht dauerhaften Änderungen dann schon
 * verloren; das Schließen meldet das im Protokoll und gibt Sperre und Verzeichnis trotzdem frei, statt das Beenden des
 * Dienstes scheitern zu lassen.
 */
final class TragicWriters {

    private static final System.Logger LOG = System.getLogger(TragicWriters.class.getName());

    private TragicWriters() {
    }

    /**
     * Macht die offenen Änderungen dauerhaft, solange der Writer offen ist; hat ihn ein tragisches Ereignis
     * geschlossen, meldet es Ablage und Ursache (nur die Klasse) und wirft nicht.
     *
     * @param store Name der Ablage für das Protokoll, ohne Inhalte
     * @return ob dauerhaft gemacht wurde
     * @throws IOException wenn das Schreiben selbst scheitert; der Aufrufer meldet es wie bisher
     */
    static boolean commitOnClose(IndexWriter writer, String store) throws IOException {
        try {
            writer.commit();
            return true;
        } catch (AlreadyClosedException e) {
            LOG.log(System.Logger.Level.WARNING, "{0}: Writer war beim Schließen schon geschlossen ({1}); nicht "
                    + "dauerhafte Änderungen sind verloren", store, cause(writer, e));
            return false;
        }
    }

    /**
     * Verwirft die offenen Änderungen und schließt den Writer. Hat ein Interrupt die Sperrdatei ungültig gemacht
     * ({@code FileLock invalidated by an external force}), wirft Lucene dabei {@link AlreadyClosedException}; dann
     * meldet es Ablage und Ursache und wirft nicht, damit der Aufrufer das Verzeichnis trotzdem freigibt.
     */
    static void rollbackOnClose(IndexWriter writer, String store) throws IOException {
        try {
            writer.rollback();
        } catch (AlreadyClosedException e) {
            LOG.log(System.Logger.Level.WARNING, "{0}: Writer beim Schließen nicht sauber verworfen ({1})", store,
                    cause(writer, e));
        }
    }

    /** Wie {@link #rollbackOnClose}, für einen Writer, der beim Schließen dauerhaft macht. */
    static void closeOnClose(IndexWriter writer, String store) throws IOException {
        try {
            writer.close();
        } catch (AlreadyClosedException e) {
            LOG.log(System.Logger.Level.WARNING, "{0}: Writer beim Schließen nicht sauber geschlossen ({1})", store,
                    cause(writer, e));
            rollbackOnClose(writer, store);
        }
    }

    /** Die Klasse des tragischen Ereignisses, sonst die der Ausnahme. */
    static String cause(IndexWriter writer, RuntimeException e) {
        Throwable tragic = writer.getTragicException();
        return (tragic != null ? tragic : e).getClass().getName();
    }
}
