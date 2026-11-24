plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Srs/PeerSync use org.json: Android has it built in, the desktop app adds the jar itself.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testRuntimeOnly(libs.sqlite.jdbc)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
