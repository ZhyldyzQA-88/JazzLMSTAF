package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Элемент ответа GET /api/enrollments/my */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MyEnrollmentResponse {
    private UUID enrollmentId;
    private String role;
    private int progress;
    private String enrolledAt;
    private String completedAt;
    private CourseResponse course;
    private List<String> instructors;
}
