import kg.jazzlms.constants.ResourceLocks;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.CourseController;
import kg.jazzlms.controller.GamificationController;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.CertificateResponse;
import kg.jazzlms.models.response.CourseResponse;
import kg.jazzlms.models.response.GamificationProfileResponse;
import kg.jazzlms.models.response.GamificationSettings;
import kg.jazzlms.models.response.LeaderboardResponse;
import kg.jazzlms.models.response.MyEnrollmentResponse;
import kg.jazzlms.models.response.UnitResponse;
import kg.jazzlms.models.response.UserResponse;
import kg.jazzlms.utils.Wait;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static kg.jazzlms.utils.EntityManager.courseBuilder;
import static kg.jazzlms.utils.EntityManager.userBuilder;
import static kg.jazzlms.utils.EntityManager.youtubeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: переключатель POINTS выключен (OFF) -> студенту не начисляются баллы за действия.
 *
 * Каждый тест независимый: сам создаёт своего студента и, если нужно, свой курс с двумя YouTube-уроками,
 * поэтому тесты можно запускать по одному и в любом порядке. Всё созданное тестом удаляется в @AfterEach.
 *
 * Общее для всех тестов — только состояние стенда: POINTS выключается один раз в @BeforeAll
 * и обязательно возвращается в исходное в @AfterAll (настройки gamification глобальные).
 * Блок points отправляем целиком: PUT заменяет вложенный блок полностью (см. GamificationSettings).
 *
 * Ожидаемое поведение при POINTS = OFF:
 *  - баллы не начисляются ни за что: логин, уроки, курс, сертификат -> всегда 0;
 *  - активность считается: logins / units / courses растут, в истории действия с 0 баллов;
 *  - бейджи выдаются (у них свой переключатель BADGES), сертификат за курс тоже выдаётся;
 *  - после включения POINTS баллы задним числом не начисляются.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("E2E: POINTS = OFF -> студенту не начисляются баллы за действия")
@Epic("E2E")
@Feature("Gamification: начисление баллов")
@ResourceLock(value = ResourceLocks.GAMIFICATION_SETTINGS, mode = ResourceAccessMode.READ_WRITE)
public class GamificationPointsOffE2ETest {

    private static final String PASSWORD = "Pass123!";
    private static final String VIDEO_1 = "https://www.youtube.com/watch?v=lH2a0A6NO34";
    private static final String VIDEO_2 = "https://www.youtube.com/watch?v=XtOJZ1T3qw4";

    private final AuthController auth = new AuthController(Endpoints.GATEWAY_URL);
    private final UserController adminUsers = new UserController(Endpoints.GATEWAY_URL);
    private final CourseController adminCourses = new CourseController(Endpoints.GATEWAY_URL);
    private final GamificationController gamification = new GamificationController(Endpoints.GAMIFICATION_URL);

    private GamificationSettings originalSettings;

    // что создал текущий тест — удаляем после каждого теста
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdCourses = new ArrayList<>();

    /** Студент, вошедший в систему: его данные и клиенты от его имени */
    private record Student(UserResponse user, CourseController courses, GamificationController gamification) {
        UUID id() {
            return user.getId();
        }
    }

    /** Только что созданный пользователь: логин нужен, чтобы войти от его имени */
    private record CreatedUser(String username, UserResponse user) {
    }

    /** Курс с двумя уроками-видео */
    private record CourseWithLessons(CourseResponse course, UnitResponse lesson1, UnitResponse lesson2) {
        UUID id() {
            return course.getId();
        }
    }

    // ---------------------------------------------------------------- состояние стенда

    @BeforeAll
    void turnPointsOff() {
        originalSettings = gamification.getSettings();
        gamification.updateSettings(pointsSwitch(false));
    }

    @AfterAll
    void restoreSettings() {
        // даже если тесты упали, стенд должен остаться с исходными настройками
        gamification.updateSettingsResponse(originalSettings);
    }

    @AfterEach
    void deleteCreatedData() {
        createdCourses.forEach(id -> {
            adminCourses.deleteCourseResponse(id);
            adminCourses.deleteCoursePermanentlyResponse(id);
        });
        createdUsers.forEach(adminUsers::deleteUserResponse);
        createdCourses.clear();
        createdUsers.clear();
    }

    // ---------------------------------------------------------------- тесты

    @Test
    @DisplayName("POINTS выключен, стоимость действий сохранена, UI получает points = false")
    void pointsAreOffAndRuleValuesKept() {
        GamificationSettings settings = gamification.getSettings();

        assertAll(
                () -> assertFalse(settings.getPoints().getEnabled(), "points.enabled = false"),
                () -> assertEquals(originalSettings.getPoints().getLogin(), settings.getPoints().getLogin(), "login не тронут"),
                () -> assertEquals(originalSettings.getPoints().getUnit(), settings.getPoints().getUnit(), "unit не тронут"),
                () -> assertEquals(originalSettings.getPoints().getCourse(), settings.getPoints().getCourse(), "course не тронут"),
                () -> assertEquals(originalSettings.getPoints().getCertificate(), settings.getPoints().getCertificate(), "certificate не тронут"),
                () -> assertFalse(gamification.configResponse().jsonPath().getBoolean("points"), "config: points = false")
        );
    }

