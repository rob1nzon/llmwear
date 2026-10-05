plugins {
    id("com.android.application") version "8.7.3" apply false
}

check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    "LiteRT-LM requires JDK 21 or newer for the build. Set JAVA_HOME before running Gradle."
}
