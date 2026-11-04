package kg.jazzlms.constants;

import kg.jazzlms.config.ConfigReader;

public class Endpoints {

    public static final String BASE_URL = ConfigReader.getValue("base.url");
    public static final String COURSE_URL = ConfigReader.getValue("course.url");
    public static final String GATEWAY_URL = ConfigReader.getValue("gateway.url");

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

    // enrollments
    public static final String ENROLLMENTS_MY = "api/enrollments/my";
    public static final String ENROLLMENTS_ID = "api/enrollments/%s";
    public static final String ENROLLMENTS_PROGRESS = "api/enrollments/%s/progress";

}
