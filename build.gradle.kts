plugins {
    id("io.micronaut.application") version "5.0.2"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.micronaut.aot") version "5.0.2"
    id("io.micronaut.test-resources") version "5.0.2"
}

version = "0.1"
group = "com.jtalk"



repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    annotationProcessor("io.micronaut.data:micronaut-data-processor")

    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut.problem:micronaut-problem-json")

    // PostgreSQL: persistent room metadata
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("io.micronaut.flyway:micronaut-flyway")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // Redis: ephemeral stage / presence state
    implementation("io.micronaut.redis:micronaut-redis-lettuce")

    // LiveKit: real-time audio (not managed by the Micronaut BOM)
    implementation("io.livekit:livekit-server:0.17.0")

    // HTTP/3 (QUIC), only active in the "quic" environment. Netty's QUIC codec needs a native library per platform.
    runtimeOnly("io.micronaut:micronaut-http-netty-http3")
    listOf("linux-x86_64", "linux-aarch_64", "osx-x86_64", "osx-aarch_64").forEach {
        runtimeOnly("io.netty:netty-codec-native-quic::$it")
    }

    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("com.auth0:java-jwt:4.6.0")   // signs webhooks the way LiveKit does
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}



application {
    mainClass = "com.jtalk.livecommunication.LiveCommunicationServiceApplication"
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}




graalvmNative.toolchainDetection = false
graalvmNative {
    binaries {
        all {
            buildArgs.add("-H:+SharedArenaSupport")
        }
    }
}




micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.jtalk.livecommunication.*")
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        convertYamlToJava = false
        precomputeOperations = false
        cacheEnvironment = false
        optimizeClassLoading = true
        deduceEnvironment = false
        optimizeNetty = true
        replaceLogbackXml = true
    }

}

// Media Plane: the off-heap mixer worker (plain Java, no Micronaut). Needs REDIS_URI and a RoomAudioTransport.
tasks.register<JavaExec>("runMixer") {
    group = "application"
    description = "Runs the Media Plane mixer worker"
    mainClass = "com.jtalk.livecommunication.mixer.MixerWorkerApplication"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-XX:+UseCompactObjectHeaders")
}

// The FFM-based mixer code loads native libopus.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<io.micronaut.gradle.docker.MicronautDockerfile>("dockerfile") {

    baseImage = "eclipse-temurin:25-jre"
}







// https://docs.gradle.org/current/userguide/upgrading_major_version_9.html#test_task_fails_when_no_tests_are_discovered
tasks.withType<AbstractTestTask>().configureEach {
    failOnNoDiscoveredTests = false
}




