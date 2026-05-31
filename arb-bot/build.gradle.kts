plugins {
    java
    application
    id("com.diffplug.spotless") version "6.25.0"
    checkstyle
    id("com.gradleup.shadow") version "8.3.6"
}

application {
    mainClass.set("com.arbbot.Main")
}

tasks.shadowJar {
    archiveBaseName.set("arb-bot")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.arbbot.Main"
    }
}

group = "com.arbbot"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val okhttpVersion = "4.12.0"
val jacksonVersion = "2.17.2"
val slf4jVersion = "2.0.13"
val logbackVersion = "1.5.6"
val micrometerVersion = "1.13.1"
val oshiVersion = "6.6.1"
val junitVersion = "5.10.3"
val mockitoVersion = "5.12.0"

dependencies {
    // HTTP + WebSocket
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpVersion")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // Logging
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Config
    implementation("com.typesafe:config:1.4.3")

    // Metrics
    implementation("io.micrometer:micrometer-core:$micrometerVersion")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    // System stats (CPU, GPU, RAM, Network)
    implementation("com.github.oshi:oshi-core:$oshiVersion")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testImplementation("org.wiremock:wiremock:3.6.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<Test> {
    jvmArgs("--enable-preview")
    useJUnitPlatform {
        val testTags = System.getProperty("test.tags")
        if (testTags != null) {
            includeTags(testTags)
        } else {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}

spotless {
    java {
        googleJavaFormat("1.22.0")
        removeUnusedImports()
    }
}

checkstyle {
    toolVersion = "10.17.0"
    configFile = file("checkstyle.xml")
    isIgnoreFailures = false
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
