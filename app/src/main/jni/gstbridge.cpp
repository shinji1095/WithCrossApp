#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <thread>
#include <mutex>
#include <chrono>
#include <cmath>

#include <gst/gst.h>
#include <gst/app/gstappsink.h>

static JavaVM* g_vm = nullptr;
static jclass g_cls = nullptr;                // com/example/withcrossdemo/gst/GstReceiver
static jmethodID g_midOnFrame = nullptr;      // onFrameFromNative([B)V
static jmethodID g_midOnDebug = nullptr;      // onDebugFromNative(Ljava/lang/String;)V
static jmethodID g_midOnStats = nullptr;      // onStatsFromNative(DDDLjava/lang/String;)V
static jobject g_obj = nullptr;               // GlobalRef to instance (set in nativeInit)

static std::mutex g_mutex;
static std::atomic<bool> g_running(false);
static std::thread g_thread;

struct Ctx {
    GstElement* pipeline = nullptr;
    GstElement* appsink = nullptr;

    // RTSP 用（動的パッドリンク）
    GstElement* rtspsrc = nullptr;
    GstElement* depay   = nullptr;
    GstElement* parse   = nullptr;

    GMainLoop* loop = nullptr;

    // stats
    std::string pipeline_desc;
    std::vector<double> inter_arrival_ms; // 直近 N フレームのΔ
    std::chrono::steady_clock::time_point last_tp{};
    double fps = 0.0;
    double avg = 0.0;
    double jitter = 0.0;
};
static Ctx* g_ctx = nullptr;

/* ---------- helpers ---------- */

static JNIEnv* getEnv() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    return env;
}

static void callDebug(const char* msg) {
    JNIEnv* env = getEnv();
    if (!env || !g_obj || !g_midOnDebug) return;
    jstring jmsg = env->NewStringUTF(msg ? msg : "");
    env->CallVoidMethod(g_obj, g_midOnDebug, jmsg);
    env->DeleteLocalRef(jmsg);
}

static void pushStats() {
    if (!g_ctx) return;
    // fps: 直近1秒のカウントではなく、移動平均Δから算出
    if (!g_ctx->inter_arrival_ms.empty()) {
        double sum = 0.0;
        for (double d : g_ctx->inter_arrival_ms) sum += d;
        g_ctx->avg = sum / g_ctx->inter_arrival_ms.size();
        // 標準偏差
        double var = 0.0;
        for (double d : g_ctx->inter_arrival_ms) var += (d - g_ctx->avg) * (d - g_ctx->avg);
        g_ctx->jitter = std::sqrt(var / g_ctx->inter_arrival_ms.size());
        if (g_ctx->avg > 0.0001) g_ctx->fps = 1000.0 / g_ctx->avg;
    }

    JNIEnv* env = getEnv();
    if (!env || !g_obj || !g_midOnStats) return;
    jstring jp = env->NewStringUTF(g_ctx->pipeline_desc.c_str());
    env->CallVoidMethod(g_obj, g_midOnStats, (jdouble)g_ctx->fps, (jdouble)g_ctx->avg, (jdouble)g_ctx->jitter, jp);
    env->DeleteLocalRef(jp);
}

static GstFlowReturn on_new_sample(GstAppSink* sink, gpointer) {
    GstSample* sample = gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_OK;

    GstBuffer* buffer = gst_sample_get_buffer(sample);
    if (!buffer) { gst_sample_unref(sample); return GST_FLOW_OK; }

    GstMapInfo map;
    if (gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        // 時系列
        auto now = std::chrono::steady_clock::now();
        if (g_ctx) {
            if (g_ctx->last_tp.time_since_epoch().count() != 0) {
                double delta = std::chrono::duration<double, std::milli>(now - g_ctx->last_tp).count();
                g_ctx->inter_arrival_ms.push_back(delta);
                if (g_ctx->inter_arrival_ms.size() > 120) g_ctx->inter_arrival_ms.erase(g_ctx->inter_arrival_ms.begin());
            }
            g_ctx->last_tp = now;
        }

        // Java へ JPEG を渡す
        JNIEnv* env = getEnv();
        if (env && g_obj && g_midOnFrame && map.size > 0) {
            jbyteArray arr = env->NewByteArray((jsize)map.size);
            if (arr) {
                env->SetByteArrayRegion(arr, 0, (jsize)map.size, reinterpret_cast<const jbyte*>(map.data));
                env->CallVoidMethod(g_obj, g_midOnFrame, arr);
                env->DeleteLocalRef(arr);
            }
        }
        gst_buffer_unmap(buffer, &map);
    }
    gst_sample_unref(sample);

    return GST_FLOW_OK;
}

