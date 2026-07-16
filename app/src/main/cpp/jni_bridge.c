#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include "ncz_engine.h"
#include "nca_verifier.h"
#include "nca_cnmt.h"
#include "cpu_affinity.h"
#include "nsz_debug.h"

#define LOG_TAG "AndroNSZ"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)  // NOLINT(*-macro-usage)

/* ---------- Progress callback context ---------- */
typedef struct {
    JavaVM  *jvm;
    jobject  callback_obj;
    jmethodID on_progress;
} JniProgressCtx;

static void jni_progress_cb(int64_t done, int64_t total, void *user_data)
{
    JniProgressCtx *ctx = (JniProgressCtx *)user_data;
    JNIEnv *env   = NULL;
    int attached  = 0;

    jint get_env = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (get_env == JNI_EDETACHED) {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (get_env != JNI_OK) {
        return;
    }

    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_progress,
                           (jlong)done, (jlong)total);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    if (attached) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
}

/* ---------- Status callback context ---------- */
typedef struct {
    JavaVM   *jvm;
    jobject   callback_obj;
    jmethodID on_status;
} JniStatusCtx;

static void jni_status_cb(const char *tag, const char *msg, void *user_data)
{
    JniStatusCtx *ctx = (JniStatusCtx *)user_data;
    JNIEnv *env  = NULL;
    int attached = 0;

    jint get_env = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (get_env == JNI_EDETACHED) {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != JNI_OK) return;
        attached = 1;
    } else if (get_env != JNI_OK) {
        return;
    }

    jstring j_tag = (*env)->NewStringUTF(env, tag ? tag : "");
    jstring j_msg = (*env)->NewStringUTF(env, msg ? msg : "");

    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_status, j_tag, j_msg);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    (*env)->DeleteLocalRef(env, j_tag);
    (*env)->DeleteLocalRef(env, j_msg);

    if (attached) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
}

/* ---------- JNI: convert ---------- */
JNIEXPORT jint JNICALL
Java_com_androNSZ_NszConverter_nativeConvert(
        JNIEnv *env, jclass clazz,
        jstring j_input, jstring j_output,
        jobject j_progress_callback,
        jobject j_status_callback)
{
    (void)clazz;

    const char *input  = (*env)->GetStringUTFChars(env, j_input,  NULL);
    const char *output = (*env)->GetStringUTFChars(env, j_output, NULL);

    LOGI("convert: %s -> %s", input, output);

    JavaVM *jvm = NULL;
    (*env)->GetJavaVM(env, &jvm);

    /* Progress callback */
    JniProgressCtx *prog_ctx = NULL;
    NczProgressCb    prog_fn  = NULL;
    if (j_progress_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, j_progress_callback);
        jmethodID mid   = (*env)->GetMethodID(env, cb_class, "onProgress", "(JJ)V");
        (*env)->DeleteLocalRef(env, cb_class);

        prog_ctx = malloc(sizeof(JniProgressCtx));
        prog_ctx->jvm          = jvm;
        prog_ctx->callback_obj = (*env)->NewGlobalRef(env, j_progress_callback);
        prog_ctx->on_progress  = mid;
        prog_fn = jni_progress_cb;
    }

    /* Status callback */
    JniStatusCtx *stat_ctx = NULL;
    NczStatusCb   stat_fn  = NULL;
    if (j_status_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, j_status_callback);
        jmethodID mid   = (*env)->GetMethodID(env, cb_class, "onStatus",
                                               "(Ljava/lang/String;Ljava/lang/String;)V");
        (*env)->DeleteLocalRef(env, cb_class);

        stat_ctx = malloc(sizeof(JniStatusCtx));
        stat_ctx->jvm          = jvm;
        stat_ctx->callback_obj = (*env)->NewGlobalRef(env, j_status_callback);
        stat_ctx->on_status    = mid;
        stat_fn = jni_status_cb;
    }

    int result = ncz_convert_nsz_to_nsp(input, output,
                                         prog_fn, prog_ctx,
                                         stat_fn, stat_ctx);
    LOGI("convert finished: result=%d", result);

    (*env)->ReleaseStringUTFChars(env, j_input,  input);
    (*env)->ReleaseStringUTFChars(env, j_output, output);

    if (prog_ctx) {
        (*env)->DeleteGlobalRef(env, prog_ctx->callback_obj);
        free(prog_ctx);
    }
    if (stat_ctx) {
        (*env)->DeleteGlobalRef(env, stat_ctx->callback_obj);
        free(stat_ctx);
    }

    return (jint)result;
}

