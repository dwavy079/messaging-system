package com.example.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Encrypts and compresses messages, using the native C library when it can be
 * loaded and the pure-Java implementation otherwise. Both produce the same
 * format, so either can decode what the other encoded.
 *
 * Configuration (environment variables, never committed to Git):
 *   MESSAGING_AES_KEY    - required: Base64 of 32 random bytes
 *                          (generate with: openssl rand -base64 32)
 *   MESSAGING_NATIVE_LIB - optional: absolute path to libmessagecodec.so / .dylib
 */
@Component
public class NativeCodecBridge {

    static final String KEY_ENV = "MESSAGING_AES_KEY";
    static final String LIB_ENV = "MESSAGING_NATIVE_LIB";

    private final byte[] key;
    private final boolean nativeEnabled;

    public NativeCodecBridge() {
        this(System.getenv(KEY_ENV), System.getenv(LIB_ENV));
    }

    NativeCodecBridge(String base64Key, String libPath) {
        this.key = parseKey(base64Key);
        this.nativeEnabled = tryLoad(libPath);
    }

    /** Fails loudly at startup rather than silently running without encryption. */
    private static byte[] parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(KEY_ENV + " is not set. Generate one with: openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(KEY_ENV + " must be Base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(KEY_ENV + " must decode to 32 bytes for AES-256, got " + decoded.length);
        }
        return decoded;
    }

    private static boolean tryLoad(String libPath) {
        if (libPath == null || libPath.isBlank()) {
            return false;
        }
        try {
            System.load(libPath);
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            // Missing file, wrong OS or wrong CPU architecture: use the Java implementation.
            return false;
        }
    }

    public boolean isNativeEnabled() {
        return nativeEnabled;
    }

    public String encode(String plainText) {
        byte[] plain = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] payload = nativeEnabled ? nativeEncode(key, plain) : JavaCodec.encode(key, plain);
        return Base64.getEncoder().encodeToString(payload);
    }

    public String decode(String encoded) {
        byte[] payload = Base64.getDecoder().decode(encoded);
        byte[] plain = nativeEnabled ? nativeDecode(key, payload) : JavaCodec.decode(key, payload);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private native byte[] nativeEncode(byte[] key, byte[] plaintext);

    private native byte[] nativeDecode(byte[] key, byte[] payload);
}
