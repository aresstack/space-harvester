package com.aresstack.askai.research.search.security;

import java.util.Arrays;

public final class SecretArrays {

    private SecretArrays() {
    }

    public static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    public static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
