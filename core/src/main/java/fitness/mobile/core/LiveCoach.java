package fitness.mobile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fixed-side research cues, using current/past observations only. No diagnosis or universal depth rule. */
public final class LiveCoach {
    public static final long HOLD_MS = 700, CALIBRATION_MS = 1200;
    public static final class Update {
        public final boolean calibrated;
        public final String status;
        public final List<CoachEvent> candidates;
        public final Double armDeviation, trunkDeviation;
        public final Double baselineArm, baselineTrunk;
        public final long calibrationStartMs, calibrationEndMs;
        Update(boolean calibrated, String status, List<CoachEvent> events, Double arm, Double trunk,
               Double baselineArm, Double baselineTrunk, long start, long end) {
            this.calibrated = calibrated; this.status = status;
            candidates = Collections.unmodifiableList(events); armDeviation = arm; trunkDeviation = trunk;
            this.baselineArm = baselineArm; this.baselineTrunk = baselineTrunk;
            calibrationStartMs = start; calibrationEndMs = end;
        }
    }
    private final Exercise exercise;
    private final Double squatTarget;
    private final Map<String, Long> holds = new HashMap<>();
    private long last = -1, epoch = -1, calibrationStart = -1, cycleStart = -1;
    private int calibrationSamples;
    private double trunkSum, armSum, firstTrunk, firstArm, baselineTrunk, baselineArm, peak;
    private boolean calibrated;
    private CoachEvent targetEvent;
    private CoachEvent readyEvent;
    private long preparingStart = -1;
    private long calibrationEnd = -1;
    public LiveCoach(Exercise exercise, Double squatTarget) {
        if (exercise != Exercise.SQUAT && exercise != Exercise.BICEPS_CURL)
            throw new IllegalArgumentException("Unsupported live coaching exercise");
        if (squatTarget != null && (!Double.isFinite(squatTarget) || squatTarget < 40 || squatTarget > 120))
            throw new IllegalArgumentException("Target must be an explicit finite value in [40,120]");
        this.exercise = exercise; this.squatTarget = squatTarget;
    }
    public void pause() {
        holds.clear(); calibrated = false; clearCalibration(); cycleStart = preparingStart = -1; targetEvent = readyEvent = null;
    }
    private void clearCalibration() { calibrationStart = -1; calibrationSamples = 0; trunkSum = armSum = 0; }
    private static boolean valid(Double value) { return value != null && Double.isFinite(value); }
    private static double wrap(double value) { return ((value + 180) % 360 + 360) % 360 - 180; }
    private static int approx(double value) { return (int) (Math.round(value / 5) * 5); }
    private boolean held(String rule, boolean condition, long now, long duration) {
        if (!condition) { holds.remove(rule); return false; }
        holds.putIfAbsent(rule, now);
        return now - holds.get(rule) >= duration;
    }
    private Update result(String status, List<CoachEvent> events, Double arm, Double trunk) {
        return new Update(calibrated, status, events, arm, trunk,
                calibrated && exercise == Exercise.BICEPS_CURL ? baselineArm : null,
                calibrated ? baselineTrunk : null, calibrated ? calibrationStart : -1, calibrated ? calibrationEnd : -1);
    }
    public Update accept(long t, long trackingEpoch, Map<String, Double> metrics,
                         String qualityReason, LiveCounter.Phase phase) {
        List<CoachEvent> events = new ArrayList<>();
        if (t < 0 || t <= last) { pause(); return result("等待新的有效画面", events, null, null); }
        if ((last >= 0 && t - last > LiveCounter.MAX_GAP_MS) ||
                (qualityReason == null && epoch >= 0 && epoch != trackingEpoch)) pause();
        last = t; epoch = trackingEpoch;
        if (qualityReason != null) {
            calibrated = false; clearCalibration(); cycleStart = preparingStart = -1; targetEvent = readyEvent = null;
            String rule = "setup:" + qualityReason;
            holds.keySet().removeIf(key -> !key.equals(rule));
            String message = setupText(qualityReason);
            if (held(rule, true, t, 1200)) events.add(new CoachEvent(rule, CoachEvent.Kind.SETUP, 100,
                    holds.get(rule), t, message, message, "visibility", null, null));
            return result(message, events, null, null);
        }
        holds.keySet().removeIf(key -> key.startsWith("setup:"));
        Double flex = metrics.get(exercise.metric), trunk = metrics.get("trunk_lean_deg"), arm = metrics.get("upper_arm_tilt_deg");
        if (!valid(flex) || flex < 0 || flex > 180 || !valid(trunk) ||
                (exercise == Exercise.BICEPS_CURL && !valid(arm))) {
            pause(); return result("相关关节看不清，暂停动作提醒", events, null, null);
        }
        double relativeArm = exercise == Exercise.BICEPS_CURL ? wrap(arm + trunk) : 0;
        if (!calibrated) {
            if (preparingStart < 0) preparingStart = t;
            String prepare = "请自然站稳并保持伸展，先记录准备姿势";
            events.add(new CoachEvent("calibration:prepare", CoachEvent.Kind.SETUP, 90, preparingStart, t,
                    prepare, prepare, "standing_reference", null, null));
            if (flex > 25 || Math.abs(trunk) > 20 || Math.abs(relativeArm) > 30) {
                clearCalibration(); return result("请自然站稳、上臂下垂，保持伸展以记录准备姿势", events, null, null);
            }
            if (calibrationStart < 0 || Math.abs(trunk - firstTrunk) > 6 || Math.abs(wrap(relativeArm - firstArm)) > 6) {
                clearCalibration(); calibrationStart = t; firstTrunk = trunk; firstArm = relativeArm;
            }
            trunkSum += trunk; armSum += relativeArm; calibrationSamples++;
            if (t - calibrationStart >= CALIBRATION_MS && calibrationSamples >= 8) {
                baselineTrunk = trunkSum / calibrationSamples; baselineArm = armSum / calibrationSamples; calibrated = true;
                calibrationEnd = t;
                readyEvent = new CoachEvent("calibration:ready", CoachEvent.Kind.SETUP, 90, calibrationStart, t,
                        "准备姿势已记录，可以开始", "准备姿势已记录，可以开始", "standing_reference", null, null);
                events.clear();
                events.add(readyEvent);
            }
            return result(calibrated ? "准备姿势已记录，可以开始" : "保持自然伸展，正在记录准备姿势", events, null, null);
        }
        double trunkDelta = Math.abs(wrap(trunk - baselineTrunk)), armDelta = Math.abs(wrap(relativeArm - baselineArm));
        if (flex > 25) readyEvent = null;
        if (readyEvent != null && flex <= 25 && t < readyEvent.expiresAtMs) events.add(readyEvent);
        if (exercise == Exercise.BICEPS_CURL) {
            boolean moving = flex > 25;
            if (held("curl:trunk", moving && trunkDelta > 12, t, HOLD_MS)) {
                String text = "先站稳，减少身体摆动，再继续弯举";
                events.add(new CoachEvent("curl:trunk", CoachEvent.Kind.MOVEMENT, 80, holds.get("curl:trunk"), t,
                        text, "躯干偏离准备姿势约" + approx(trunkDelta) + "度。" + text,
                        "trunk_deviation_deg", trunkDelta, 0.0));
            }
            if (held("curl:arm", moving && armDelta > 15, t, HOLD_MS)) {
                String text = "让肘部回到身体两侧附近，尽量保持上臂稳定";
                events.add(new CoachEvent("curl:arm", CoachEvent.Kind.MOVEMENT, 70, holds.get("curl:arm"), t,
                        text, "上臂相对准备姿势偏移约" + approx(armDelta) + "度。" + text,
                        "upper_arm_deviation_deg", armDelta, 0.0));
            }
        } else if (squatTarget != null) {
            if (held("squat:goal_reached", flex >= squatTarget && phase != LiveCounter.Phase.READY, t, 300)) {
                String text = "已到你设定的幅度，保持控制，按计划返回";
                events.add(new CoachEvent("squat:goal_reached", CoachEvent.Kind.TARGET, 60,
                        holds.get("squat:goal_reached"), t, text,
                        "当前屈膝约" + approx(flex) + "度。" + text, "knee_flexion_deg", flex, squatTarget));
            }
            if (flex > 25 && cycleStart < 0) { cycleStart = t; peak = flex; targetEvent = null; }
            if (cycleStart >= 0) {
                peak = Math.max(peak, flex);
                if (t - cycleStart > 20000 || phase == LiveCounter.Phase.UNAVAILABLE) { cycleStart = -1; targetEvent = null; }
                else if (phase == LiveCounter.Phase.READY && flex <= 25) {
                    if (peak >= 35 && peak < squatTarget - 10 && t - cycleStart >= 600) {
                        String text = "这次没到设定幅度，下次只在舒适范围内向目标调整";
                        targetEvent = new CoachEvent("squat:target", CoachEvent.Kind.TARGET, 60, cycleStart, t,
                                text, "这次最大屈膝约" + approx(peak) + "度，你设的目标是" + approx(squatTarget) + "度。" + text,
                                "peak_knee_flexion_deg", peak, squatTarget);
                    }
                    cycleStart = -1;
                }
            }
            if (targetEvent != null && t <= targetEvent.expiresAtMs) events.add(targetEvent);
        }
        return result(events.isEmpty() ? "正在观察，未触发提醒不代表动作达标" : events.get(0).detailedText,
                events, exercise == Exercise.BICEPS_CURL ? armDelta : null, trunkDelta);
    }
    public static String setupText(String reason) {
        if ("multiple_people".equals(reason)) return "画面中有多人，请保持单人训练";
        if ("check_side_view".equals(reason) || "not_eligible_front_view".equals(reason)) return "请暂停，重新把手机固定在身体侧面";
        if ("tracking_reset".equals(reason)) return "位置变化，请暂停并重新站稳";
        if ("stream_timeout".equals(reason) || "stale_frame".equals(reason)) return "画面处理跟不上，暂停动作评价，请检查相机";
        return "看不清关键部位，请调整距离，让身体和关节完整入镜";
    }
}
