package kg.jazzlms.models.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Урок (unit) курса: часть "unit" multipart-запроса POST /api/courses/{courseId}/units.
 * Обязательные: name (max 80), type. Для видео с YouTube: type = VIDEO, sourceType = YOUTUBE, youtubeUrl.
 * completionType по умолчанию CHECKBOX — урок завершается кнопкой "Готово" после открытия.
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnitRequest {

    private String name;
    private String type;              // VIDEO, PRESENTATION, CONTENT, TEST
    private String completionType;    // CHECKBOX, QUESTION, TIME
    private Integer timeLimitSeconds;  // для TIME
    private String question;          // для QUESTION
    private String answer;
    private String sourceType;        // YOUTUBE, UPLOAD
    private String youtubeUrl;
    private String textContent;
    private String description;
    private Boolean active;
    private Boolean autoplay;
    private Boolean showSpeed;
}
