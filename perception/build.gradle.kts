import java.security.MessageDigest

plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.sight.perception"
    compileSdk = 35
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
}
dependencies {
    api(project(":core"))
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
tasks.register("verifyModels") {
    val expected = mapOf(
        "efficientdet_lite0_int8.tflite" to "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb",
        "efficientdet_lite2_int8.tflite" to "b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896")
    expected.keys.forEach { inputs.file(layout.projectDirectory.file("src/main/assets/$it")) }
    doLast { expected.forEach { (name, hash) ->
        val file = layout.projectDirectory.file("src/main/assets/$name").asFile
        check(file.isFile) { "Run scripts/fetch-models.ps1: missing $name" }
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == hash) { "Refusing unverified model: $name" }
    } }
}
tasks.named("preBuild") { dependsOn("verifyModels") }
