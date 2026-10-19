// Pure Kotlin/JVM: policy, scoring, diagnosis and chunk scheduling.
// No android.* imports, so all decision logic is unit-testable on a plain JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Target JVM 17 bytecode without pinning a toolchain, so any JDK 17+ (incl. Android Studio's JBR) builds it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
