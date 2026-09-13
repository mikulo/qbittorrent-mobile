plugins {
    id("com.android.application")
}

android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    namespace = "org.qbittorrent.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.qbittorrent.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.3.6"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"

        vectorDrawables.useSupportLibrary = true
        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-39")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
}
