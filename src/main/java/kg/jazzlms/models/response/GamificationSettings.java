package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET/PUT /api/gamification/settings — одна и та же схема в запросе и в ответе.
 * PUT частичный только на верхнем уровне: блок, которого нет в JSON, не меняется (поэтому обёртки + NON_NULL),
 * а переданный блок (points, badges, levels, leaderboard) заменяется целиком — отправляйте его полностью.
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GamificationSettings {

    private String id;               // всегда "default"
    private Boolean enabled;
    private Points points;
    private Badges badges;
    private Levels levels;
    private Leaderboard leaderboard;

    @AllArgsConstructor @NoArgsConstructor @Builder @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Points {
        private Boolean enabled;
        private PointRule login;
        private PointRule unit;
        private PointRule course;
        private PointRule certificate;
        private PointRule test;
    }

    @AllArgsConstructor @NoArgsConstructor @Builder @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PointRule {
        private Boolean enabled;
        private Integer points;
    }

    @AllArgsConstructor @NoArgsConstructor @Builder @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Badges {
        private Boolean enabled;
        private Boolean activity;
        private Boolean learning;
        private Boolean certification;
    }

    @AllArgsConstructor @NoArgsConstructor @Builder @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Levels {
        private Boolean enabled;
        private LevelRule byPoints;
        private LevelRule byCourses;
        private LevelRule byBadges;
    }

    @AllArgsConstructor @NoArgsConstructor @Builder @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LevelRule {
        private Boolean enabled;
        private Integer every;
    }

    @AllArgsConstructor @NoArgsConstructor @Builder @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Leaderboard {
        private Boolean enabled;
        private Boolean showLevels;
        private Boolean showPoints;
        private Boolean showBadges;
    }
}
