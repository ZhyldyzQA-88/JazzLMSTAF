import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AnalyticsController;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.ActivityEventResponse;
import kg.jazzlms.models.response.TimelinePageResponse;
import kg.jazzlms.models.response.UserResponse;
import kg.jazzlms.utils.Wait;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static kg.jazzlms.controller.AnalyticsController.params;
import static kg.jazzlms.utils.EntityManager.userBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/analytics/** (analytics service, analytics.url = 8084): только чтение.
 * Данные приходят из Kafka-событий других сервисов, поэтому свежие события ждём через Wait.until.
 * В @BeforeAll создаём ученика и логинимся им: в ленте гарантированно есть USER_CREATED и USER_LOGGED_IN.
 */
@Tag("regression")
@DisplayName("Analytics service: статистика и лента событий")
@Epic("Analytics service")
@Feature("Статистика и лента событий")
public class AnalyticsTest {

    private static final String PASSWORD = "Pass123!";

    private static final AnalyticsController ANALYTICS = new AnalyticsController(Endpoints.ANALYTICS_URL);
    private static final UserController ADMIN_USERS = new UserController(Endpoints.BASE_URL);

    private static UserResponse learner;
    private static String learnerToken;

    @BeforeAll
    static void setUp() {
        UserRequest request = userBuilder().password(PASSWORD).userType("LEARNER").active(true).build();
        Response response = ADMIN_USERS.createUserResponse(request);
        assertEquals(201, response.statusCode(), response.asString());
        learner = response.as(UserResponse.class);
        learnerToken = new AuthController(Endpoints.BASE_URL).login(request.getUsername(), PASSWORD).getToken();
    }

    @AfterAll
    static void tearDown() {
        if (learner != null) ADMIN_USERS.deleteUserResponse(learner.getId());
    }

    // =====================================================================
    //                              СВОДКИ
    // =====================================================================

    @Tag("smoke")
    @Test
    @DisplayName("summary: today и week, у каждой метрики value / previous / changePercent")
    void summary() {
        Response response = ANALYTICS.summaryResponse();
        assertEquals(200, response.statusCode(), response.asString());

        JsonPath json = response.jsonPath();
        for (String period : List.of("today", "week")) {
            for (String metric : List.of("logins", "courseCompletions", "usersWithActivity")) {
                String path = period + "." + metric;
                assertNotNull(json.get(path + ".value"), path + ".value");
                assertNotNull(json.get(path + ".previous"), path + ".previous");
                assertNotNull(json.get(path + ".changePercent"), path + ".changePercent");
            }
        }
        assertTrue(json.getInt("today.logins.value") >= 1, "в @BeforeAll был логин сегодня");
    }

    @Test
    @DisplayName("overview: topUsers и eventsByType")
    void overview() {
        Response response = ANALYTICS.overviewResponse();
        assertEquals(200, response.statusCode(), response.asString());

        JsonPath json = response.jsonPath();
        assertNotNull(json.getList("topUsers"), "topUsers");
        Map<String, Integer> eventsByType = json.getMap("eventsByType");
        assertTrue(eventsByType.getOrDefault("USER_CREATED", 0) > 0, "eventsByType.USER_CREATED > 0: " + eventsByType);
    }

    // dashboard: количество точек на графике зависит от периода
    @ParameterizedTest(name = "period = {0} -> {1} точек")
    @CsvSource({"today, 24", "week, 7"})
    void dashboardPeriods(String period, int points) {
        Response response = ANALYTICS.dashboardResponse(period);
        assertEquals(200, response.statusCode(), response.asString());

        JsonPath json = response.jsonPath();
        assertAll(
                () -> assertEquals(period, json.getString("period")),
                () -> assertEquals(points, json.getList("labels").size(), "labels"),
                () -> assertEquals(points, json.getList("logins").size(), "logins"),
                () -> assertEquals(points, json.getList("completions").size(), "completions"),
                () -> assertNotNull(json.get("totals.logins"), "totals.logins")
        );
    }

    @Test
    @DisplayName("dashboard без period -> today")
    void dashboardDefaultPeriod() {
        assertEquals("today", ANALYTICS.dashboardResponse(null).jsonPath().getString("period"));
    }

