import io.restassured.response.Response;
import kg.jazzlms.config.ConfigReader;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.CourseController;
import kg.jazzlms.models.request.CourseRequest;
import kg.jazzlms.models.response.CourseResponse;
import kg.jazzlms.utils.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static kg.jazzlms.utils.EntityManager.courseBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * POST /api/courses (course-controller → create), course service: course.url (8082).
 * Ожидаемый успешный ответ по swagger — 201 Created + CourseDto.
 * Валидация в CourseRequest на бэке: name @NotBlank max 100; code max 20; description max 5000.
 * code НЕ уникальный, level — свободная строка (в БД VARCHAR(20)), price/capacity не валидируются.
 */
@Tag("regression")
@DisplayName("Course service: создание курса")
public class CreateCourseTest {

    private static final CourseController COURSE_CONTROLLER = new CourseController(Endpoints.COURSE_URL);
    private static UUID categoryId;
    private static UUID adminId;

    @BeforeAll
    static void setUp() {
        categoryId = COURSE_CONTROLLER.getOrCreateCategoryId();
        adminId = new AuthController(Endpoints.BASE_URL)
                .login(ConfigReader.getValue("admin.username"), ConfigReader.getValue("admin.password"))
                .getUser().getId();
    }

    // =====================================================================
    //                              ПОЗИТИВНЫЕ
    // =====================================================================

    // 1. Минимальный запрос: только name
    @Tag("smoke")
    @Test
    @DisplayName("Только name -> 201, сервер проставляет служебные поля")
    void createCourseWithOnlyName() {
        CourseRequest request = CourseRequest.builder()
                .name("Only name " + UUID.randomUUID().toString().substring(0, 8))
                .build();

        Response response = COURSE_CONTROLLER.createCourseResponse(request);
        assertEquals(201, response.statusCode(), response.asString());

        CourseResponse created = response.as(CourseResponse.class);
        assertAll(
                () -> assertNotNull(created.getId(), "id"),
                () -> assertEquals(request.getName(), created.getName(), "name"),
                () -> assertNotNull(created.getCreatedAt(), "createdAt"),
                () -> assertEquals(adminId, created.getCreatedBy(), "createdBy = id из токена"),
                () -> assertEquals(0, created.getLearnersCount(), "у нового курса нет слушателей"),
                () -> assertNotNull(created.getCertificateTemplateId(), "без noCertificate ставится шаблон по умолчанию")
        );
    }

    // 2. Все поля заполнены
    @Test
    @DisplayName("Все поля заполнены -> 201, ответ совпадает с запросом")
    void createCourseWithAllFields() {
        CourseRequest request = courseBuilder()
                .description("Полное описание курса")
                .categoryId(categoryId)
                .price(new BigDecimal("1500.50"))
                .capacity(30)
                .level("BEGINNER")
                .active(true)
                .hiddenFromCatalog(false)
                .sequential(true)
                .noCertificate(true)
                .build();

        CourseResponse created = COURSE_CONTROLLER.createCourse(request);

        assertCourseMatches(request, created);
        assertNotNull(created.getCategoryName(), "categoryName подтягивается по categoryId");
    }

    // 3. Созданный курс можно получить по id
    @Tag("smoke")
    @Test
    @DisplayName("Созданный курс доступен через GET /api/courses/{id}")
    void createdCourseIsAvailableById() {
        CourseResponse created = COURSE_CONTROLLER.createCourse(EntityManager.generateCourse());

        CourseResponse found = COURSE_CONTROLLER.getCourseById(created.getId());

        assertAll(
                () -> assertEquals(created.getId(), found.getId()),
                () -> assertEquals(created.getName(), found.getName()),
                () -> assertEquals(created.getCode(), found.getCode())
        );
    }

    // 4. Чужой X-User-Id игнорируется: сервис берёт id из токена (ServiceJwtFilter)
    @Test
    @DisplayName("Подменённый X-User-Id не влияет на createdBy")
    void spoofedUserIdHeaderIsIgnored() {
        Response response = COURSE_CONTROLLER.createCourseResponse(EntityManager.generateCourse(), UUID.randomUUID());

        assertEquals(201, response.statusCode(), response.asString());
        assertEquals(adminId, response.as(CourseResponse.class).getCreatedBy());
    }

    // 4.1 Без сертификата
    @Test
    @DisplayName("noCertificate = true -> certificateTemplateId = null")
    void createCourseWithoutCertificate() {
        CourseResponse created = COURSE_CONTROLLER.createCourse(courseBuilder().noCertificate(true).build());
        assertNull(created.getCertificateTemplateId());
    }

    // 4.2 code не уникальный — два курса с одним code допустимы
    @Test
    @DisplayName("Повторный code допустим -> 201")
    void createCourseWithDuplicateCodeIsAllowed() {
        CourseRequest first = EntityManager.generateCourse();
        COURSE_CONTROLLER.createCourse(first);

        CourseResponse second = COURSE_CONTROLLER.createCourse(courseBuilder().code(first.getCode()).build());
        assertEquals(first.getCode(), second.getCode());
    }

