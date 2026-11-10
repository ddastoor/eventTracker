import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI passes the run number so every build installs as an update over the previous one.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.ddastoor.eventtracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ddastoor.eventtracker"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // One fixed key for every build: Google sign-in identifies the app by package name + the
    // key's SHA-1, and Android only installs updates signed with the same key. The key is NOT in
    // git: locally it is read from signing/signing.properties, in CI from repository secrets
    // (SIGNING_* environment variables). Without it, builds fall back to the debug key.
    val signing = Properties().apply {
        rootProject.file("signing/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingValue(env: String, prop: String): String? = System.getenv(env)?.ifBlank { null } ?: signing.getProperty(prop)
    val keystore = signingValue("SIGNING_KEYSTORE_PATH", "storeFile")?.let { rootProject.file("signing").resolve(it) }
    val appKey = if (keystore != null && keystore.exists()) {
        signingConfigs.create("app") {
            storeFile = keystore
            storePassword = signingValue("SIGNING_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("SIGNING_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("SIGNING_KEY_PASSWORD", "keyPassword")
        }
    } else {
        logger.warn("Event Tracker: no signing key configured, using the debug key (Google sign-in needs its SHA-1 registered).")
        signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = appKey
        }
        debug {
            signingConfig = appKey
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Screenshot tests render the real UI on the JVM (Robolectric + Roborazzi). They are slow-ish, so
// they only run when asked for: ./gradlew testDebugUnitTest -Pscreenshots
tasks.withType<Test>().configureEach {
    if (project.hasProperty("screenshots")) {
        forkEvery = 1 // each screenshot class gets a fresh app process
        systemProperty("roborazzi.test.record", "true")
        systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
    } else {
        exclude("**/*ScreenshotTest*")
    }
    project.findProperty("robolectricRepo")?.let { systemProperty("robolectric.dependency.repo.url", it.toString()) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.vico.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
