#include <jni.h>
#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/video/video.h>
#include <string>
#include <thread>
#include <mutex>
#include <atomic>
#include <vector>
#include <cmath>

static JavaVM* g_vm = nullptr;
static jclass g_cls = nullptr;
static jobject g_obj = nullptr;
static jmethodID g_midOnFrame = nullptr;
static jmethodID g_midOnRgba  = nullptr;
static jmethodID g_midOnDebug = nullptr;
static jmethodID g_midOnStats = nullptr;

static std::mutex g_mutex;
static std::atomic<bool> g_running{false};

struct Ctx {
    GstElement* pipeline = nullptr;
    GstElement* udpsrc   = nullptr;
    GstElement* jb       = nullptr;
    GstElement* depay    = nullptr;
    GstElement* parse    = nullptr;
    GstElement* jpegdec  = nullptr;
    GstElement* convert  = nullptr;
    GstElement* filter   = nullptr;
    GstElement* appsink  = nullptr;
    GMainLoop*  loop     = nullptr;

    bool  raw_rgba       = false;
    bool  use_jitter     = false;
    int   jb_latency_ms  = 120;

    std::string pipeline_desc;

    // simple fps/jitter
    std::vector<double> inter_arrival_ms;
    double last_ts_ms = 0.0;
    double avg = 0.0, jitter = 0.0;
};
static Ctx* g_ctx = nullptr;

// ---- counters ----
static std::atomic<uint64_t> g_cnt_src{0}, g_cnt_jb_in{0}, g_cnt_jb_out{0}, g_cnt_depay{0}, g_cnt_sink{0};

static GstPadProbeReturn on_probe_count(GstPad*, GstPadProbeInfo*, gpointer tag) {
    const char* name = static_cast<const char*>(tag);
    if      (!strcmp(name, "src"))    g_cnt_src++;
    else if (!strcmp(name, "jb-in"))  g_cnt_jb_in++;
    else if (!strcmp(name, "jb-out")) g_cnt_jb_out++;
    else if (!strcmp(name, "depay"))  g_cnt_depay++;
    else if (!strcmp(name, "sink"))   g_cnt_sink++;
    return GST_PAD_PROBE_OK;
}


/* ---------- helpers ---------- */
static JNIEnv* getEnv() {
    JNIEnv* env = nullptr;
    if (g_vm && g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    return env;
}
static void callDebug(const char* msg) {
    JNIEnv* env = getEnv();
    if (!env || !g_obj || !g_midOnDebug) return;
    jstring j = env->NewStringUTF(msg ? msg : "");
    env->CallVoidMethod(g_obj, g_midOnDebug, j);
    env->DeleteLocalRef(j);
}
static void pushStats() {
    if (!g_ctx || !g_midOnStats || !g_obj) return;
    double fps = 0.0;
    if (g_ctx->inter_arrival_ms.size() > 1) {
        double sum = 0.0;
        for (double d : g_ctx->inter_arrival_ms) sum += d;
        g_ctx->avg = sum / g_ctx->inter_arrival_ms.size();
        double var = 0.0;
        for (double d : g_ctx->inter_arrival_ms) var += (d - g_ctx->avg) * (d - g_ctx->avg);
        g_ctx->jitter = std::sqrt(var / g_ctx->inter_arrival_ms.size());
        if (g_ctx->avg > 0.0) fps = 1000.0 / g_ctx->avg;
        if (g_ctx->inter_arrival_ms.size() > 150) g_ctx->inter_arrival_ms.clear();
    }
    JNIEnv* env = getEnv();
    if (!env) return;
    jstring desc = env->NewStringUTF(g_ctx->pipeline_desc.c_str());
    env->CallVoidMethod(g_obj, g_midOnStats, fps, g_ctx->avg, g_ctx->jitter, desc);
    env->DeleteLocalRef(desc);
    if (env->ExceptionCheck()) { env->ExceptionClear(); }
}

static gboolean poll_rtp_metrics(gpointer) {
    uint64_t src  = g_cnt_src.exchange(0);
    uint64_t jbi  = g_cnt_jb_in.exchange(0);
    uint64_t jbo  = g_cnt_jb_out.exchange(0);
    uint64_t dep  = g_cnt_depay.exchange(0);
    uint64_t sink = g_cnt_sink.exchange(0);
    char buf[256];
    snprintf(buf, sizeof(buf),
             "RTP-METRICS src=%llu/s jb-in=%llu/s jb-out=%llu/s depay=%llu/s sink=%llu/s",
             (unsigned long long)src, (unsigned long long)jbi, (unsigned long long)jbo,
             (unsigned long long)dep, (unsigned long long)sink);
    callDebug(buf);
    return TRUE;
}

/* ---------- appsink callback ---------- */
static GstFlowReturn on_new_sample(GstAppSink* sink, gpointer) {
    GstSample* sample = gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_OK;

    GstBuffer* buffer = gst_sample_get_buffer(sample);
    GstMapInfo map;
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    bool is_raw = g_ctx && g_ctx->raw_rgba;
    JNIEnv* env = getEnv();

    if (is_raw && g_midOnRgba && env && g_obj) {
        // width/height を caps から取得
        int width = 0, height = 0;
        GstCaps* caps = gst_sample_get_caps(sample);
        if (caps) {
            const GstStructure* s = gst_caps_get_structure(caps, 0);
            if (s) {
                gst_structure_get_int(s, "width", &width);
                gst_structure_get_int(s, "height", &height);
            }
        }
        if (width > 0 && height > 0) {
            jbyteArray arr = env->NewByteArray(static_cast<jsize>(map.size));
            if (arr) {
                env->SetByteArrayRegion(arr, 0, static_cast<jsize>(map.size),
                                        reinterpret_cast<const jbyte*>(map.data));
                env->CallVoidMethod(g_obj, g_midOnRgba, arr, width, height);
                env->DeleteLocalRef(arr);
                if (env->ExceptionCheck()) { env->ExceptionClear(); }
            }
        } else {
            callDebug("RAW sample without width/height caps");
        }
    } else if (g_midOnFrame && env && g_obj) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(map.size));
        if (arr) {
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(map.size),
                                    reinterpret_cast<const jbyte*>(map.data));
            env->CallVoidMethod(g_obj, g_midOnFrame, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) { env->ExceptionClear(); }
        }
    }

    // 簡易 FPS 計測
    GstClockTime pts = GST_BUFFER_PTS(buffer);
    if (pts != GST_CLOCK_TIME_NONE) {
        double ms = pts / 1000000.0;
        if (g_ctx->last_ts_ms > 0.0) g_ctx->inter_arrival_ms.push_back(ms - g_ctx->last_ts_ms);
        g_ctx->last_ts_ms = ms;
    }

    gst_buffer_unmap(buffer, &map);
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

