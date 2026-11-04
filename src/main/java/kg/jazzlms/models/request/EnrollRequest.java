package kg.jazzlms.models.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Тело POST /api/courses/{id}/enrollments. role: LEARNER | INSTRUCTOR (null -> LEARNER) */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class EnrollRequest {
    private UUID userId;
    private String role;
}
