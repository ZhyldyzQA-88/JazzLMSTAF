package kg.jazzlms.constants;

/**
 * Имена общих ресурсов стенда для @ResourceLock при параллельном запуске тестов.
 *
 * Тест-классы идут параллельно (junit-platform.properties), но некоторые данные стенда глобальные.
 * JUnit не запустит одновременно классы с конфликтующими блокировками:
 *  - READ_WRITE — класс МЕНЯЕТ ресурс: пока он идёт, никто другой с этим ресурсом не работает;
 *  - READ       — класс только ПОЛАГАЕТСЯ на ресурс: такие классы идут параллельно друг с другом,
 *                 но не одновременно с READ_WRITE.
 */
public final class ResourceLocks {

    /**
     * Настройки gamification (одни на весь стенд): включены ли баллы, сколько стоит логин/урок/курс.
     * READ_WRITE — кто их меняет (GamificationTest, GamificationPointsOffE2ETest).
     * READ — кто проверяет начисленные баллы (EventsE2ETest, CourseJourneyE2ETest, GamificationPointsE2ETest).
     */
    public static final String GAMIFICATION_SETTINGS = "gamification-settings";

    private ResourceLocks() {
    }
}
