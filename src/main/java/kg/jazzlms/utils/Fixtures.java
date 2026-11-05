package kg.jazzlms.utils;

import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.AuthController;
import kg.jazzlms.controller.CourseController;
import kg.jazzlms.controller.GamificationController;
import kg.jazzlms.controller.NotificationController;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.CourseRequest;
import kg.jazzlms.models.request.NotificationRuleRequest;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.CourseResponse;
import kg.jazzlms.models.response.EnrollmentResponse;
import kg.jazzlms.models.response.MyEnrollmentResponse;
import kg.jazzlms.models.response.NotificationRuleResponse;
import kg.jazzlms.models.response.UnitResponse;
import kg.jazzlms.models.response.UserResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static kg.jazzlms.utils.EntityManager.courseBuilder;
import static kg.jazzlms.utils.EntityManager.userBuilder;
import static kg.jazzlms.utils.EntityManager.youtubeUnit;

/**
 * Подготовка данных для независимых E2E-тестов.
 *
 * Каждый тест сам создаёт всё, что ему нужно (пользователей, курс, уроки, запись, правило рассылки),
 * через этот класс. Fixtures запоминает созданное и удаляет в cleanUp() — вызывайте его в @AfterEach.
 * Так тесты не зависят друг от друга: их можно запускать по одному, в любом порядке и параллельно.
 *
 * Админские действия идут через gateway, как в UI.
 */
public class Fixtures {

    public static final String PASSWORD = "Pass123!";

    private final AuthController auth = new AuthController(Endpoints.GATEWAY_URL);
    private final UserController adminUsers = new UserController(Endpoints.GATEWAY_URL);
    private final CourseController adminCourses = new CourseController(Endpoints.GATEWAY_URL);
    private final NotificationController notifications = new NotificationController(Endpoints.NOTIFICATION_URL);

    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> courses = new ArrayList<>();
    private final List<String> rules = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();

    /** Созданный пользователь. username/пароль нужны, чтобы войти от его имени */
    public record Account(UserRequest request, UserResponse user) {
        public UUID id() {
            return user.getId();
        }

        public String username() {
            return request.getUsername();
        }

        public String email() {
            return user.getEmail();
        }

        public String fullName() {
            return user.getFirstName() + " " + user.getLastName();
        }
    }

    /** Пользователь, вошедший в систему, и клиенты от его имени (через gateway) */
    public record Session(Account account, String token, CourseController courses, GamificationController gamification) {
        public UUID id() {
            return account.id();
        }

        public UserResponse user() {
            return account.user();
        }
    }

    /** Курс с уроками (в порядке создания) */
    public record CourseWithLessons(CourseResponse course, List<UnitResponse> lessons) {
        public UUID id() {
            return course.getId();
        }
    }

    // ---------------------------------------------------------------- пользователи

    /** Админ создаёт пользователя с паролем PASSWORD */
    public Account createUser(String userType, boolean active) {
        UserRequest request = userBuilder().password(PASSWORD).userType(userType).active(active).build();
        UserResponse user = adminUsers.createUserResponse(request)
                .then().statusCode(201).extract().as(UserResponse.class);
        users.add(user.getId());
        return new Account(request, user);
    }

    public Account createLearner() {
        return createUser("LEARNER", true);
    }

    public Account createTrainer() {
        return createUser("TRAINER", true);
    }

    /** Логин через gateway */
    public Session logIn(Account account) {
        String token = auth.login(account.username(), PASSWORD).getToken();
        return new Session(account, token,
                new CourseController(Endpoints.GATEWAY_URL, token),
                new GamificationController(Endpoints.GATEWAY_URL, token));
    }

    public Session newLearnerSession() {
        return logIn(createLearner());
    }

    public Session newTrainerSession() {
        return logIn(createTrainer());
    }

    // ---------------------------------------------------------------- курсы

    /** Курс от имени админа */
    public CourseResponse createCourse(CourseRequest request) {
        return createCourseAs(adminCourses, request);
    }

