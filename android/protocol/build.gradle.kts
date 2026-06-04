plugins {
    kotlin("jvm") version "2.1.0"
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }
}

sourceSets {
    main {
        resources.srcDir(rootProject.file("../protocol"))
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
