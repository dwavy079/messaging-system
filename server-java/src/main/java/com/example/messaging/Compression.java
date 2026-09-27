package com.example.messaging;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** zlib compression, byte-compatible with the native C module. */
final class Compression {

    /** Guards against decompression bombs: a tiny payload that inflates to gigabytes. */
    static final int MAX_PLAINTEXT = 1024 * 1024;

    private Compression() {}

    static byte[] compress(byte[] input) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 64);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] decompress(byte[] input) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 4 + 64);
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int written = inflater.inflate(buffer);
                if (written == 0 && !inflater.finished()
                        && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalStateException("Truncated or invalid compressed data");
                }
                out.write(buffer, 0, written);
                if (out.size() > MAX_PLAINTEXT) {
                    throw new IllegalStateException("Decompressed message too large");
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalStateException("Invalid compressed data", e);
        } finally {
            inflater.end();
        }
    }
}
