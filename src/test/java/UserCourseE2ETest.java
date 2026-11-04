import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.CourseController;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.CourseRequest;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.CourseResponse;
import kg.jazzlms.models.response.EnrollmentResponse;
import kg.jazzlms.models.response.MyEnrollmentResponse;
import kg.jazzlms.models.response.UserResponse;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static kg.jazzlms.utils.EntityManager.courseBuilder;
import static kg.jazzlms.utils.EntityManager.userBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: user-service + course-service через API gateway (8080), как работает фронт.
 *
 * Сценарий: админ заводит тренера и учеников -> они логинятся -> тренер создаёт курс ->
 * ученик находит его в каталоге и записывается -> course-service проверяет пользователя
 * в user-service по gRPC (имя/email в записи, неактивного не записать) -> прогресс до 100% ->
 * отписка -> удаление курса. Плюс ролевые ограничения gateway и сервисов.
 *
 * Шаги зависят друг от друга, поэтому идут по @Order в одном экземпляре класса.
 * Всё созданное удаляется в @AfterAll.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("E2E: user service + course service")
public class UserCourseE2ETest {

    private static final String PASSWORD = "Pass123!";

    private final AuthController auth = new AuthController(Endpoints.GATEWAY_URL);
    private final UserController adminUsers = new UserController(Endpoints.GATEWAY_URL);
    private final CourseController adminCourses = new CourseController(Endpoints.GATEWAY_URL);

    private UUID categoryId;

    // пользователи
    private UserRequest trainerReq, learnerReq, learner2Req, inactiveReq;
    private UserResponse trainer, learner, learner2, inactive;
    private final List<UUID> createdUsers = new ArrayList<>();

    // клиенты от имени пользователей
    private String learnerToken;
    private CourseController trainerCourses, learnerCourses, learner2Courses;

    // курс и запись
    private CourseResponse course;
    private UUID enrollmentId;

    @BeforeAll
    void setUp() {
        categoryId = adminCourses.getOrCreateCategoryId();
    }

    // ---------------------------------------------------------------- user service

    @Test
    @Order(1)
    @DisplayName("1. Админ создаёт тренера и учеников")
    void adminCreatesUsers() {
        trainerReq  = userBuilder().password(PASSWORD).userType("TRAINER").active(true).build();
        learnerReq  = userBuilder().password(PASSWORD).userType("LEARNER").active(true).build();
        learner2Req = userBuilder().password(PASSWORD).userType("LEARNER").active(true).build();
        inactiveReq = userBuilder().password(PASSWORD).userType("LEARNER").active(false).build();

        trainer  = createUser(trainerReq);
        learner  = createUser(learnerReq);
        learner2 = createUser(learner2Req);
        inactive = createUser(inactiveReq);

        assertAll(
                () -> assertEquals("TRAINER", trainer.getUserType()),
                () -> assertEquals("LEARNER", learner.getUserType()),
                () -> assertTrue(learner.isActive()),
                () -> assertFalse(inactive.isActive())
        );
    }

    @Test
    @Order(2)
    @DisplayName("2. Тренер и ученики логинятся, /me возвращает их же")
    void usersLogIn() {
        String trainerToken = auth.login(trainerReq.getUsername(), PASSWORD).getToken();
        learnerToken = auth.login(learnerReq.getUsername(), PASSWORD).getToken();
        String learner2Token = auth.login(learner2Req.getUsername(), PASSWORD).getToken();

        assertEquals(trainer.getId(), auth.me(trainerToken).getId());
        assertEquals(learner.getId(), auth.me(learnerToken).getId());

        trainerCourses  = new CourseController(Endpoints.GATEWAY_URL, trainerToken);
        learnerCourses  = new CourseController(Endpoints.GATEWAY_URL, learnerToken);
        learner2Courses = new CourseController(Endpoints.GATEWAY_URL, learner2Token);
    }

    @Test
    @Order(3)
    @DisplayName("3. Неактивный пользователь не может войти")
    void inactiveUserCannotLogIn() {
        Response response = auth.loginResponse(inactiveReq.getUsername(), PASSWORD);
        assertEquals(401, response.statusCode(), response.asString());
    }

    // ---------------------------------------------------------------- ограничения по ролям

    @Test
    @Order(4)
    @DisplayName("4. Ученик не может создать курс (gateway -> 403)")
    void learnerCannotCreateCourse() {
        Response response = learnerCourses.createCourseResponse(courseBuilder().build());
        assertEquals(403, response.statusCode(), response.asString());
    }

    @Test
    @Order(5)
    @DisplayName("5. Ученик не может ходить в course-service напрямую, мимо gateway (401)")
    void learnerCannotCallCourseServiceDirectly() {
        CourseController direct = new CourseController(Endpoints.COURSE_URL, learnerToken);
        Response response = direct.get(Endpoints.COURSES);
        assertEquals(401, response.statusCode(), response.asString());
    }

