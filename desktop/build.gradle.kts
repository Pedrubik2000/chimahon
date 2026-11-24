import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(androidxCompose.plugins.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(projects.kumahonCore)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "kumahon.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "kumahon"
        }
    }
}
