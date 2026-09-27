package com.example.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * Native tests run only when MESSAGING_NATIVE_LIB points at a built library:
 *   cd native-c && make
 *   MESSAGING_NATIVE_LIB=$(pwd)/native-c/libmessagecodec.so mvn test
 */
class NativeCodecBridgeTest {

    private static final String KEY = randomKey();
    private static final String LIB = System.getenv("MESSAGING_NATIVE_LIB");

    private static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private static NativeCodecBridge javaOnly() {
        return new NativeCodecBridge(KEY, null);
    }

    private static NativeCodecBridge nativeBridge() {
        NativeCodecBridge bridge = new NativeCodecBridge(KEY, LIB);
        assumeTrue(bridge.isNativeEnabled(), "native library not available");
        return bridge;
    }

    @Test
    void javaRoundTrip() {
        NativeCodecBridge codec = javaOnly();
        for (String msg : new String[] {"hello", "", "héllo wörld 👋", "x".repeat(10_000)}) {
            assertEquals(msg, codec.decode(codec.encode(msg)));
        }
    }

    @Test
    void nativeRoundTrip() {
        NativeCodecBridge codec = nativeBridge();
        assertEquals("héllo wörld 👋", codec.decode(codec.encode("héllo wörld 👋")));
    }

    @Test
    void nativeAndJavaAreInterchangeable() {
        NativeCodecBridge nat = nativeBridge();
        NativeCodecBridge java = javaOnly();
        assertEquals("from C to Java", java.decode(nat.encode("from C to Java")));
        assertEquals("from Java to C", nat.decode(java.encode("from Java to C")));
    }

    @Test
    void sameMessageEncryptsDifferentlyEachTime() {
        NativeCodecBridge codec = javaOnly();
        assertNotEquals(codec.encode("same"), codec.encode("same")); // fresh nonce each time
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        String encoded = javaOnly().encode("secret message");
        assertFalse(new String(Base64.getDecoder().decode(encoded)).contains("secret"));
    }

    @Test
    void compressionShrinksRepetitiveMessages() {
        String msg = "ha".repeat(5_000);
        assertTrue(Base64.getDecoder().decode(javaOnly().encode(msg)).length < msg.length() / 10);
    }

    @Test
    void tamperedMessageIsRejected() {
        NativeCodecBridge codec = javaOnly();
        byte[] payload = Base64.getDecoder().decode(codec.encode("pay Alice 10 euro"));
        payload[payload.length - 20] ^= 1; // flip one bit in the ciphertext
        String tampered = Base64.getEncoder().encodeToString(payload);
        assertThrows(IllegalStateException.class, () -> codec.decode(tampered));
    }

    @Test
    void wrongKeyIsRejected() {
        String encoded = javaOnly().encode("hello");
        NativeCodecBridge other = new NativeCodecBridge(randomKey(), null);
        assertThrows(IllegalStateException.class, () -> other.decode(encoded));
    }

    @Test
    void missingOrShortKeyFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new NativeCodecBridge(null, null));
        assertThrows(IllegalStateException.class, () -> new NativeCodecBridge("c2hvcnQ=", null));
    }

    @Test
    void badLibraryPathFallsBackToJava() {
        NativeCodecBridge codec = new NativeCodecBridge(KEY, "/does/not/exist.so");
        assertFalse(codec.isNativeEnabled());
        assertEquals("still works", codec.decode(codec.encode("still works")));
    }
}
