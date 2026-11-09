import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val backendUrl: String = (System.getenv("BACKEND_URL") ?: "https://pc5-android.onrender.com").trimEnd('/')
val raw1Key: String = System.getenv("RAW1_KEY") ?: "pc5-dev-default-key-change-me"
val expectedCert: String = System.getenv("EXPECTED_CERT_SHA256") ?: ""
val ndkVer = "27.0.12077973"

val keystoreFile: File? = System.getenv("KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.isFile }

val encOut = layout.buildDirectory.dir("generated/raw1enc")

val sealRaw1 = tasks.register("sealRaw1") {
    val srcDir = rootProject.file("raw1")
    val outDir = encOut
    val key = raw1Key
    inputs.dir(srcDir)
    inputs.property("k", key)
    outputs.dir(outDir)
    doLast {
        val dest = outDir.get().asFile.resolve("raw1")
        dest.deleteRecursively()
        dest.mkdirs()

        val files = srcDir.listFiles()
            ?.filter { it.isFile && !it.name.equals("README.txt", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (files.isEmpty()) {
            println("raw1: EMPTY — nothing will be injected")
            return@doLast
        }

        val aesKey = SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()), "AES"
        )

        fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }

        val sb = StringBuilder("{")
        files.forEachIndexed { i, f ->
            if (i > 0) sb.append(",")
            val name = f.name.replace("\\", "\\\\").replace("\"", "\\\"")
            val b64 = Base64.getEncoder().encodeToString(seal(f.readBytes()))
            sb.append("\"").append(name).append("\":\"").append(b64).append("\"")
        }
        sb.append("}")

        dest.resolve("p.bin").writeBytes(seal(sb.toString().toByteArray()))
        println("raw1: sealed ${files.size} file(s) -> ${files.joinToString { it.name }}")
    }
}

android {
    namespace = "com.pc5.android"
    compileSdk = 34
    ndkVersion = ndkVer

    defaultConfig {
        applicationId = "com.pc5.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DRAW1_KEY=$raw1Key",
                    "-DBACKEND_URL=$backendUrl",
                    "-DEXPECTED_CERT=$expectedCert"
                )
                cppFlags += listOf("-O2", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].assets.srcDir(encOut)

    signingConfigs {
        keystoreFile?.let { ks ->
            create("rel") {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("rel")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    androidResources {
        noCompress += listOf("bin")
    }
}

tasks.named("preBuild") { dependsOn(sealRaw1) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
