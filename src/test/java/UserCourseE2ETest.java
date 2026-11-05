import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.CourseController;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.CourseRequest;
import kg.jazzlms.models.response.CourseResponse;
import kg.jazzlms.models.response.EnrollmentResponse;
import kg.jazzlms.models.response.MyEnrollmentResponse;
import kg.jazzlms.utils.Fixtures;
import kg.jazzlms.utils.Fixtures.Account;
import kg.jazzlms.utils.Fixtures.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static kg.jazzlms.utils.EntityManager.courseBuilder;
import static kg.jazzlms.utils.Fixtures.PASSWORD;
import static kg.jazzlms.utils.Fixtures.findMyEnrollment;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: user-service + course-service через API gateway (8080), как работает фронт.
 *
 * Каждый тест независимый: сам создаёт тренера, учеников и курс через Fixtures и удаляет их в @AfterEach.
 * Тесты можно запускать по одному и в любом порядке.
 *
 * Покрыто: логин и /me, неактивный пользователь, ролевые ограничения gateway и сервисов,
 * создание курса тренером (createdBy из токена), автоматический INSTRUCTOR,
 * каталог и самозапись (данные пользователя по gRPC), 409 на повтор и на полный курс,
 * 404 для неактивного/несуществующего, learnersCount, "Мои курсы", прогресс до 100%,
 * деактивация, отписка, удаление курса.
 */
@Tag("e2e")
@DisplayName("E2E: user service + course service")
@Epic("E2E")
@Feature("User + Course")
public class UserCourseE2ETest {

    private final Fixtures fixtures = new Fixtures();
    private final AuthController auth = new AuthController(Endpoints.GATEWAY_URL);
    private final UserController adminUsers = new UserController(Endpoints.GATEWAY_URL);
    private final CourseController adminCourses = new CourseController(Endpoints.GATEWAY_URL);

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    // ---------------------------------------------------------------- user service

    @Test
    @DisplayName("Админ создаёт тренера и ученика с нужными ролями")
    void adminCreatesUsers() {
        Account trainer = fixtures.createTrainer();
        Account learner = fixtures.createLearner();
        Account inactive = fixtures.createUser("LEARNER", false);

        assertAll(
                () -> assertEquals("TRAINER", trainer.user().getUserType()),
                () -> assertEquals("LEARNER", learner.user().getUserType()),
                () -> assertTrue(learner.user().isActive()),
                () -> assertFalse(inactive.user().isActive())
        );
    }

    @Test
    @DisplayName("Тренер и ученик логинятся, /me возвращает их же")
    void usersLogIn() {
        Session trainer = fixtures.newTrainerSession();
        Session learner = fixtures.newLearnerSession();

        assertEquals(trainer.id(), auth.me(trainer.token()).getId());
        assertEquals(learner.id(), auth.me(learner.token()).getId());
    }

