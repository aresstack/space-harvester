package com.aresstack.askai.research.search.security;

public interface SecretCipher {

    EncryptedSecret encrypt(char[] plainText);

    char[] decrypt(EncryptedSecret encryptedSecret);
}
