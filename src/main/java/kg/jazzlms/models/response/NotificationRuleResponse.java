package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Правило рассылки. id — Mongo ObjectId (24 hex-символа), не UUID */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationRuleResponse {

    private String id;
    private String name;
    private String eventType;
    private String recipient;
    private boolean active;
    private int delayMinutes;
    private String subjectTemplate;
    private String bodyTemplate;
}
