package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CourseResponse {

    private UUID id;
    private String name;
    private String code;
    private String description;
    private UUID categoryId;
    private String categoryName;
    private BigDecimal price;
    private Integer capacity;
    private String level;
    private boolean active;
    private boolean hiddenFromCatalog;
    private UUID createdBy;
    private String createdAt;
    private String updatedAt;
    private long learnersCount;       // в swagger int64
    private boolean sequential;
    private UUID certificateTemplateId;
}
