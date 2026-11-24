plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "chimahon.local.ocr"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":chimahon"))
    implementation(project(":presentation-core"))
    implementation(project(":domain"))

    implementation(androidxCompose.foundation)
    implementation(androidxCompose.material3.core)
}
