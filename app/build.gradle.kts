import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Stable signing key for the debug APKs that are published on GitHub. Installing an APK over an
// existing one only works while the signature stays the same, so the published builds must not be
// signed with the throwaway debug key that every CI runner generates. The keystore is provided
// through the environment (GitHub Actions secrets), never committed:
//   IMMICHFRAME_KEYSTORE_FILE, IMMICHFRAME_KEYSTORE_PASSWORD, IMMICHFRAME_KEY_ALIAS
val sharedKeystorePath = System.getenv("IMMICHFRAME_KEYSTORE_FILE")
val sharedKeystorePassword = System.getenv("IMMICHFRAME_KEYSTORE_PASSWORD")
val sharedKeyAlias = System.getenv("IMMICHFRAME_KEY_ALIAS")
val sharedKeyPassword = System.getenv("IMMICHFRAME_KEY_PASSWORD") ?: sharedKeystorePassword
val useSharedSigning = !sharedKeystorePath.isNullOrBlank() && !sharedKeystorePassword.isNullOrBlank() &&
        !sharedKeyAlias.isNullOrBlank() && file(sharedKeystorePath).exists()

android {
    signingConfigs {
        if (useSharedSigning) {
            create("Shared") {
                storeFile = file(sharedKeystorePath!!)
                storePassword = sharedKeystorePassword
                keyAlias = sharedKeyAlias
                keyPassword = sharedKeyPassword
            }
        }
        create("Release") {
            val propertiesFile = rootProject.file("signing.properties")
            if (propertiesFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(propertiesFile))

                storeFile = file(properties["KEYSTORE_FILE"] as String)
                storePassword = properties["KEYSTORE_PASSWORD"] as String
                keyAlias = properties["KEY_ALIAS"] as String
                keyPassword = properties["KEY_PASSWORD"] as String
            } else {
                println("Warning: signing.properties file not found!")
            }
        }
    }
    namespace = "com.immichframe.immichframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.immichframe.immichframe"
        minSdk = 21
        targetSdk = 36
        versionCode = 53
        versionName = "1.0.53.0"
    }

    buildTypes {
        debug {
            // Same key for every published build -> the next release installs over the previous one.
            if (useSharedSigning) {
                signingConfig = signingConfigs.getByName("Shared")
            } else {
                println("Warning: no stable signing key configured, using the default debug key!")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("Release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.retrofit)
    implementation(libs.retrofitgson)
    implementation(libs.nanohttpd)
    implementation(libs.androidx.preference)
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
