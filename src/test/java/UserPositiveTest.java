import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.UserRequest;
import kg.jazzlms.models.response.UserResponse;
import kg.jazzlms.utils.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static kg.jazzlms.utils.EntityManager.userBuilder;
import static org.junit.jupiter.api.Assertions.*;

@Tag("regression")
public class UserPositiveTest {

    private final UserController userController = new UserController(Endpoints.BASE_URL);

    // 1. Минимальный набор: проверяем дефолты
    @Tag("smoke")
    @Test
    void createUserWithMinimalFieldsSetsDefaults() {
        UserRequest request = EntityManager.generateUser();

        UserResponse created = userController.createUser(request);

        assertAll(
                () -> assertNotNull(created.getId()),
                () -> assertEquals(request.getFirstName(), created.getFirstName()),
                () -> assertEquals(request.getEmail(), created.getEmail()),
                () -> assertEquals(request.getUsername(), created.getUsername()),
                () -> assertEquals("LEARNER", created.getUserType()),
                () -> assertEquals("UTC", created.getTimeZone()),
                () -> assertEquals("en", created.getLanguage()),
                () -> assertFalse(created.isActive()),
                () -> assertFalse(created.isExcludeFromEmails()),
                () -> assertNull(created.getBio()),
                () -> assertNull(created.getLastLoginAt()),
                () -> assertNotNull(created.getCreatedAt())
        );
    }

    // 2. Разные наборы данных
    static Stream<Arguments> userDataSets() {
        return Stream.of(
                Arguments.of("Все поля заполнены", userBuilder()
                        .password("Pass123!").userType("SUPER_ADMIN").timeZone("Asia/Bishkek")
                        .language("ru").bio("Текст о себе").active(true).excludeFromEmails(true).build()),
                Arguments.of("Кириллица в имени", userBuilder()
                        .firstName("Жылдыз").lastName("Асанова").build()),
                Arguments.of("Апостроф и дефис", userBuilder()
                        .firstName("Anne-Marie").lastName("O'Neil").build()),
                Arguments.of("Заполненное bio", userBuilder().bio("Bio text").build()),
                Arguments.of("active=true", userBuilder().active(true).build()),
                Arguments.of("excludeFromEmails=true", userBuilder().excludeFromEmails(true).build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userDataSets")
    void createUserWithDifferentData(String caseName, UserRequest request) {
        UserResponse created = userController.createUser(request);

        assertAll(
                () -> assertNotNull(created.getId()),
                () -> assertEquals(request.getFirstName(), created.getFirstName()),
                () -> assertEquals(request.getLastName(), created.getLastName()),
                () -> assertEquals(request.getEmail(), created.getEmail()),
                () -> assertEquals(request.getUsername(), created.getUsername()),
                () -> assertEquals(request.getBio(), created.getBio()),
                () -> assertEquals(request.isActive(), created.isActive()),
                () -> assertEquals(request.isExcludeFromEmails(), created.isExcludeFromEmails())
        );
        if (request.getUserType() != null) assertEquals(request.getUserType(), created.getUserType());
        if (request.getTimeZone() != null) assertEquals(request.getTimeZone(), created.getTimeZone());
        if (request.getLanguage() != null) assertEquals(request.getLanguage(), created.getLanguage());
    }

    // 3. userType
    @ParameterizedTest(name = "userType = {0}")
    @ValueSource(strings = {"LEARNER", "SUPER_ADMIN"}) // добавь остальные значения enum
    void createUserWithEachUserType(String userType) {
        UserResponse created = userController.createUser(userBuilder().userType(userType).build());
        assertEquals(userType, created.getUserType());
    }

    // 4. Часовые пояса
    @ParameterizedTest(name = "timeZone = {0}")
    @ValueSource(strings = {"UTC", "Asia/Bishkek", "Europe/Moscow", "America/New_York"})
    void createUserWithDifferentTimeZones(String timeZone) {
        UserResponse created = userController.createUser(userBuilder().timeZone(timeZone).build());
        assertEquals(timeZone, created.getTimeZone());
    }

    // 5. Языки
    @ParameterizedTest(name = "language = {0}")
    @ValueSource(strings = {"en", "ru", "ky"})
    void createUserWithDifferentLanguages(String language) {
        UserResponse created = userController.createUser(userBuilder().language(language).build());
        assertEquals(language, created.getLanguage());
    }

    // 6. Комбинации флагов
    static Stream<Arguments> flags() {
        return Stream.of(
                Arguments.of(false, false),
                Arguments.of(true, false),
                Arguments.of(false, true),
                Arguments.of(true, true)
        );
    }

    @ParameterizedTest(name = "active={0}, excludeFromEmails={1}")
    @MethodSource("flags")
    void createUserWithFlagCombinations(boolean active, boolean excludeFromEmails) {
        UserResponse created = userController.createUser(
                userBuilder().active(active).excludeFromEmails(excludeFromEmails).build());

        assertAll(
                () -> assertEquals(active, created.isActive()),
                () -> assertEquals(excludeFromEmails, created.isExcludeFromEmails())
        );
    }
}