import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import kg.jazzlms.constants.Endpoints;
import kg.jazzlms.constants.ResourceLocks;
import kg.jazzlms.controller.AnalyticsController;
import kg.jazzlms.controller.GamificationController;
import kg.jazzlms.controller.NotificationController;
import kg.jazzlms.models.response.GamificationProfileResponse;
import kg.jazzlms.models.response.NotificationMessageResponse;
import kg.jazzlms.models.response.NotificationRuleResponse;
import kg.jazzlms.models.response.TimelinePageResponse;
import kg.jazzlms.utils.Fixtures;
import kg.jazzlms.utils.Fixtures.Account;
import kg.jazzlms.utils.Wait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.Optional;

import static kg.jazzlms.utils.EntityManager.notificationRuleBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Сквозные сценарии: событие из user service через Kafka доходит до notification, analytics и gamification.
 *
 * Каждый тест независимый: сам создаёт пользователя (и правило рассылки, если нужно) через Fixtures
 * и удаляет всё в @AfterEach. Тесты можно запускать по одному и в любом порядке.
 */
@Tag("e2e")
@DisplayName("E2E: user service -> notification + analytics + gamification")
@Epic("E2E")
@Feature("User -> Notification + Analytics + Gamification")
@ResourceLock(value = ResourceLocks.GAMIFICATION_SETTINGS, mode = ResourceAccessMode.READ)
public class EventsE2ETest {

    private static final int DELAY_MINUTES = 60;

    private final Fixtures fixtures = new Fixtures();
    private final NotificationController notifications = new NotificationController(Endpoints.NOTIFICATION_URL);
    private final AnalyticsController analytics = new AnalyticsController(Endpoints.ANALYTICS_URL);
    private final GamificationController gamification = new GamificationController(Endpoints.GAMIFICATION_URL);

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    // ---------------------------------------------------------------- notification

    @Test
    @DisplayName("Notification: при создании пользователя стандартное письмо уходит сразу (history, SENT)")
    void welcomeMessageIsSent() {
        Account learner = fixtures.createLearner();

        NotificationMessageResponse sent = Wait.until("письмо пользователю в history",
                () -> findMessage(notifications.getHistory(), learner, null),
                Optional::isPresent).orElseThrow();

        assertAll(
                () -> assertEquals("USER_CREATED", sent.getEventType()),
                () -> assertEquals("SENT", sent.getStatus()),
                () -> assertNotNull(sent.getSentAt(), "sentAt")
        );
    }

    @Test
    @DisplayName("Notification: отложенное правило создаёт сообщение в pending с подставленным шаблоном")
    void delayedRuleCreatesPendingMessage() {
        NotificationRuleResponse rule = createDelayedUserCreatedRule();
        Account learner = fixtures.createLearner();

        NotificationMessageResponse pending = waitForPending(rule, learner);

        String expectedSubject = rule.getSubjectTemplate().replace("{{firstName}}", learner.user().getFirstName());
        String expectedBody = rule.getBodyTemplate()
                .replace("{{firstName}}", learner.user().getFirstName())
                .replace("{{lastName}}", learner.user().getLastName());

        assertAll(
                () -> assertEquals("PENDING", pending.getStatus()),
                () -> assertEquals(rule.getName(), pending.getRuleName()),
                () -> assertEquals(expectedSubject, pending.getSubject(), "{{firstName}} подставлен в тему"),
                () -> assertEquals(expectedBody, pending.getBody(), "{{firstName}} {{lastName}} подставлены в текст"),
                () -> assertNotNull(pending.getScheduledAt(), "scheduledAt"),
                () -> assertNull(pending.getSentAt(), "ещё не отправлено")
        );
    }

    @Test
    @DisplayName("Notification: отложенное сообщение можно удалить из pending")
    void pendingMessageCanBeDeleted() {
        NotificationRuleResponse rule = createDelayedUserCreatedRule();
        Account learner = fixtures.createLearner();
        NotificationMessageResponse pending = waitForPending(rule, learner);

        assertEquals(204, notifications.deleteMessageResponse(pending.getId()).statusCode());
        assertTrue(findMessage(notifications.getPending(), learner, rule.getId()).isEmpty(), "сообщения больше нет в pending");
    }

    // ---------------------------------------------------------------- analytics

    @Test
    @DisplayName("Analytics: создание и логин пользователя попадают в ленту (USER_CREATED, USER_LOGGED_IN)")
    void analyticsSeesEvents() {
        Account learner = fixtures.createLearner();
        fixtures.logIn(learner);

        TimelinePageResponse page = Wait.until("USER_CREATED и USER_LOGGED_IN в ленте",
                () -> analytics.timelineForUser(learner.id(), null, 20),
                p -> p.getItems().stream().anyMatch(e -> "USER_CREATED".equals(e.getType()))
                        && p.getItems().stream().anyMatch(e -> "USER_LOGGED_IN".equals(e.getType())));

        assertTrue(page.getTotal() >= 2);
    }

    // ---------------------------------------------------------------- gamification

    @Test
    @DisplayName("Gamification: за первый логин +баллы за логин и бейдж Newcomer")
    void gamificationAwardsLoginPoints() {
        int loginPoints = gamification.getSettings().getPoints().getLogin().getPoints();
        Account learner = fixtures.createLearner();
        fixtures.logIn(learner);

        GamificationProfileResponse profile = Wait.until("баллы за логин",
                () -> gamification.getUserProfile(learner.id()),
                p -> p.getPoints() >= loginPoints);

        assertAll(
                () -> assertEquals(loginPoints, profile.getPoints()),
                () -> assertTrue(profile.getBadges().stream().anyMatch(b -> "newcomer".equals(b.getCode())))
        );
    }

    // ---------------------------------------------------------------- helpers

    /** Правило USER_CREATED -> сам пользователь, через 60 минут (успевает полежать в pending) */
    private NotificationRuleResponse createDelayedUserCreatedRule() {
        NotificationRuleResponse rule = fixtures.createRule(notificationRuleBuilder()
                .eventType("USER_CREATED")
                .recipient("RELATED_USER")
                .delayMinutes(DELAY_MINUTES)
                .build());
        assertTrue(rule.isActive());
        assertEquals(DELAY_MINUTES, rule.getDelayMinutes());
        return rule;
    }

    private NotificationMessageResponse waitForPending(NotificationRuleResponse rule, Account learner) {
        NotificationMessageResponse pending = Wait.until("сообщение по правилу в pending",
                () -> findMessage(notifications.getPending(), learner, rule.getId()),
                Optional::isPresent).orElseThrow();
        fixtures.trackMessage(pending.getId()); // иначе через час уйдёт письмо на тестовый адрес
        return pending;
    }

    /** Сообщение для пользователя; ruleId = null — по любому правилу */
    private static Optional<NotificationMessageResponse> findMessage(List<NotificationMessageResponse> messages,
                                                                     Account user, String ruleId) {
        return messages.stream()
                .filter(m -> user.email().equalsIgnoreCase(m.getRecipientEmail()))
                .filter(m -> ruleId == null || ruleId.equals(m.getRuleId()))
                .findFirst();
    }
}
