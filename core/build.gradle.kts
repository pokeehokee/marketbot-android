plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    implementation(libs.bouncycastle)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.snakeyaml)
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // The live checks talk to the venues; they run on their own schedule (contractTest), never in the build.
    exclude("**/*LiveContract*")
}

/** The daily API wording check against the live venues (.github/workflows/api-contract.yml). */
tasks.register<Test>("contractTest") {
    description = "Checks the venues still answer in the words the app relies on (live network)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*LiveContract*")
    outputs.upToDateWhen { false }
    systemProperty("contract.report", layout.buildDirectory.file("api-contract/report.md").get().asFile.path)
    listOf("KALSHI_DEMO_KEY_ID", "KALSHI_DEMO_PRIVATE_KEY").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
    testLogging { events("failed", "passed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
