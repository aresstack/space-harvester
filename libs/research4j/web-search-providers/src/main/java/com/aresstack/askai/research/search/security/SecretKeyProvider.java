package com.aresstack.askai.research.search.security;

import javax.crypto.SecretKey;

public interface SecretKeyProvider {

    SecretKey getOrCreateKey();
}
