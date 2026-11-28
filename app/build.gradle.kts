plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mytv.tvos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mytv.tvos"
        minSdk = 26
        targetSdk = 34
        versionCode = 43
        versionName = "3.0.9"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources { excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "DebugProbesKt.bin",
        // BouncyCastle ships post-quantum tables and cert-path messages in many languages that nothing here uses.
        "org/bouncycastle/pqc/**", "org/bouncycastle/x509/CertPathReviewerMessages*", "org/bouncycastle/pkix/CertPathReviewerMessages*", "org/bouncycastle/**/*.properties") } }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
