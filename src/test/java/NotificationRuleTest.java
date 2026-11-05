import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.NotificationController;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.NotificationRuleRequest;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.NotificationRuleResponse;
import kg.jazzlms.models.response.UserResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static kg.jazzlms.utils.EntityManager.generateNotificationRule;
import static kg.jazzlms.utils.EntityManager.notificationRuleBuilder;
import static kg.jazzlms.utils.EntityManager.userBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/notifications (notification service, notification.url = 8083): CRUD правил рассылки.
 * Успешное создание — 201 + NotificationRule. id правила — Mongo ObjectId (строка), не UUID.
 * Валидация: name @NotBlank max 100; eventType, recipient @NotNull; subject/bodyTemplate @NotBlank;
 * delayMinutes 0..43200. Неизвестный id -> 404.
 * Все созданные тестом правила удаляются в @AfterAll, чтобы не слать письма на стенде.
 */
@Tag("regression")
@DisplayName("Notification service: правила рассылки")
@Epic("Notification service")
@Feature("Правила рассылки")
public class NotificationRuleTest {

    private static final String PASSWORD = "Pass123!";
    private static final String UNKNOWN_ID = "aaaaaaaaaaaaaaaaaaaaaaaa"; // валидный ObjectId, которого нет

    private static final NotificationController NOTIFICATIONS = new NotificationController(Endpoints.NOTIFICATION_URL);
    private static final UserController ADMIN_USERS = new UserController(Endpoints.BASE_URL);
    private static final List<String> createdRules = new ArrayList<>();
    private static final List<UUID> createdUsers = new ArrayList<>();

    private static String learnerToken;

    @BeforeAll
    static void setUp() {
        UserRequest learnerReq = userBuilder().password(PASSWORD).userType("LEARNER").active(true).build();
        Response response = ADMIN_USERS.createUserResponse(learnerReq);
        assertEquals(201, response.statusCode(), response.asString());
        createdUsers.add(response.as(UserResponse.class).getId());
        learnerToken = new AuthController(Endpoints.BASE_URL).login(learnerReq.getUsername(), PASSWORD).getToken();
    }

    @AfterAll
    static void tearDown() {
        createdRules.forEach(NOTIFICATIONS::deleteRuleResponse);
        createdUsers.forEach(ADMIN_USERS::deleteUserResponse);
    }

    // =====================================================================
    //                              ПОЗИТИВНЫЕ
    // =====================================================================

    // 1. Только обязательные поля -> сервер ставит дефолты
    @Tag("smoke")
    @Test
    @DisplayName("Только обязательные поля -> 201, active = true, delayMinutes = 0")
    void createRuleWithRequiredFieldsOnly() {
        NotificationRuleRequest request = generateNotificationRule();

        NotificationRuleResponse created = create(request);

        assertAll(
                () -> assertNotNull(created.getId(), "id"),
                () -> assertTrue(created.getId().matches("[0-9a-f]{24}"), "id — Mongo ObjectId: " + created.getId()),
                () -> assertEquals(request.getName(), created.getName(), "name"),
                () -> assertEquals(request.getEventType(), created.getEventType(), "eventType"),
                () -> assertEquals(request.getRecipient(), created.getRecipient(), "recipient"),
                () -> assertEquals(request.getSubjectTemplate(), created.getSubjectTemplate(), "subjectTemplate"),
                () -> assertEquals(request.getBodyTemplate(), created.getBodyTemplate(), "bodyTemplate"),
                () -> assertTrue(created.isActive(), "active по умолчанию true"),
                () -> assertEquals(0, created.getDelayMinutes(), "delayMinutes по умолчанию 0")
        );
    }

