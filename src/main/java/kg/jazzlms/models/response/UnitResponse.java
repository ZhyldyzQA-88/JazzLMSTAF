package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** UnitDto. completed / startedAt / locked — с точки зрения текущего пользователя (по токену) */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnitResponse {

    private UUID id;
    private UUID courseId;
    private String type;
    private String name;
    private int position;             // порядок в курсе, с 1
    private boolean active;
    private String completionType;
    private String sourceType;
    private String youtubeUrl;
    private String embedUrl;          // youtube.com/embed/<id> — то, что показывает плеер
    private String description;
    private String createdAt;
    private boolean completed;
    private String startedAt;
    private boolean locked;           // true в последовательном курсе, пока не пройден предыдущий урок
}
