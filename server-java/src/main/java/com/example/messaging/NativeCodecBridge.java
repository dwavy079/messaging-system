package com.example.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class NativeCodecBridge {
    private final boolean nativeEnabled;

    public NativeCodecBridge() {
        String libPath = System.getenv("MESSAGING_NATIVE_LIB");
        boolean loaded = false;
        if (libPath != null && !libPath.isBlank()) {
            try {
                System.load(libPath);
                loaded = true;
            } catch (Throwable ignored) {
                loaded = false;
            }
        }
        this.nativeEnabled = loaded;
    }

    public String encode(String plainText) {
        if (nativeEnabled) {
            try {
                return nativeEncode(plainText);
            } catch (Throwable ignored) {
                // fall through to Java fallback
            }
        }
        return Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
    }

    public String decode(String encoded) {
        if (nativeEnabled) {
            try {
                return nativeDecode(encoded);
            } catch (Throwable ignored) {
                // fall through to Java fallback
            }
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private native String nativeEncode(String input);

    private native String nativeDecode(String input);
}
