import kg.jazzlms.constants.ResourceLocks;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.GamificationController;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.GamificationProfileResponse;
import kg.jazzlms.models.response.GamificationSettings;
import kg.jazzlms.models.response.LeaderboardResponse;
import kg.jazzlms.models.response.UserResponse;
import kg.jazzlms.utils.JsonUtils;
import kg.jazzlms.utils.Wait;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static kg.jazzlms.utils.EntityManager.userBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/gamification/** (gamification service, gamification.url = 8085).
 * Очки начисляются по Kafka-событиям (логин = 10 очков раз в день + бейдж Newcomer), поэтому ждём через Wait.until.
 * Настройки глобальные для всего стенда: тесты, которые их меняют, восстанавливают исходные в @AfterEach.
 * reset-statistics здесь не вызывается — он обнуляет очки всех пользователей.
 */
@Tag("regression")
@DisplayName("Gamification service: очки, бейджи, лидерборд, настройки")
@Epic("Gamification service")
@Feature("Очки, бейджи, лидерборд, настройки")
@ResourceLock(value = ResourceLocks.GAMIFICATION_SETTINGS, mode = ResourceAccessMode.READ_WRITE)
public class GamificationTest {

    private static final String PASSWORD = "Pass123!";

    private static final GamificationController ADMIN = new GamificationController(Endpoints.GAMIFICATION_URL);
    private static final UserController ADMIN_USERS = new UserController(Endpoints.BASE_URL);

    private static UserResponse learner;
    private static GamificationController learnerViaGateway;
    private static GamificationSettings originalSettings;

    @BeforeAll
    static void setUp() {
        originalSettings = ADMIN.getSettings();

        UserRequest request = userBuilder().password(PASSWORD).userType("LEARNER").active(true).build();
        Response response = ADMIN_USERS.createUserResponse(request);
        assertEquals(201, response.statusCode(), response.asString());
        learner = response.as(UserResponse.class);

        String token = new AuthController(Endpoints.BASE_URL).login(request.getUsername(), PASSWORD).getToken();
        learnerViaGateway = new GamificationController(Endpoints.GATEWAY_URL, token);
    }

    /** Возвращаем настройки как были, даже если тест упал посередине */
    @AfterEach
    void restoreSettings() {
        ADMIN.updateSettingsResponse(originalSettings);
    }

    @AfterAll
    static void tearDown() {
        if (learner != null) ADMIN_USERS.deleteUserResponse(learner.getId());
    }

    // =====================================================================
    //                              ПРОФИЛЬ
    // =====================================================================

    @Tag("smoke")
    @Test
    @DisplayName("Первый логин: +10 очков, бейдж Newcomer, запись в истории")
    void firstLoginGivesPointsAndBadge() {
        GamificationProfileResponse me = Wait.until("начислены очки за первый логин",
                learnerViaGateway::me, p -> p.getPoints() >= 10);

        assertAll(
                () -> assertEquals(learner.getId(), me.getUserId(), "userId из токена"),
                () -> assertEquals(10, me.getPoints(), "10 очков за логин"),
                () -> assertEquals(1, me.getLogins(), "logins"),
                () -> assertEquals(1, me.getLevel(), "level"),
                () -> assertEquals(300, me.getNextLevelPoints(), "следующий уровень — 300 очков"),
                () -> assertTrue(me.getBadges().stream().anyMatch(b -> "newcomer".equals(b.getCode())), "бейдж newcomer"),
                () -> assertTrue(me.getHistory().stream().anyMatch(h -> "Sign in".equals(h.getAction()) && h.getPoints() == 10),
                        "в истории есть Sign in +10"),
                () -> assertNotNull(me.getRank(), "rank появляется, когда есть очки")
        );
    }