    // ---------------------------------------------------------------- course service

    @Test
    @Order(6)
    @DisplayName("6. Тренер создаёт курс; createdBy берётся из токена, а не из X-User-Id")
    void trainerCreatesCourse() {
        CourseRequest request = courseBuilder()
                .description("E2E course")
                .categoryId(categoryId)
                .price(new BigDecimal("100.00"))
                .capacity(1)                 // одно место: проверим "Course is full"
                .level("BEGINNER")
                .active(true)
                .hiddenFromCatalog(false)
                .noCertificate(true)
                .build();

        // пытаемся выдать курс за ученика — сервис должен проигнорировать заголовок
        Response response = trainerCourses.createCourseResponse(request, learner.getId());
        assertEquals(201, response.statusCode(), response.asString());
        course = response.as(CourseResponse.class);

        assertAll(
                () -> assertNotNull(course.getId()),
                () -> assertEquals(request.getName(), course.getName()),
                () -> assertEquals(trainer.getId(), course.getCreatedBy(), "createdBy = id из токена тренера"),
                () -> assertEquals(1, course.getCapacity()),
                () -> assertEquals(0, course.getLearnersCount()),
                () -> assertNull(course.getCertificateTemplateId(), "noCertificate=true")
        );
    }

    @Test
    @Order(7)
    @DisplayName("7. Создатель курса автоматически становится INSTRUCTOR (имя и email из user-service)")
    void creatorIsInstructor() {
        List<EnrollmentResponse> enrollments = adminCourses.getCourseEnrollments(course.getId());

        assertEquals(1, enrollments.size(), "в новом курсе только преподаватель");
        EnrollmentResponse instructor = enrollments.get(0);
        assertAll(
                () -> assertEquals(trainer.getId(), instructor.getUserId()),
                () -> assertEquals("INSTRUCTOR", instructor.getRole()),
                () -> assertEquals(fullName(trainer), instructor.getUserFullName()),
                () -> assertEquals(trainer.getEmail(), instructor.getUserEmail())
        );
    }

    @Test
    @Order(8)
    @DisplayName("8. Курс виден ученику в каталоге, enrolled = false")
    void courseIsInCatalog() {
        Response response = learnerCourses.catalogResponse();
        assertEquals(200, response.statusCode(), response.asString());

        List<Boolean> enrolled = response.jsonPath()
                .getList("findAll { it.course.id == '" + course.getId() + "' }.enrolled");
        assertEquals(1, enrolled.size(), "курс должен быть в каталоге");
        assertFalse(enrolled.get(0));
    }

    @Test
    @Order(9)
    @DisplayName("9. Ученик записывается сам: роль LEARNER, данные пользователя по gRPC")
    void learnerSelfEnrolls() {
        Response response = learnerCourses.selfEnrollResponse(course.getId());
        assertEquals(201, response.statusCode(), response.asString());

        EnrollmentResponse enrollment = response.as(EnrollmentResponse.class);
        enrollmentId = enrollment.getId();
        assertAll(
                () -> assertNotNull(enrollment.getId()),
                () -> assertEquals(course.getId(), enrollment.getCourseId()),
                () -> assertEquals(learner.getId(), enrollment.getUserId()),
                () -> assertEquals("LEARNER", enrollment.getRole()),
                () -> assertEquals(0, enrollment.getProgress()),
                () -> assertEquals(fullName(learner), enrollment.getUserFullName()),
                () -> assertEquals(learner.getEmail(), enrollment.getUserEmail()),
                () -> assertNull(enrollment.getCompletedAt())
        );
    }

    @Test
    @Order(10)
    @DisplayName("10. Повторная запись -> 409")
    void secondEnrollIsConflict() {
        Response response = learnerCourses.selfEnrollResponse(course.getId());
        assertEquals(409, response.statusCode(), response.asString());
    }

    @Test
    @Order(11)
    @DisplayName("11. Мест нет (capacity = 1) -> 409 для второго ученика")
    void courseIsFull() {
        Response response = learner2Courses.selfEnrollResponse(course.getId());
        assertEquals(409, response.statusCode(), response.asString());
    }

    @Test
    @Order(12)
    @DisplayName("12. Неактивного и несуществующего пользователя записать нельзя -> 404")
    void cannotEnrollInactiveOrUnknownUser() {
        Response inactiveUser = adminCourses.enrollResponse(course.getId(), inactive.getId(), "LEARNER");
        Response unknownUser  = adminCourses.enrollResponse(course.getId(), UUID.randomUUID(), "LEARNER");

        assertAll(
                () -> assertEquals(404, inactiveUser.statusCode(), inactiveUser.asString()),
                () -> assertEquals(404, unknownUser.statusCode(), unknownUser.asString())
        );
    }