    // 2. Все поля заполнены
    @Test
    @DisplayName("Все поля заполнены -> 201, ответ совпадает с запросом")
    void createRuleWithAllFields() {
        NotificationRuleRequest request = notificationRuleBuilder()
                .eventType("COURSE_COMPLETED")
                .recipient("COURSE_INSTRUCTORS")
                .active(false)
                .delayMinutes(60)
                .build();

        NotificationRuleResponse created = create(request);

        assertAll(
                () -> assertEquals("COURSE_COMPLETED", created.getEventType()),
                () -> assertEquals("COURSE_INSTRUCTORS", created.getRecipient()),
                () -> assertFalse(created.isActive()),
                () -> assertEquals(60, created.getDelayMinutes())
        );
    }

    // 3. Каждый тип получателя
    @ParameterizedTest(name = "recipient = {0}")
    @ValueSource(strings = {"RELATED_USER", "ACCOUNT_OWNER", "COURSE_INSTRUCTORS"})
    void createRuleWithEachRecipient(String recipient) {
        NotificationRuleResponse created = create(notificationRuleBuilder().recipient(recipient).active(false).build());
        assertEquals(recipient, created.getRecipient());
    }

    // 4. Граничные значения delayMinutes
    @ParameterizedTest(name = "delayMinutes = {0}")
    @ValueSource(ints = {0, 1, 43200})
    void createRuleWithBoundaryDelay(int delay) {
        NotificationRuleResponse created = create(notificationRuleBuilder().delayMinutes(delay).active(false).build());
        assertEquals(delay, created.getDelayMinutes());
    }

    // 5. name ровно 100 символов
    @Test
    @DisplayName("name = 100 символов -> 201")
    void createRuleWithMaxName() {
        NotificationRuleResponse created = create(notificationRuleBuilder().name("n".repeat(100)).active(false).build());
        assertEquals(100, created.getName().length());
    }

    // 6. GET по id и в общем списке
    @Tag("smoke")
    @Test
    @DisplayName("Созданное правило доступно по id и есть в общем списке")
    void createdRuleIsAvailable() {
        NotificationRuleResponse created = create(notificationRuleBuilder().active(false).build());

        NotificationRuleResponse found = NOTIFICATIONS.getRuleById(created.getId());
        assertEquals(created, found, "GET /{id} возвращает то же, что вернул POST");

        assertTrue(NOTIFICATIONS.getAllRules().stream().anyMatch(r -> created.getId().equals(r.getId())),
                "правило есть в GET /api/notifications");
    }

    // 7. PUT — полная замена
    @Test
    @DisplayName("PUT меняет все поля правила, id остаётся прежним")
    void updateRule() {
        NotificationRuleResponse created = create(notificationRuleBuilder().active(false).build());

        NotificationRuleRequest update = notificationRuleBuilder()
                .eventType("USER_ENROLLED")
                .recipient("ACCOUNT_OWNER")
                .active(false)
                .delayMinutes(15)
                .subjectTemplate("Updated {{courseName}}")
                .bodyTemplate("Updated body")
                .build();
        NotificationRuleResponse updated = NOTIFICATIONS.updateRule(created.getId(), update);

        assertAll(
                () -> assertEquals(created.getId(), updated.getId(), "id"),
                () -> assertEquals(update.getName(), updated.getName(), "name"),
                () -> assertEquals("USER_ENROLLED", updated.getEventType(), "eventType"),
                () -> assertEquals("ACCOUNT_OWNER", updated.getRecipient(), "recipient"),
                () -> assertEquals(15, updated.getDelayMinutes(), "delayMinutes"),
                () -> assertEquals(update.getSubjectTemplate(), updated.getSubjectTemplate(), "subjectTemplate"),
                () -> assertEquals(update, toRequest(NOTIFICATIONS.getRuleById(created.getId())), "изменения сохранились")
        );
    }

    // 8. DELETE -> 204, потом 404
    @Tag("smoke")
    @Test
    @DisplayName("DELETE -> 204, после удаления GET и повторный DELETE -> 404")
    void deleteRule() {
        NotificationRuleResponse created = create(notificationRuleBuilder().active(false).build());

        assertEquals(204, NOTIFICATIONS.deleteRuleResponse(created.getId()).statusCode());
        assertEquals(404, NOTIFICATIONS.getRuleByIdResponse(created.getId()).statusCode());
        assertEquals(404, NOTIFICATIONS.deleteRuleResponse(created.getId()).statusCode());
    }

