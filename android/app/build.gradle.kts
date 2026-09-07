import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "fitness.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "fitness.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
    androidResources { noCompress += "task" }
    sourceSets["main"].assets.srcDir("../../licenses")
    sourceSets["main"].assets.srcDir("../../protocols")
    packaging { resources.excludes += "META-INF/DEPENDENCIES" }
}
dependencyLocking { lockAllConfigurations() }
val verifyPoseModel by tasks.registering {
    val model = layout.projectDirectory.file("src/main/assets/pose_landmarker_lite.task")
    inputs.file(model)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(model.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a") {
            "Pose model missing or changed: run scripts/fetch-model.ps1"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyPoseModel) }
dependencies {
    implementation(project(":motion-core"))
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.32")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
