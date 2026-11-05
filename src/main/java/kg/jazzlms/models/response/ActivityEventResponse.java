package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Событие из ленты /api/analytics/timeline (analytics service собирает их из Kafka) */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActivityEventResponse {

    private String id;
    private String eventId;
    private String type;             // USER_CREATED, USER_LOGGED_IN, COURSE_CREATED, ...
    private String source;           // user-service, course-service
    private String occurredAt;
    private String userId;
    private String description;
    private Map<String, String> payload;
}
