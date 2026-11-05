import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.constants.ResourceLocks;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.CourseController;
import kg.jazzlms.controller.GamificationController;
import kg.jazzlms.models.response.CertificateResponse;
import kg.jazzlms.models.response.GamificationProfileResponse;
import kg.jazzlms.models.response.GamificationSettings;
import kg.jazzlms.models.response.MyEnrollmentResponse;
import kg.jazzlms.models.response.UnitResponse;
import kg.jazzlms.utils.Fixtures;
import kg.jazzlms.utils.Fixtures.Account;
import kg.jazzlms.utils.Fixtures.CourseWithLessons;
import kg.jazzlms.utils.Fixtures.Session;
import kg.jazzlms.utils.Wait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;

import static kg.jazzlms.utils.Fixtures.PASSWORD;
import static kg.jazzlms.utils.Fixtures.findMyEnrollment;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: начисление баллов gamification за путь студента.
 * Админ создаёт студента и курс с двумя уроками (YouTube-видео), студент логинится, записывается и проходит курс.
 *
 * Каждый тест независимый: сам создаёт своего студента и, если нужно, курс через Fixtures
 * и удаляет всё в @AfterEach. Тесты можно запускать по одному и в любом порядке.
 *
 * Стоимость действий берётся из настроек (Admin -> Gamification -> Points), по умолчанию:
 * login 10, unit 15, course 100, certificate 50 -> весь путь = 10 + 15 + 15 + 100 + 50 = 190.
 * Повторный логин в тот же день и повторное прохождение урока баллов не добавляют.
 */
@Tag("e2e")
@DisplayName("E2E: баллы студента за логин, уроки, курс и сертификат")
@Epic("E2E")
@Feature("Gamification: начисление баллов")
@ResourceLock(value = ResourceLocks.GAMIFICATION_SETTINGS, mode = ResourceAccessMode.READ)
public class GamificationPointsE2ETest {

    private static final String VIDEO_1 = "https://www.youtube.com/watch?v=lH2a0A6NO34";
    private static final String VIDEO_2 = "https://www.youtube.com/watch?v=XtOJZ1T3qw4";

    private final Fixtures fixtures = new Fixtures();
    private final AuthController auth = new AuthController(Endpoints.GATEWAY_URL);
    private final CourseController adminCourses = new CourseController(Endpoints.GATEWAY_URL);
    private final GamificationController gamification = new GamificationController(Endpoints.GAMIFICATION_URL);

    // стоимость действий из настроек
    private int loginPoints, unitPoints, coursePoints, certificatePoints;

