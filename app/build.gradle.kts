import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Phase 12: โหลดการตั้งค่า Owner Keystore จากไฟล์ภายนอก (keystore.properties) เฉพาะในเครื่องเจ้าของเท่านั้น
// ห้ามฝัง Keystore หรือรหัสผ่านลงในซอร์สโค้ดหรือ Git โดยเด็ดขาด
val ownerKeystorePropertiesFile = rootProject.file("keystore.properties")
val ownerKeystoreProperties = Properties()
val hasOwnerKeystoreConfig = ownerKeystorePropertiesFile.isFile && runCatching {
    FileInputStream(ownerKeystorePropertiesFile).use { ownerKeystoreProperties.load(it) }
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { key ->
        !ownerKeystoreProperties.getProperty(key).isNullOrBlank()
    }
}.getOrDefault(false)

android {
    namespace = "com.oneproject.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oneproject.offline"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.0.0-phase12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasOwnerKeystoreConfig) {
            create("ownerRelease") {
                storeFile = rootProject.file(ownerKeystoreProperties.getProperty("storeFile"))
                storePassword = ownerKeystoreProperties.getProperty("storePassword")
                keyAlias = ownerKeystoreProperties.getProperty("keyAlias")
                keyPassword = ownerKeystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            isMinifyEnabled = false
        }
        release {
            // กฎข้อ 01 และ Phase 12: Debug และ Owner-signed Release แยกจากกันชัดเจน
            // ห้ามใช้ debug signing config มาปลอมเป็น release เด็ดขาด
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasOwnerKeystoreConfig) {
                signingConfig = signingConfigs.getByName("ownerRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
