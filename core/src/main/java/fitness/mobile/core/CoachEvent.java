package fitness.mobile.core;

/** Immutable, expiring evidence. Angles are image-plane estimates, not anatomical truth. */
public final class CoachEvent {
    public enum Kind { SETUP, MOVEMENT, TARGET, COUNT }
    public final String id, ruleId, text, detailedText, metric;
    public final Kind kind;
    public final int priority;
    public final long evidenceStartMs, evidenceEndMs, expiresAtMs;
    public final Double measured, reference;
    public final String ruleVersion = "coach-research-1";
    public CoachEvent(String ruleId, Kind kind, int priority, long start, long end,
                      String text, String detailedText, String metric, Double measured, Double reference) {
        this.id = ruleId + ":" + start; this.ruleId = ruleId; this.kind = kind; this.priority = priority;
        evidenceStartMs = start; evidenceEndMs = end; expiresAtMs = end + 1500;
        this.text = text; this.detailedText = detailedText; this.metric = metric;
        this.measured = measured; this.reference = reference;
    }
    public static CoachEvent count(LiveCounter.Event event) {
        return new CoachEvent("count", Kind.COUNT, 10, event.evidenceStartMs, event.evidenceEndMs,
                Integer.toString(event.count), Integer.toString(event.count), "candidate_count", (double) event.count, null);
    }
}
