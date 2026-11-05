package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Сообщение из /pending или /history: правило, применённое к конкретному событию */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotificationMessageResponse {

    private String id;
    private String ruleId;
    private String ruleName;
    private String eventType;
    private String recipientEmail;
    private String subject;          // шаблон с уже подставленными значениями
    private String body;
    private String status;           // PENDING, SENT, FAILED
    private String createdAt;
    private String scheduledAt;
    private String sentAt;
}
