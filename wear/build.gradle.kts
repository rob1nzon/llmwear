plugins {
    id("com.android.application")
}

android {
    namespace = "dev.veedo.llmwear.wear"
    compileSdk = 35
    sourceSets.getByName("main").res.srcDir("../shared-ui/res")
    sourceSets.getByName("main").java.srcDir("../shared-commands/java")

    defaultConfig {
        applicationId = "dev.veedo.llmwear.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
}
