plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "mihon.chimahon"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Release's optimised native flags in debug builds too: at Debug's -O0 every dictionary lookup
                // (hoshidicts, zstd, glaze) ran ~6x slower in Chimahon Dev, the build Pedro reads with.
                arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    api(project(":kumahon-core"))
    implementation(libs.okhttp.core)
    implementation(libs.image.decoder)
    implementation(androidx.corektx)
    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.protobuf)
    implementation(kotlinx.serialization.json)

    // Compose
    implementation(androidxCompose.activity)
    implementation(androidxCompose.foundation)
    implementation(androidxCompose.material3.core)
    implementation(androidxCompose.material.icons)
    implementation(androidxCompose.animation)
    implementation(androidxCompose.ui.util)

    // ChimaReader
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.preferencektx)
    implementation(libs.compose.webview)
    implementation(libs.jsoup)
    implementation(libs.datastore.preferences)
    implementation(libs.bundles.media3)

    // Zoomable image viewer for novel illustrations
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }

    // Dependency injection
    implementation(libs.injekt)

    // Mihon core
    implementation(projects.core.common)
    implementation(projects.domain)
    implementation(projects.i18nKmk)
    implementation(libs.unifile)

    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
