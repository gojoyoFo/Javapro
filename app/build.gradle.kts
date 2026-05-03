
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
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
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("com.google.firebase:firebase-analytics")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
}

afterEvaluate {
    val abis      = listOf("arm64-v8a", "armeabi-v7a")
    val assetsDir = file("src/main/assets")

    android.applicationVariants.forEach { variant ->
        val variantName = variant.name
        val variantCap  = variantName.replaceFirstChar { it.uppercase() }

        val copyTask = tasks.register("copyFpsCoreBinaries$variantCap") {
            group       = "javapro"
            description = "Copy fps_core binaries to assets for $variantName"

            doLast {
                assetsDir.mkdirs()
                var anyFound = false

                abis.forEach { abi ->
                    // Cari binary secara rekursif di semua kemungkinan direktori CMake
                    val searchRoots = listOf(
                        file("build/intermediates/cmake"),
                        file(".cxx")
                    )
                    var src: File? = null
                    for (root in searchRoots) {
                        if (!root.exists()) continue
                        src = root.walkTopDown()
                            .filter { f ->
                                f.name == "fps_core" &&
                                f.isFile &&
                                f.parentFile?.name == abi
                            }
                            .firstOrNull()
                        if (src != null) break
                    }

                    if (src != null) {
                        val dest = file("$assetsDir/fps_core_$abi")
                        src.copyTo(dest, overwrite = true)
                        println("✓ fps_core [$abi] → ${dest.name} (${src.length()} bytes) from ${src.path}")
                        anyFound = true
                    } else {
                        println("⚠ fps_core not found for $abi")
                        // Print semua file di .cxx untuk debug
                        file(".cxx").walkTopDown()
                            .filter { it.isFile }
                            .take(30)
                            .forEach { println("  found: ${it.path}") }
                    }
                }

                if (!anyFound) {
                    throw GradleException(
                        "fps_core binary not found. NDK terinstall? CMakeLists.txt ada di src/main/cpp/?"
                    )
                }
            }
        }

        // Dependensi: jalan setelah semua CMake build tasks
        tasks.matching { t ->
            val n = t.name.lowercase()
            (n.startsWith("buildcmake") || n.startsWith("linkexternal")) &&
            n.contains(variantName.lowercase())
        }.configureEach { finalizedBy(copyTask) }

        tasks.matching { it.name == "merge${variantCap}Assets" }
            .configureEach { dependsOn(copyTask) }
        tasks.matching { it.name == "generate${variantCap}Assets" }
            .configureEach { dependsOn(copyTask) }
    }
}
