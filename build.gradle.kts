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

val lombokVersion = "1.18.48"
val mapstructVersion = "1.6.3"

dependencies {
    // Order matters: Lombok must run first, then MapStruct (with its Lombok bridge), then Micronaut's processors.
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")
    implementation("org.mapstruct:mapstruct:$mapstructVersion")

    annotationProcessor("io.micronaut.security:micronaut-security-annotations")
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    annotationProcessor("io.micronaut.data:micronaut-data-processor")

    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut.problem:micronaut-problem-json")
    implementation("io.micronaut:micronaut-management")   // /health for the load balancer or orchestrator
    implementation("io.micronaut.security:micronaut-security-jwt")

    // PostgreSQL: persistent room metadata
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("io.micronaut.flyway:micronaut-flyway")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // Redis: ephemeral stage / presence state
    implementation("io.micronaut.redis:micronaut-redis-lettuce")

    implementation("io.micronaut.cache:micronaut-cache-caffeine")

    // The room feed: server-sent events to the hallways, carried between instances by Redis pub/sub
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.redis:micronaut-redis-lettuce-pubsub")

    // LiveKit: real-time audio (not managed by the Micronaut BOM)
    implementation("io.livekit:livekit-server:0.17.0")

    // HTTP/3 (QUIC), only active in the "quic" environment. Netty's QUIC codec needs a native library per platform.
    runtimeOnly("io.micronaut:micronaut-http-netty-http3")
    listOf("linux-x86_64", "linux-aarch_64", "osx-x86_64", "osx-aarch_64").forEach {
        runtimeOnly("io.netty:netty-codec-native-quic::$it")
    }

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("ch.qos.logback:logback-classic")   // SqlStatementCounter reads Micronaut Data's query log
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("com.auth0:java-jwt:4.6.0")   // signs webhooks the way LiveKit does
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.jtalk.livecommunication.LiveCommunicationApplication"
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}

graalvmNative.toolchainDetection = false

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

// Docker: the Dockerfile at the project root is the source of truth (it began as the output of `./gradlew dockerfile` and builds
// the layered jar itself), so `docker build .` builds the image. With a root Dockerfile the plugin no longer configures its own
// `dockerfile` task, which is why that task is not tuned here.
