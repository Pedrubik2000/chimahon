plugins {
    id("mihon.library")
    id("mihon.library.compose")
    kotlin("android")
}

android {
    namespace = "tachiyomi.presentation.core"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    api(projects.core.common)
    api(projects.i18n)
    // SY -->
    api(projects.i18nSy)
    // SY <--

    // Compose
    implementation(androidxCompose.activity)
    implementation(androidxCompose.foundation)
    implementation(androidxCompose.material3.core)
    implementation(androidxCompose.material.icons)
    implementation(androidxCompose.animation)
    implementation(androidxCompose.animation.graphics)
    debugImplementation(androidxCompose.ui.tooling)
    implementation(androidxCompose.ui.tooling.preview)
    implementation(androidxCompose.ui.util)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)
    implementation(kotlinx.immutables)
}
