package kg.jazzlms.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** GET /api/gamification/leaderboard?by=points|levels|badges&limit=N */
@AllArgsConstructor
@NoArgsConstructor
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeaderboardResponse {

    private String by;
    private List<Entry> top;
    private Entry me;                // текущий пользователь (по токену), даже если он не в top

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        private int rank;
        private UUID userId;
        private String name;
        private long score;
        private boolean me;
    }
}
