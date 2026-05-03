
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace  = "com.javapro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.javapro"
        minSdk        = 28
        targetSdk     = 36
        versionCode   = 28
        versionName   = "2.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        aidl    = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    externalNativeBuild {
    cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }

    signingConfigs {
        create("release") {
            storeFile     = rootProject.file(keystoreProps["storeFile"] as? String ?: "javapro-release.jks")
            storePassword = keystoreProps["storePassword"] as? String ?: ""
            keyAlias      = keystoreProps["keyAlias"]      as? String ?: ""
            keyPassword   = keystoreProps["keyPassword"]   as? String ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            isDebuggable      = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    lint {
        disable  += "InvalidFragmentVersionForActivityResult"
        disable  += "BlockedPrivateApi"
        abortOnError = false
    }
}

dependencies {
    compileOnly(fileTree(mapOf("dir" to "${android.sdkDirectory}/platforms/android-33", "include" to listOf("android.jar"))))
    implementation(libs.androidx.core.ktx)
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.unity3d.ads:unity-ads:4.12.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// ── Auto-copy fps_core binary ke assets setelah CMake build ──────────────────
afterEvaluate {
    val abis      = listOf("arm64-v8a", "armeabi-v7a")
    val assetsDir = file("src/main/assets")

    android.applicationVariants.forEach { variant ->
        val variantName  = variant.name                                      // "release" / "debug"
        val variantCap   = variantName.replaceFirstChar { it.uppercase() }   // "Release" / "Debug"

        val copyTask = tasks.register("copyFpsCoreBinaries$variantCap") {
            group       = "javapro"
            description = "Copy fps_core binaries to assets for $variantName"

            doLast {
                assetsDir.mkdirs()
                var anyFound = false
                abis.forEach { abi ->
                    // CMake output path yang benar di GitHub Actions & Android Studio
                    val candidates = listOf(
                        file("build/intermediates/cmake/$variantName/obj/$abi/fps_core"),
                        file(".cxx/cmake/$variantName/$abi/fps_core"),
                        file(".cxx/RelWithDebInfo/$abi/fps_core"),
                        file(".cxx/Release/$abi/fps_core")
                    )
                    val src = candidates.firstOrNull { it.exists() }
                    if (src != null) {
                        val dest = file("$assetsDir/fps_core_$abi")
                        src.copyTo(dest, overwrite = true)
                        println("✓ Copied fps_core [$abi] → ${dest.name} (${src.length()} bytes)")
                        anyFound = true
                    } else {
                        println("⚠ fps_core not found for $abi, searched: ${candidates.map { it.path }}")
                    }
                }
                if (!anyFound) {
                    throw GradleException(
                        "fps_core binary not found for any ABI. " +
                        "Pastikan NDK terinstall dan CMakeLists.txt ada di src/main/cpp/"
                    )
                }
            }
        }

        // Dependensi: copyTask harus jalan SETELAH semua buildCMake tasks selesai
        tasks.matching { t ->
            t.name.lowercase().let { n ->
                (n.startsWith("buildcmake") || n.startsWith("configurecmake") || n.startsWith("linkexternal")) &&
                n.contains(variantName.lowercase())
            }
        }.configureEach { finalizedBy(copyTask) }

        // mergeAssets harus SETELAH copy selesai
        tasks.matching { it.name == "merge${variantCap}Assets" }
            .configureEach { dependsOn(copyTask) }

        // generateAssets juga perlu tahu
        tasks.matching { it.name == "generate${variantCap}Assets" }
            .configureEach { dependsOn(copyTask) }
    }
}
