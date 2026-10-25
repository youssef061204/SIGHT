plugins { kotlin("jvm"); `java-library` }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies { testImplementation("junit:junit:4.13.2") }

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}

tasks.register<JavaExec>("replay") {
    workingDir = rootProject.projectDir
    description = "Replay recorded detection TSV through the actual JVM engine and a naive per-detection narration baseline."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.sight.core.ReplayRunner")
    dependsOn(tasks.testClasses)
    doFirst {
        val input = providers.gradleProperty("replayInput").orNull ?: error("Supply -PreplayInput=<TSV path>")
        val output = providers.gradleProperty("replayOutput").orNull ?: error("Supply -PreplayOutput=<JSONL path>")
        args(input, output)
    }
}
