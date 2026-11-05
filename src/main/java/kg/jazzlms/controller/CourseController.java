package kg.jazzlms.controller;

import io.restassured.response.Response;
import kg.jazzlms.http.ApiRequest;
import kg.jazzlms.models.request.CourseRequest;
import kg.jazzlms.models.request.EnrollRequest;
import kg.jazzlms.models.request.UnitRequest;
import kg.jazzlms.models.response.CertificateResponse;
import kg.jazzlms.models.response.CourseResponse;
import kg.jazzlms.models.response.EnrollmentResponse;
import kg.jazzlms.models.response.MyEnrollmentResponse;
import kg.jazzlms.models.response.UnitResponse;
import kg.jazzlms.utils.JsonUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static kg.jazzlms.constants.Endpoints.*;

public class CourseController extends ApiRequest {

    private static final String X_USER_ID = "X-User-Id";

    public CourseController(String url) {
        super(url);
    }

    public CourseController(String url, String token) {
        super(url, token);
    }

    // ---------- create ----------

    public CourseResponse createCourse(CourseRequest request) {
        return createCourseResponse(request).then().statusCode(201).extract().as(CourseResponse.class);
    }

    public Response createCourseResponse(CourseRequest request) {
        return post(COURSES, JsonUtils.toJson(request));
    }

    /**
     * POST с заголовком X-User-Id. Сервис всё равно берёт id из токена,
     * поэтому подставить чужой id так нельзя (на этом есть тест).
     */
    public Response createCourseResponse(CourseRequest request, UUID userId) {
        this.response = given().spec(requestSpecification)
                .header(X_USER_ID, userId.toString())
                .body(JsonUtils.toJson(request))
                .post(COURSES);
        return this.response;
    }

    /** Для битого JSON, неверных типов и пустого тела */
    public Response createCourseRaw(String body) {
        return post(COURSES, body);
    }

    // ---------- read ----------

    public Response getCourseByIdResponse(UUID id) {
        return get(String.format(COURSES_ID, id));
    }

    public CourseResponse getCourseById(UUID id) {
        return getCourseByIdResponse(id).then().statusCode(200).extract().as(CourseResponse.class);
    }

    public List<CourseResponse> getAllCourses() {
        return Arrays.asList(get(COURSES).as(CourseResponse[].class));
    }

    /** GET /api/courses/catalog: [{ "course": {...}, "enrolled": true|false }] */
    public Response catalogResponse() {
        return get(COURSES_CATALOG);
    }

    // ---------- delete ----------

    /** Мягкое удаление: создатель курса или админ */
    public Response deleteCourseResponse(UUID id) {
        return delete(String.format(COURSES_ID, id));
    }

    /** Окончательное удаление из корзины (только после мягкого, только админ) */
    public Response deleteCoursePermanentlyResponse(UUID id) {
        return delete(String.format(COURSES_PERMANENT, id));
    }

    // ---------- enrollments ----------

    /** Самозапись из каталога, роль всегда LEARNER */
    public Response selfEnrollResponse(UUID courseId) {
        return post(String.format(COURSES_SELF_ENROLL, courseId), "");
    }

    /** Запись пользователя на курс (staff) */
    public Response enrollResponse(UUID courseId, UUID userId, String role) {
        return post(String.format(COURSES_ENROLLMENTS, courseId), JsonUtils.toJson(new EnrollRequest(userId, role)));
    }

    public List<EnrollmentResponse> getCourseEnrollments(UUID courseId) {
        return Arrays.asList(get(String.format(COURSES_ENROLLMENTS, courseId))
                .then().statusCode(200).extract().as(EnrollmentResponse[].class));
    }

    /** Курсы текущего пользователя (по токену) */
    public List<MyEnrollmentResponse> getMyEnrollments() {
        return Arrays.asList(get(ENROLLMENTS_MY).then().statusCode(200).extract().as(MyEnrollmentResponse[].class));
    }

    public Response updateProgressResponse(UUID enrollmentId, int progress) {
        return patch(String.format(ENROLLMENTS_PROGRESS, enrollmentId), JsonUtils.toJson(Map.of("progress", progress)));
    }

    public Response unenrollResponse(UUID enrollmentId) {
        return delete(String.format(ENROLLMENTS_ID, enrollmentId));
    }

    // ---------- units (уроки) ----------

    /**
     * POST /api/courses/{courseId}/units — multipart: часть "unit" (JSON) + необязательный "file".
     * Для YouTube-видео файл не нужен, отправляем только JSON-часть.
     */
    public Response createUnitResponse(UUID courseId, UnitRequest unit) {
        this.response = given().spec(requestSpecification)
                .contentType("multipart/form-data")
                .multiPart("unit", JsonUtils.toJson(unit), "application/json")
                .post(String.format(COURSES_UNITS, courseId));
        return this.response;
    }

    public UnitResponse createUnit(UUID courseId, UnitRequest unit) {
        return createUnitResponse(courseId, unit).then().statusCode(201).extract().as(UnitResponse.class);
    }

    /** Уроки курса по порядку; completed/locked — для владельца токена */
    public List<UnitResponse> getUnits(UUID courseId) {
        return Arrays.asList(get(String.format(COURSES_UNITS, courseId))
                .then().statusCode(200).extract().as(UnitResponse[].class));
    }

    /** "Открыть урок" — без этого complete вернёт 400 "Open the unit first" */
    public Response startUnitResponse(UUID unitId) {
        return post(String.format(UNITS_START, unitId), "");
    }

    /** Завершить урок (CHECKBOX — пустое тело; QUESTION — {"answer": "..."}) */
    public Response completeUnitResponse(UUID unitId) {
        return post(String.format(UNITS_COMPLETE, unitId), "{}");
    }

    /** Открыть и сразу завершить урок, как делает ученик в UI */
    public UnitResponse passUnit(UUID unitId) {
        startUnitResponse(unitId).then().statusCode(200);
        return completeUnitResponse(unitId).then().statusCode(200).extract().as(UnitResponse.class);
    }

    // ---------- certificates ----------

    public List<CertificateResponse> getMyCertificates() {
        return Arrays.asList(get(CERTIFICATES_MY).then().statusCode(200).extract().as(CertificateResponse[].class));
    }

    // ---------- categories (нужны для categoryId) ----------

    /** id любой существующей категории; если категорий нет — создаёт новую */
    public UUID getOrCreateCategoryId() {
        List<String> ids = get(CATEGORIES).then().statusCode(200).extract().jsonPath().getList("id");
        if (ids != null && !ids.isEmpty()) {
            return UUID.fromString(ids.get(0));
        }
        String body = JsonUtils.toJson(Map.of("name", "Auto category " + UUID.randomUUID().toString().substring(0, 8)));
        String id = post(CATEGORIES, body).then().statusCode(201).extract().path("id");
        return UUID.fromString(id);
    }
}
