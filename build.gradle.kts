// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
}

tasks.register<Exec>("runDebug") {
    group = "application"
    description = "Builds, installs, and launches the debug app on the connected Android device."
    notCompatibleWithConfigurationCache("Starts the local Iriun camera bridge process")
    dependsOn(":app:installDebug")

    val androidHome = System.getenv("ANDROID_HOME")
        ?: "${System.getenv("LOCALAPPDATA")}\\Android\\Sdk"
    val adb = file("$androidHome/platform-tools/adb.exe")

    doFirst {
        check(adb.exists()) { "adb.exe was not found at ${adb.absolutePath}" }

        // ProcessBuilder(
        //     "powershell.exe",
        //     "-NoProfile",
        //     "-WindowStyle",
        //     "Hidden",
        //     "-Command",
        //     "Start-Process -WindowStyle Hidden python -ArgumentList '${file("tools/iriun_frame_server.py").absolutePath}'",
        // ).start()

        // val reverse = ProcessBuilder(
        //     adb.absolutePath,
        //     "reverse",
        //     "tcp:8765",
        //     "tcp:8765",
        // ).inheritIO().start()
        // check(reverse.waitFor() == 0) { "Could not create the Iriun ADB connection" }
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
