package kg.jazzlms.controller;

import io.restassured.response.Response;
import kg.jazzlms.http.ApiRequest;
import kg.jazzlms.models.response.GamificationProfileResponse;
import kg.jazzlms.models.response.GamificationSettings;
import kg.jazzlms.models.response.LeaderboardResponse;
import kg.jazzlms.utils.JsonUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static kg.jazzlms.constants.Endpoints.*;

/**
 * Gamification service (gamification.url, 8085): очки, бейджи, уровни, лидерборд.
 * Ученику через gateway доступны /me, /rules, /config, /leaderboard;
 * /settings и /users/{id} — только админу.
 * X-User-Id сервис берёт из токена: подставить чужой id в заголовке нельзя (на это есть тест).
 */
public class GamificationController extends ApiRequest {

    private static final String X_USER_ID = "X-User-Id";

    public GamificationController(String url) {
        super(url);
    }

    public GamificationController(String url, String token) {
        super(url, token);
    }

    // ---------- настройки (админ) ----------

    public GamificationSettings getSettings() {
        return getSettingsResponse().then().statusCode(200).extract().as(GamificationSettings.class);
    }

    public Response getSettingsResponse() {
        return get(GAMIFICATION_SETTINGS);
    }

    /** PUT: отсутствующие блоки не меняются, переданный блок заменяется целиком (см. GamificationSettings) */
    public Response updateSettingsResponse(GamificationSettings settings) {
        return put(GAMIFICATION_SETTINGS, JsonUtils.toJson(settings));
    }

    public GamificationSettings updateSettings(GamificationSettings settings) {
        return updateSettingsResponse(settings).then().statusCode(200).extract().as(GamificationSettings.class);
    }

    /** Для битого JSON и неверных типов */
    public Response updateSettingsRaw(String body) {
        return put(GAMIFICATION_SETTINGS, body);
    }

    /** Возвращает настройки по умолчанию */
    public Response resetSettingsResponse() {
        return post(GAMIFICATION_SETTINGS_RESET, "");
    }

    // POST /api/gamification/reset-statistics (обнуляет очки ВСЕХ пользователей) намеренно не вынесен:
    // на общем стенде это стирает чужие данные.

    // ---------- публичная часть ----------

    /** { enabled, points, badges, levels, leaderboard: {...} } — что показывать в UI */
    public Response configResponse() {
        return get(GAMIFICATION_CONFIG);
    }

    /** { points: [{action, points, note}], levels: [{points, level}], levelRules: {...} } */
    public Response rulesResponse() {
        return get(GAMIFICATION_RULES);
    }

    /** Профиль текущего пользователя (по токену) */
    public GamificationProfileResponse me() {
        return meResponse().then().statusCode(200).extract().as(GamificationProfileResponse.class);
    }

    public Response meResponse() {
        return get(GAMIFICATION_ME);
    }

    /** /me с подменённым X-User-Id */
    public Response meResponse(UUID spoofedUserId) {
        this.response = given().spec(requestSpecification)
                .header(X_USER_ID, spoofedUserId.toString())
                .get(GAMIFICATION_ME);
        return this.response;
    }

    /** Профиль любого пользователя (админ). Для неизвестного id — пустой профиль, не 404 */
    public GamificationProfileResponse getUserProfile(UUID userId) {
        return getUserProfileResponse(userId).then().statusCode(200).extract().as(GamificationProfileResponse.class);
    }

    public Response getUserProfileResponse(UUID userId) {
        return get(String.format(GAMIFICATION_USERS_ID, userId));
    }

    /** by: points | levels | badges (null — points) */
    public LeaderboardResponse leaderboard(String by, Integer limit) {
        return leaderboardResponse(by, limit).then().statusCode(200).extract().as(LeaderboardResponse.class);
    }

    public Response leaderboardResponse(String by, Integer limit) {
        Map<String, Object> params = new HashMap<>();
        if (by != null) params.put("by", by);
        if (limit != null) params.put("limit", limit);
        this.response = given().spec(requestSpecification).queryParams(params).get(GAMIFICATION_LEADERBOARD);
        return this.response;
    }
}