    @Test
    @DisplayName("Новый студент: 0 баллов, нет логинов")
    void newStudentHasZeroPoints() {
        UserResponse user = createStudentUser().user();

        GamificationProfileResponse profile = gamification.getUserProfile(user.getId());

        assertAll(
                () -> assertEquals(0, profile.getPoints()),
                () -> assertEquals(0, profile.getLogins()),
                () -> assertTrue(profile.getHistory().isEmpty())
        );
    }

    @Test
    @DisplayName("Логин засчитан, но 0 баллов; бейдж Newcomer выдан")
    void loginGivesNoPoints() {
        Student student = createAndLogInStudent();

        // ждём не баллы (их не будет), а сам факт обработки события логина
        GamificationProfileResponse profile = waitForProfile(student, "логин обработан", p -> p.getLogins() >= 1);

        assertAll(
                () -> assertEquals(0, profile.getPoints(), "баллов за логин нет"),
                () -> assertEquals(1, profile.getLogins(), "логин засчитан"),
                () -> assertTrue(hasHistory(profile, "Sign in"), "Sign in в истории"),
                () -> assertTrue(allHistoryZero(profile), "в истории всё по 0 баллов: " + profile.getHistory()),
                () -> assertTrue(hasBadge(profile, "newcomer"), "бейджи работают отдельно от баллов")
        );
    }

    @Test
    @DisplayName("Пройденный урок засчитан, прогресс 50%, но 0 баллов")
    void lessonGivesNoPoints() {
        CourseWithLessons course = createCourseWithTwoLessons();
        Student student = createAndLogInStudent();
        enroll(student, course);

        student.courses().passUnit(course.lesson1().getId());

        GamificationProfileResponse profile = waitForProfile(student, "урок обработан", p -> p.getUnits() >= 1);

        assertAll(
                () -> assertEquals(0, profile.getPoints(), "баллов за урок нет"),
                () -> assertEquals(1, profile.getUnits(), "урок засчитан"),
                () -> assertTrue(hasHistory(profile, "Unit completed"), "Unit completed в истории"),
                () -> assertTrue(allHistoryZero(profile), "в истории всё по 0 баллов"),
                () -> assertEquals(50, myEnrollment(student, course).getProgress(), "прогресс считается как обычно")
        );
    }

    @Test
    @DisplayName("Курс из 2 уроков завершён, сертификат выдан, но итого 0 баллов")
    void courseAndCertificateGiveNoPoints() {
        CourseWithLessons course = createCourseWithTwoLessons();
        Student student = createAndLogInStudent();
        enroll(student, course);

        passWholeCourse(student, course);

        MyEnrollmentResponse enrollment = Wait.until("курс завершён",
                () -> myEnrollment(student, course), e -> e.getCompletedAt() != null);
        List<CertificateResponse> certificates = student.courses().getMyCertificates().stream()
                .filter(c -> course.id().equals(c.getCourseId())).toList();
        GamificationProfileResponse profile = waitForProfile(student, "завершение курса и сертификат обработаны",
                p -> p.getCourses() >= 1 && hasHistory(p, "Certificate"));

        assertAll(
                () -> assertEquals(100, enrollment.getProgress()),
                () -> assertEquals(1, certificates.size(), "сертификат выдаётся и при выключенных баллах"),
                () -> assertEquals(0, profile.getPoints(), "итого 0 баллов"),
                () -> assertEquals(1, profile.getLogins(), "logins"),
                () -> assertEquals(2, profile.getUnits(), "units"),
                () -> assertEquals(1, profile.getCourses(), "courses"),
                () -> assertTrue(hasHistory(profile, "Course completed"), "Course completed в истории"),
                () -> assertTrue(allHistoryZero(profile), "все записи истории по 0 баллов: " + profile.getHistory()),
                () -> assertTrue(hasBadge(profile, "graduate"), "бейдж Graduate выдан")
        );
    }

    @Test
    @DisplayName("Студент видит 0 баллов в своём профиле и в лидерборде")
    void studentSeesZeroPoints() {
        Student student = createAndLogInStudent();
        waitForProfile(student, "логин обработан", p -> p.getLogins() >= 1);

        GamificationProfileResponse me = student.gamification().me();
        LeaderboardResponse board = student.gamification().leaderboard("points", 5);

        assertAll(
                () -> assertEquals(0, me.getPoints(), "/me"),
                () -> assertEquals(student.id(), board.getMe().getUserId()),
                () -> assertEquals(0, board.getMe().getScore(), "score в лидерборде")
        );
    }