/* ---------- JNI: convertXcz ---------- */
JNIEXPORT jint JNICALL
Java_com_androNSZ_NszConverter_nativeConvertXcz(
        JNIEnv *env, jclass clazz,
        jstring j_input, jstring j_output,
        jobject j_progress_callback,
        jobject j_status_callback)
{
    (void)clazz;

    const char *input  = (*env)->GetStringUTFChars(env, j_input,  NULL);
    const char *output = (*env)->GetStringUTFChars(env, j_output, NULL);

    LOGI("convertXcz: %s -> %s", input, output);

    JavaVM *jvm = NULL;
    (*env)->GetJavaVM(env, &jvm);

    /* Progress callback */
    JniProgressCtx *prog_ctx = NULL;
    NczProgressCb    prog_fn  = NULL;
    if (j_progress_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, j_progress_callback);
        jmethodID mid   = (*env)->GetMethodID(env, cb_class, "onProgress", "(JJ)V");
        (*env)->DeleteLocalRef(env, cb_class);

        prog_ctx = malloc(sizeof(JniProgressCtx));
        prog_ctx->jvm          = jvm;
        prog_ctx->callback_obj = (*env)->NewGlobalRef(env, j_progress_callback);
        prog_ctx->on_progress  = mid;
        prog_fn = jni_progress_cb;
    }

    /* Status callback */
    JniStatusCtx *stat_ctx = NULL;
    NczStatusCb   stat_fn  = NULL;
    if (j_status_callback != NULL) {
        jclass cb_class = (*env)->GetObjectClass(env, j_status_callback);
        jmethodID mid   = (*env)->GetMethodID(env, cb_class, "onStatus",
                                               "(Ljava/lang/String;Ljava/lang/String;)V");
        (*env)->DeleteLocalRef(env, cb_class);

        stat_ctx = malloc(sizeof(JniStatusCtx));
        stat_ctx->jvm          = jvm;
        stat_ctx->callback_obj = (*env)->NewGlobalRef(env, j_status_callback);
        stat_ctx->on_status    = mid;
        stat_fn = jni_status_cb;
    }

    int result = ncz_convert_xcz_to_xci(input, output,
                                         prog_fn, prog_ctx,
                                         stat_fn, stat_ctx);
    LOGI("convertXcz finished: result=%d", result);

    (*env)->ReleaseStringUTFChars(env, j_input,  input);
    (*env)->ReleaseStringUTFChars(env, j_output, output);

    if (prog_ctx) {
        (*env)->DeleteGlobalRef(env, prog_ctx->callback_obj);
        free(prog_ctx);
    }
    if (stat_ctx) {
        (*env)->DeleteGlobalRef(env, stat_ctx->callback_obj);
        free(stat_ctx);
    }

    return (jint)result;
}

/* ---------- JNI: setDebugLog ---------- */
JNIEXPORT void JNICALL
Java_com_androNSZ_NszConverter_nativeSetDebugLog(
        JNIEnv *env, jclass clazz, jstring j_path)
{
    (void)clazz;
    if (j_path == NULL) { dbg_open(NULL); return; }
    const char *path = (*env)->GetStringUTFChars(env, j_path, NULL);
    dbg_open(path);
    (*env)->ReleaseStringUTFChars(env, j_path, path);
}

/* ---------- JNI: closeDebugLog ---------- */
JNIEXPORT void JNICALL
Java_com_androNSZ_NszConverter_nativeCloseDebugLog(
        JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    dbg_close();
}

/* ---------- JNI: cancel ---------- */
JNIEXPORT void JNICALL
Java_com_androNSZ_NszConverter_nativeCancel(
        JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    ncz_request_cancel();
    LOGI("cancel requested");
}

