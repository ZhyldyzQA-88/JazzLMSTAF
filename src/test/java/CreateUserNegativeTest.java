import io.restassured.response.Response;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.controller.UserController;
import kg.jazzlms.models.request.UserRequest;
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
public class CreateUserNegativeTest {

    private final UserController userController = new UserController(Endpoints.BASE_URL);

    // ---------- 1. Невалидные данные в полях ----------

    static Stream<Arguments> invalidData() {
        return Stream.of(
                Arguments.of("firstName = null",   userBuilder().firstName(null).build()),
                Arguments.of("firstName пустой",   userBuilder().firstName("").build()),
                Arguments.of("firstName пробелы",  userBuilder().firstName("   ").build()),
                Arguments.of("lastName = null",    userBuilder().lastName(null).build()),
                Arguments.of("lastName пустой",    userBuilder().lastName("").build()),
                Arguments.of("email = null",       userBuilder().email(null).build()),
                Arguments.of("email пустой",       userBuilder().email("").build()),
                Arguments.of("email без @",        userBuilder().email("testexample.com").build()),
                Arguments.of("email без домена",   userBuilder().email("test@").build()),
                Arguments.of("email без имени",    userBuilder().email("@example.com").build()),
                Arguments.of("email с пробелом",   userBuilder().email("te st@example.com").build()),
                Arguments.of("email с двумя @",    userBuilder().email("a@@example.com").build()),
                Arguments.of("username = null",    userBuilder().username(null).build()),
                Arguments.of("username пустой",    userBuilder().username("").build()),
                Arguments.of("username пробелы",   userBuilder().username("   ").build()),
                // ПАДАЕТ (баг бэка): 500 вместо 400 — невалидное значение enum userType не обрабатывается
                Arguments.of("userType невалидный", userBuilder().userType("INVALID_ROLE").build()),
                // ПАДАЕТ (баг бэка): 201 вместо 400 — timeZone не валидируется, сохраняется "Mars/Olympus"
                Arguments.of("timeZone невалидный", userBuilder().timeZone("Mars/Olympus").build()),
                // ПАДАЕТ (баг бэка): 201 вместо 400 — language не валидируется, сохраняется "xx"
                Arguments.of("language невалидный", userBuilder().language("xx").build()),
                Arguments.of("firstName 300 символов", userBuilder().firstName("a".repeat(300)).build()),
                Arguments.of("username 300 символов",  userBuilder().username("u".repeat(300)).build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidData")
    void createUserWithInvalidData(String caseName, UserRequest request) {
        Response response = userController.createUserResponse(request);

        assertEquals(400, response.statusCode(), response.asString());
    }

    // ---------- 2. Дубликаты ----------

    @Test
    void createUserWithDuplicateEmail() {
        UserRequest first = EntityManager.generateUser();
        userController.createUser(first);

        UserRequest duplicate = userBuilder().email(first.getEmail()).build();
        Response response = userController.createUserResponse(duplicate);

        assertTrue(response.statusCode() == 409 || response.statusCode() == 400,
                "Ожидали 409/400, получили " + response.statusCode());
    }

    @Test
    void createUserWithDuplicateUsername() {
        UserRequest first = EntityManager.generateUser();
        userController.createUser(first);

        UserRequest duplicate = userBuilder().username(first.getUsername()).build();
        Response response = userController.createUserResponse(duplicate);

        assertTrue(response.statusCode() == 409 || response.statusCode() == 400,
                "Ожидали 409/400, получили " + response.statusCode());
    }

    // ---------- 3. Некорректное тело запроса ----------
    // Баг бэка: HttpMessageNotReadableException (нечитаемый JSON) не обрабатывается -> 500 вместо 400.
    // Проходят только "{}" (честный 400 по @NotBlank) и firstName:123 (Jackson приводит число к строке).

    @ParameterizedTest(name = "Тело: {0}")
    @ValueSource(strings = {
            "",               // ПАДАЕТ: 500 вместо 400 — пустое тело
            "{}",
            "not a json",     // ПАДАЕТ: 500 вместо 400 — не JSON
            "{\"firstName\": \"Casey\",",  // ПАДАЕТ: 500 вместо 400 — обрезанный JSON
            "[]",             // ПАДАЕТ: 500 вместо 400 — массив вместо объекта
            // ПАДАЕТ: 500 вместо 400 — active: "yes" не приводится к boolean
            "{\"firstName\":\"Casey\",\"lastName\":\"Miller\",\"email\":\"a@b.com\",\"username\":\"u1\",\"active\":\"yes\"}",
            "{\"firstName\":123,\"lastName\":\"Miller\",\"email\":\"a@b.com\",\"username\":\"u2\"}"
    })
    void createUserWithMalformedBody(String body) {
        Response response = userController.createUserRaw(body);

        assertEquals(400, response.statusCode(), response.asString());
    }
}