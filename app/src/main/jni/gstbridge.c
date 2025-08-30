#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <gst/gst.h>
#include <gst/app/gstappsink.h>

static JavaVM *g_jvm = NULL;
static jobject g_receiver = NULL;          // GstReceiver の GlobalRef
static GstElement *g_pipeline = NULL;
static GstElement *g_sink = NULL;

static void call_debug(const char *msg) {
    if (!g_receiver || !msg) return;
    JNIEnv *env = NULL;
    (*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL);
    jclass cls = (*env)->GetObjectClass(env, g_receiver);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onNativeDebug", "(Ljava/lang/String;)V");
    if (mid) {
        jstring jmsg = (*env)->NewStringUTF(env, msg);
        (*env)->CallVoidMethod(env, g_receiver, mid, jmsg);
        (*env)->DeleteLocalRef(env, jmsg);
    }
}

static GstFlowReturn on_new_sample(GstAppSink *app_sink, gpointer user_data) {
    if (!g_receiver) return GST_FLOW_OK;

    GstSample *sample = gst_app_sink_pull_sample(app_sink);
    if (!sample) return GST_FLOW_OK;

    GstBuffer *buffer = gst_sample_get_buffer(sample);
    GstMapInfo map;
    if (gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        JNIEnv *env = NULL;
        (*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL);

        jbyteArray arr = (*env)->NewByteArray(env, (jsize)map.size);
        if (arr) {
            (*env)->SetByteArrayRegion(env, arr, 0, (jsize)map.size, (const jbyte*)map.data);

            jclass cls = (*env)->GetObjectClass(env, g_receiver);
            jmethodID mid = (*env)->GetMethodID(env, cls, "onNativeFrame", "([B)V");
            if (mid) (*env)->CallVoidMethod(env, g_receiver, mid, arr);

            (*env)->DeleteLocalRef(env, arr);
        }
        gst_buffer_unmap(buffer, &map);
    }

    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

static void stop_pipeline() {
    if (g_pipeline) {
        gst_element_set_state(g_pipeline, GST_STATE_NULL);
        gst_object_unref(g_pipeline);
        g_pipeline = NULL;
    }
    if (g_sink) {
        gst_object_unref(g_sink);
        g_sink = NULL;
    }
}

static void start_with_desc(const char *desc) {
    stop_pipeline();
    call_debug(desc);

    GError *err = NULL;
    g_pipeline = gst_parse_launch(desc, &err);
    if (!g_pipeline) {
        call_debug("gst_parse_launch failed");
        if (err) g_error_free(err);
        return;
    }
    if (err) { g_error_free(err); }

    g_sink = gst_bin_get_by_name(GST_BIN(g_pipeline), "sink");
    if (!g_sink) {
        call_debug("appsink not found");
        stop_pipeline();
        return;
    }

    // appsink 設定とコールバック接続
    g_object_set(G_OBJECT(g_sink),
                 "emit-signals", TRUE,
                 "sync", FALSE,
                 "max-buffers", 1,
                 "drop", TRUE, NULL);

    g_signal_connect(g_sink, "new-sample", G_CALLBACK(on_new_sample), NULL);

    gst_element_set_state(g_pipeline, GST_STATE_PLAYING);
}

/* ---------- JNI エクスポート ---------- */

JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_00024Companion_nativeSetJvm(
        JNIEnv *env, jclass clazz) {
// 使っていないが、拡張したい場合のフック（未使用）
(void)env; (void)clazz;
}

JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeInit(
        JNIEnv *env, jobject thiz) {
(*env)->GetJavaVM(env, &g_jvm);

if (g_receiver) {
(*env)->DeleteGlobalRef(env, g_receiver);
g_receiver = NULL;
}
g_receiver = (*env)->NewGlobalRef(env, thiz);

int argc = 0; char **argv = NULL;
gst_init(&argc, &argv);
call_debug("gst_init done");
}

JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStartRtp(
        JNIEnv *env, jobject thiz, jint port, jint payloadType, jint latencyMs) {
(void)env; (void)thiz; (void)latencyMs; // latency は rtpjpegdepay 系では使わない
char caps[128];
snprintf(caps, sizeof(caps),
"application/x-rtp,media=video,encoding-name=JPEG,payload=%d",
(int)payloadType);

char desc[512];
snprintf(desc, sizeof(desc),
"udpsrc port=%d caps=\"%s\" ! "
"rtpjpegdepay ! jpegparse ! "
"appsink name=sink",
(int)port, caps);

start_with_desc(desc);
}

JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStartRtsp(
        JNIEnv *env, jobject thiz, jstring jurl, jint latencyMs) {
(void)thiz;
const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);

char desc[1024];
snprintf(desc, sizeof(desc),
"rtspsrc location=\"%s\" protocols=udp latency=%d ! "
"rtpjpegdepay ! jpegparse ! "
"appsink name=sink",
url, (int)latencyMs);

(*env)->ReleaseStringUTFChars(env, jurl, url);
start_with_desc(desc);
}

JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStop(
        JNIEnv *env, jobject thiz) {
(void)env; (void)thiz;
stop_pipeline();
}