    // 9. Справочники
    @Test
    @DisplayName("Справочники событий и получателей")
    void dictionaries() {
        List<String> events = NOTIFICATIONS.getEventTypes();
        List<String> recipients = NOTIFICATIONS.getRecipients();

        assertAll(
                () -> assertTrue(events.containsAll(List.of("USER_CREATED", "USER_ENROLLED", "COURSE_COMPLETED")), events.toString()),
                () -> assertEquals(List.of("RELATED_USER", "ACCOUNT_OWNER", "COURSE_INSTRUCTORS"), recipients)
        );
    }

    // =====================================================================
    //                              НЕГАТИВНЫЕ
    // =====================================================================

    // 10. Невалидные поля -> 400
    static Stream<Arguments> invalidData() {
        return Stream.of(
                Arguments.of("name = null",              notificationRuleBuilder().name(null).build()),
                Arguments.of("name пустой",              notificationRuleBuilder().name("").build()),
                Arguments.of("name пробелы",             notificationRuleBuilder().name("   ").build()),
                Arguments.of("name = 101 символ",        notificationRuleBuilder().name("n".repeat(101)).build()),
                Arguments.of("eventType = null",         notificationRuleBuilder().eventType(null).build()),
                Arguments.of("recipient = null",         notificationRuleBuilder().recipient(null).build()),
                Arguments.of("subjectTemplate = null",   notificationRuleBuilder().subjectTemplate(null).build()),
                Arguments.of("subjectTemplate пустой",   notificationRuleBuilder().subjectTemplate("").build()),
                Arguments.of("bodyTemplate = null",      notificationRuleBuilder().bodyTemplate(null).build()),
                Arguments.of("bodyTemplate пробелы",     notificationRuleBuilder().bodyTemplate("   ").build()),
                Arguments.of("delayMinutes = -1",        notificationRuleBuilder().delayMinutes(-1).build()),
                Arguments.of("delayMinutes = 43201",     notificationRuleBuilder().delayMinutes(43201).build()),
                // ПАДАЕТ (баг бэка): 500 вместо 400 — неизвестное значение enum не обрабатывается
                Arguments.of("eventType невалидный",     notificationRuleBuilder().eventType("NOT_AN_EVENT").build()),
                // ПАДАЕТ (баг бэка): 500 вместо 400 — неизвестное значение enum не обрабатывается
                Arguments.of("recipient невалидный",     notificationRuleBuilder().recipient("EVERYONE").build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidData")
    void createRuleWithInvalidData(String caseName, NotificationRuleRequest request) {
        Response response = NOTIFICATIONS.createRuleResponse(request);
        rememberIfCreated(response);
        assertEquals(400, response.statusCode(), response.asString());
    }

    // 11. Некорректное тело запроса.
    // Баг бэка: HttpMessageNotReadableException не обрабатывается -> 500 (как в user и course service).
    // "{}" даёт честный 400 (@NotBlank / @NotNull), остальные сейчас падают с 500.
    @ParameterizedTest(name = "Тело: {0}")
    @ValueSource(strings = {
            "",                                       // ПАДАЕТ: 500 вместо 400 — пустое тело
            "{}",
            "not a json",                             // ПАДАЕТ: 500 вместо 400 — не JSON
            "{\"name\": \"Broken\",",                 // ПАДАЕТ: 500 вместо 400 — обрезанный JSON
            "[]",                                     // ПАДАЕТ: 500 вместо 400 — массив вместо объекта
            "{\"name\":\"R\",\"eventType\":\"USER_CREATED\",\"recipient\":\"RELATED_USER\",\"subjectTemplate\":\"S\",\"bodyTemplate\":\"B\",\"delayMinutes\":\"ten\"}" // ПАДАЕТ: 500 вместо 400 — строка вместо числа
    })
    void createRuleWithMalformedBody(String body) {
        Response response = NOTIFICATIONS.createRuleRaw(body);
        rememberIfCreated(response);
        assertEquals(400, response.statusCode(), response.asString());
    }

    // 12. PUT без обязательных полей
    @Test
    @DisplayName("PUT только с name -> 400 (это полная замена, не PATCH)")
    void updateRuleWithPartialBody() {
        NotificationRuleResponse created = create(notificationRuleBuilder().active(false).build());

        Response response = NOTIFICATIONS.updateRuleResponse(created.getId(), NotificationRuleRequest.builder().name("Only name").build());

        assertEquals(400, response.statusCode(), response.asString());
        assertEquals(created, NOTIFICATIONS.getRuleById(created.getId()), "правило не изменилось");
    }

    // 13. Несуществующий id
    @Test
    @DisplayName("Несуществующий id: GET / PUT / DELETE -> 404")
    void unknownRuleId() {
        assertAll(
                () -> assertEquals(404, NOTIFICATIONS.getRuleByIdResponse(UNKNOWN_ID).statusCode(), "GET"),
                () -> assertEquals(404, NOTIFICATIONS.updateRuleResponse(UNKNOWN_ID, notificationRuleBuilder().active(false).build()).statusCode(), "PUT"),
                () -> assertEquals(404, NOTIFICATIONS.deleteRuleResponse(UNKNOWN_ID).statusCode(), "DELETE"),
                () -> assertEquals(404, NOTIFICATIONS.getRuleByIdResponse("not-an-object-id").statusCode(), "GET с невалидным id")
        );
    }

    // =====================================================================
    //                              ДОСТУП
    // =====================================================================

    @Test
    @DisplayName("Без токена -> 401")
    void noToken() {
        Response response = io.restassured.RestAssured.given().baseUri(Endpoints.NOTIFICATION_URL).get(Endpoints.NOTIFICATIONS);
        assertEquals(401, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("Ученик напрямую -> 401 (прямой доступ только админу)")
    void learnerDirectAccess() {
        NotificationController learner = new NotificationController(Endpoints.NOTIFICATION_URL, learnerToken);
        assertEquals(401, learner.getAllRulesResponse().statusCode());
    }

    @Test
    @DisplayName("Ученик через gateway: чтение и создание правил -> 403")
    void learnerViaGateway() {
        NotificationController learner = new NotificationController(Endpoints.GATEWAY_URL, learnerToken);

        Response createResponse = learner.createRuleResponse(notificationRuleBuilder().active(false).build());
        rememberIfCreated(createResponse);

        assertAll(
                () -> assertEquals(403, learner.getAllRulesResponse().statusCode(), "GET"),
                () -> assertEquals(403, createResponse.statusCode(), "POST")
        );
    }

    // =====================================================================
    //                              ХЕЛПЕРЫ
    // =====================================================================

    private static NotificationRuleResponse create(NotificationRuleRequest request) {
        NotificationRuleResponse created = NOTIFICATIONS.createRule(request);
        createdRules.add(created.getId());
        return created;
    }

    /** Если негативный тест неожиданно создал правило — всё равно удалим его */
    private static void rememberIfCreated(Response response) {
        if (response.statusCode() == 201) {
            createdRules.add(response.path("id"));
        }
    }

    private static NotificationRuleRequest toRequest(NotificationRuleResponse rule) {
        return NotificationRuleRequest.builder()
                .name(rule.getName())
                .eventType(rule.getEventType())
                .recipient(rule.getRecipient())
                .active(rule.isActive())
                .delayMinutes(rule.getDelayMinutes())
                .subjectTemplate(rule.getSubjectTemplate())
                .bodyTemplate(rule.getBodyTemplate())
                .build();
    }
}