/* ---------- pipeline builder ---------- */
static bool build_udp_pipeline_ex(int port, bool raw_rgba, int jb_latency_ms, bool use_jitter) {
    g_ctx = new(std::nothrow) Ctx();
    if (!g_ctx) { callDebug("new Ctx() failed"); return false; }

    g_ctx->raw_rgba      = raw_rgba;
    g_ctx->use_jitter    = use_jitter;
    g_ctx->jb_latency_ms = jb_latency_ms;

    g_ctx->pipeline = gst_pipeline_new("udp-pipe");
    g_ctx->udpsrc   = gst_element_factory_make("udpsrc", "src");
    g_ctx->depay    = gst_element_factory_make("rtpjpegdepay", "depay");
    g_ctx->parse    = gst_element_factory_make("jpegparse", "parse");
    if (raw_rgba) {
        g_ctx->jpegdec = gst_element_factory_make("jpegdec", "jpegdec");
        g_ctx->convert = gst_element_factory_make("videoconvert", "convert");
        g_ctx->filter  = gst_element_factory_make("capsfilter", "f");
    }
    g_ctx->appsink = gst_element_factory_make("appsink", "mysink");

    if (!g_ctx->pipeline || !g_ctx->udpsrc || !g_ctx->depay || !g_ctx->parse || !g_ctx->appsink) {
        callDebug("Failed to create UDP elements");
        return false;
    }

    // udpsrc caps & socket（★ 後追いを作らないよう buffer-size を縮小）
    {
        GstCaps* caps = gst_caps_from_string(
                "application/x-rtp,media=video,encoding-name=JPEG,payload=26,clock-rate=90000");
        g_object_set(g_ctx->udpsrc,
                     "port", port,
                     "address", "0.0.0.0",
                     "buffer-size", 512*1024,      // ★ 2MB → 512KB に縮小
                     "caps", caps,
                     NULL);
        gst_caps_unref(caps);
    }

    // appsink setup（既存の鮮度優先：1バッファ・drop）
    g_object_set(g_ctx->appsink,
                 "emit-signals", TRUE,
                 "sync", FALSE,
                 "max-buffers", 1,
                 "drop", TRUE,
                 NULL);
    g_signal_connect(g_ctx->appsink, "new-sample", G_CALLBACK(on_new_sample), nullptr);

    // jitterbuffer (optional)（★ 鮮度優先のプロパティを追加・プロパティ有無は安全に確認）
    if (use_jitter) {
        g_ctx->jb = gst_element_factory_make("rtpjitterbuffer", "jb");
        if (!g_ctx->jb) {
            callDebug("rtpjitterbuffer not present → continue without it");
        } else {
            // latency
            if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_ctx->jb), "latency"))
                g_object_set(g_ctx->jb, "latency", jb_latency_ms, NULL);

            // drop-on-late / do-lost
            if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_ctx->jb), "drop-on-late"))
                g_object_set(g_ctx->jb, "drop-on-late", TRUE, NULL);
            if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_ctx->jb), "do-lost"))
                g_object_set(g_ctx->jb, "do-lost", TRUE, NULL);

            // misorder/dropout 許容時間を短く（大きな乱順・欠損は早めに捨てる）
            if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_ctx->jb), "max-dropout-time"))
                g_object_set(g_ctx->jb, "max-dropout-time", 200, NULL);  // ms
            if (g_object_class_find_property(G_OBJECT_GET_CLASS(g_ctx->jb), "max-misorder-time"))
                g_object_set(g_ctx->jb, "max-misorder-time", 100, NULL); // ms
        }
    }

    // add & link
    if (g_ctx->jb) {
        gst_bin_add_many(GST_BIN(g_ctx->pipeline), g_ctx->udpsrc, g_ctx->jb, g_ctx->depay, g_ctx->parse, NULL);
        if (!gst_element_link_many(g_ctx->udpsrc, g_ctx->jb, g_ctx->depay, g_ctx->parse, NULL)) {
            callDebug("Failed to link src->jb->depay->parse");
            return false;
        }
    } else {
        gst_bin_add_many(GST_BIN(g_ctx->pipeline), g_ctx->udpsrc, g_ctx->depay, g_ctx->parse, NULL);
        if (!gst_element_link_many(g_ctx->udpsrc, g_ctx->depay, g_ctx->parse, NULL)) {
            callDebug("Failed to link src->depay->parse");
            return false;
        }
    }

    if (raw_rgba) {
        if (!g_ctx->jpegdec || !g_ctx->convert || !g_ctx->filter) {
            callDebug("Failed to create RAW path");
            return false;
        }
        GstCaps* raw = gst_caps_from_string("video/x-raw,format=RGBA");
        g_object_set(g_ctx->filter, "caps", raw, NULL);
        gst_caps_unref(raw);

        gst_bin_add_many(GST_BIN(g_ctx->pipeline),
                         g_ctx->jpegdec, g_ctx->convert, g_ctx->filter, g_ctx->appsink, NULL);
        if (!gst_element_link_many(g_ctx->parse, g_ctx->jpegdec, g_ctx->convert, g_ctx->filter, g_ctx->appsink, NULL)) {
            callDebug("Failed to link parse->jpegdec->convert->filter->appsink");
            return false;
        }
    } else {
        gst_bin_add(GST_BIN(g_ctx->pipeline), g_ctx->appsink);
        if (!gst_element_link(g_ctx->parse, g_ctx->appsink)) {
            callDebug("Failed to link parse->appsink");
            return false;
        }
    }

    // ★ ここから RTP-METRICS 用 pad probe を装着（既存機能を壊さない範囲で追加）
    {
        // udpsrc src
        GstPad* p = gst_element_get_static_pad(g_ctx->udpsrc, "src");
        if (p) { gst_pad_add_probe(p, GST_PAD_PROBE_TYPE_BUFFER, on_probe_count, (gpointer)"src", NULL);
            gst_object_unref(p); }
    }
    if (g_ctx->jb) {
        // jitterbuffer sink/src
        GstPad* ps = gst_element_get_static_pad(g_ctx->jb, "sink");
        if (ps) { gst_pad_add_probe(ps, GST_PAD_PROBE_TYPE_BUFFER, on_probe_count, (gpointer)"jb-in", NULL);
            gst_object_unref(ps); }
        GstPad* po = gst_element_get_static_pad(g_ctx->jb, "src");
        if (po) { gst_pad_add_probe(po, GST_PAD_PROBE_TYPE_BUFFER, on_probe_count, (gpointer)"jb-out", NULL);
            gst_object_unref(po); }
    }
    {
        // depay src
        GstPad* p = gst_element_get_static_pad(g_ctx->depay, "src");
        if (p) { gst_pad_add_probe(p, GST_PAD_PROBE_TYPE_BUFFER, on_probe_count, (gpointer)"depay", NULL);
            gst_object_unref(p); }
    }
    {
        // appsink sink（new-sample直前）
        GstPad* p = gst_element_get_static_pad(g_ctx->appsink, "sink");
        if (p) { gst_pad_add_probe(p, GST_PAD_PROBE_TYPE_BUFFER, on_probe_count, (gpointer)"sink", NULL);
            gst_object_unref(p); }
    }

    // text for log
    g_ctx->pipeline_desc =
            std::string("udpsrc(port=") + std::to_string(port) + ") ! " +
            (g_ctx->jb ? ("rtpjitterbuffer(latency=" + std::to_string(jb_latency_ms) + ") ! ") : "(jb not-present) ") +
            "rtpjpegdepay ! jpegparse ! " +
            (raw_rgba ? "jpegdec ! videoconvert ! video/x-raw,format=RGBA ! appsink"
                      : "appsink");

    return true;
}

