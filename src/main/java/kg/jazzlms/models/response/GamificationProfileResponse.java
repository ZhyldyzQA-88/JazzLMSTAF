package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** GET /api/gamification/me и /api/gamification/users/{id} */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GamificationProfileResponse {

    private UUID userId;
    private boolean enabled;
    private int points;
    private int level;
    private int nextLevelPoints;
    private int logins;
    private int units;
    private int courses;
    private List<Badge> badges;
    private List<HistoryItem> history;
    private Integer rank;            // null, пока у пользователя нет очков
    private Integer levelRank;
    private Integer badgeRank;

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Badge {
        private String code;         // newcomer, ...
        private String name;
        private String category;     // Activity, Learning, Certification
        private String icon;
        private String awardedAt;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HistoryItem {
        private String at;
        private String action;       // "Sign in", "Badge: Newcomer", ...
        private int points;
        private String note;
    }
}
