package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Элемент GET /api/certificates/my: выдаётся при завершении курса, если у курса есть шаблон */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CertificateResponse {

    private UUID id;
    private String code;              // JZ-XXXX-XXXX
    private String issuedAt;
    private UUID courseId;
    private String courseName;
    private UUID templateId;
}