static void run_loop() {
    if (!g_ctx) return;

    g_ctx->loop = g_main_loop_new(nullptr, FALSE);
    callDebug(("Pipeline: " + g_ctx->pipeline_desc).c_str());

    gst_element_set_state(g_ctx->pipeline, GST_STATE_PLAYING);

    // best-effort で状態遷移を拾う（既存通り）
    GstState state = GST_STATE_NULL, pending = GST_STATE_VOID_PENDING;
    gst_element_get_state(g_ctx->pipeline, &state, &pending, GST_CLOCK_TIME_NONE);
    callDebug("GST set PLAYING");

    // ★ 1秒ごとに pushStats（既存）＋ RTP-METRICS を追加
    guint t_stats = g_timeout_add_seconds(1, [](gpointer) -> gboolean {
        pushStats();
        return TRUE;
    }, nullptr);
    guint t_rtp = g_timeout_add_seconds(1, poll_rtp_metrics, nullptr); // ★ 追加

    g_main_loop_run(g_ctx->loop);

    if (t_rtp)   g_source_remove(t_rtp);
    if (t_stats) g_source_remove(t_stats);

    gst_element_set_state(g_ctx->pipeline, GST_STATE_NULL);
    if (g_ctx->pipeline) gst_object_unref(g_ctx->pipeline);
    if (g_ctx->loop)     g_main_loop_unref(g_ctx->loop);

    delete g_ctx;
    g_ctx = nullptr;
}


