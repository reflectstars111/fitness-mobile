package fitness.mobile.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** No queue: choose at most one current event; consumed episodes never reappear. */
public final class FeedbackGate {
    private long lastDelivery = -100000;
    private int lastPriority;
    private String lastRule = "";
    private final Map<String, Long> ruleDelivery = new HashMap<>();
    private final Map<String, String> deliveredEpisode = new HashMap<>();
    public CoachEvent select(List<CoachEvent> events, long now) {
        CoachEvent highest = null;
        for (CoachEvent e : events) {
            if (now < e.evidenceEndMs || now >= e.expiresAtMs) continue;
            if (highest == null || e.priority > highest.priority) highest = e;
        }
        // An ongoing correction suppresses lower-priority counts even while cooling down.
        if (highest == null || highest.id.equals(deliveredEpisode.get(highest.ruleId))) return null;
        long globalCooldown = highest.priority > lastPriority ? 0 :
                lastRule.startsWith("calibration:") ? 0 : "count".equals(lastRule) ? 1500 :
                highest.kind == CoachEvent.Kind.COUNT ? 2000 : 4500;
        long ruleCooldown = highest.kind == CoachEvent.Kind.COUNT ? 2000 : 10000;
        if (now - lastDelivery < globalCooldown || now - ruleDelivery.getOrDefault(highest.ruleId, -100000L) < ruleCooldown) return null;
        return highest;
    }
    /** Record only a delivery actually accepted by the speech transport (or text-only mode). */
    public void delivered(CoachEvent event, long now) {
        lastDelivery = now; lastPriority = event.priority; lastRule = event.ruleId;
        ruleDelivery.put(event.ruleId, now); deliveredEpisode.put(event.ruleId, event.id);
    }
}
