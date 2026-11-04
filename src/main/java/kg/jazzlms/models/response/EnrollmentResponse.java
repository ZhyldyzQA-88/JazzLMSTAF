package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** EnrollmentDto. userFullName и userEmail course-service берёт из user-service по gRPC */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnrollmentResponse {
    private UUID id;
    private UUID courseId;
    private UUID userId;
    private String userFullName;
    private String userEmail;
    private String role;
    private int progress;
    private String enrolledAt;
    private String completedAt;
}
