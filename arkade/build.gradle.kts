import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.ktlint.gradle)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.square.wire)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ksp)
}

val currentOs: String = System.getProperty("os.name").lowercase()

wire {
    kotlin {}
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    androidLibrary {
        namespace = "com.arkade"
        compileSdk {
            version = release(37)
        }
        minSdk = 24

        withHostTestBuilder {}

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    val xcfName = "libKit"

    iosX64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    configurations.all {
        // cel-java introduced some conflicts with the following modules
        // That is why they are excluded
        exclude(group = "com.google.code.findbugs", module = "annotations")
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.bitcoin.kmp)
                implementation(libs.secp256k1.kmp)
                implementation(libs.bignum)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.square.wire.runtime)
                implementation(libs.square.wire.grpc.client)
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.koin.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.dev.cel)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.androidx.room.sqlite.wrapper)
                implementation(libs.koin.android)
                implementation(libs.dev.cel)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.koin.test)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.kotlinx.serialization.json.okio)
            }
        }

        jvmTest {
            dependencies {
                val targetDep =
                    when {
                        currentOs.contains("linux") -> libs.secp256k1.kmp.jni.jvm.linux
                        currentOs.contains("windows") -> libs.secp256k1.kmp.jni.jvm.windows
                        currentOs.contains("mac") || currentOs.contains("darwin") -> libs.secp256k1.kmp.jni.jvm.macos
                        else -> error("Unsupported OS: $currentOs")
                    }
                implementation(targetDep)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.jvm)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.junit)
                implementation(libs.robolectric)
                implementation(libs.androidx.sqlite.bundled.jvm)
            }
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.secp256k1.kmp.jni.android)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.junit)
            }
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
}

tasks.register<E2ETestTask>("testE2E")

tasks.register<UnitTestTask>("testUnit")

tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )
}