    // 5. Граничные длины (ровно максимум)
    static Stream<Arguments> maxLengthData() {
        return Stream.of(
                Arguments.of("name = 100 символов",         courseBuilder().name("n".repeat(100)).build()),
                Arguments.of("code = 20 символов",          courseBuilder().code(uniqueCode(20)).build()),
                Arguments.of("description = 5000 символов", courseBuilder().description("d".repeat(5000)).build()),
                Arguments.of("name = 1 символ",             courseBuilder().name("A").build()),
                Arguments.of("Кириллица в name",            courseBuilder().name("Основы Java " + uniqueCode(4)).build()),
                Arguments.of("Спецсимволы в name",          courseBuilder().name("C++ & C# / \"Intro\" " + uniqueCode(4)).build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maxLengthData")
    void createCourseWithBoundaryValues(String caseName, CourseRequest request) {
        CourseResponse created = COURSE_CONTROLLER.createCourse(request);
        assertCourseMatches(request, created);
    }

    // 6. Цена
    @ParameterizedTest(name = "price = {0}")
    @ValueSource(strings = {"0", "0.01", "99.99", "1000000"})
    void createCourseWithDifferentPrices(String price) {
        CourseResponse created = COURSE_CONTROLLER.createCourse(courseBuilder().price(new BigDecimal(price)).build());
        assertEquals(0, new BigDecimal(price).compareTo(created.getPrice()), "price");
    }

    // 7. Вместимость
    @ParameterizedTest(name = "capacity = {0}")
    @ValueSource(ints = {1, 25, 1000})
    void createCourseWithDifferentCapacity(int capacity) {
        CourseResponse created = COURSE_CONTROLLER.createCourse(courseBuilder().capacity(capacity).build());
        assertEquals(capacity, created.getCapacity());
    }

    // 8. Уровни: на бэке это свободная строка (фронт шлёт эти значения)
    @ParameterizedTest(name = "level = {0}")
    @ValueSource(strings = {"BEGINNER", "INTERMEDIATE", "ADVANCED"})
    void createCourseWithEachLevel(String level) {
        CourseResponse created = COURSE_CONTROLLER.createCourse(courseBuilder().level(level).build());
        assertEquals(level, created.getLevel());
    }

    // 9. Комбинации флагов
    static Stream<Arguments> flags() {
        return Stream.of(
                Arguments.of(false, false, false),
                Arguments.of(true,  false, false),
                Arguments.of(false, true,  false),
                Arguments.of(false, false, true),
                Arguments.of(true,  true,  true)
        );
    }

    @ParameterizedTest(name = "active={0}, hiddenFromCatalog={1}, sequential={2}")
    @MethodSource("flags")
    void createCourseWithFlagCombinations(boolean active, boolean hidden, boolean sequential) {
        CourseResponse created = COURSE_CONTROLLER.createCourse(courseBuilder()
                .active(active).hiddenFromCatalog(hidden).sequential(sequential).build());

        assertAll(
                () -> assertEquals(active, created.isActive(), "active"),
                () -> assertEquals(hidden, created.isHiddenFromCatalog(), "hiddenFromCatalog"),
                () -> assertEquals(sequential, created.isSequential(), "sequential")
        );
    }

    // =====================================================================
    //                              НЕГАТИВНЫЕ
    // =====================================================================

    // 10. Невалидные значения полей -> 400
    static Stream<Arguments> invalidData() {
        return Stream.of(
                Arguments.of("name = null",                 courseBuilder().name(null).build()),
                Arguments.of("name пустой",                 courseBuilder().name("").build()),
                Arguments.of("name пробелы",                courseBuilder().name("   ").build()),
                Arguments.of("name = 101 символ",           courseBuilder().name("n".repeat(101)).build()),
                Arguments.of("code = 21 символ",            courseBuilder().code(uniqueCode(21)).build()),
                Arguments.of("description = 5001 символ",   courseBuilder().description("d".repeat(5001)).build()),
                // ниже — ожидаемое поведение, которого на бэке сейчас НЕТ: падение теста = баг
                // ПАДАЕТ (баг бэка): 201 вместо 400 — на price нет @PositiveOrZero, курс создаётся с price = -1
                Arguments.of("price < 0 (нет валидации)",    courseBuilder().price(new BigDecimal("-1")).build()),
                // ПАДАЕТ (баг бэка): 201 вместо 400 — на capacity нет @PositiveOrZero, курс создаётся с capacity = -1
                Arguments.of("capacity < 0 (нет валидации)", courseBuilder().capacity(-1).build()),
                // ПАДАЕТ (баг бэка): 500 вместо 400 — нет @Size(max = 20) на level, падает на VARCHAR(20) в БД
                Arguments.of("level 21 символ (VARCHAR(20) -> сейчас 500)", courseBuilder().level("L".repeat(21)).build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidData")
    void createCourseWithInvalidData(String caseName, CourseRequest request) {
        Response response = COURSE_CONTROLLER.createCourseResponse(request);
        assertEquals(400, response.statusCode(), response.asString());
    }

    // 11. Ссылки на несуществующие сущности
    @Test
    @DisplayName("Несуществующий categoryId -> 404")
    void createCourseWithNonExistingCategory() {
        Response response = COURSE_CONTROLLER.createCourseResponse(courseBuilder().categoryId(UUID.randomUUID()).build());
        assertEquals(404, response.statusCode(), response.asString());
    }

    // ПАДАЕТ (баг бэка): 500 вместо 400/404 — существование certificateTemplateId не проверяется,
    // запрос падает на внешнем ключе в БД
    @Test
    @DisplayName("Несуществующий certificateTemplateId -> 400/404")
    void createCourseWithNonExistingCertificateTemplate() {
        Response response = COURSE_CONTROLLER.createCourseResponse(
                courseBuilder().certificateTemplateId(UUID.randomUUID()).build());
        assertStatusIn(response, 400, 404);
    }

    // 12. Некорректное тело запроса.
    // Известный баг (docs/postman/ainura/issues/04): HttpMessageNotReadableException не обрабатывается -> 500.
    // "{}" даёт честный 400 (name @NotBlank), остальные сейчас упадут с 500.
    @ParameterizedTest(name = "Тело: {0}")
    @ValueSource(strings = {
            "",                                               // ПАДАЕТ: 500 вместо 400 — пустое тело
            "{}",
            "not a json",                                     // ПАДАЕТ: 500 вместо 400 — не JSON
            "{\"name\": \"Broken\",",                         // ПАДАЕТ: 500 вместо 400 — обрезанный JSON
            "[]",                                             // ПАДАЕТ: 500 вместо 400 — массив вместо объекта
            "{\"name\":\"Course\",\"price\":\"free\"}",       // ПАДАЕТ: 500 вместо 400 — строка вместо числа
            "{\"name\":\"Course\",\"capacity\":\"ten\"}",     // ПАДАЕТ: 500 вместо 400 — строка вместо числа
            "{\"name\":\"Course\",\"active\":\"yes\"}",       // ПАДАЕТ: 500 вместо 400 — не boolean
            "{\"name\":\"Course\",\"categoryId\":\"not-a-uuid\"}" // ПАДАЕТ: 500 вместо 400 — не UUID
    })
    void createCourseWithMalformedBody(String body) {
        Response response = COURSE_CONTROLLER.createCourseRaw(body);
        assertEquals(400, response.statusCode(), response.asString());
    }

    // =====================================================================
    //                              ХЕЛПЕРЫ
    // =====================================================================

    /** Проверяет только те поля, которые были отправлены в запросе */
    private static void assertCourseMatches(CourseRequest expected, CourseResponse actual) {
        assertNotNull(actual.getId(), "id");
        assertNotNull(actual.getCreatedAt(), "createdAt");
        assertEquals(expected.getName(), actual.getName(), "name");
        if (expected.getCode() != null)        assertEquals(expected.getCode(), actual.getCode(), "code");
        if (expected.getDescription() != null) assertEquals(expected.getDescription(), actual.getDescription(), "description");
        if (expected.getCategoryId() != null)  assertEquals(expected.getCategoryId(), actual.getCategoryId(), "categoryId");
        if (expected.getPrice() != null)       assertEquals(0, expected.getPrice().compareTo(actual.getPrice()), "price");
        if (expected.getCapacity() != null)    assertEquals(expected.getCapacity(), actual.getCapacity(), "capacity");
        if (expected.getLevel() != null)       assertEquals(expected.getLevel(), actual.getLevel(), "level");
        if (expected.getActive() != null)      assertEquals(expected.getActive(), actual.isActive(), "active");
        if (expected.getHiddenFromCatalog() != null)
            assertEquals(expected.getHiddenFromCatalog(), actual.isHiddenFromCatalog(), "hiddenFromCatalog");
        if (expected.getSequential() != null)  assertEquals(expected.getSequential(), actual.isSequential(), "sequential");
    }

    private static void assertStatusIn(Response response, int... expected) {
        int actual = response.statusCode();
        for (int code : expected) {
            if (code == actual) return;
        }
        fail("Ожидали один из " + java.util.Arrays.toString(expected) + ", получили " + actual
                + ". Тело: " + response.asString());
    }

    /** Уникальный code заданной длины */
    private static String uniqueCode(int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) sb.append(UUID.randomUUID().toString().replace("-", ""));
        return sb.substring(0, length).toUpperCase();
    }
}
