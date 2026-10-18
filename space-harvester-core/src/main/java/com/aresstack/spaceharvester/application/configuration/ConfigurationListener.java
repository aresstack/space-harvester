package com.aresstack.spaceharvester.application.configuration;

/**
 * Eine Komponente, die beim Wechsel Instanzen austauscht: KI-Client, Embeddings, Reranker, Transport, Zeitplan,
 * Konnektoren. Der Wechsel hat zwei Phasen, damit eine fehlerhafte Änderung nichts halb umstellt:
 *
 * <ol>
 *     <li>{@link #prepare}: neue Instanzen bauen und, wo sinnvoll, prüfen (etwa ob der KI-Dienst das Modell kennt);
 *     noch nichts austauschen. Wer ablehnt, wirft {@link ConfigurationRejectedException}.</li>
 *     <li>{@link Preparation#commit()}: haben alle Beobachter vorbereitet, tauscht jeder seine Instanzen aus.
 *     Laufende Aufgaben behalten die alten (siehe {@link Swappable}); freigegeben werden sie erst nach ihrem Ende.
 *     </li>
 * </ol>
 *
 * Lehnt ein Beobachter ab, bekommen alle bereits vorbereiteten {@link Preparation#rollback()}, und die bisherige
 * Konfiguration bleibt aktiv. {@code commit} darf nicht scheitern; was scheitern kann, gehört in {@code prepare}.
 * {@code commit} liest die neue Konfiguration aus {@link ConfigurationChange#next()}; {@code ConfigurationManager.current()}
 * zeigt sie erst, wenn alle ausgetauscht haben.
 */
@FunctionalInterface
public interface ConfigurationListener {

    /**
     * @param change bisherige und neue Konfiguration
     * @return was beim Aktivieren oder Verwerfen geschieht; {@link Preparation#NOTHING}, wenn die Änderung den
     *         Beobachter nicht betrifft
     * @throws ConfigurationRejectedException wenn die neue Konfiguration für diese Komponente nicht taugt
     */
    Preparation prepare(ConfigurationChange change);

    /** Vorbereiteter Austausch. */
    interface Preparation {

        /** Nichts zu tun. */
        Preparation NOTHING = () -> {
        };

        /** Austauschen; wird nur aufgerufen, wenn alle Beobachter vorbereitet haben. */
        void commit();

        /** Vorbereitete Instanzen verwerfen; Standard: nichts. */
        default void rollback() {
        }
    }
}
