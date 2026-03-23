#include <jni.h>
#include <string.h>
#include <stdlib.h>

// NOTE:
// This starter native implementation keeps behavior simple:
// - nativeEncode: returns Base64-like passthrough marker + input
// - nativeDecode: strips marker if present
// Replace with strong crypto+compression (e.g., AES-GCM + zlib).

static const char* PREFIX = "NATIVE::";

JNIEXPORT jstring JNICALL
Java_com_example_messaging_NativeCodecBridge_nativeEncode(JNIEnv* env, jobject obj, jstring input) {
    (void)obj;
    const char* in = (*env)->GetStringUTFChars(env, input, 0);
    size_t out_len = strlen(PREFIX) + strlen(in) + 1;
    char* out = (char*)malloc(out_len);
    if (!out) {
        (*env)->ReleaseStringUTFChars(env, input, in);
        return (*env)->NewStringUTF(env, "");
    }
    strcpy(out, PREFIX);
    strcat(out, in);
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    (*env)->ReleaseStringUTFChars(env, input, in);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_messaging_NativeCodecBridge_nativeDecode(JNIEnv* env, jobject obj, jstring input) {
    (void)obj;
    const char* in = (*env)->GetStringUTFChars(env, input, 0);
    const char* start = in;
    if (strncmp(in, PREFIX, strlen(PREFIX)) == 0) {
        start = in + strlen(PREFIX);
    }
    jstring result = (*env)->NewStringUTF(env, start);
    (*env)->ReleaseStringUTFChars(env, input, in);
    return result;
}
