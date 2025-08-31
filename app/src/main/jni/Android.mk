# app/src/main/jni/Android.mk
LOCAL_PATH := $(call my-dir)

# ===== GStreamer root (SDK 1.26.x の展開場所を Gradle から渡す) =====
ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined!)
endif

# ABI → GStreamer 配布フォルダ名
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
  GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/armv7
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
  GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm64
else ifeq ($(TARGET_ARCH_ABI),x86)
  GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86
else ifeq ($(TARGET_ARCH_ABI),x86_64)
  GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/x86_64
else
  $(error Unsupported ABI $(TARGET_ARCH_ABI))
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build

# ★ AGP が探しに行く標準の出力先（app/gst-android-build）に作らせる
GSTREAMER_OUTPUT_DIR := $(LOCAL_PATH)/../../../gst-android-build

# 必要プラグイン（ライブラリ名）
GSTREAMER_PLUGINS := \
    coreelements app udp rtp rtpmanager rtsp \
    jpeg jpegformat videoconvertscale

# 追加のヘッダ/ライブラリ
GSTREAMER_EXTRA_DEPS := \
    gstreamer-video-1.0 gstreamer-app-1.0 gstreamer-rtp-1.0 gstreamer-rtsp-1.0 gstreamer-pbutils-1.0

# gstreamer_android のアグリゲータを生成
include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk

# ===== JNI ブリッジ（あなたのライブラリ）=====
include $(CLEAR_VARS)
LOCAL_MODULE           := gstbridge
LOCAL_SRC_FILES        := gstbridge.cpp
LOCAL_CPPFLAGS         += -std=c++17 -fexceptions -frtti
LOCAL_LDLIBS           := -llog -landroid

# gstreamer_android にリンク
LOCAL_SHARED_LIBRARIES += gstreamer_android

# GStreamer が用意する CFLAGS / LDFLAGS を取り込む
LOCAL_CFLAGS  += $(GSTREAMER_CFLAGS)
LOCAL_LDFLAGS += $(GSTREAMER_LDFLAGS)

include $(BUILD_SHARED_LIBRARY)
