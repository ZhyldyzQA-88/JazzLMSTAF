package kg.jazzlms.models.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Тело POST/PUT /api/courses.
 * Обёртки (Integer, Boolean) + NON_NULL: незаполненные поля не уходят в JSON,
 * поэтому можно проверить и минимальный запрос, и дефолты сервера.
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CourseRequest {

    private String name;              // required, max 100
    private String code;              // max 20
    private String description;       // max 5000
    private UUID categoryId;
    private BigDecimal price;
    private Integer capacity;
    private String level;
    private Boolean active;
    private Boolean hiddenFromCatalog;
    private Boolean sequential;
    private UUID certificateTemplateId;
    private Boolean noCertificate;
}
