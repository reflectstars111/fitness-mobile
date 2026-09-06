package fitness.mobile.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class LiveCoachTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static Map<String, Double> metrics(double flex, double trunk, double arm) {
        Map<String, Double> result = new HashMap<>();
        result.put("knee_flexion_deg", flex); result.put("elbow_flexion_deg", flex);
        result.put("trunk_lean_deg", trunk); result.put("upper_arm_tilt_deg", arm);
        return result;
    }
    private static LiveCoach.Update feed(LiveCoach coach, long t, double flex, double trunk, double arm) {
        return coach.accept(t, 0, metrics(flex, trunk, arm), null,
                flex <= 25 ? LiveCounter.Phase.READY : LiveCounter.Phase.MOVING);
    }
    private static void prepare(LiveCoach coach, double trunk, double arm) {
        LiveCoach.Update result = null;
        for (int t = 0; t <= 1200; t += 100) result = feed(coach, t, 10, trunk, arm);
        check(result.calibrated, "stable standing must calibrate");
        check(result.candidates.get(0).ruleId.equals("calibration:ready"), "ready cue");
    }
    private static boolean rule(LiveCoach.Update u, String id) {
        return u.candidates.stream().anyMatch(e -> e.ruleId.equals(id));
    }
    static void run() {
        LiveCoach arm = new LiveCoach(Exercise.BICEPS_CURL, null); prepare(arm, 10, -10);
        for (int t = 1300; t < 2000; t += 100)
            check(!rule(feed(arm, t, 90, 10, 15), "curl:arm"), "no premature arm cue");
        LiveCoach.Update drift = feed(arm, 2000, 90, 10, 15);
        check(rule(drift, "curl:arm"), "sustained arm drift cue");
        check(Math.abs(drift.armDeviation - 25) < 1e-8, "arm must be relative to torso, not vertical");
        CoachEvent evidence = drift.candidates.get(0);
        check(evidence.evidenceStartMs == 1300 && evidence.evidenceEndMs == 2000, "evidence interval");
        check(evidence.detailedText.contains("25度") && evidence.text.contains("肘部"), "actionable angle cue");
        check(!rule(feed(arm, 2100, 90, 10, -10), "curl:arm"), "resolved evidence immediately removed");
        LiveCoach mirrored = new LiveCoach(Exercise.BICEPS_CURL, null); prepare(mirrored, -10, 10);
        for (int t = 1300; t <= 2000; t += 100) drift = feed(mirrored, t, 90, -10, -15);
        check(Math.abs(drift.armDeviation - 25) < 1e-8 && rule(drift, "curl:arm"), "mirror-invariant magnitude");
        LiveCoach sway = new LiveCoach(Exercise.BICEPS_CURL, null); prepare(sway, 0, 0);
        for (int t = 1300; t <= 2000; t += 100) drift = feed(sway, t, 90, 20, -20);
        check(rule(drift, "curl:trunk") && !rule(drift, "curl:arm"), "body sway must not impersonate arm drift");
        LiveCoach missing = new LiveCoach(Exercise.BICEPS_CURL, null); prepare(missing, 0, 0);
        feed(missing, 1300, 90, 0, 30);
        drift = missing.accept(1400, 0, metrics(90, 0, 30), "missing_landmarks", LiveCounter.Phase.UNAVAILABLE);
        check(!drift.calibrated && drift.candidates.isEmpty(), "missing visibility clears action evidence");
        for (int t = 1500; t <= 2600; t += 100) drift = feed(missing, t, 90, 0, 30);
        check(!rule(drift, "curl:arm"), "cannot resume coaching without a new standing reference");
        LiveCoach absent = new LiveCoach(Exercise.SQUAT, null);
        for (int t = 0; t <= 1200; t += 100)
            drift = absent.accept(t, t, Collections.emptyMap(), "no_person", LiveCounter.Phase.UNAVAILABLE);
        check(rule(drift, "setup:no_person"), "unstable missing-person epoch still yields visibility cue");
        LiveCoach gap = new LiveCoach(Exercise.BICEPS_CURL, null); prepare(gap, 0, 0);
        feed(gap, 1300, 90, 0, 30);
        check(!feed(gap, 2000, 90, 0, 30).calibrated, "long gaps invalidate reference");
        check(feed(gap, 1900, 90, 0, 30).candidates.isEmpty(), "out-of-order cannot emit feedback");
        LiveCoach squat = new LiveCoach(Exercise.SQUAT, 80.0); prepare(squat, 0, 0);
        LiveCounter counter = new LiveCounter();
        for (int t = 0; t <= 1200; t += 100) counter.accept(new LiveCounter.Observation(t, 0, 10.0, null));
        double[] shallow = {40, 50, 55, 50, 30, 20, 10, 10};
        for (int i = 0; i < shallow.length; i++) {
            long t = 1300 + i * 100;
            LiveCounter.Update counted = counter.accept(new LiveCounter.Observation(t, 0, shallow[i], null));
            drift = squat.accept(t, 0, metrics(shallow[i], 0, 0), null, counted.phase);
            check(i == shallow.length - 1 || !rule(drift, "squat:target"), "target deficit only after observed return");
        }
        check(rule(drift, "squat:target"), "shallow uncounted cycle still receives target feedback");
        CoachEvent target = drift.candidates.get(0);
        check(target.measured == 55 && target.reference == 80, "personal target evidence");
        check(!rule(feed(squat, 2100, 40, 0, 0), "squat:target"), "old target advice expires on next cycle");
        LiveCoach noTarget = new LiveCoach(Exercise.SQUAT, null); prepare(noTarget, 0, 0);
        for (int i = 0; i < shallow.length; i++) drift = feed(noTarget, 1300 + i * 100, shallow[i], 0, 0);
        check(drift.candidates.isEmpty(), "no universal squat-depth target");
        LiveCoach reached = new LiveCoach(Exercise.SQUAT, 80.0); prepare(reached, 0, 0);
        for (int t = 1300; t <= 1600; t += 100) drift = feed(reached, t, 85, 0, 0);
        check(rule(drift, "squat:goal_reached"), "live goal-reached cue");
        FeedbackGate gate = new FeedbackGate();
        CoachEvent count = new CoachEvent("count", CoachEvent.Kind.COUNT, 10, 1000, 2000, "1", "1", "count", 1.0, null);
        check(gate.select(Arrays.asList(count, evidence), 2000) == evidence, "correction before count");
        gate.delivered(evidence, 2000);
        check(gate.select(Arrays.asList(count, evidence), 2100) == null, "ongoing correction suppresses count spam");
        CoachEvent renewed = new CoachEvent(evidence.ruleId, evidence.kind, evidence.priority, 1300, 20000,
                evidence.text, evidence.detailedText, evidence.metric, 25.0, 0.0);
        check(gate.select(Collections.singletonList(renewed), 20000) == null, "same episode cannot repeat after cooldown");
        CoachEvent visibility = new CoachEvent("setup:no_person", CoachEvent.Kind.SETUP, 100, 2200, 3400, "pause", "pause", "visibility", null, null);
        check(gate.select(Collections.singletonList(visibility), 3400) == visibility, "higher-priority setup bypasses lower-priority global cooldown");
        check(new FeedbackGate().select(Collections.singletonList(evidence), 3500) == null, "expiration boundary excluded");
        check(new FeedbackGate().select(Collections.singletonList(evidence), 1999) == null, "future evidence forbidden");
        try { new LiveCoach(Exercise.SQUAT, Double.NaN); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("PASS: " + checks + " live coaching and feedback checks");
    }
}
