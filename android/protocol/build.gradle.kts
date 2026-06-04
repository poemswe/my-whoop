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
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
