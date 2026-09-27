package com.example.messaging;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-Java codec: compress, then AES-256-GCM encrypt.
 * Produces exactly the same wire format as the native C module:
 *
 *     nonce (12 bytes) || ciphertext || GCM tag (16 bytes)
 *
 * so data written by either implementation can be read by the other.
 */
final class JavaCodec {

    static final int NONCE_LEN = 12;
    static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private JavaCodec() {}

    static byte[] encode(byte[] key, byte[] plaintext) {
        if (plaintext.length > Compression.MAX_PLAINTEXT) {
            throw new IllegalStateException("Message too large");
        }
        byte[] packed = Compression.compress(plaintext);

        byte[] nonce = new byte[NONCE_LEN];
        RANDOM.nextBytes(nonce); // a fresh nonce for every message: GCM breaks if one is reused

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertextAndTag = cipher.doFinal(packed); // Java appends the tag after the ciphertext

            byte[] out = new byte[NONCE_LEN + ciphertextAndTag.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
            System.arraycopy(ciphertextAndTag, 0, out, NONCE_LEN, ciphertextAndTag.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    static byte[] decode(byte[] key, byte[] payload) {
        if (payload.length < NONCE_LEN + TAG_BITS / 8) {
            throw new IllegalStateException("Payload too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, payload, 0, NONCE_LEN));
            // doFinal verifies the tag and throws if the data was tampered with
            byte[] packed = cipher.doFinal(payload, NONCE_LEN, payload.length - NONCE_LEN);
            return Compression.decompress(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Authentication failed: message was tampered with or wrong key", e);
        }
    }
}
