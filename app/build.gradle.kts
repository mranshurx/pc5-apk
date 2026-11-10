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

/*
 * Seals raw1/ into a single AES-256-GCM blob at assets/raw1/p.bin.
 * Recursive. ".network-original-absent" is stripped from the name and recorded
 * as a skip-if-present flag, so the original file on device survives.
 */
val sealRaw1 = tasks.register("sealRaw1") {
    val srcDir = rootProject.file("raw1")
    val outDir = encOut
    val key = raw1Key
    inputs.dir(srcDir).optional()
    inputs.property("k", key)
    outputs.dir(outDir)
    doLast {
        val dest = outDir.get().asFile.resolve("raw1")
        dest.deleteRecursively()
        dest.mkdirs()

        val suffix = ".network-original-absent"

        val found = if (srcDir.isDirectory) {
            srcDir.walkTopDown()
                .filter { it.isFile && !it.name.equals("README.txt", ignoreCase = true) }
                .associateBy { it.relativeTo(srcDir).invariantSeparatorsPath }
        } else {
            emptyMap()
        }

        if (found.isEmpty()) {
            throw GradleException("raw1/ is empty — drop payload files in raw1/ before building")
        }

        /* Strip the marker, remember it, and refuse silent collisions. */
        val planned = LinkedHashMap<String, Pair<File, Boolean>>()
        for ((rel, f) in found.toSortedMap()) {
            val skip = rel.endsWith(suffix)
            val clean = (if (skip) rel.removeSuffix(suffix) else rel).trim('/')
            if (clean.isEmpty()) continue
            val prev = planned.put(clean, f to skip)
            if (prev != null) {
                throw GradleException("raw1/: '$clean' collides after stripping $suffix")
            }
        }
        if (planned.isEmpty()) throw GradleException("raw1/ holds no usable files")

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
        planned.entries.forEachIndexed { i, (clean, pair) ->
            if (i > 0) sb.append(',')
            val esc = clean.replace("\\", "\\\\").replace("\"", "\\\"")
            val b64 = Base64.getEncoder().encodeToString(seal(pair.first.readBytes()))
            sb.append('"').append(esc).append("\":{\"d\":\"").append(b64).append('"')
            if (pair.second) sb.append(",\"o\":true")
            sb.append('}')
        }
        sb.append('}')

        dest.resolve("p.bin").writeBytes(seal(sb.toString().toByteArray()))

        val skipCount = planned.count { it.value.second }
        println("raw1: sealed ${planned.size} file(s), $skipCount skip-if-present")
        planned.keys.forEach { println("  -> $it") }
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

        buildConfigField("String", "RAW1_KEY", "\"$raw1Key\"")

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
        /* p.bin must stay stored, not deflated, so assets.open() yields the raw blob. */
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
