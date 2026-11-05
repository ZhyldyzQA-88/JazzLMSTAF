import java.util.Properties

plugins {
    id("java")
    // Allure: ./gradlew allureReport (HTML в build/reports/allure-report) или ./gradlew allureServe (сразу в браузере)
    id("io.qameta.allure") version "4.3.0"
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

    // Allure: адаптер JUnit 5 + фильтр RestAssured (запрос/ответ каждого вызова прикладываются к шагу отчёта)
    implementation(platform("io.qameta.allure:allure-bom:2.35.5"))
    implementation("io.qameta.allure:allure-junit5")
    implementation("io.qameta.allure:allure-rest-assured")
}

allure {
    version.set("2.46.1") // версия Allure commandline (генератор отчёта), скачивается плагином сам
}

// отчёт всегда пересобирается поверх старого (история уже скопирована в allure-results перед тестами)
tasks.named<io.qameta.allure.gradle.report.tasks.AllureReport>("allureReport") {
    clean.set(true)
}

// ---------------------------------------------------------------- запуск тестов по JUnit-тегам
//   ./gradlew test                    все тесты
//   ./gradlew test -Ptags=smoke,e2e   тесты с любым из перечисленных тегов
//   ./gradlew smokeTest               быстрые проверки основных сценариев
//   ./gradlew regressionTest          полная функциональная проверка (smoke входит)
//   ./gradlew e2eTest                 сквозной сценарий user service + course service

// ---------------------------------------------------------------- Allure
//   ./gradlew test allureServe --continue        прогнать тесты и открыть отчёт в браузере
//   ./gradlew test allureReport --continue       HTML-отчёт в build/reports/allure-report/allureReport/index.html
//   ./gradlew smokeTest allureServe --continue   то же для отдельного набора
// --continue нужен, чтобы отчёт собрался, даже если часть тестов упала.
// Перед каждым прогоном старые результаты удаляются, но история прошлого отчёта сохраняется -> график Trend.
val allureResults = layout.buildDirectory.dir("allure-results")
val allureReportDir = layout.buildDirectory.dir("reports/allure-report/allureReport")

tasks.withType<Test>().configureEach {
    systemProperty("allure.results.directory", allureResults.get().asFile.absolutePath)
    // параллельность настроена в src/test/resources/junit-platform.properties (5 потоков);
    // ./gradlew test -Pthreads=3 — другое число потоков, -Pthreads=1 — последовательно
    (project.findProperty("threads") as String?)?.toInt()?.let { threads ->
        systemProperty("junit.jupiter.execution.parallel.enabled", threads > 1)
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", threads)
        systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", threads)
    }
    doFirst {
        val results = allureResults.get().asFile
        results.deleteRecursively()
        results.mkdirs()
        // история прошлых запусков -> тренды в новом отчёте
        val history = allureReportDir.get().dir("history").asFile
        if (history.exists()) history.copyRecursively(results.resolve("history"), overwrite = true)
        // категории багов (вкладка Categories)
        file("src/test/resources/allure/categories.json").copyTo(results.resolve("categories.json"), overwrite = true)
        // блок Environment на главной странице отчёта
        val props = Properties()
        file("src/main/resources/app.properties").inputStream().use { props.load(it) }
        results.resolve("environment.properties").writeText(
            props.stringPropertyNames().filter { it.endsWith(".url") }.sorted()
                .joinToString("\n") { "${it} = ${props.getProperty(it).trim()}" } +
            "\nJava = ${System.getProperty("java.version")}\nOS = ${System.getProperty("os.name")}\n"
        )
    }
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