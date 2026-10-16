package com.aresstack.enterpriseai.adyton;

import com.aresstack.enterpriseai.astu.security.SecretRef;
import org.junit.Test;

import java.io.Serializable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SecretMaterialTest {

    private static final SecretRef REF = SecretRef.of("keepass:confluence");

    @Test
    public void exposesPrincipalAndACopyOfTheSecret() {
        char[] input = "s3cr3t".toCharArray();
        SecretMaterial material = new SecretMaterial(REF, "alice", input);
        input[0] = 'X';

        char[] copy = material.copySecret();
        assertArrayEquals("s3cr3t".toCharArray(), copy);
        copy[0] = 'Y';
        assertArrayEquals("modifying a copy must not change the material", "s3cr3t".toCharArray(),
                material.copySecret());
        assertEquals("alice", material.principal());
        assertTrue(material.hasPrincipal());
        assertEquals(REF, material.ref());
    }

    @Test
    public void principalIsOptional() {
        SecretMaterial token = new SecretMaterial(REF, null, "token".toCharArray());
        assertEquals("", token.principal());
        assertFalse(token.hasPrincipal());
    }

    @Test
    public void toStringRevealsNeitherPrincipalNorSecret() {
        String text = new SecretMaterial(REF, "alice", "s3cr3t".toCharArray()).toString();
        assertFalse(text, text.contains("alice"));
        assertFalse(text, text.contains("s3cr3t"));
        assertTrue(text, text.contains("keepass:confluence"));
    }

    @Test
    public void closeWipesAndBlocksFurtherAccess() {
        SecretMaterial material = new SecretMaterial(REF, "alice", "s3cr3t".toCharArray());
        material.close();
        material.close();
        assertTrue(material.isClosed());
        try {
            material.copySecret();
            fail("secret readable after close");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("s3cr3t"));
        }
        try {
            material.principal();
            fail("principal readable after close");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("alice"));
        }
    }

    @Test
    public void isNotSerializable() {
        assertFalse(Serializable.class.isAssignableFrom(SecretMaterial.class));
    }

    @Test
    public void equalityIsIdentityOnly() {
        SecretMaterial a = new SecretMaterial(REF, "alice", "x".toCharArray());
        SecretMaterial b = new SecretMaterial(REF, "alice", "x".toCharArray());
        assertNotEquals(a, b);
    }

    @Test(expected = IllegalArgumentException.class)
    public void requiresRef() {
        new SecretMaterial(null, "alice", new char[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void requiresSecret() {
        new SecretMaterial(REF, "alice", null);
    }
}