    @BeforeEach
    void readPointRules() {
        GamificationSettings.Points points = gamification.getSettings().getPoints();
        assertTrue(points.getEnabled(), "очки должны быть включены (Points ON)");
        loginPoints = points.getLogin().getPoints();
        unitPoints = points.getUnit().getPoints();
        coursePoints = points.getCourse().getPoints();
        certificatePoints = points.getCertificate().getPoints();
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    // ---------------------------------------------------------------- курс

    @Test
    @DisplayName("Админ создаёт курс с 2 уроками YouTube: порядок, ссылки, ссылки для плеера")
    void adminCreatesCourseWithTwoLessons() {
        CourseWithLessons course = fixtures.createCourseWithYoutubeLessons(VIDEO_1, VIDEO_2);
        UnitResponse lesson1 = course.lessons().get(0);
        UnitResponse lesson2 = course.lessons().get(1);

        List<UnitResponse> units = adminCourses.getUnits(course.id());
        assertAll(
                () -> assertNotNull(course.course().getCertificateTemplateId(), "у нового курса шаблон сертификата по умолчанию"),
                () -> assertEquals(2, units.size(), "в курсе 2 урока"),
                () -> assertEquals(List.of(1, 2), units.stream().map(UnitResponse::getPosition).toList(), "порядок уроков"),
                () -> assertEquals("VIDEO", lesson1.getType()),
                () -> assertEquals("YOUTUBE", lesson1.getSourceType()),
                () -> assertEquals(VIDEO_1, lesson1.getYoutubeUrl()),
                () -> assertEquals("https://www.youtube.com/embed/lH2a0A6NO34", lesson1.getEmbedUrl(), "ссылка для плеера"),
                () -> assertEquals(VIDEO_2, lesson2.getYoutubeUrl()),
                () -> assertEquals("https://www.youtube.com/embed/XtOJZ1T3qw4", lesson2.getEmbedUrl())
        );
    }

    // ---------------------------------------------------------------- логин

    @Test
    @DisplayName("У только что созданного студента 0 баллов")
    void newStudentHasZeroPoints() {
        Account student = fixtures.createLearner();

        GamificationProfileResponse profile = gamification.getUserProfile(student.id());

        assertAll(
                () -> assertEquals(0, profile.getPoints(), "points"),
                () -> assertEquals(0, profile.getLogins(), "logins"),
                () -> assertTrue(profile.getBadges().isEmpty(), "бейджей нет"),
                () -> assertTrue(profile.getHistory().isEmpty(), "истории нет")
        );
    }

    @Test
    @DisplayName("Студент логинится -> +10 баллов за логин и бейдж Newcomer")
    void loginGivesPoints() {
        Session student = fixtures.newLearnerSession();

        GamificationProfileResponse profile = waitForPoints(student, loginPoints);

        assertAll(
                () -> assertEquals(loginPoints, profile.getPoints(), "баллы за логин"),
                () -> assertEquals(1, profile.getLogins()),
                () -> assertTrue(hasBadge(profile, "newcomer"), "бейдж Newcomer"),
                () -> assertTrue(hasHistory(profile, "Sign in", loginPoints), "в истории Sign in +" + loginPoints)
        );
    }

    @Test
    @DisplayName("Повторный логин в тот же день баллов не добавляет")
    void secondLoginSameDayGivesNothing() {
        Session student = fixtures.newLearnerSession();
        waitForPoints(student, loginPoints);

        auth.login(student.account().username(), PASSWORD);

        sleepForEvents(); // проверяем, что баллы НЕ выросли — ждать нечего, даём событию время
        assertEquals(loginPoints, gamification.getUserProfile(student.id()).getPoints(), "баллы за логин раз в день");
    }

    // ---------------------------------------------------------------- уроки

    @Test
    @DisplayName("Завершить урок, не открыв его -> 400")
    void cannotCompleteUnopenedLesson() {
        CourseWithLessons course = fixtures.createCourseWithYoutubeLessons(VIDEO_1, VIDEO_2);
        Session student = fixtures.newLearnerSession();
        fixtures.enroll(student, course.id());

        Response response = student.courses().completeUnitResponse(course.lessons().get(0).getId());
        assertEquals(400, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("Пройден урок 1 -> +15 баллов, прогресс 50%, бейдж First step")
    void lessonGivesPoints() {
        CourseWithLessons course = fixtures.createCourseWithYoutubeLessons(VIDEO_1, VIDEO_2);
        Session student = fixtures.newLearnerSession();
        fixtures.enroll(student, course.id());

        UnitResponse passed = student.courses().passUnit(course.lessons().get(0).getId());
        assertTrue(passed.isCompleted(), "урок отмечен пройденным");

        GamificationProfileResponse profile = waitForPoints(student, loginPoints + unitPoints);
        MyEnrollmentResponse enrollment = findMyEnrollment(student, course.id());

        assertAll(
                () -> assertEquals(loginPoints + unitPoints, profile.getPoints(), "логин + урок"),
                () -> assertEquals(1, profile.getUnits(), "units"),
                () -> assertEquals(0, profile.getCourses(), "курс ещё не завершён"),
                () -> assertTrue(hasBadge(profile, "first-step"), "бейдж First step"),
                () -> assertTrue(hasHistory(profile, "Unit completed", unitPoints, "Lesson 1"), "Unit completed: Lesson 1"),
                () -> assertEquals(50, enrollment.getProgress(), "1 из 2 уроков = 50%"),
                () -> assertNull(enrollment.getCompletedAt(), "курс ещё не завершён")
        );
    }

    @Test
    @DisplayName("Повторное прохождение урока баллов не добавляет")
    void repeatLessonGivesNothing() {
        CourseWithLessons course = fixtures.createCourseWithYoutubeLessons(VIDEO_1, VIDEO_2);
        Session student = fixtures.newLearnerSession();
        fixtures.enroll(student, course.id());
        UnitResponse lesson1 = course.lessons().get(0);
        student.courses().passUnit(lesson1.getId());
        waitForPoints(student, loginPoints + unitPoints);

        Response response = student.courses().completeUnitResponse(lesson1.getId());
        assertEquals(200, response.statusCode(), response.asString());

        sleepForEvents();
        assertEquals(loginPoints + unitPoints, gamification.getUserProfile(student.id()).getPoints());
    }

    // ---------------------------------------------------------------- весь курс

    @Test
    @DisplayName("Пройдены оба урока -> курс завершён на 100%, выдан сертификат")
    void bothLessonsCompleteCourse() {
        CourseWithLessons course = fixtures.createCourseWithYoutubeLessons(VIDEO_1, VIDEO_2);
        Session student = fixtures.newLearnerSession();
        fixtures.enroll(student, course.id());

        fixtures.passAllLessons(student, course);

        MyEnrollmentResponse enrollment = Wait.until("курс завершён",
                () -> findMyEnrollment(student, course.id()), e -> e.getCompletedAt() != null);
        List<CertificateResponse> certificates = student.courses().getMyCertificates().stream()
                .filter(c -> course.id().equals(c.getCourseId())).toList();

        assertAll(
                () -> assertEquals(100, enrollment.getProgress()),
                () -> assertEquals(1, certificates.size(), "сертификат за курс"),
                () -> assertEquals(course.course().getName(), certificates.get(0).getCourseName())
        );
    }

    @Test
    @DisplayName("Итог за весь путь: 10 (логин) + 15 + 15 (уроки) + 100 (курс) + 50 (сертификат) = 190 баллов")
    void totalPointsForWholeJourney() {
        CourseWithLessons course = fixtures.createCourseWithYoutubeLessons(VIDEO_1, VIDEO_2);
        Session student = fixtures.newLearnerSession();
        fixtures.enroll(student, course.id());
        fixtures.passAllLessons(student, course);
        int expected = loginPoints + 2 * unitPoints + coursePoints + certificatePoints;

        GamificationProfileResponse profile = waitForPoints(student, expected);

        String courseName = course.course().getName();
        assertAll(
                () -> assertEquals(expected, profile.getPoints(), "итого баллов"),
                () -> assertEquals(1, profile.getLogins(), "logins"),
                () -> assertEquals(2, profile.getUnits(), "units"),
                () -> assertEquals(1, profile.getCourses(), "courses"),
                () -> assertTrue(hasBadge(profile, "graduate"), "бейдж Graduate"),
                () -> assertTrue(hasHistory(profile, "Unit completed", unitPoints, "Lesson 1"), "Unit completed: Lesson 1"),
                () -> assertTrue(hasHistory(profile, "Unit completed", unitPoints, "Lesson 2"), "Unit completed: Lesson 2"),
                () -> assertTrue(hasHistory(profile, "Course completed", coursePoints, courseName), "Course completed"),
                () -> assertTrue(hasHistory(profile, "Certificate", certificatePoints, courseName), "Certificate"),
                () -> assertEquals(expected, profile.getHistory().stream().mapToInt(GamificationProfileResponse.HistoryItem::getPoints).sum(),
                        "сумма по истории = итог")
        );
    }

    // ---------------------------------------------------------------- helpers

    /** Kafka доставляет события с задержкой: ждём, пока баллы дойдут до нужного значения */
    private GamificationProfileResponse waitForPoints(Session student, int expected) {
        return Wait.until("у студента " + expected + " баллов",
                () -> gamification.getUserProfile(student.id()),
                p -> p.getPoints() >= expected);
    }

    /** Для проверок "баллы НЕ начислены" ждать нечего — даём событию время дойти */
    private static void sleepForEvents() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasBadge(GamificationProfileResponse profile, String code) {
        return profile.getBadges().stream().anyMatch(b -> code.equals(b.getCode()));
    }

    private static boolean hasHistory(GamificationProfileResponse profile, String action, int points) {
        return profile.getHistory().stream().anyMatch(h -> action.equals(h.getAction()) && h.getPoints() == points);
    }

    private static boolean hasHistory(GamificationProfileResponse profile, String action, int points, String note) {
        return profile.getHistory().stream()
                .anyMatch(h -> action.equals(h.getAction()) && h.getPoints() == points && note.equals(h.getNote()));
    }
}