/* ---------- JNI ---------- */

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeClassInit(JNIEnv* env, jobject) {
    jclass cls = env->FindClass("com/example/withcrossdemo/gst/GstReceiver");
    g_cls = (jclass)env->NewGlobalRef(cls);
    g_midOnFrame = env->GetMethodID(g_cls, "onFrameFromNative", "([B)V");
    g_midOnRgba  = env->GetMethodID(g_cls, "onRgbaFromNative", "([BII)V");
    g_midOnDebug = env->GetMethodID(g_cls, "onDebugFromNative", "(Ljava/lang/String;)V");
    g_midOnStats = env->GetMethodID(g_cls, "onStatsFromNative", "(DDDLjava/lang/String;)V");

    int argc = 0; char** argv = nullptr;
    gst_init(&argc, &argv);

    guint maj, min, mic, nan;
    gst_version(&maj, &min, &mic, &nan);
    char v[64]; snprintf(v, sizeof(v), "GST version %u.%u.%u", maj, min, mic);
    callDebug(v); // g_obj 未設定でも安全
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeInit(JNIEnv* env, jobject thiz) {
    if (g_obj) { env->DeleteGlobalRef(g_obj); g_obj = nullptr; }
    g_obj = env->NewGlobalRef(thiz);
    callDebug("GST native initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStartRtpJpegUdp(JNIEnv*, jobject, jint port) {
    try {
        std::lock_guard<std::mutex> lk(g_mutex);
        if (g_running.load()) { callDebug("Already running"); return; }
        if (!build_udp_pipeline_ex((int)port, false, 120, false)) {
            callDebug("Failed to build UDP pipeline");
            return;
        }
        g_running.store(true);
        std::thread([]{
            run_loop();
            g_running.store(false);
        }).detach();
    } catch (...) {
        callDebug("nativeStartRtpJpegUdp: unexpected exception");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStartRtpJpegUdpEx(
        JNIEnv*, jobject, jint port, jboolean rawRgba, jint jbLatencyMs, jboolean useJitter) {
    try {
        std::lock_guard<std::mutex> lk(g_mutex);
        if (g_running.load()) { callDebug("Already running"); return; }
        if (!build_udp_pipeline_ex((int)port, rawRgba == JNI_TRUE, (int)jbLatencyMs, useJitter == JNI_TRUE)) {
            callDebug("Failed to build UDP pipeline");
            return;
        }
        g_running.store(true);
        std::thread([]{
            run_loop();
            g_running.store(false);
        }).detach();
    } catch (...) {
        callDebug("nativeStartRtpJpegUdpEx: unexpected exception");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_withcrossdemo_gst_GstReceiver_nativeStop(JNIEnv*, jobject) {
    try {
        std::lock_guard<std::mutex> lk(g_mutex);
        if (!g_running.load()) return;
        if (g_ctx && g_ctx->loop) g_main_loop_quit(g_ctx->loop);
    } catch (...) {
        callDebug("nativeStop: unexpected exception");
    }
}
