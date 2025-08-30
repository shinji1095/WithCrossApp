LOCAL_PATH := $(call my-dir)

# ---- あなたの "空" ネイティブライブラリ（梱包のため） ----
include $(CLEAR_VARS)
LOCAL_MODULE    := gstbridge                # 任意名
LOCAL_SRC_FILES := gstbridge.c              # 中身は空でOK
LOCAL_LDLIBS    := -llog -landroid
LOCAL_SHARED_LIBRARIES := gstreamer_android
include $(BUILD_SHARED_LIBRARY)

# ---- GStreamer の設定（必須）----
ifndef GSTREAMER_ROOT
ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined!)
endif
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build/

# 必要プラグインをカテゴリで一括指定
#  - NET: udp/rtp/rtpmanager/rtsp など
#  - CODECS: jpeg/jpegformat/androidmedia など
include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk
GSTREAMER_PLUGINS := $(GSTREAMER_PLUGINS_CORE) \
                     $(GSTREAMER_PLUGINS_NET) \
                     $(GSTREAMER_PLUGINS_CODECS)

# 他に必要なら gstreamer-video などの追加ライブラリを指定
GSTREAMER_EXTRA_DEPS := gstreamer-app-1.0 gstreamer-video-1.0

# 最後に gstreamer のmkを読み込み
include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer.mk

