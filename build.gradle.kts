// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
}

tasks.register<Exec>("runDebug") {
    group = "application"
    description = "Builds, installs, and launches the debug app on the connected Android device."
    dependsOn(":app:installDebug")

    val androidHome = System.getenv("ANDROID_HOME")
        ?: "${System.getenv("LOCALAPPDATA")}\\Android\\Sdk"
    val adb = file("$androidHome/platform-tools/adb.exe")

    doFirst {
        check(adb.exists()) { "adb.exe was not found at ${adb.absolutePath}" }
    }

    commandLine(
        adb.absolutePath,
        "shell",
        "am",
        "start",
        "-n",
        "com.example.cameraremotecontroller/.MainActivity",
    )
}
