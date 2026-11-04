plugins {
    id("java")
}

group = "org.spring"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.junit:junit-bom:6.0.0"))
    implementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Source: https://mvnrepository.com/artifact/io.rest-assured/rest-assured
    implementation("io.rest-assured:rest-assured:6.3.0")
    // Source: https://mvnrepository.com/artifact/org.projectlombok/lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testCompileOnly("org.projectlombok:lombok:1.18.42")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.42")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    implementation("com.github.javafaker:javafaker:1.0.2")
}

// ---------------------------------------------------------------- запуск тестов по JUnit-тегам
//   ./gradlew test                    все тесты
//   ./gradlew test -Ptags=smoke,e2e   тесты с любым из перечисленных тегов
//   ./gradlew smokeTest               быстрые проверки основных сценариев
//   ./gradlew regressionTest          полная функциональная проверка (smoke входит)
//   ./gradlew e2eTest                 сквозной сценарий user service + course service

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false } // API-тесты всегда гоняем заново, без кэша Gradle
}

tasks.test {
    useJUnitPlatform {
        (project.findProperty("tags") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.let { includeTags(*it.toTypedArray()) }
    }
}

fun registerTagTask(taskName: String, tag: String, about: String) =
    tasks.register<Test>(taskName) {
        group = "verification"
        description = about
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags(tag)
        }
    }

registerTagTask("smokeTest", "smoke", "Smoke: быстрые проверки основных сценариев")
registerTagTask("regressionTest", "regression", "Regression: все функциональные тесты сервисов")
registerTagTask("e2eTest", "e2e", "E2E: сквозной сценарий user service + course service")