plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.echo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.echo"
        minSdk = 26          // Galaxy A23 ships Android 13, keep headroom down to 26
        targetSdk = 34       // Android 14
        versionCode = 1
        versionName = "0.1"

        // Uncomment once native (C++/NDK) code is added under app/src/main/cpp
        // externalNativeBuild {
        //     cmake {
        //         cppFlags += "-std=c++17"
        //     }
        // }
    }

    // Uncomment once native code is added
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Encrypted token storage (OAuth tokens)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking (Google APIs + Gemini)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")


    // Porcupine wake-word (get access key from console.picovoice.ai first)
    // Version pinned deliberately — verified current as of integration date.
    // Re-check https://mvnrepository.com/artifact/ai.picovoice/porcupine-android before bumping.
    implementation("ai.picovoice:porcupine-android:4.0.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // Sherpa-ONNX wake-word engine
    implementation(files("libs/sherpa-onnx-1.12.21.aar"))
}