static gboolean bus_cb(GstBus*, GstMessage* msg, gpointer) {
    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError* err = nullptr; gchar* dbg = nullptr;
            gst_message_parse_error(msg, &err, &dbg);
            if (err) {
                std::string s = std::string("GST ERROR: ") + err->message;
                callDebug(s.c_str());
                g_error_free(err);
            }
            if (dbg) { callDebug(dbg); g_free(dbg); }
            break;
        }
        case GST_MESSAGE_WARNING: {
            GError* err = nullptr; gchar* dbg = nullptr;
            gst_message_parse_warning(msg, &err, &dbg);
            if (err) {
                std::string s = std::string("GST WARN: ") + err->message;
                callDebug(s.c_str());
                g_error_free(err);
            }
            if (dbg) { callDebug(dbg); g_free(dbg); }
            break;
        }
        case GST_MESSAGE_EOS:
            callDebug("GST EOS");
            break;
        default: break;
    }
    return TRUE;
}

/* ---- RTSP: rtspsrc の動的パッドを rtpjpegdepay に接続 ---- */
static void on_rtsp_pad_added(GstElement* src, GstPad* pad, gpointer user_data) {
    Ctx* c = reinterpret_cast<Ctx*>(user_data);
    if (!c || !c->depay) return;

    GstCaps* caps = gst_pad_get_current_caps(pad);
    if (!caps) caps = gst_pad_query_caps(pad, nullptr);

    gboolean ok = FALSE;
    if (caps) {
        GstStructure* s = gst_caps_get_structure(caps, 0);
        const gchar* name = gst_structure_get_name(s);
        if (name && g_str_has_prefix(name, "application/x-rtp")) ok = TRUE;
        gst_caps_unref(caps);
    }
    if (!ok) return;

    GstPad* sinkpad = gst_element_get_static_pad(c->depay, "sink");
    if (!sinkpad) return;

    if (!gst_pad_is_linked(sinkpad)) {
        if (gst_pad_link(pad, sinkpad) == GST_PAD_LINK_OK) {
            callDebug("Linked rtspsrc -> rtpjpegdepay");
        } else {
            callDebug("Failed to link rtspsrc to depay");
        }
    }
    gst_object_unref(sinkpad);
}

/* ---------- 起動共通 ---------- */
static void run_loop() {
    if (!g_ctx) return;

    GstBus* bus = gst_element_get_bus(g_ctx->pipeline);
    gst_bus_add_watch(bus, bus_cb, nullptr);
    gst_object_unref(bus);

    gst_element_set_state(g_ctx->pipeline, GST_STATE_PLAYING);
    callDebug("GST set PLAYING");

    g_ctx->loop = g_main_loop_new(nullptr, FALSE);

    // 1秒ごとに stats 通知
    g_timeout_add_seconds(1, [](gpointer) -> gboolean {
        pushStats();
        return TRUE; // repeat
    }, nullptr);

    g_main_loop_run(g_ctx->loop);

    gst_element_set_state(g_ctx->pipeline, GST_STATE_NULL);
    if (g_ctx->loop) { g_main_loop_unref(g_ctx->loop); g_ctx->loop = nullptr; }
    callDebug("GST loop finished");
}

/* ---------- パイプライン生成 ---------- */

static bool build_udp_pipeline(int port) {
    g_ctx = new Ctx();

    char desc[512];
    snprintf(desc, sizeof(desc),
             "udpsrc port=%d caps=application/x-rtp,media=video,encoding-name=JPEG,payload=26 "
             "! rtpjpegdepay ! jpegparse ! appsink name=mysink emit-signals=true sync=false max-buffers=1 drop=true",
             port);
    g_ctx->pipeline_desc = desc;

    GError* err = nullptr;
    g_ctx->pipeline = gst_parse_launch(desc, &err);
    if (err) {
        callDebug(err->message);
        g_error_free(err);
        return false;
    }
    g_ctx->appsink = gst_bin_get_by_name(GST_BIN(g_ctx->pipeline), "mysink");
    if (!g_ctx->appsink) return false;

    g_signal_connect(g_ctx->appsink, "new-sample", G_CALLBACK(on_new_sample), nullptr);
    return true;
}

