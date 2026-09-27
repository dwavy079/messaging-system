/*
 * Native message codec: zlib compression + AES-256-GCM encryption.
 *
 * Wire format (shared with the Java fallback, so either side can read
 * what the other wrote):
 *
 *     nonce (12 bytes) || ciphertext || GCM tag (16 bytes)
 *
 * Pipeline: compress first, then encrypt. Ciphertext looks random and
 * does not compress, so the reverse order would waste the compression.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>
#include <openssl/evp.h>
#include <openssl/rand.h>

#define KEY_LEN 32
#define NONCE_LEN 12
#define TAG_LEN 16
#define MAX_PLAINTEXT (1024 * 1024) /* guards against decompression bombs */

static void throw_error(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

/* Copies a Java byte[] into a malloc'd C buffer. Caller frees. */
static unsigned char *copy_bytes(JNIEnv *env, jbyteArray arr, jsize *len) {
    *len = (*env)->GetArrayLength(env, arr);
    unsigned char *buf = malloc(*len > 0 ? *len : 1);
    if (buf) (*env)->GetByteArrayRegion(env, arr, 0, *len, (jbyte *)buf);
    return buf;
}

static jbyteArray to_java(JNIEnv *env, const unsigned char *data, int len) {
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *)data);
    return out;
}

/* zlib inflate with a growing buffer and a hard size cap. */
static unsigned char *inflate_all(const unsigned char *in, size_t in_len, size_t *out_len) {
    size_t cap = in_len * 4 + 64;
    unsigned char *out = malloc(cap);
    if (!out) return NULL;

    z_stream zs;
    memset(&zs, 0, sizeof zs);
    if (inflateInit(&zs) != Z_OK) { free(out); return NULL; }
    zs.next_in = (Bytef *)in;
    zs.avail_in = (uInt)in_len;

    int rc;
    do {
        if (zs.total_out == cap) {
            if (cap >= MAX_PLAINTEXT) { rc = Z_BUF_ERROR; break; }
            cap = cap * 2 > MAX_PLAINTEXT ? MAX_PLAINTEXT : cap * 2;
            unsigned char *bigger = realloc(out, cap);
            if (!bigger) { rc = Z_MEM_ERROR; break; }
            out = bigger;
        }
        zs.next_out = out + zs.total_out;
        zs.avail_out = (uInt)(cap - zs.total_out);
        rc = inflate(&zs, Z_NO_FLUSH);
    } while (rc == Z_OK);

    *out_len = zs.total_out;
    inflateEnd(&zs);
    if (rc != Z_STREAM_END) { free(out); return NULL; }
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_messaging_NativeCodecBridge_nativeEncode(
        JNIEnv *env, jobject obj, jbyteArray jkey, jbyteArray jplain) {
    (void)obj;
    jsize key_len, plain_len;
    unsigned char *key = copy_bytes(env, jkey, &key_len);
    unsigned char *plain = copy_bytes(env, jplain, &plain_len);
    unsigned char *packed = NULL, *out = NULL;
    EVP_CIPHER_CTX *ctx = NULL;
    jbyteArray result = NULL;

    if (!key || !plain) { throw_error(env, "out of memory"); goto done; }
    if (key_len != KEY_LEN) { throw_error(env, "key must be 32 bytes"); goto done; }
    if (plain_len > MAX_PLAINTEXT) { throw_error(env, "message too large"); goto done; }

    /* 1. Compress. */
    uLongf packed_len = compressBound(plain_len);
    packed = malloc(packed_len);
    if (!packed || compress2(packed, &packed_len, plain, plain_len, Z_DEFAULT_COMPRESSION) != Z_OK) {
        throw_error(env, "compression failed"); goto done;
    }

    /* 2. Encrypt: nonce || ciphertext || tag. GCM needs a fresh nonce every time. */
    out = malloc(NONCE_LEN + packed_len + TAG_LEN);
    if (!out) { throw_error(env, "out of memory"); goto done; }
    if (RAND_bytes(out, NONCE_LEN) != 1) { throw_error(env, "nonce generation failed"); goto done; }

    int len, ct_len;
    ctx = EVP_CIPHER_CTX_new();
    if (!ctx
        || EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1
        || EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_LEN, NULL) != 1
        || EVP_EncryptInit_ex(ctx, NULL, NULL, key, out) != 1
        || EVP_EncryptUpdate(ctx, out + NONCE_LEN, &len, packed, (int)packed_len) != 1) {
        throw_error(env, "encryption failed"); goto done;
    }
    ct_len = len;
    if (EVP_EncryptFinal_ex(ctx, out + NONCE_LEN + ct_len, &len) != 1
        || EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_LEN, out + NONCE_LEN + ct_len + len) != 1) {
        throw_error(env, "encryption failed"); goto done;
    }
    ct_len += len;

    result = to_java(env, out, NONCE_LEN + ct_len + TAG_LEN);

done:
    EVP_CIPHER_CTX_free(ctx);
    if (key) { OPENSSL_cleanse(key, key_len); free(key); }
    free(plain);
    free(packed);
    free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_messaging_NativeCodecBridge_nativeDecode(
        JNIEnv *env, jobject obj, jbyteArray jkey, jbyteArray jpayload) {
    (void)obj;
    jsize key_len, in_len;
    unsigned char *key = copy_bytes(env, jkey, &key_len);
    unsigned char *in = copy_bytes(env, jpayload, &in_len);
    unsigned char *packed = NULL, *plain = NULL;
    EVP_CIPHER_CTX *ctx = NULL;
    jbyteArray result = NULL;

    if (!key || !in) { throw_error(env, "out of memory"); goto done; }
    if (key_len != KEY_LEN) { throw_error(env, "key must be 32 bytes"); goto done; }
    if (in_len < NONCE_LEN + TAG_LEN) { throw_error(env, "payload too short"); goto done; }

    int ct_len = in_len - NONCE_LEN - TAG_LEN;
    unsigned char *nonce = in;
    unsigned char *ct = in + NONCE_LEN;
    unsigned char *tag = in + NONCE_LEN + ct_len;

    /* 1. Decrypt and verify the tag. A failed tag means the data was tampered with. */
    packed = malloc(ct_len > 0 ? ct_len : 1);
    int len, packed_len;
    ctx = EVP_CIPHER_CTX_new();
    if (!packed || !ctx
        || EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1
        || EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_LEN, NULL) != 1
        || EVP_DecryptInit_ex(ctx, NULL, NULL, key, nonce) != 1
        || EVP_DecryptUpdate(ctx, packed, &len, ct, ct_len) != 1) {
        throw_error(env, "decryption failed"); goto done;
    }
    packed_len = len;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_LEN, tag) != 1
        || EVP_DecryptFinal_ex(ctx, packed + packed_len, &len) != 1) {
        throw_error(env, "authentication failed: message was tampered with or wrong key"); goto done;
    }
    packed_len += len;

    /* 2. Decompress. */
    size_t plain_len;
    plain = inflate_all(packed, packed_len, &plain_len);
    if (!plain) { throw_error(env, "decompression failed"); goto done; }

    result = to_java(env, plain, (int)plain_len);

done:
    EVP_CIPHER_CTX_free(ctx);
    if (key) { OPENSSL_cleanse(key, key_len); free(key); }
    free(in);
    free(packed);
    free(plain);
    return result;
}
