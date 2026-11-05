import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.constants.ResourceLocks;
import kg.jazzlms.controller.AnalyticsController;
import kg.jazzlms.controller.GamificationController;
import kg.jazzlms.controller.NotificationController;
import kg.jazzlms.models.response.ActivityEventResponse;
import kg.jazzlms.models.response.CourseResponse;
import kg.jazzlms.models.response.EnrollmentResponse;
import kg.jazzlms.models.response.GamificationProfileResponse;
import kg.jazzlms.models.response.GamificationSettings;
import kg.jazzlms.models.response.LeaderboardResponse;
import kg.jazzlms.models.response.NotificationMessageResponse;
import kg.jazzlms.models.response.NotificationRuleResponse;
import kg.jazzlms.models.response.TimelinePageResponse;
import kg.jazzlms.utils.Fixtures;
import kg.jazzlms.utils.Fixtures.Session;
import kg.jazzlms.utils.Wait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static kg.jazzlms.controller.AnalyticsController.params;
import static kg.jazzlms.utils.EntityManager.notificationRuleBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Сквозные сценарии курса: тренер создаёт курс -> ученик записывается -> проходит курс на 100%.
 * Проверяем, что события course service (COURSE_CREATED, USER_ENROLLED, COURSE_COMPLETED)
 * через Kafka доходят до notification, analytics и gamification.
 *
 * Каждый тест независимый: сам проходит нужную часть пути через Fixtures (свой тренер, ученик, курс)
 * и удаляет всё в @AfterEach. Тесты можно запускать по одному и в любом порядке.
 *
 * Стандартное правило "Course completion" на стенде выключено, поэтому тест на письмо о завершении
 * создаёт своё правило COURSE_COMPLETED -> COURSE_INSTRUCTORS. Очки берём из настроек gamification.
 */
@Tag("e2e")
@DisplayName("E2E: путь ученика по курсу -> notification + analytics + gamification")
@Epic("E2E")
@Feature("Course -> Notification + Analytics + Gamification")
@ResourceLock(value = ResourceLocks.GAMIFICATION_SETTINGS, mode = ResourceAccessMode.READ)
public class CourseJourneyE2ETest {

    private final Fixtures fixtures = new Fixtures();
    private final NotificationController notifications = new NotificationController(Endpoints.NOTIFICATION_URL);
    private final AnalyticsController analytics = new AnalyticsController(Endpoints.ANALYTICS_URL);
    private final GamificationController adminGamification = new GamificationController(Endpoints.GAMIFICATION_URL);

    /** Результат подготовки: тренер, его курс, записанный ученик */
    private record Journey(Session trainer, Session learner, CourseResponse course, UUID enrollmentId) {
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    // ---------------------------------------------------------------- notification

    @Test
    @DisplayName("Notification: при записи ученику уходит письмо с названием курса")
    void learnerGetsEnrollmentEmail() {
        Journey journey = enrolledLearner();

        NotificationMessageResponse message = Wait.until("письмо о записи ученику",
                () -> findMessage(journey.learner().account().email(), "USER_ENROLLED"),
                Optional::isPresent).orElseThrow();

        assertAll(
                () -> assertEquals("SENT", message.getStatus()),
                () -> assertTrue(message.getSubject().contains(journey.course().getName()), "{{courseName}} в теме: " + message.getSubject()),
                () -> assertTrue(message.getBody().contains(journey.learner().user().getFirstName()), "{{firstName}} в тексте: " + message.getBody())
        );
    }

