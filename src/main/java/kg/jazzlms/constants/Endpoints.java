package kg.jazzlms.constants;

import kg.jazzlms.config.ConfigReader;

public class Endpoints {

    public static final String BASE_URL = ConfigReader.getValue("base.url");
    public static final String COURSE_URL = ConfigReader.getValue("course.url");
    public static final String GATEWAY_URL = ConfigReader.getValue("gateway.url");
    public static final String NOTIFICATION_URL = ConfigReader.getValue("notification.url");
    public static final String ANALYTICS_URL = ConfigReader.getValue("analytics.url");
    public static final String GAMIFICATION_URL = ConfigReader.getValue("gamification.url");

    // user service
    public static final String USERS = "api/users";
    public static final String USERS_ID = "api/users/%s";

    // auth controller
    public static final String LOGIN = "api/auth/login";
    public static final String ME = "api/auth/me";

    // course controller
    public static final String COURSES = "api/courses";
    public static final String COURSES_ID = "api/courses/%s";
    public static final String CATEGORIES = "api/categories";
    public static final String COURSES_CATALOG = "api/courses/catalog";
    public static final String COURSES_SELF_ENROLL = "api/courses/%s/enroll";
    public static final String COURSES_ENROLLMENTS = "api/courses/%s/enrollments";
    public static final String COURSES_PERMANENT = "api/courses/%s/permanent";

    // units (уроки курса)
    public static final String COURSES_UNITS = "api/courses/%s/units";
    public static final String UNITS_ID = "api/units/%s";
    public static final String UNITS_START = "api/units/%s/start";
    public static final String UNITS_COMPLETE = "api/units/%s/complete";

    // certificates
    public static final String CERTIFICATES_MY = "api/certificates/my";

    // enrollments
    public static final String ENROLLMENTS_MY = "api/enrollments/my";
    public static final String ENROLLMENTS_ID = "api/enrollments/%s";
    public static final String ENROLLMENTS_PROGRESS = "api/enrollments/%s/progress";

    // notification service (правила рассылки и журнал сообщений)
    public static final String NOTIFICATIONS = "api/notifications";
    public static final String NOTIFICATIONS_ID = "api/notifications/%s";
    public static final String NOTIFICATIONS_EVENTS = "api/notifications/events";
    public static final String NOTIFICATIONS_RECIPIENTS = "api/notifications/recipients";
    public static final String NOTIFICATIONS_PENDING = "api/notifications/pending";
    public static final String NOTIFICATIONS_HISTORY = "api/notifications/history";
    public static final String NOTIFICATIONS_MESSAGES_ID = "api/notifications/messages/%s";

    // analytics service
    public static final String ANALYTICS_SUMMARY = "api/analytics/summary";
    public static final String ANALYTICS_OVERVIEW = "api/analytics/overview";
    public static final String ANALYTICS_DASHBOARD = "api/analytics/dashboard";
    public static final String ANALYTICS_TOP_USERS = "api/analytics/top-users";
    public static final String ANALYTICS_TIMELINE = "api/analytics/timeline";
    public static final String ANALYTICS_TIMELINE_EXPORT = "api/analytics/timeline/export";
    public static final String ANALYTICS_USER_ACTIVITY = "api/analytics/users/%s/activity";
    public static final String ANALYTICS_COURSE_ACTIVITY = "api/analytics/courses/%s/activity";

    // gamification service
    public static final String GAMIFICATION_SETTINGS = "api/gamification/settings";
    public static final String GAMIFICATION_SETTINGS_RESET = "api/gamification/settings/reset";
    public static final String GAMIFICATION_RESET_STATISTICS = "api/gamification/reset-statistics";
    public static final String GAMIFICATION_CONFIG = "api/gamification/config";
    public static final String GAMIFICATION_RULES = "api/gamification/rules";
    public static final String GAMIFICATION_ME = "api/gamification/me";
    public static final String GAMIFICATION_USERS_ID = "api/gamification/users/%s";
    public static final String GAMIFICATION_LEADERBOARD = "api/gamification/leaderboard";
}
