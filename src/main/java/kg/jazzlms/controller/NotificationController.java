package kg.jazzlms.controller;

import io.restassured.response.Response;
import kg.jazzlms.http.ApiRequest;
import kg.jazzlms.models.request.NotificationRuleRequest;
import kg.jazzlms.models.response.NotificationMessageResponse;
import kg.jazzlms.models.response.NotificationRuleResponse;
import kg.jazzlms.utils.JsonUtils;

import java.util.Arrays;
import java.util.List;

import static kg.jazzlms.constants.Endpoints.*;

/**
 * Notification service (notification.url, 8083): правила рассылки + журнал сообщений.
 * Напрямую — только админ; тренер и ученик через gateway получают 403.
 */
public class NotificationController extends ApiRequest {

    public NotificationController(String url) {
        super(url);
    }

    public NotificationController(String url, String token) {
        super(url, token);
    }

    // ---------- правила: create ----------

    public NotificationRuleResponse createRule(NotificationRuleRequest request) {
        return createRuleResponse(request).then().statusCode(201).extract().as(NotificationRuleResponse.class);
    }

    public Response createRuleResponse(NotificationRuleRequest request) {
        return post(NOTIFICATIONS, JsonUtils.toJson(request));
    }

    /** Для битого JSON, неверных типов и пустого тела */
    public Response createRuleRaw(String body) {
        return post(NOTIFICATIONS, body);
    }

    // ---------- правила: read ----------

    public List<NotificationRuleResponse> getAllRules() {
        return Arrays.asList(getAllRulesResponse().then().statusCode(200).extract().as(NotificationRuleResponse[].class));
    }

    public Response getAllRulesResponse() {
        return get(NOTIFICATIONS);
    }

    public NotificationRuleResponse getRuleById(String id) {
        return getRuleByIdResponse(id).then().statusCode(200).extract().as(NotificationRuleResponse.class);
    }

    public Response getRuleByIdResponse(String id) {
        return get(String.format(NOTIFICATIONS_ID, id));
    }

    // ---------- правила: update / delete ----------

    /** PUT — полная замена правила */
    public Response updateRuleResponse(String id, NotificationRuleRequest request) {
        return put(String.format(NOTIFICATIONS_ID, id), JsonUtils.toJson(request));
    }

    public NotificationRuleResponse updateRule(String id, NotificationRuleRequest request) {
        return updateRuleResponse(id, request).then().statusCode(200).extract().as(NotificationRuleResponse.class);
    }

    public Response deleteRuleResponse(String id) {
        return delete(String.format(NOTIFICATIONS_ID, id));
    }

    // ---------- справочники ----------

    public List<String> getEventTypes() {
        return Arrays.asList(get(NOTIFICATIONS_EVENTS).then().statusCode(200).extract().as(String[].class));
    }

    public List<String> getRecipients() {
        return Arrays.asList(get(NOTIFICATIONS_RECIPIENTS).then().statusCode(200).extract().as(String[].class));
    }

    // ---------- журнал сообщений ----------

    /** Отложенные сообщения (delayMinutes > 0), ещё не отправленные */
    public List<NotificationMessageResponse> getPending() {
        return Arrays.asList(get(NOTIFICATIONS_PENDING).then().statusCode(200).extract().as(NotificationMessageResponse[].class));
    }

    /** Отправленные сообщения, новые сверху */
    public List<NotificationMessageResponse> getHistory() {
        return Arrays.asList(get(NOTIFICATIONS_HISTORY).then().statusCode(200).extract().as(NotificationMessageResponse[].class));
    }

    /** Удаляет одно сообщение из pending/history */
    public Response deleteMessageResponse(String id) {
        return delete(String.format(NOTIFICATIONS_MESSAGES_ID, id));
    }

    // Очистка всей истории (DELETE /api/notifications/history) намеренно не вынесена в контроллер:
    // она стирает журнал на общем стенде и ломает чужие проверки.
}
