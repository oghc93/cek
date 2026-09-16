plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lite.xraylite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lite.xraylite"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        viewBinding = true
        // AGP 8+ tidak lagi generate BuildConfig secara default — harus di-eksplisit-kan.
        // Dipakai di XrayLiteApp buat log "App version X build Y" di layar Show logs.
        buildConfig = true
    }

    packaging {
        // Xray-core AAR sering menyertakan native .so ganda arch; jangan strip yang dipakai
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // --- Layar Settings (halaman Xray options / SSH options / DNS / dsb) ---
    implementation("androidx.preference:preference-ktx:1.2.1")

    // --- Xray-core engine (AndroidLibXrayLite hasil build gomobile) ---
    // Repo resmi: https://github.com/2dust/AndroidLibXrayLite
    // File ini di-build OTOMATIS oleh .github/workflows/build.yml (job gomobile bind)
    // dan ditaruh di app/libs/libv2ray.aar sebelum step assembleDebug/assembleRelease jalan --
    // tidak usah dibuild manual selama kamu build lewat GitHub Actions.
    // TUN fd dikasih langsung ke CoreController.startLoop(), jadi tidak perlu lagi
    // tun2socks/hev-socks5-tunnel terpisah seperti rencana awal.
    implementation(files("libs/libv2ray.aar"))

    // --- SSH murni (port forwarding, tanpa Xray) ---
    implementation("com.hierynomus:sshj:0.38.0")

    // QR code scan untuk import config (opsional, boleh dihapus kalau mau lebih ringan)
    implementation("com.google.zxing:core:3.5.3")

    // JSON parsing config Xray
    implementation("com.google.code.gson:gson:2.11.0")
}