    @Test
    @DisplayName("Админ видит тот же профиль через /users/{id}")
    void adminSeesUserProfile() {
        GamificationProfileResponse me = Wait.until("начислены очки", learnerViaGateway::me, p -> p.getPoints() >= 10);
        GamificationProfileResponse byAdmin = ADMIN.getUserProfile(learner.getId());

        assertAll(
                () -> assertEquals(me.getUserId(), byAdmin.getUserId()),
                () -> assertEquals(me.getPoints(), byAdmin.getPoints()),
                () -> assertEquals(me.getBadges().size(), byAdmin.getBadges().size())
        );
    }

    @Test
    @DisplayName("Неизвестный пользователь -> 200 с пустым профилем")
    void unknownUserProfile() {
        GamificationProfileResponse profile = ADMIN.getUserProfile(UUID.randomUUID());

        assertAll(
                () -> assertEquals(0, profile.getPoints()),
                () -> assertTrue(profile.getBadges().isEmpty()),
                () -> assertTrue(profile.getHistory().isEmpty()),
                () -> assertNull(profile.getRank(), "rank = null без очков")
        );
    }

    @Test
    @DisplayName("Подменённый X-User-Id игнорируется: /me возвращает владельца токена")
    void spoofedUserIdHeaderIsIgnored() {
        Response response = learnerViaGateway.meResponse(UUID.randomUUID());

        assertEquals(200, response.statusCode(), response.asString());
        assertEquals(learner.getId(), response.as(GamificationProfileResponse.class).getUserId());
    }

    // =====================================================================
    //                              ЛИДЕРБОРД
    // =====================================================================

