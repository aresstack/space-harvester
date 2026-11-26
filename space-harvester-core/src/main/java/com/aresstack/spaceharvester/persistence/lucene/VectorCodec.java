package com.aresstack.spaceharvester.persistence.lucene;

import java.io.IOException;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/**
 * Lucenes Standard-Codec, nur ohne die Obergrenze von {@value KnnVectorsFormat#DEFAULT_MAX_DIMENSIONS} Dimensionen je
 * Vektor (Angelo 26.11.2026, docs/grenzen.md): Embedding-Modelle mit 1536 oder 3072 Dimensionen bekamen sonst keine
 * Vektoren, und die semantische Suche blieb still leer. Die Dimension gibt das eingestellte Modell vor.
 *
 * <p>Der Index bleibt im Standardformat: Codec und Vektorformat tragen die Namen des Standards, Lucene liest den Index
 * also über seine eigenen Klassen. Die Grenze prüft Lucene nur beim Schreiben, über das Format dieses Codecs.
 */
final class VectorCodec {

    private VectorCodec() {
    }

    /** Ein Codec für {@code IndexWriterConfig#setCodec}. */
    static Codec create() {
        Codec standard = Codec.getDefault();
        KnnVectorsFormat vectors = new PerFieldKnnVectorsFormat() {
            private final KnnVectorsFormat hnsw = new AnyDimension(new Lucene99HnswVectorsFormat());

            @Override
            public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
                return hnsw;
            }
        };
        return new FilterCodec(standard.getName(), standard) {
            @Override
            public KnnVectorsFormat knnVectorsFormat() {
                return vectors;
            }
        };
    }

    /** Das HNSW-Format des Standards unter seinem Namen, ohne Grenze der Dimension. */
    private static final class AnyDimension extends KnnVectorsFormat {

        private final KnnVectorsFormat delegate;

        AnyDimension(KnnVectorsFormat delegate) {
            super(delegate.getName());
            this.delegate = delegate;
        }

        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
            return delegate.fieldsWriter(state);
        }

        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
            return delegate.fieldsReader(state);
        }

        @Override
        public int getMaxDimensions(String fieldName) {
            return Integer.MAX_VALUE;
        }
    }
}