    // ПАДАЕТ (баг бэка): получатель COURSE_INSTRUCTORS не раскрывается в email преподавателей.
    // Сообщение создаётся и помечается SENT, но уходит на заглушку "instructors-of-<courseId>@local",
    // хотя course service знает преподавателя (GET /api/courses/{id}/enrollments -> INSTRUCTOR с email).
    // Тема и текст при этом подставляются правильно — эти проверки проходят.
    @Test
    @DisplayName("Notification: письмо о завершении курса по правилу COURSE_INSTRUCTORS уходит тренеру")
    void trainerGetsCompletionEmail() {
        NotificationRuleResponse rule = fixtures.createRule(notificationRuleBuilder()
                .eventType("COURSE_COMPLETED")
                .recipient("COURSE_INSTRUCTORS")
                .subjectTemplate("Course finished: {{courseName}} [" + UUID.randomUUID().toString().substring(0, 8) + "]")
                .bodyTemplate("{{firstName}} {{lastName}} completed {{courseName}}.")
                .build());
        Journey journey = completedCourse();

        // ждём сообщение по нашему правилу и нашему курсу, кому бы оно ни ушло: так видно, что событие дошло,
        // а ошибка — в адресе. Фильтр по курсу обязателен: правило срабатывает на ЛЮБОЙ завершённый курс стенда,
        // а параллельно другие тесты тоже завершают курсы.
        String courseName = journey.course().getName();
        NotificationMessageResponse message = Wait.until("сообщение по правилу о завершении нашего курса",
                () -> notifications.getHistory().stream()
                        .filter(m -> rule.getId().equals(m.getRuleId()))
                        .filter(m -> m.getSubject() != null && m.getSubject().contains(courseName))
                        .findFirst(),
                Optional::isPresent).orElseThrow();

        String expectedSubject = rule.getSubjectTemplate().replace("{{courseName}}", courseName);
        String expectedBody = journey.learner().account().fullName() + " completed " + courseName + ".";

        assertAll(
                () -> assertEquals("COURSE_COMPLETED", message.getEventType()),
                () -> assertEquals("SENT", message.getStatus()),
                () -> assertEquals(expectedSubject, message.getSubject(), "тема"),
                () -> assertEquals(expectedBody, message.getBody(), "в тексте имя УЧЕНИКА, а письмо — тренеру"),
                () -> assertEquals(journey.trainer().account().email(), message.getRecipientEmail(), "recipientEmail = email тренера")
        );
    }

    // ---------------------------------------------------------------- analytics

    @Test
    @DisplayName("Analytics: в ленте курса COURSE_CREATED, USER_ENROLLED, COURSE_COMPLETED")
    void analyticsHasCourseEvents() {
        Journey journey = completedCourse();
        UUID courseId = journey.course().getId();

        TimelinePageResponse page = Wait.until("три события курса в ленте",
                () -> analytics.timeline(params("courseId", courseId, "size", 20)),
                p -> event(p, "COURSE_CREATED").isPresent()
                        && event(p, "USER_ENROLLED").isPresent()
                        && event(p, "COURSE_COMPLETED").isPresent());

        ActivityEventResponse created = event(page, "COURSE_CREATED").orElseThrow();
        ActivityEventResponse completed = event(page, "COURSE_COMPLETED").orElseThrow();
        assertAll(
                () -> assertEquals(journey.trainer().id().toString(), created.getUserId(), "курс создал тренер"),
                () -> assertEquals("course-service", completed.getSource()),
                () -> assertEquals(journey.learner().id().toString(), completed.getUserId(), "курс завершил ученик"),
                () -> assertEquals(courseId.toString(), completed.getPayload().get("courseId")),
                () -> assertEquals(journey.enrollmentId().toString(), completed.getPayload().get("enrollmentId")),
                () -> assertEquals("100", completed.getPayload().get("progress")),
                () -> assertEquals(journey.course().getName(), completed.getPayload().get("courseName"))
        );
    }

    @Test
    @DisplayName("Analytics: активность курса за неделю — одно завершение")
    void analyticsCourseActivity() {
        Journey journey = completedCourse();

        Response response = Wait.until("завершение в активности курса",
                () -> analytics.courseActivityResponse(journey.course().getId().toString(), "week"),
                r -> r.statusCode() == 200 && sum(r.jsonPath().getList("completions", Integer.class)) >= 1);

        assertEquals(1, sum(response.jsonPath().getList("completions", Integer.class)));
    }

    // ---------------------------------------------------------------- gamification

