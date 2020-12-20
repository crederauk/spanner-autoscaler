import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.ajoberstar.reckon") version "0.13.0"
    id("org.springframework.boot") version "2.3.3.RELEASE"
    id("io.spring.dependency-management") version "1.0.10.RELEASE"
    id("com.google.cloud.tools.jib") version "2.5.0"
    id("com.github.johnrengelman.shadow") version "5.2.0"
    kotlin("jvm") version "1.3.72"
    kotlin("plugin.spring") version "1.3.72"
    kotlin("kapt") version "1.3.71"
}

group = "com.dmwgroup.gcp.spanner"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_11

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "Hoxton.SR8"
extra["testcontainersVersion"] = "1.14.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.springframework.cloud:spring-cloud-gcp-starter")
    implementation("org.springframework.cloud:spring-cloud-gcp-starter-logging")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    implementation("org.springframework.cloud:spring-cloud-gcp-starter-data-spanner")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("com.google.api.grpc:proto-google-cloud-spanner-v1:1.61.0")
    implementation("com.google.cloud:google-cloud-spanner:1.61.0")
    implementation("com.google.cloud:google-cloud-monitoring:2.0.1")
    implementation("com.google.auth:google-auth-library-oauth2-http:0.21.1")
    implementation("com.konghq:unirest-java:3.10.00")
    implementation("io.arrow-kt:arrow-core:0.11.0")
    implementation("com.github.f4b6a3:uuid-creator:2.7.8")
    implementation("org.hibernate:hibernate-validator:6.1.5.Final")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.testcontainers:junit-jupiter")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

reckon {
    scopeFromProp()
    stageFromProp("final", "hotfix")
}

// Make sure the project is in a fit state before tagging
subprojects.forEach {
    tasks["reckonTagCreate"].dependsOn("${it.name}:check")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "11"
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    languageVersion = "1.4"
}

// Configure Jib to generate docker images
jib {
    from {
        image = "gcr.io/distroless/java:11"
    }
    to {
        image = "docker.pkg.github.com/dmwgroup/spanner-autoscaler/spanner-autoscaler"
        tags = getAdditionalDockerTags()
    }
}

fun getAdditionalDockerTags(): Set<String> {
    val tags = mutableListOf<String>()
    var tag = ""
    project.version.toString().split(".").forEach {
        tags.add("$tag$it")
        tag += "$it."
    }
    println("Inferred Docker tags: $tags")
    return tags.toSet()
}