    /** Курс от имени тренера (или любого клиента с правами) */
    public CourseResponse createCourseAs(CourseController creator, CourseRequest request) {
        CourseResponse course = creator.createCourseResponse(request)
                .then().statusCode(201).extract().as(CourseResponse.class);
        courses.add(course.getId());
        return course;
    }

    /** Активный курс в каталоге от имени тренера, без сертификата */
    public CourseResponse createCourseAs(Session trainer) {
        return createCourseAs(trainer.courses(), courseBuilder().active(true).hiddenFromCatalog(false).noCertificate(true).build());
    }

    /** Админ создаёт курс (сертификат по умолчанию) и уроки-видео YouTube: "Lesson 1", "Lesson 2", ... */
    public CourseWithLessons createCourseWithYoutubeLessons(String... youtubeUrls) {
        CourseResponse course = createCourse(courseBuilder().active(true).hiddenFromCatalog(false).build());
        List<UnitResponse> lessons = new ArrayList<>();
        for (int i = 0; i < youtubeUrls.length; i++) {
            lessons.add(adminCourses.createUnit(course.getId(), youtubeUnit("Lesson " + (i + 1), youtubeUrls[i])));
        }
        return new CourseWithLessons(course, lessons);
    }

    /** Курс создан в обход Fixtures (например, нужен сырой Response) — всё равно удалить после теста */
    public void trackCourse(UUID courseId) {
        courses.add(courseId);
    }

    /** Если тест сам удалил курс — убираем из списка на уборку */
    public void forgetCourse(UUID courseId) {
        courses.remove(courseId);
    }

    // ---------------------------------------------------------------- записи на курс

    /** Ученик сам записывается из каталога */
    public EnrollmentResponse enroll(Session learner, UUID courseId) {
        return learner.courses().selfEnrollResponse(courseId)
                .then().statusCode(201).extract().as(EnrollmentResponse.class);
    }

    /** Ученик проходит все уроки курса (открыть + завершить) */
    public void passAllLessons(Session learner, CourseWithLessons course) {
        course.lessons().forEach(lesson -> learner.courses().passUnit(lesson.getId()));
    }

    /** Запись курса в "Моих курсах" пользователя или null */
    public static MyEnrollmentResponse findMyEnrollment(Session session, UUID courseId) {
        return session.courses().getMyEnrollments().stream()
                .filter(e -> e.getCourse() != null && courseId.equals(e.getCourse().getId()))
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------- рассылки

    public NotificationRuleResponse createRule(NotificationRuleRequest request) {
        NotificationRuleResponse rule = notifications.createRule(request);
        rules.add(rule.getId());
        return rule;
    }

    /** Сообщение (pending/history), которое нужно удалить после теста */
    public void trackMessage(String messageId) {
        messages.add(messageId);
    }

    // ---------------------------------------------------------------- уборка

    /** Удаляет всё созданное. Ошибки не роняют тест: что уже удалено самим тестом — пропускается */
    public void cleanUp() {
        messages.forEach(notifications::deleteMessageResponse);
        rules.forEach(notifications::deleteRuleResponse);
        // Правило срабатывает на события ВСЕГО стенда, пока существует: при параллельном запуске
        // отложенное правило успевает создать pending-сообщения и для пользователей из других тестов.
        // Удаляем всё, что осталось в очереди по нашим правилам, иначе письма уйдут позже.
        if (!rules.isEmpty()) {
            notifications.getPending().stream()
                    .filter(m -> rules.contains(m.getRuleId()))
                    .forEach(m -> notifications.deleteMessageResponse(m.getId()));
        }
        courses.forEach(id -> {
            adminCourses.deleteCourseResponse(id);
            adminCourses.deleteCoursePermanentlyResponse(id);
        });
        users.forEach(adminUsers::deleteUserResponse);
        messages.clear();
        rules.clear();
        courses.clear();
        users.clear();
    }
}
