plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// One build number for the APK, the in-app update check and the release tag: 100 + the commit
// count (the offset keeps it above the run-number builds published before, so they upgrade).
val buildNumber: Int = 100 + providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 0 }.getOrElse(0)

android {
    namespace = "com.marketbot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marketbot.app"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"pokeehokee/marketbot-android\"")
    }

    signingConfigs {
        // A fixed debug key checked into the repo so every CI build installs over the
        // previous one. It signs debug builds only; see README for a release key.
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val path = System.getenv("RELEASE_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("RELEASE_KEYSTORE_PATH") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures { compose = true; buildConfig = true }
    // Debug builds carry the exported Room schemas (a few KB) so the Robolectric schema test can read them.
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { t ->
            // Robolectric downloads its Android runtime at test time; allow a mirror where Maven Central rate-limits.
            (project.findProperty("robolectricRepo") as String?)?.let { t.systemProperty("robolectric.dependency.repo.url", it) }
            t.maxHeapSize = "2g"
            // One JVM per test class: Robolectric sandboxes reused across classes leaked state
            // (a view model's shared flows never started after another class had run).
            t.forkEvery = 1
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.bcpkix)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.splashscreen)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