/* ---------- JNI: errorString ---------- */
JNIEXPORT jstring JNICALL
Java_com_androNSZ_NszConverter_nativeErrorString(
        JNIEnv *env, jclass clazz, jint error_code)
{
    (void)clazz;
    return (*env)->NewStringUTF(env, ncz_error_string((int)error_code));
}

/* ---------- JNI: setVerification ----------
 * Configure CNMT verification once before a batch starts. header_key is 32
 * bytes (or null); kak_records is N*17 bytes: [generation u8][key 16B] per
 * key_area_key_application_XX (or null). Read-only during conversion. */
JNIEXPORT void JNICALL
Java_com_androNSZ_NszConverter_nativeSetVerification(
        JNIEnv *env, jclass clazz,
        jboolean enabled,
        jbyteArray j_header_key,
        jbyteArray j_kak_records)
{
    (void)clazz;

    uint8_t  header_key[32];
    uint8_t *header_ptr = NULL;
    if (j_header_key != NULL && (*env)->GetArrayLength(env, j_header_key) == 32) {
        (*env)->GetByteArrayRegion(env, j_header_key, 0, 32, (jbyte *)header_key);
        header_ptr = header_key;
    }

    uint8_t *kak = NULL;
    int      kak_count = 0;
    if (j_kak_records != NULL) {
        jsize len = (*env)->GetArrayLength(env, j_kak_records);
        if (len > 0 && len % 17 == 0) {
            kak = malloc((size_t)len);
            if (kak) {
                (*env)->GetByteArrayRegion(env, j_kak_records, 0, len, (jbyte *)kak);
                kak_count = len / 17;
            }
        }
    }

    nca_verify_config_set(enabled ? 1 : 0, header_ptr, kak, kak_count);
    LOGI("setVerification: enabled=%d header_key=%d kak_count=%d",
         (int)enabled, header_ptr ? 1 : 0, kak_count);

    free(kak);
}

/* ---------- JNI: verifyNsp ---------- */
JNIEXPORT jstring JNICALL
Java_com_androNSZ_NszConverter_nativeVerifyNsp(
        JNIEnv *env, jclass clazz,
        jstring j_nsp_path,
        jbyteArray j_header_key)
{
    (void)clazz;

    const char *nsp_path = (*env)->GetStringUTFChars(env, j_nsp_path, NULL);

    jsize key_len = (*env)->GetArrayLength(env, j_header_key);
    if (key_len != 32) {
        (*env)->ReleaseStringUTFChars(env, j_nsp_path, nsp_path);
        return (*env)->NewStringUTF(env, "verify: header_key must be 32 bytes");
    }

    jbyte *key_bytes = (*env)->GetByteArrayElements(env, j_header_key, NULL);

    char err[512] = {0};
    int rc = nca_verify_nsp(nsp_path, (const uint8_t *)key_bytes, err, (int)sizeof(err));
    LOGI("nativeVerifyNsp: rc=%d  path=%s", rc, nsp_path);

    (*env)->ReleaseByteArrayElements(env, j_header_key, key_bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, j_nsp_path, nsp_path);

    if (rc == 0) return NULL;
    return (*env)->NewStringUTF(env, err[0] ? err : "verify: unknown error");
}

/* ---------- JNI: thread CPU affinity ----------
 * Pin the CURRENT thread (the IO thread that runs nativeConvert) to a CPU
 * cluster mask, so the core-aware scheduler keeps a conversion on the intended
 * big/little cores. The spawned async_writer pthread inherits this mask. */
JNIEXPORT jint JNICALL
Java_com_androNSZ_NszConverter_nativeSetThreadAffinity(
        JNIEnv *env, jclass clazz, jlong mask)
{
    (void)env;
    (void)clazz;
    return (jint)cpu_affinity_set((uint64_t)mask);
}

JNIEXPORT void JNICALL
Java_com_androNSZ_NszConverter_nativeClearThreadAffinity(
        JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    cpu_affinity_reset();
}