    @Test
    @DisplayName("После включения POINTS баллы за прошлые действия задним числом не начисляются")
    void turningPointsOnIsNotRetroactive() {
        CourseWithLessons course = createCourseWithTwoLessons();
        Student student = createAndLogInStudent();
        enroll(student, course);
        passWholeCourse(student, course);
        waitForProfile(student, "курс обработан при OFF", p -> p.getCourses() >= 1 && hasHistory(p, "Certificate"));

        try {
            gamification.updateSettings(pointsSwitch(true));
            sleepForEvents(); // проверяем, что ничего НЕ пересчиталось — ждать нечего, даём время

            GamificationProfileResponse profile = gamification.getUserProfile(student.id());
            assertAll(
                    () -> assertEquals(0, profile.getPoints(), "старые действия не дают баллов после включения"),
                    () -> assertEquals(2, profile.getUnits()),
                    () -> assertEquals(1, profile.getCourses())
            );
        } finally {
            // возвращаем OFF, чтобы остальные тесты класса работали в своём режиме при любом порядке
            gamification.updateSettings(pointsSwitch(false));
        }
    }

    // ---------------------------------------------------------------- подготовка данных

    /** Админ создаёт активного студента (без логина) */
    private CreatedUser createStudentUser() {
        UserRequest request = userBuilder().password(PASSWORD).userType("LEARNER").active(true).build();
        Response response = adminUsers.createUserResponse(request);
        assertEquals(201, response.statusCode(), response.asString());
        UserResponse user = response.as(UserResponse.class);
        createdUsers.add(user.getId());
        return new CreatedUser(request.getUsername(), user);
    }

    /** Админ создаёт студента, студент логинится через gateway */
    private Student createAndLogInStudent() {
        CreatedUser created = createStudentUser();
        String token = auth.login(created.username(), PASSWORD).getToken();
        return new Student(created.user(),
                new CourseController(Endpoints.GATEWAY_URL, token),
                new GamificationController(Endpoints.GATEWAY_URL, token));
    }

    /** Админ создаёт курс (с сертификатом по умолчанию) и два урока-видео YouTube */
    private CourseWithLessons createCourseWithTwoLessons() {
        CourseResponse course = adminCourses.createCourse(courseBuilder().active(true).hiddenFromCatalog(false).build());
        createdCourses.add(course.getId());
        UnitResponse lesson1 = adminCourses.createUnit(course.getId(), youtubeUnit("Lesson 1", VIDEO_1));
        UnitResponse lesson2 = adminCourses.createUnit(course.getId(), youtubeUnit("Lesson 2", VIDEO_2));
        return new CourseWithLessons(course, lesson1, lesson2);
    }

    private static void enroll(Student student, CourseWithLessons course) {
        Response response = student.courses().selfEnrollResponse(course.id());
        assertEquals(201, response.statusCode(), response.asString());
    }

    private static void passWholeCourse(Student student, CourseWithLessons course) {
        student.courses().passUnit(course.lesson1().getId());
        student.courses().passUnit(course.lesson2().getId());
    }

    // ---------------------------------------------------------------- helpers

    /** Блок points целиком из исходных настроек, меняем только переключатель */
    private GamificationSettings pointsSwitch(boolean enabled) {
        GamificationSettings.Points p = originalSettings.getPoints();
        GamificationSettings.Points points = GamificationSettings.Points.builder()
                .enabled(enabled)
                .login(p.getLogin())
                .unit(p.getUnit())
                .course(p.getCourse())
                .certificate(p.getCertificate())
                .test(p.getTest())
                .build();
        return GamificationSettings.builder().points(points).build();
    }

    private GamificationProfileResponse waitForProfile(Student student, String what,
                                                       Predicate<GamificationProfileResponse> condition) {
        return Wait.until(what, () -> gamification.getUserProfile(student.id()), condition);
    }

    private static MyEnrollmentResponse myEnrollment(Student student, CourseWithLessons course) {
        return student.courses().getMyEnrollments().stream()
                .filter(e -> e.getCourse() != null && course.id().equals(e.getCourse().getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("курса нет в 'Моих курсах'"));
    }

    private static boolean hasHistory(GamificationProfileResponse profile, String action) {
        return profile.getHistory().stream().anyMatch(h -> action.equals(h.getAction()));
    }

    private static boolean allHistoryZero(GamificationProfileResponse profile) {
        return profile.getHistory().stream().allMatch(h -> h.getPoints() == 0);
    }

    private static boolean hasBadge(GamificationProfileResponse profile, String code) {
        return profile.getBadges().stream().anyMatch(b -> code.equals(b.getCode()));
    }

    private static void sleepForEvents() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