    @ParameterizedTest(name = "by = {0}")
    @ValueSource(strings = {"points", "levels", "badges"})
    void leaderboard(String by) {
        LeaderboardResponse board = learnerViaGateway.leaderboard(by, 5);

        assertAll(
                () -> assertEquals(by, board.getBy()),
                () -> assertTrue(board.getTop().size() <= 5, "не больше limit"),
                () -> assertNotNull(board.getMe(), "me есть, даже если пользователь не в top"),
                () -> assertEquals(learner.getId(), board.getMe().getUserId(), "me = владелец токена"),
                () -> assertTrue(board.getMe().isMe())
        );
        List<LeaderboardResponse.Entry> top = board.getTop();
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).getScore() >= top.get(i).getScore(), "сортировка по score");
            assertTrue(top.get(i - 1).getRank() <= top.get(i).getRank(), "rank по возрастанию");
        }
    }

    @Test
    @DisplayName("Лидерборд без параметров -> by = points")
    void leaderboardDefault() {
        assertEquals("points", learnerViaGateway.leaderboard(null, null).getBy());
    }

    // ПАДАЕТ (баг бэка): 200 вместо 400 — неизвестный by не валидируется,
    // сервис эхом возвращает by = "zzz" и молча строит рейтинг по points
    @Test
    @DisplayName("Лидерборд с неизвестным by -> 400")
    void leaderboardUnknownBy() {
        Response response = learnerViaGateway.leaderboardResponse("zzz", 5);
        assertEquals(400, response.statusCode(), response.asString());
    }

    // =====================================================================
    //                              ПРАВИЛА И КОНФИГ
    // =====================================================================

    @Test
    @DisplayName("rules: очки совпадают с настройками, 20 уровней с шагом byPoints.every")
    void rulesMatchSettings() {
        JsonPath rules = learnerViaGateway.rulesResponse().then().statusCode(200).extract().jsonPath();
        GamificationSettings s = ADMIN.getSettings();

        int every = s.getLevels().getByPoints().getEvery();
        List<Integer> levelPoints = rules.getList("levels.points", Integer.class);
        assertAll(
                () -> assertEquals(s.getPoints().getLogin().getPoints(), rules.getInt("points.find { it.action == 'Sign in' }.points")),
                () -> assertEquals(s.getPoints().getCourse().getPoints(), rules.getInt("points.find { it.action == 'Complete a course' }.points")),
                () -> assertEquals(20, levelPoints.size(), "уровней"),
                () -> assertEquals(0, levelPoints.get(0), "1 уровень с 0 очков"),
                () -> assertEquals(every, levelPoints.get(1) - levelPoints.get(0), "шаг уровня")
        );
    }

    @Test
    @DisplayName("config доступен ученику и отражает enabled из настроек")
    void config() {
        JsonPath config = learnerViaGateway.configResponse().then().statusCode(200).extract().jsonPath();
        assertEquals(originalSettings.getEnabled(), config.getBoolean("enabled"));
        assertNotNull(config.get("leaderboard.showPoints"));
    }

    // =====================================================================
    //                              НАСТРОЙКИ (админ)
    // =====================================================================

    // PUT частичный только на верхнем уровне: блок, которого нет в JSON, не меняется,
    // а переданный блок (points, levels, ...) заменяется ЦЕЛИКОМ. Поэтому блок points отправляем полностью.
    @Tag("smoke")
    @Test
    @DisplayName("PUT: переданный блок points меняется, остальные блоки сохраняются")
    void partialSettingsUpdate() {
        int newLoginPoints = originalSettings.getPoints().getLogin().getPoints() + 5;
        GamificationSettings.Points points = copyPoints(originalSettings.getPoints());
        points.getLogin().setPoints(newLoginPoints);

        GamificationSettings updated = ADMIN.updateSettings(GamificationSettings.builder().points(points).build());

        assertAll(
                () -> assertEquals(newLoginPoints, updated.getPoints().getLogin().getPoints(), "login.points изменился"),
                () -> assertEquals(originalSettings.getPoints().getCourse(), updated.getPoints().getCourse(), "course не тронут"),
                () -> assertEquals(originalSettings.getEnabled(), updated.getEnabled(), "enabled не тронут"),
                () -> assertEquals(originalSettings.getLevels(), updated.getLevels(), "levels не тронуты"),
                () -> assertEquals(originalSettings.getBadges(), updated.getBadges(), "badges не тронуты"),
                () -> assertEquals(originalSettings.getLeaderboard(), updated.getLeaderboard(), "leaderboard не тронут"),
                () -> assertEquals(newLoginPoints, ADMIN.getSettings().getPoints().getLogin().getPoints(), "GET видит изменение")
        );
    }

    // ПАДАЕТ (баг бэка): неполный вложенный блок затирает соседние поля.
    // {"points":{"login":{"points":15}}} -> points.enabled = false, login.enabled = false, unit/course/certificate = null.
    // Ожидаем либо слияние (остальное не меняется), либо 400 — но не молча сломанные настройки.
    @Test
    @DisplayName("Неполный блок points не затирает соседние правила")
    void nestedPartialUpdateKeepsSiblings() {
        Response response = ADMIN.updateSettingsRaw("{\"points\":{\"login\":{\"points\":15}}}");
        if (response.statusCode() == 400) return; // тоже корректное поведение

        assertEquals(200, response.statusCode(), response.asString());
        GamificationSettings.Points points = response.as(GamificationSettings.class).getPoints();
        assertAll(
                () -> assertEquals(originalSettings.getPoints().getEnabled(), points.getEnabled(), "points.enabled"),
                () -> assertEquals(originalSettings.getPoints().getLogin().getEnabled(), points.getLogin().getEnabled(), "login.enabled"),
                () -> assertEquals(originalSettings.getPoints().getUnit(), points.getUnit(), "unit"),
                () -> assertEquals(originalSettings.getPoints().getCourse(), points.getCourse(), "course"),
                () -> assertEquals(originalSettings.getPoints().getCertificate(), points.getCertificate(), "certificate")
        );
    }

    @Test
    @DisplayName("Выключение leaderboard.showPoints видно в config")
    void settingsAffectConfig() {
        GamificationSettings.Leaderboard lb = originalSettings.getLeaderboard();
        ADMIN.updateSettings(GamificationSettings.builder()
                .leaderboard(GamificationSettings.Leaderboard.builder()
                        .enabled(lb.getEnabled()).showLevels(lb.getShowLevels()).showBadges(lb.getShowBadges())
                        .showPoints(false)
                        .build())
                .build());

        JsonPath config = learnerViaGateway.configResponse().jsonPath();
        assertFalse(config.getBoolean("leaderboard.showPoints"));
        assertEquals(lb.getShowLevels(), config.getBoolean("leaderboard.showLevels"), "остальные флаги не тронуты");
    }

    @Test
    @DisplayName("settings/reset возвращает значения по умолчанию")
    void resetSettings() {
        GamificationSettings.Points points = copyPoints(originalSettings.getPoints());
        points.getLogin().setPoints(999);
        ADMIN.updateSettings(GamificationSettings.builder().points(points).build());

        Response response = ADMIN.resetSettingsResponse();
        assertEquals(200, response.statusCode(), response.asString());

        GamificationSettings defaults = response.as(GamificationSettings.class);
        assertAll(
                () -> assertTrue(defaults.getEnabled()),
                () -> assertEquals(10, defaults.getPoints().getLogin().getPoints(), "login по умолчанию 10"),
                () -> assertEquals(100, defaults.getPoints().getCourse().getPoints(), "course по умолчанию 100"),
                () -> assertEquals(300, defaults.getLevels().getByPoints().getEvery(), "уровень каждые 300")
        );
    }

    // Некорректное тело.
    // Баг бэка: HttpMessageNotReadableException не обрабатывается -> 500 (как в остальных сервисах).
    @ParameterizedTest(name = "Тело: {0}")
    @ValueSource(strings = {
            "not a json",                                   // ПАДАЕТ: 500 вместо 400 — не JSON
            "{\"enabled\": ",                               // ПАДАЕТ: 500 вместо 400 — обрезанный JSON
            "{\"enabled\":\"yes\"}",                        // ПАДАЕТ: 500 вместо 400 — не boolean
            "{\"points\":{\"login\":{\"points\":\"ten\"}}}" // ПАДАЕТ: 500 вместо 400 — строка вместо числа
    })
    void updateSettingsWithMalformedBody(String body) {
        Response response = ADMIN.updateSettingsRaw(body);
        assertEquals(400, response.statusCode(), response.asString());
    }

    // ПАДАЕТ (баг бэка): 200 вместо 400 — отрицательные очки не валидируются
    @Test
    @DisplayName("Отрицательные очки за логин -> 400")
    void negativePoints() {
        GamificationSettings.Points points = copyPoints(originalSettings.getPoints());
        points.getLogin().setPoints(-10);
        Response response = ADMIN.updateSettingsRaw(JsonUtils.toJson(GamificationSettings.builder().points(points).build()));
        assertEquals(400, response.statusCode(), response.asString());
    }

    // =====================================================================
    //                              ДОСТУП
    // =====================================================================

    @Test
    @DisplayName("Ученик через gateway: settings, reset и чужой профиль -> 403")
    void learnerCannotManage() {
        assertAll(
                () -> assertEquals(403, learnerViaGateway.getSettingsResponse().statusCode(), "GET settings"),
                () -> assertEquals(403, learnerViaGateway.updateSettingsResponse(GamificationSettings.builder().enabled(false).build()).statusCode(), "PUT settings"),
                () -> assertEquals(403, learnerViaGateway.resetSettingsResponse().statusCode(), "settings/reset"),
                () -> assertEquals(403, learnerViaGateway.getUserProfileResponse(UUID.randomUUID()).statusCode(), "users/{id}")
        );
    }

    // =====================================================================
    //                              ХЕЛПЕРЫ
    // =====================================================================

    /** Глубокая копия блока points, чтобы не испортить originalSettings */
    private static GamificationSettings.Points copyPoints(GamificationSettings.Points p) {
        return GamificationSettings.Points.builder()
                .enabled(p.getEnabled())
                .login(copyRule(p.getLogin()))
                .unit(copyRule(p.getUnit()))
                .course(copyRule(p.getCourse()))
                .certificate(copyRule(p.getCertificate()))
                .test(copyRule(p.getTest()))
                .build();
    }

    private static GamificationSettings.PointRule copyRule(GamificationSettings.PointRule r) {
        return r == null ? null : GamificationSettings.PointRule.builder().enabled(r.getEnabled()).points(r.getPoints()).build();
    }
}
