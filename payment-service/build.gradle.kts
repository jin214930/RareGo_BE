plugins {
    java
    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "com.bugzero"
version = "0.0.1-SNAPSHOT"
description = "payment-service"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common"))
    implementation(platform("software.amazon.awssdk:bom:2.41.29"))
    implementation("software.amazon.awssdk:s3")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.batch:spring-batch-test")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly("com.mysql:mysql-connector-j")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    implementation("org.springframework.boot:spring-boot-starter-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")

    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // Swagger/OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")

    // ShedLock for distributed scheduler locking
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.6.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.6.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test")
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                // 초기 개발 단계
                minimum = "0.00".toBigDecimal()
            }
        }
    }
}

// 명시적으로 실행하는 대용량 실측. 일반 test/CI 및 운영 클래스패스에서 제외한다.
val benchmark by sourceSets.creating
configurations[benchmark.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[benchmark.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
configurations[benchmark.annotationProcessorConfigurationName].extendsFrom(configurations.annotationProcessor.get())
configurations[benchmark.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
benchmark.compileClasspath += sourceSets.main.get().output
benchmark.runtimeClasspath += sourceSets.main.get().output

// build/check에서도 벤치마크 컴파일이 끼어들지 않도록 기본 검사 대상을 유지한다.
checkstyle {
    sourceSets = listOf(project.sourceSets.main.get(), project.sourceSets.test.get())
}

tasks.register<Test>("settlementBenchmark") {
    description = "격리된 로컬 MySQL에서 정산 방식별 대용량 성능을 측정합니다."
    group = "verification"
    testClassesDirs = benchmark.output.classesDirs
    classpath = benchmark.runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "4g"
    minHeapSize = "4g"
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    outputs.upToDateWhen { false }
    systemProperty("benchmark.output", layout.buildDirectory.dir("settlement-benchmark").get().asFile.absolutePath)
    project.properties.filterKeys { it.startsWith("benchmark.") }.forEach { (key, value) ->
        systemProperty(key, value.toString())
    }
    testLogging.showStandardStreams = true
}

tasks.register<Copy>("settlementBenchmarkBundle") {
    description = "같은 벤치마크를 DB와 동일 Docker 네트워크에서 실행할 클래스패스를 구성합니다."
    dependsOn(tasks.named(benchmark.classesTaskName))
    into(layout.buildDirectory.dir("settlement-benchmark-runtime"))
    from(benchmark.output) { into("classes") }
    from(sourceSets.main.get().output) { into("classes") }
    from(benchmark.runtimeClasspath.filter { it.isFile }) { into("lib") }
}
