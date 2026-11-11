plugins {
    alias(libs.plugins.android.application)
    // KSP (Room) is not yet compatible with AGP 9 built-in Kotlin, so the project opts out
    // (android.builtInKotlin=false) and applies KGP explicitly.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.dandle"
    // navigation 2.10 / activity 1.12 / lifecycle 2.11 ship minCompileSdk=37 (aar-metadata).
    compileSdk = 37

    buildFeatures {
        buildConfig = true // BuildConfig.DEBUG gates testTagsAsResourceId in debug builds.
    }

    defaultConfig {
        // Product identity is "Dandle"; namespace and applicationId match.
        applicationId = "app.dandle"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Security review 2026-11-10: keystore ROTATED to v2 (RSA 4096, 10000 days).
            // The old ~/.dandle-release.keystore and its in-repo password literal are retired —
            // existing 0.3.x beta installs need one uninstall/reinstall (signature mismatch).
            // The password lives ONLY in ~/.config/dandle/release-keystore.env (chmod 600,
            // outside the repo). Read from the FILE at configuration time rather than
            // System.getenv alone: Gradle daemon reuse means the daemon's environment may
            // predate a rotation — a shell-side `export` alone is not reliable.
            val keystoreEnv: Map<String, String> by lazy {
                val f = File(System.getProperty("user.home"), ".config/dandle/release-keystore.env")
                if (f.exists()) {
                    f.readLines().filter { "=" in it }.associate { line ->
                        line.substringBefore("=") to line.substringAfter("=")
                    }
                } else emptyMap()
            }
            val releaseStorePassword =
                System.getenv("DANDLE_STORE_PASSWORD")
                    ?: keystoreEnv["DANDLE_STORE_PASSWORD"]
                    ?: throw GradleException("DANDLE_STORE_PASSWORD not set (env or ~/.config/dandle/release-keystore.env)")
            storeFile = file(System.getProperty("user.home") + "/.dandle-release-v2.keystore")
            storePassword = releaseStorePassword
            keyAlias = "dandle"
            keyPassword = releaseStorePassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources (themes, manifest) at test runtime.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Brief 05 §3: export Room schemas so future migrations can use MigrationTestHelper.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    // BB/API request logging (BbLog-gated; see app.dandle.log.BbLog).
    implementation(libs.okhttp.logging)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget) // Stage 4 widgets (spec §7)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.ktx)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