    // ПАДАЕТ (баг бэка): 200 вместо 400 — неизвестный period не валидируется,
    // сервис эхом возвращает period = "zzz" и молча строит график за today
    @Test
    @DisplayName("dashboard с неизвестным period -> 400")
    void dashboardUnknownPeriod() {
        Response response = ANALYTICS.dashboardResponse("zzz");
        assertEquals(400, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("top-users: не больше limit, отсортированы по score по убыванию")
    void topUsers() {
        Response response = ANALYTICS.topUsersResponse(3);
        assertEquals(200, response.statusCode(), response.asString());

        List<Integer> scores = response.jsonPath().getList("score", Integer.class);
        assertTrue(scores.size() <= 3, "размер " + scores.size());
        for (int i = 1; i < scores.size(); i++) {
            assertTrue(scores.get(i - 1) >= scores.get(i), "сортировка по убыванию: " + scores);
        }
    }

    @Test
    @DisplayName("Активность пользователя: месяц по дням, есть сегодняшний логин")
    void userActivity() {
        Response response = Wait.until("логин ученика попал в его активность",
                () -> ANALYTICS.userActivityResponse(learner.getId().toString(), null),
                r -> r.statusCode() == 200 && r.jsonPath().getInt("loginsLastWeek") >= 1);

        JsonPath json = response.jsonPath();
        assertAll(
                () -> assertEquals("month", json.getString("period"), "период по умолчанию"),
                () -> assertEquals(json.getList("labels").size(), json.getList("logins").size()),
                () -> assertTrue(json.getInt("loginsLastMonth") >= json.getInt("loginsLastWeek"))
        );
    }

    @Test
    @DisplayName("Активность курса без событий -> 200 с нулями")
    void courseActivityForUnknownCourse() {
        Response response = ANALYTICS.courseActivityResponse(UUID.randomUUID().toString(), "week");
        assertEquals(200, response.statusCode(), response.asString());
        assertTrue(response.jsonPath().getList("logins", Integer.class).stream().allMatch(v -> v == 0));
    }

    // =====================================================================
    //                              ЛЕНТА СОБЫТИЙ
    // =====================================================================

    @Tag("smoke")
    @Test
    @DisplayName("timeline: события ученика (USER_CREATED и USER_LOGGED_IN) приходят из user service")
    void timelineHasEventsOfNewUser() {
        TimelinePageResponse page = Wait.until("в ленте появились USER_CREATED и USER_LOGGED_IN ученика",
                () -> ANALYTICS.timelineForUser(learner.getId(), null, 20),
                p -> hasType(p, "USER_CREATED") && hasType(p, "USER_LOGGED_IN"));

        ActivityEventResponse created = page.getItems().stream()
                .filter(e -> "USER_CREATED".equals(e.getType())).findFirst().orElseThrow();
        assertAll(
                () -> assertEquals("user-service", created.getSource(), "source"),
                () -> assertEquals(learner.getId().toString(), created.getUserId(), "userId"),
                () -> assertEquals(learner.getUsername(), created.getPayload().get("username"), "payload.username"),
                () -> assertNotNull(created.getOccurredAt(), "occurredAt"),
                () -> assertTrue(page.getItems().stream().allMatch(e -> learner.getId().toString().equals(e.getUserId())),
                        "фильтр userId: в ленте только события ученика")
        );
    }

    @Test
    @DisplayName("timeline: фильтр type и пагинация")
    void timelineFilterAndPaging() {
        TimelinePageResponse page0 = ANALYTICS.timeline(params("type", "USER_CREATED", "page", 0, "size", 2));
        TimelinePageResponse page1 = ANALYTICS.timeline(params("type", "USER_CREATED", "page", 1, "size", 2));

        assertAll(
                () -> assertEquals(0, page0.getPage()),
                () -> assertEquals(2, page0.getSize()),
                () -> assertTrue(page0.getItems().size() <= 2),
                () -> assertTrue(page0.getItems().stream().allMatch(e -> "USER_CREATED".equals(e.getType())), "только USER_CREATED"),
                () -> assertEquals(1, page1.getPage()),
                // total сравниваем с допуском: между запросами из Kafka может прийти новое событие
                () -> assertTrue(Math.abs(page0.getTotal() - page1.getTotal()) <= 5, "total не зависит от страницы")
        );
    }

    @Test
    @DisplayName("timeline/export: CSV с заголовком и событием ученика")
    void timelineExport() {
        String csv = Wait.until("событие ученика попало в CSV",
                () -> ANALYTICS.timelineExportResponse(params("userId", learner.getId(), "type", "USER_CREATED")).asString(),
                body -> body.contains(learner.getFirstName()));

        assertTrue(csv.replace("﻿", "").startsWith("date,event,user,course,description"), "заголовок CSV");
        assertTrue(csv.contains("\"USER_CREATED\""), csv);
    }

    // Некорректные фильтры
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "type=NOT_AN_EVENT",   // ПАДАЕТ (баг бэка): 500 вместо 400 — неизвестный type не обрабатывается
            "from=not-a-date"      // ПАДАЕТ (баг бэка): 500 вместо 400 — неразборчивая дата не обрабатывается
    })
    void timelineWithInvalidFilter(String filter) {
        String[] kv = filter.split("=");
        Response response = ANALYTICS.timelineResponse(params(kv[0], kv[1]));
        assertEquals(400, response.statusCode(), response.asString());
    }

    // =====================================================================
    //                              ДОСТУП
    // =====================================================================

    @Test
    @DisplayName("Ученик напрямую -> 401, через gateway -> 403")
    void learnerHasNoAccess() {
        AnalyticsController direct = new AnalyticsController(Endpoints.ANALYTICS_URL, learnerToken);
        AnalyticsController viaGateway = new AnalyticsController(Endpoints.GATEWAY_URL, learnerToken);

        assertAll(
                () -> assertEquals(401, direct.summaryResponse().statusCode(), "напрямую"),
                () -> assertEquals(403, viaGateway.summaryResponse().statusCode(), "через gateway"),
                () -> assertEquals(403, viaGateway.timelineResponse(Map.of()).statusCode(), "лента через gateway")
        );
    }

    // =====================================================================
    //                              ХЕЛПЕРЫ
    // =====================================================================

    private static boolean hasType(TimelinePageResponse page, String type) {
        return page.getItems() != null && page.getItems().stream().anyMatch(e -> type.equals(e.getType()));
    }
}
