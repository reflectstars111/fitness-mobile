package fitness.mobile.core;

/** Local observed deviation, NOT an overall correctness classifier or proof of pad contact. */
public final class ChestPressCoach {
    public static final String VERSION = "chest-local-deviation-research-1";
    public static final class Update {
        public final String status;
        public final CoachEvent issue;
        public final Double baselineTrunk, deviation;
        public final long referenceStartMs, referenceEndMs;
        Update(String status, CoachEvent issue, Double baseline, Double deviation, long start, long end) {
            this.status = status; this.issue = issue; baselineTrunk = baseline; this.deviation = deviation;
            referenceStartMs = start; referenceEndMs = end;
        }
    }
    private long last = -1, epoch = -1, start = -1, referenceEnd = -1, hold = -1, motionAt = -1;
    private int samples;
    private double first, sum, baseline, anchor;
    private boolean calibrated;
    private void reset() { start = referenceEnd = hold = motionAt = -1; samples = 0; sum = 0; calibrated = false; }
    private static double difference(double a, double b) { return Math.abs(((a - b + 180) % 360 + 360) % 360 - 180); }
    private Update result(String status, CoachEvent issue, Double deviation) {
        return new Update(status, issue, calibrated ? baseline : null, deviation, start, referenceEnd);
    }
    public Update accept(long t, long trackingEpoch, Double trunk, Double elbowFlexion, String quality, boolean fixedCamera) {
        if (t < 0 || t <= last) { reset(); return result("invalid_timestamp", null, null); }
        if (last >= 0 && (t - last > 350 || epoch != trackingEpoch)) reset();
        last = t; epoch = trackingEpoch;
        if (!fixedCamera || quality != null || trunk == null || elbowFlexion == null ||
                !Double.isFinite(trunk) || !Double.isFinite(elbowFlexion) || elbowFlexion < 0 || elbowFlexion > 180) {
            reset(); return result(!fixedCamera ? "camera_not_confirmed_fixed" : "local_landmarks_unavailable", null, null);
        }
        if (!calibrated) {
            // A seated reference may lean with the backrest. Never impose a vertical-torso target.
            if (start < 0 || difference(trunk, first) > 4) {
                reset(); start = t; first = trunk; anchor = elbowFlexion;
            }
            sum += trunk; samples++;
            if (t - start >= 1200 && samples >= 8) { baseline = sum / samples; calibrated = true; referenceEnd = t; }
            return result(calibrated ? "reference_ready" : "recording_reference", null, null);
        }
        if (Math.abs(elbowFlexion - anchor) > 8) { motionAt = t; anchor = elbowFlexion; }
        double delta = difference(trunk, baseline);
        if (delta > 12 && motionAt >= 0 && t - motionAt <= 1500) {
            if (hold < 0) hold = t;
            if (t - hold >= 700) {
                String text = "推胸时躯干姿态明显变化，请保持身体稳定，检查背部支撑";
                CoachEvent issue = new CoachEvent("chest:trunk_change", CoachEvent.Kind.MOVEMENT, 80, hold, t,
                    text, "躯干相对本组准备姿势变化约" + Math.round(delta / 5) * 5 + "度。" + text,
                    "trunk_lean_deg", trunk, baseline, VERSION);
                return result("local_deviation_candidate", issue, delta);
            }
        } else hold = -1;
        return result("no_sustained_local_deviation", null, delta);
    }
}