    @Test
    @Order(13)
    @DisplayName("13. learnersCount курса = 1 (преподаватель не считается)")
    void learnersCountUpdated() {
        assertEquals(1, adminCourses.getCourseById(course.getId()).getLearnersCount());
    }

    @Test
    @Order(14)
    @DisplayName("14. Курс в 'Моих курсах' ученика, тренер в списке преподавателей")
    void courseInMyCourses() {
        MyEnrollmentResponse mine = findMyEnrollment(learnerCourses, course.getId());

        assertNotNull(mine, "курс должен быть в /api/enrollments/my");
        assertAll(
                () -> assertEquals(enrollmentId, mine.getEnrollmentId()),
                () -> assertEquals("LEARNER", mine.getRole()),
                () -> assertEquals(0, mine.getProgress()),
                () -> assertTrue(mine.getInstructors().contains(fullName(trainer)), "instructors: " + mine.getInstructors())
        );
    }

    @Test
    @Order(15)
    @DisplayName("15. Прогресс 50% -> курс не завершён, 100% -> completedAt проставлен")
    void progressToCompletion() {
        Response half = learnerCourses.updateProgressResponse(enrollmentId, 50);
        assertEquals(200, half.statusCode(), half.asString());
        EnrollmentResponse halfDone = half.as(EnrollmentResponse.class);
        assertEquals(50, halfDone.getProgress());
        assertNull(halfDone.getCompletedAt());

        Response full = learnerCourses.updateProgressResponse(enrollmentId, 100);
        assertEquals(200, full.statusCode(), full.asString());
        EnrollmentResponse done = full.as(EnrollmentResponse.class);
        assertEquals(100, done.getProgress());
        assertNotNull(done.getCompletedAt());
    }

    @Test
    @Order(16)
    @DisplayName("16. Деактивированный админом пользователь больше не может войти")
    void deactivatedUserCannotLogIn() {
        Response update = adminUsers.updateUserResponse(learner2.getId(), Map.of("active", false));
        assertEquals(200, update.statusCode(), update.asString());

        Response login = auth.loginResponse(learner2Req.getUsername(), PASSWORD);
        assertEquals(401, login.statusCode(), login.asString());
    }

    @Test
    @Order(17)
    @DisplayName("17. Ученик не может удалить курс (gateway -> 403)")
    void learnerCannotDeleteCourse() {
        Response response = learnerCourses.deleteCourseResponse(course.getId());
        assertEquals(403, response.statusCode(), response.asString());
    }

    @Test
    @Order(18)
    @DisplayName("18. Отписка: курс пропадает из 'Моих курсов'")
    void unenroll() {
        Response response = adminCourses.unenrollResponse(enrollmentId);
        assertEquals(204, response.statusCode(), response.asString());

        assertNull(findMyEnrollment(learnerCourses, course.getId()));
    }

    @Test
    @Order(19)
    @DisplayName("19. Тренер удаляет свой курс -> 404 по id, админ удаляет его окончательно")
    void trainerDeletesCourse() {
        Response delete = trainerCourses.deleteCourseResponse(course.getId());
        assertEquals(204, delete.statusCode(), delete.asString());

        Response get = adminCourses.getCourseByIdResponse(course.getId());
        assertEquals(404, get.statusCode(), get.asString());

        Response permanent = adminCourses.deleteCoursePermanentlyResponse(course.getId());
        assertEquals(204, permanent.statusCode(), permanent.asString());
        course = null;
    }

    // ---------------------------------------------------------------- cleanup

    @AfterAll
    void cleanUp() {
        if (course != null) {   // сценарий упал раньше шага 19
            adminCourses.deleteCourseResponse(course.getId());
            adminCourses.deleteCoursePermanentlyResponse(course.getId());
        }
        createdUsers.forEach(adminUsers::deleteUserResponse);
    }

    // ---------------------------------------------------------------- helpers

    private UserResponse createUser(UserRequest request) {
        Response response = adminUsers.createUserResponse(request);
        assertEquals(201, response.statusCode(), response.asString());
        UserResponse user = response.as(UserResponse.class);
        createdUsers.add(user.getId());
        return user;
    }

    private static MyEnrollmentResponse findMyEnrollment(CourseController client, UUID courseId) {
        return client.getMyEnrollments().stream()
                .filter(e -> e.getCourse() != null && courseId.equals(e.getCourse().getId()))
                .findFirst()
                .orElse(null);
    }

    private static String fullName(UserResponse user) {
        return user.getFirstName() + " " + user.getLastName();
    }
}
