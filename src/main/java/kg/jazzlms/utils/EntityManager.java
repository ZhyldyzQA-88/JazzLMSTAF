package kg.jazzlms.utils;

import com.github.javafaker.Faker;
import kg.jazzlms.models.request.CourseRequest;
import kg.jazzlms.models.request.UserRequest;

import java.util.UUID;

public class EntityManager {

    private static final Faker faker = new Faker();

    /** Билдер с валидной базой: в тесте переопределяешь только нужные поля */
    public static UserRequest.UserRequestBuilder userBuilder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return UserRequest.builder()
                .firstName(faker.name().firstName())
                .lastName(faker.name().lastName())
                .email(suffix + "." + faker.internet().emailAddress())
                .username(faker.name().username() + "_" + suffix);
    }

    /** Минимальный пользователь (как раньше) */
    public static UserRequest generateUser() {
        return userBuilder().build();
    }

    /** Курс только с обязательным полем name (+ уникальный code) */
    public static CourseRequest.CourseRequestBuilder courseBuilder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return CourseRequest.builder()
                .name(faker.educator().course() + " " + suffix)
                .code("C-" + suffix);
    }

    public static CourseRequest generateCourse() {
        return courseBuilder().build();
    }
}
