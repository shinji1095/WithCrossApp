@file:Suppress("DSL_SCOPE_VIOLATION") // ← Version Catalog で libs 使用

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.hilt)
    id("io.ktor.plugin") version "2.3.6" apply false
}

/* ─────★ 追加/移動: GStreamer Android ルートを先に解決 ───── */
val gstRootProp = (findProperty("gstAndroidRoot") as String?)
    ?: System.getenv("GSTREAMER_ROOT_ANDROID")
val gstRoot = gstRootProp?.replace("\\", "/")
    ?: throw GradleException(
        "Set 'gstAndroidRoot' in gradle.properties or 'GSTREAMER_ROOT_ANDROID' env var"
    )
/* ─────────────────────────────────────────────────────────── */

android {
    namespace   = "com.example.withcrossdemo"
    compileSdk  = 35
    ndkVersion  = "25.2.9519653"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    defaultConfig {
        applicationId = "com.example.withcrossdemo"
        minSdk        = 24
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            ndkBuild {
                // “gstreamer_android” をターゲットから外し、gstbridge のみを明示
                targets.clear()
                targets.add("gstbridge")

                // 引数・ABI の指定も Kotlin 風に
                arguments += listOf(
                    "GSTREAMER_ROOT_ANDROID=C:/gstreamer-1.0-android-universal-1.26.5"
                )
                abiFilters += listOf("arm64-v8a")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug { isDebuggable = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin-2.0 以降は composeOptions ではなく plugin 側で管理されるが、
    // 下記の書き方でも問題なく動く
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xjvm-default=all"
    }

    // ──★ 追加: JNI ライブラリのパッケージングをレガシー形式に（ndk-build 連携が安定）
    packaging {
        jniLibs { useLegacyPackaging = true }  // ★ 追加
    }
    // 既存の excludes はこのままでOK（重複していても害はありません）
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    /* ───────────── Compose ───────────── */
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    /* ───────────── AndroidX 基本 ─────── */
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    /* WebSocket サーバ */
    implementation("org.java-websocket:Java-WebSocket:1.5.3")
    implementation("io.ktor:ktor-server-core:2.3.6")
    implementation("io.ktor:ktor-server-cio:2.3.6")
    implementation("io.ktor:ktor-server-websockets:2.3.6")

    /* ───────────── 追加ライブラリ ────── */
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation(libs.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.dagger:hilt-android:${libs.versions.hilt.get()}")
    kapt("com.google.dagger:hilt-compiler:${libs.versions.hilt.get()}")
    implementation("no.nordicsemi.android.support.v18:scanner:1.6.0")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.9.3")

    // TFLite / LiteRT（既存を維持）
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-support:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("io.github.google-ai-edge:litert-select-tf-ops:0.1.0")

    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    /* ───────────── テスト ───────────── */
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.squareup" && requested.name == "javapoet") {
            useVersion(libs.versions.javapoet.get())
            because("Hilt 2.51+ requires javapoet ≥1.13")
        }
    }
}

kapt { correctErrorTypes = true }
