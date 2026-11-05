package kg.jazzlms.controller;

import io.restassured.response.Response;
import kg.jazzlms.http.ApiRequest;
import kg.jazzlms.models.response.TimelinePageResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static kg.jazzlms.constants.Endpoints.*;

/**
 * Analytics service (analytics.url, 8084): только чтение, данные собираются из событий Kafka.
 * Напрямую — только админ; тренер и ученик через gateway получают 403.
 * Большинство ответов — свободные JSON-объекты (в swagger additionalProperties),
 * поэтому возвращаем Response и проверяем через jsonPath.
 */
public class AnalyticsController extends ApiRequest {

    public AnalyticsController(String url) {
        super(url);
    }

    public AnalyticsController(String url, String token) {
        super(url, token);
    }

    /** { today: {logins, courseCompletions, usersWithActivity}, week: {...} }, у каждой метрики value/previous/changePercent */
    public Response summaryResponse() {
        return get(ANALYTICS_SUMMARY);
    }

    /** { topUsers: [...], eventsByType: {USER_CREATED: n, ...} } */
    public Response overviewResponse() {
        return get(ANALYTICS_OVERVIEW);
    }

    /** period: today (по часам, 24 точки), week (7 дней), month; null — today */
    public Response dashboardResponse(String period) {
        return getWithParams(ANALYTICS_DASHBOARD, params("period", period));
    }

    /** [{userId, score}], отсортировано по score по убыванию */
    public Response topUsersResponse(Integer limit) {
        return getWithParams(ANALYTICS_TOP_USERS, params("limit", limit));
    }

    public Response userActivityResponse(String userId, String period) {
        return getWithParams(String.format(ANALYTICS_USER_ACTIVITY, userId), params("period", period));
    }

    public Response courseActivityResponse(String courseId, String period) {
        return getWithParams(String.format(ANALYTICS_COURSE_ACTIVITY, courseId), params("period", period));
    }

    // ---------- лента событий ----------

    /** Любые фильтры: from, to, type, userId, courseId, page, size. null-значения не отправляются */
    public Response timelineResponse(Map<String, Object> filters) {
        return getWithParams(ANALYTICS_TIMELINE, filters);
    }

    public TimelinePageResponse timeline(Map<String, Object> filters) {
        return timelineResponse(filters).then().statusCode(200).extract().as(TimelinePageResponse.class);
    }

    /** События конкретного пользователя (новые сверху) */
    public TimelinePageResponse timelineForUser(UUID userId, String type, int size) {
        return timeline(params("userId", userId, "type", type, "size", size));
    }

    /** CSV: date,event,user,course,description */
    public Response timelineExportResponse(Map<String, Object> filters) {
        return getWithParams(ANALYTICS_TIMELINE_EXPORT, filters);
    }

    // ---------- хелперы ----------

    private Response getWithParams(String endpoint, Map<String, Object> queryParams) {
        this.response = given().spec(requestSpecification).queryParams(queryParams).get(endpoint);
        return this.response;
    }

    /** params("type", "USER_CREATED", "size", 5) -> Map без null-значений */
    public static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }
}