static bool build_rtsp_pipeline(const char* url) {
    g_ctx = new Ctx();

    g_ctx->pipeline = gst_pipeline_new("rtsp-pipe");
    g_ctx->rtspsrc  = gst_element_factory_make("rtspsrc", "src");
    g_ctx->depay    = gst_element_factory_make("rtpjpegdepay", "depay");
    g_ctx->parse    = gst_element_factory_make("jpegparse", "parse");
    g_ctx->appsink  = gst_element_factory_make("appsink", "mysink");

    if (!g_ctx->pipeline || !g_ctx->rtspsrc || !g_ctx->depay || !g_ctx->parse || !g_ctx->appsink) {
        callDebug("Failed to create RTSP elements");
        return false;
    }

    g_object_set(G_OBJECT(g_ctx->rtspsrc),
                 "location", url,
                 "latency", 200,      // 必要に応じて調整
                 NULL);
    g_object_set(G_OBJECT(g_ctx->appsink),
                 "emit-signals", TRUE,
                 "sync", FALSE,
                 "max-buffers", 1,
                 "drop", TRUE,
                 NULL);

    gst_bin_add_many(GST_BIN(g_ctx->pipeline), g_ctx->rtspsrc, g_ctx->depay, g_ctx->parse, g_ctx->appsink, NULL);
    if (!gst_element_link_many(g_ctx->depay, g_ctx->parse, g_ctx->appsink, NULL)) {
        callDebug("Failed to link depay->parse->appsink");
        return false;
    }

    g_signal_connect(g_ctx->appsink, "new-sample", G_CALLBACK(on_new_sample), nullptr);
    g_signal_connect(g_ctx->rtspsrc, "pad-added",  G_CALLBACK(on_rtsp_pad_added), g_ctx);

    g_ctx->pipeline_desc = std::string("rtspsrc location=") + url + " ! rtpjpegdepay ! jpegparse ! appsink";
    return true;
}

/* ---------- JNI ---------- */

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeClassInit(JNIEnv* env, jobject /*thiz*/) {
jclass cls = env->FindClass("com/example/withcrossdemo/gst/GstReceiver");
g_cls = (jclass)env->NewGlobalRef(cls);

g_midOnFrame = env->GetMethodID(g_cls, "onFrameFromNative", "([B)V");
g_midOnDebug = env->GetMethodID(g_cls, "onDebugFromNative", "(Ljava/lang/String;)V");
g_midOnStats = env->GetMethodID(g_cls, "onStatsFromNative", "(DDDLjava/lang/String;)V");

// GStreamer 初期化（ロード済みの gstreamer_android がパス等を設定）
int argc = 0; char** argv = nullptr;
gst_init(&argc, &argv);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeInit(JNIEnv* env, jobject thiz) {
// Kotlin 側インスタンスの GlobalRef を保持
if (g_obj) { env->DeleteGlobalRef(g_obj); g_obj = nullptr; }
g_obj = env->NewGlobalRef(thiz);
callDebug("GST native initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStartRtpJpegUdp(JNIEnv*, jobject, jint port) {
std::lock_guard<std::mutex> lk(g_mutex);
if (g_running.load()) return;

if (g_ctx) { /* 残骸掃除 */ }
if (!build_udp_pipeline((int)port)) {
callDebug("Failed to build UDP pipeline");
return;
}

g_running.store(true);
g_thread = std::thread([]{
    run_loop();
    g_running.store(false);
    // 後片付け
    if (g_ctx) {
        if (g_ctx->appsink) { gst_object_unref(g_ctx->appsink); g_ctx->appsink = nullptr; }
        if (g_ctx->pipeline) { gst_object_unref(g_ctx->pipeline); g_ctx->pipeline = nullptr; }
        delete g_ctx; g_ctx = nullptr;
    }
});

callDebug(("Pipeline: " + g_ctx->pipeline_desc).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStartRtspJpegUdp(JNIEnv* env, jobject, jstring jurl) {
std::lock_guard<std::mutex> lk(g_mutex);
if (g_running.load()) return;

const char* url = env->GetStringUTFChars(jurl, nullptr);
bool ok = build_rtsp_pipeline(url);
env->ReleaseStringUTFChars(jurl, url);
if (!ok) {
callDebug("Failed to build RTSP pipeline");
return;
}

g_running.store(true);
g_thread = std::thread([]{
    run_loop();
    g_running.store(false);
    if (g_ctx) {
        if (g_ctx->appsink) { gst_object_unref(g_ctx->appsink); g_ctx->appsink = nullptr; }
        if (g_ctx->depay)   { gst_object_unref(g_ctx->depay);   g_ctx->depay   = nullptr; }
        if (g_ctx->parse)   { gst_object_unref(g_ctx->parse);   g_ctx->parse   = nullptr; }
        if (g_ctx->rtspsrc) { gst_object_unref(g_ctx->rtspsrc); g_ctx->rtspsrc = nullptr; }
        if (g_ctx->pipeline){ gst_object_unref(g_ctx->pipeline); g_ctx->pipeline= nullptr; }
        delete g_ctx; g_ctx = nullptr;
    }
});

callDebug(("Pipeline: " + g_ctx->pipeline_desc).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStop(JNIEnv*, jobject) {
std::lock_guard<std::mutex> lk(g_mutex);
if (!g_running.load()) return;

if (g_ctx && g_ctx->loop) {
g_main_loop_quit(g_ctx->loop);
}
if (g_thread.joinable()) g_thread.join();
// 後片付けはスレッド側で実施
}
