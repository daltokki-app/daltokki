import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 출시용 서명 키: 프로젝트 루트에 keystore.properties 파일을 두면 사용합니다.
// (storeFile, storePassword, keyAlias, keyPassword) 파일이 없으면 디버그 키로 서명합니다.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// gradle.properties 설정
// - pleosSdk=true  : Pleos Vehicle·NaviHelper·TextToSpeech·SpeechToText SDK 포함 (src/pleos). false면 SDK 없이 빌드 (src/nopleos)
// - carOnly=false  : 휴대폰에도 설치 가능. App Market에 차량 전용으로 낼 때만 true
val pleosSdk = (project.findProperty("pleosSdk") as String?)?.toBoolean() ?: true
val carOnly = (project.findProperty("carOnly") as String?)?.toBoolean() ?: false

android {
    namespace = "app.daltokki.drive"
    // Pleos 공식 샘플 앱(Vehicle SDK 2.3.2) 기준에 맞춰 compileSdk 35 (SDK Manager에서 Android 15 SDK Platform 설치 필요).
    // 실행 대상(targetSdk)은 Pleos 에뮬레이터와 같은 34 유지
    compileSdk = 35

    defaultConfig {
        applicationId = "app.daltokki.drive"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
        manifestPlaceholders["automotiveRequired"] = carOnly.toString()
        buildConfigField("boolean", "PLEOS_SDK", pleosSdk.toString())
    }

    // Pleos SDK 연동 코드는 별도 폴더에 두고, 설정에 따라 둘 중 하나만 빌드에 넣습니다.
    sourceSets {
        getByName("main") {
            java.srcDir(if (pleosSdk) "src/pleos/java" else "src/nopleos/java")
        }
    }

    // Android Automotive 표준 Car API (CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 코드·리소스 축소 (APK 용량 감소). Pleos SDK 클래스는 proguard-rules.pro에서 보존
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pleos Connect SDK
    if (pleosSdk) {
        implementation(libs.pleos.vehicle)    // 핸들 각도, 방향지시등, 브레이크, 경고등, 공조 상태
        implementation(libs.pleos.navihelper) // 경유지 추가
        implementation(libs.pleos.tts)        // 음성 합성 (온디바이스 모드, 앱 등록 불필요)
        implementation(libs.pleos.stt)        // 음성 인식 (온디바이스 모드, 앱 등록 불필요)
    }
    // FusedLocation SDK는 안드로이드 위치로 대신함

    testImplementation(libs.junit)
}
