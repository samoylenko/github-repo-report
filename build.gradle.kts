repositories { mavenCentral() }

plugins {
    application

    alias(libs.plugins.kotlin.plugin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "io.samoylenko"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.clikt)
    implementation(libs.commons.csv)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.samoylenko.tools.GitHubRepoReportCliKt")
}
