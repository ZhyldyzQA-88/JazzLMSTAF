package kg.jazzlms.models.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Тело POST/PUT /api/notifications (notification service, 8083).
 * Обязательные: name (max 100), eventType, recipient, subjectTemplate, bodyTemplate.
 * delayMinutes: 0..43200 (30 дней). PUT — полная замена, все обязательные поля нужны и там.
 * NON_NULL: незаполненные поля не уходят в JSON, так можно проверить дефолты сервера.
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationRuleRequest {

    private String name;
    private String eventType;        // USER_CREATED, USER_ENROLLED, COURSE_COMPLETED, ...
    private String recipient;        // RELATED_USER, ACCOUNT_OWNER, COURSE_INSTRUCTORS
    private Boolean active;          // по умолчанию true
    private Integer delayMinutes;    // по умолчанию 0
    private String subjectTemplate;  // поддерживает {{firstName}}, {{courseName}} и т.п.
    private String bodyTemplate;
}