    @Test
    @DisplayName("Неактивный пользователь не может войти")
    void inactiveUserCannotLogIn() {
        Account inactive = fixtures.createUser("LEARNER", false);

        Response response = auth.loginResponse(inactive.username(), PASSWORD);
        assertEquals(401, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("Деактивированный админом пользователь больше не может войти")
    void deactivatedUserCannotLogIn() {
        Account learner = fixtures.createLearner();
        assertEquals(200, auth.loginResponse(learner.username(), PASSWORD).statusCode(), "до деактивации вход есть");

        Response update = adminUsers.updateUserResponse(learner.id(), Map.of("active", false));
        assertEquals(200, update.statusCode(), update.asString());

        Response login = auth.loginResponse(learner.username(), PASSWORD);
        assertEquals(401, login.statusCode(), login.asString());
    }

    // ---------------------------------------------------------------- ограничения по ролям

    @Test
    @DisplayName("Ученик не может создать курс (gateway -> 403)")
    void learnerCannotCreateCourse() {
        Session learner = fixtures.newLearnerSession();

        Response response = learner.courses().createCourseResponse(courseBuilder().build());
        assertEquals(403, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("Ученик не может ходить в course-service напрямую, мимо gateway (401)")
    void learnerCannotCallCourseServiceDirectly() {
        Session learner = fixtures.newLearnerSession();

        CourseController direct = new CourseController(Endpoints.COURSE_URL, learner.token());
        Response response = direct.get(Endpoints.COURSES);
        assertEquals(401, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("Ученик не может удалить курс (gateway -> 403)")
    void learnerCannotDeleteCourse() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        Session learner = fixtures.newLearnerSession();

        Response response = learner.courses().deleteCourseResponse(course.getId());
        assertEquals(403, response.statusCode(), response.asString());
    }

    // ---------------------------------------------------------------- курс

    @Test
    @DisplayName("Тренер создаёт курс; createdBy берётся из токена, а не из X-User-Id")
    void trainerCreatesCourse() {
        Session trainer = fixtures.newTrainerSession();
        Account learner = fixtures.createLearner();
        CourseRequest request = courseBuilder()
                .description("E2E course")
                .categoryId(adminCourses.getOrCreateCategoryId())
                .price(new BigDecimal("100.00"))
                .capacity(1)
                .level("BEGINNER")
                .active(true)
                .hiddenFromCatalog(false)
                .noCertificate(true)
                .build();

        // пытаемся выдать курс за ученика — сервис должен проигнорировать заголовок
        Response response = trainer.courses().createCourseResponse(request, learner.id());
        assertEquals(201, response.statusCode(), response.asString());
        CourseResponse course = response.as(CourseResponse.class);
        fixtures.trackCourse(course.getId());

        assertAll(
                () -> assertNotNull(course.getId()),
                () -> assertEquals(request.getName(), course.getName()),
                () -> assertEquals(trainer.id(), course.getCreatedBy(), "createdBy = id из токена тренера"),
                () -> assertEquals(1, course.getCapacity()),
                () -> assertEquals(0, course.getLearnersCount()),
                () -> assertNull(course.getCertificateTemplateId(), "noCertificate=true")
        );
    }

    @Test
    @DisplayName("Создатель курса автоматически становится INSTRUCTOR (имя и email из user-service)")
    void creatorIsInstructor() {
        Session trainer = fixtures.newTrainerSession();
        CourseResponse course = fixtures.createCourseAs(trainer);

        List<EnrollmentResponse> enrollments = adminCourses.getCourseEnrollments(course.getId());

        assertEquals(1, enrollments.size(), "в новом курсе только преподаватель");
        EnrollmentResponse instructor = enrollments.get(0);
        assertAll(
                () -> assertEquals(trainer.id(), instructor.getUserId()),
                () -> assertEquals("INSTRUCTOR", instructor.getRole()),
                () -> assertEquals(trainer.account().fullName(), instructor.getUserFullName()),
                () -> assertEquals(trainer.account().email(), instructor.getUserEmail())
        );
    }

    @Test
    @DisplayName("Курс виден ученику в каталоге, enrolled = false")
    void courseIsInCatalog() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        Session learner = fixtures.newLearnerSession();

        Response response = learner.courses().catalogResponse();
        assertEquals(200, response.statusCode(), response.asString());

        List<Boolean> enrolled = response.jsonPath()
                .getList("findAll { it.course.id == '" + course.getId() + "' }.enrolled");
        assertEquals(List.of(false), enrolled, "курс в каталоге, ученик не записан");
    }

    // ---------------------------------------------------------------- запись на курс

    @Test
    @DisplayName("Ученик записывается сам: роль LEARNER, данные пользователя по gRPC")
    void learnerSelfEnrolls() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        Session learner = fixtures.newLearnerSession();

        Response response = learner.courses().selfEnrollResponse(course.getId());
        assertEquals(201, response.statusCode(), response.asString());

        EnrollmentResponse enrollment = response.as(EnrollmentResponse.class);
        assertAll(
                () -> assertNotNull(enrollment.getId()),
                () -> assertEquals(course.getId(), enrollment.getCourseId()),
                () -> assertEquals(learner.id(), enrollment.getUserId()),
                () -> assertEquals("LEARNER", enrollment.getRole()),
                () -> assertEquals(0, enrollment.getProgress()),
                () -> assertEquals(learner.account().fullName(), enrollment.getUserFullName()),
                () -> assertEquals(learner.account().email(), enrollment.getUserEmail()),
                () -> assertNull(enrollment.getCompletedAt())
        );
    }

    @Test
    @DisplayName("Повторная запись -> 409")
    void secondEnrollIsConflict() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        Session learner = fixtures.newLearnerSession();
        fixtures.enroll(learner, course.getId());

        Response response = learner.courses().selfEnrollResponse(course.getId());
        assertEquals(409, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("Мест нет (capacity = 1) -> 409 для второго ученика")
    void courseIsFull() {
        Session trainer = fixtures.newTrainerSession();
        CourseResponse course = fixtures.createCourseAs(trainer.courses(),
                courseBuilder().active(true).hiddenFromCatalog(false).noCertificate(true).capacity(1).build());
        fixtures.enroll(fixtures.newLearnerSession(), course.getId());

        Response response = fixtures.newLearnerSession().courses().selfEnrollResponse(course.getId());
        assertEquals(409, response.statusCode(), response.asString());
    }

    @Test
    @DisplayName("Неактивного и несуществующего пользователя записать нельзя -> 404")
    void cannotEnrollInactiveOrUnknownUser() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        Account inactive = fixtures.createUser("LEARNER", false);

        Response inactiveUser = adminCourses.enrollResponse(course.getId(), inactive.id(), "LEARNER");
        Response unknownUser = adminCourses.enrollResponse(course.getId(), UUID.randomUUID(), "LEARNER");

        assertAll(
                () -> assertEquals(404, inactiveUser.statusCode(), inactiveUser.asString()),
                () -> assertEquals(404, unknownUser.statusCode(), unknownUser.asString())
        );
    }

    @Test
    @DisplayName("learnersCount курса = 1 (преподаватель не считается)")
    void learnersCountUpdated() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        fixtures.enroll(fixtures.newLearnerSession(), course.getId());

        assertEquals(1, adminCourses.getCourseById(course.getId()).getLearnersCount());
    }

    @Test
    @DisplayName("Курс в 'Моих курсах' ученика, тренер в списке преподавателей")
    void courseInMyCourses() {
        Session trainer = fixtures.newTrainerSession();
        CourseResponse course = fixtures.createCourseAs(trainer);
        Session learner = fixtures.newLearnerSession();
        EnrollmentResponse enrollment = fixtures.enroll(learner, course.getId());

        MyEnrollmentResponse mine = findMyEnrollment(learner, course.getId());

        assertNotNull(mine, "курс должен быть в /api/enrollments/my");
        assertAll(
                () -> assertEquals(enrollment.getId(), mine.getEnrollmentId()),
                () -> assertEquals("LEARNER", mine.getRole()),
                () -> assertEquals(0, mine.getProgress()),
                () -> assertTrue(mine.getInstructors().contains(trainer.account().fullName()), "instructors: " + mine.getInstructors())
        );
    }

    @Test
    @DisplayName("Прогресс 50% -> курс не завершён, 100% -> completedAt проставлен")
    void progressToCompletion() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        Session learner = fixtures.newLearnerSession();
        UUID enrollmentId = fixtures.enroll(learner, course.getId()).getId();

        Response half = learner.courses().updateProgressResponse(enrollmentId, 50);
        assertEquals(200, half.statusCode(), half.asString());
        EnrollmentResponse halfDone = half.as(EnrollmentResponse.class);
        assertEquals(50, halfDone.getProgress());
        assertNull(halfDone.getCompletedAt());

        Response full = learner.courses().updateProgressResponse(enrollmentId, 100);
        assertEquals(200, full.statusCode(), full.asString());
        EnrollmentResponse done = full.as(EnrollmentResponse.class);
        assertEquals(100, done.getProgress());
        assertNotNull(done.getCompletedAt());
    }

    @Test
    @DisplayName("Отписка: курс пропадает из 'Моих курсов'")
    void unenroll() {
        CourseResponse course = fixtures.createCourseAs(fixtures.newTrainerSession());
        Session learner = fixtures.newLearnerSession();
        UUID enrollmentId = fixtures.enroll(learner, course.getId()).getId();
        assertNotNull(findMyEnrollment(learner, course.getId()), "до отписки курс есть");

        Response response = adminCourses.unenrollResponse(enrollmentId);
        assertEquals(204, response.statusCode(), response.asString());

        assertNull(findMyEnrollment(learner, course.getId()));
    }

    @Test
    @DisplayName("Тренер удаляет свой курс -> 404 по id, админ удаляет его окончательно")
    void trainerDeletesCourse() {
        Session trainer = fixtures.newTrainerSession();
        CourseResponse course = fixtures.createCourseAs(trainer);

        Response delete = trainer.courses().deleteCourseResponse(course.getId());
        assertEquals(204, delete.statusCode(), delete.asString());

        Response get = adminCourses.getCourseByIdResponse(course.getId());
        assertEquals(404, get.statusCode(), get.asString());

        Response permanent = adminCourses.deleteCoursePermanentlyResponse(course.getId());
        assertEquals(204, permanent.statusCode(), permanent.asString());
        fixtures.forgetCourse(course.getId());
    }
}