    @Test
    @DisplayName("Gamification: ученику очки за курс, courses = 1, бейдж Graduate")
    void learnerGetsCoursePoints() {
        GamificationSettings.Points points = adminGamification.getSettings().getPoints();
        int expected = points.getLogin().getPoints() + points.getCourse().getPoints();
        Journey journey = completedCourse();

        GamificationProfileResponse profile = Wait.until("очки за завершение курса",
                () -> adminGamification.getUserProfile(journey.learner().id()),
                p -> p.getCourses() >= 1 && p.getPoints() >= expected);

        assertAll(
                () -> assertEquals(expected, profile.getPoints(), "логин + курс (курс без сертификата)"),
                () -> assertEquals(1, profile.getCourses(), "courses"),
                () -> assertTrue(profile.getBadges().stream().anyMatch(b -> "graduate".equals(b.getCode())), "бейдж graduate"),
                () -> assertTrue(profile.getHistory().stream().anyMatch(h -> "Course completed".equals(h.getAction())
                        && h.getPoints() == points.getCourse().getPoints()
                        && journey.course().getName().equals(h.getNote())), "в истории Course completed с названием курса")
        );
    }

    @Test
    @DisplayName("Gamification: тренер очков за курс не получает")
    void trainerGetsNoCoursePoints() {
        Journey journey = completedCourse();
        // ждём, пока завершение обработано у ученика — значит, и у тренера уже ничего не изменится
        Wait.until("курс обработан", () -> adminGamification.getUserProfile(journey.learner().id()), p -> p.getCourses() >= 1);

        GamificationProfileResponse profile = adminGamification.getUserProfile(journey.trainer().id());
        assertAll(
                () -> assertEquals(0, profile.getCourses()),
                () -> assertTrue(profile.getHistory().stream().noneMatch(h -> "Course completed".equals(h.getAction())))
        );
    }

    @Test
    @DisplayName("Gamification: место и очки ученика в лидерборде совпадают с профилем")
    void leaderboardReflectsPoints() {
        Journey journey = completedCourse();
        Wait.until("курс обработан", () -> adminGamification.getUserProfile(journey.learner().id()), p -> p.getCourses() >= 1);

        GamificationProfileResponse profile = journey.learner().gamification().me();
        LeaderboardResponse board = journey.learner().gamification().leaderboard("points", 5);

        assertAll(
                () -> assertEquals(journey.learner().id(), board.getMe().getUserId()),
                () -> assertEquals(profile.getPoints(), board.getMe().getScore(), "score = points"),
                () -> assertEquals(profile.getRank(), board.getMe().getRank(), "rank совпадает")
        );
    }

    // ---------------------------------------------------------------- подготовка

    /** Тренер создаёт курс, ученик логинится и записывается */
    private Journey enrolledLearner() {
        Session trainer = fixtures.newTrainerSession();
        CourseResponse course = fixtures.createCourseAs(trainer);
        Session learner = fixtures.newLearnerSession();
        EnrollmentResponse enrollment = fixtures.enroll(learner, course.getId());
        return new Journey(trainer, learner, course, enrollment.getId());
    }

    /** То же + ученик проходит курс на 100% */
    private Journey completedCourse() {
        Journey journey = enrolledLearner();
        EnrollmentResponse done = journey.learner().courses().updateProgressResponse(journey.enrollmentId(), 100)
                .then().statusCode(200).extract().as(EnrollmentResponse.class);
        assertNotNull(done.getCompletedAt(), "курс завершён");
        return journey;
    }

    // ---------------------------------------------------------------- helpers

    private Optional<NotificationMessageResponse> findMessage(String email, String eventType) {
        return notifications.getHistory().stream()
                .filter(m -> email.equalsIgnoreCase(m.getRecipientEmail()))
                .filter(m -> eventType.equals(m.getEventType()))
                .findFirst();
    }

    private static Optional<ActivityEventResponse> event(TimelinePageResponse page, String type) {
        return page.getItems().stream().filter(e -> type.equals(e.getType())).findFirst();
    }

    private static int sum(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).sum();
    }
}
