package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

public enum EDasRecordKind {
    INIT {
        @Override
        public String toString() {
            return "INIT";
        }
    },
    ADD {
        @Override
        public String toString() {
            return "ADD";
        }
    },
    REMOVE {
        @Override
        public String toString() {
            return "REMOVE";
        }
    },
    REMOVEALL {
        @Override
        public String toString() {
            return "REMOVEALL";
        }
    },
    EMPTY {
        @Override
        public String toString() {
            return "EMPTY";
        }
    }
}
