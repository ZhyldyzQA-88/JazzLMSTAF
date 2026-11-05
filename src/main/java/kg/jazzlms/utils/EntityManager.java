package kg.jazzlms.utils;

import com.github.javafaker.Faker;
import kg.jazzlms.models.request.CourseRequest;
import kg.jazzlms.models.request.NotificationRuleRequest;
import kg.jazzlms.models.request.UnitRequest;
import kg.jazzlms.models.request.UserRequest;

import java.util.UUID;

public class EntityManager {

    // Faker не потокобезопасен, а тесты идут параллельно — у каждого потока свой экземпляр
    private static final ThreadLocal<Faker> FAKER = ThreadLocal.withInitial(Faker::new);

    /** Билдер с валидной базой: в тесте переопределяешь только нужные поля */
    public static UserRequest.UserRequestBuilder userBuilder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return UserRequest.builder()
                .firstName(FAKER.get().name().firstName())
                .lastName(FAKER.get().name().lastName())
                .email(suffix + "." + FAKER.get().internet().emailAddress())
                .username(FAKER.get().name().username() + "_" + suffix);
    }

    /** Минимальный пользователь (как раньше) */
    public static UserRequest generateUser() {
        return userBuilder().build();
    }

    /** Курс только с обязательным полем name (+ уникальный code) */
    public static CourseRequest.CourseRequestBuilder courseBuilder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return CourseRequest.builder()
                .name(FAKER.get().educator().course() + " " + suffix)
                .code("C-" + suffix);
    }

    public static CourseRequest generateCourse() {
        return courseBuilder().build();
    }

    /** Валидное правило рассылки: уникальное name, событие USER_CREATED, получатель — сам пользователь */
    public static NotificationRuleRequest.NotificationRuleRequestBuilder notificationRuleBuilder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return NotificationRuleRequest.builder()
                .name("Auto rule " + suffix)
                .eventType("USER_CREATED")
                .recipient("RELATED_USER")
                .subjectTemplate("Welcome, {{firstName}} [" + suffix + "]")
                .bodyTemplate("Hi {{firstName}} {{lastName}}, your account is ready.");
    }

    public static NotificationRuleRequest generateNotificationRule() {
        return notificationRuleBuilder().build();
    }

    /** Урок-видео с YouTube, завершается кнопкой (CHECKBOX) */
    public static UnitRequest youtubeUnit(String name, String youtubeUrl) {
        return UnitRequest.builder()
                .name(name)
                .type("VIDEO")
                .sourceType("YOUTUBE")
                .youtubeUrl(youtubeUrl)
                .completionType("CHECKBOX")
                .build();
    }
}
