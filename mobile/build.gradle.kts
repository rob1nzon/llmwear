plugins {
    id("com.android.application")
}

android {
    namespace = "dev.veedo.llmwear.mobile"
    compileSdk = 35
    sourceSets.getByName("main").res.srcDir("../shared-ui/res")
    sourceSets.getByName("main").java.srcDir("../shared-commands/java")

    defaultConfig {
        applicationId = "dev.veedo.llmwear.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "dev.veedo.llmwear.mobile.HardwareProbe"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.register<JavaExec>("probeWebSearch") {
    dependsOn("testDebugUnitTest")
    val unitTests = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    classpath = files(unitTests.map { it.testClassesDirs }, unitTests.map { it.classpath })
    mainClass.set("dev.veedo.llmwear.mobile.WebSearchProbe")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    args(providers.gradleProperty("searchQuery").orElse("LiteRT-LM official Android documentation").get())
}
