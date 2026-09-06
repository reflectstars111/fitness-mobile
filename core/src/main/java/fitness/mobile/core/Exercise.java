/* Derived from Good-Badminton motion/exercises.py (Apache-2.0).
 * Copyright 2026 Good-Badminton contributors.
 * Modified for Fitness Mobile: Java enum, immutable phases, primary-signal gate.
 * See provenance/source-manifest.json and licenses/Good-Badminton-Apache-2.0.txt.
 */
package fitness.mobile.core;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Research protocol definitions, NOT validated live coaching rules. */
public enum Exercise {
    SQUAT("squat", "深蹲", "knee_flexion_deg", "descent", "bottom", "ascent",
            "固定侧面，全身入镜；从站立开始并回到站立。正面仅提供描述指标。"),
    PUSHUP("pushup", "俯卧撑", "elbow_flexion_deg", "lowering", "bottom", "pressing",
            "固定侧面，全身与手脚入镜；从直臂支撑开始。仅支持地面标准俯卧撑，不支持跪姿或上斜变式。"),
    BICEPS_CURL("biceps_curl", "站姿弯举", "elbow_flexion_deg", "curling", "peak", "lowering",
            "固定侧面，选择清晰的一侧手臂；上臂自然下垂，从伸肘开始。仅统计所选侧，不推断重量。"),
    HIP_HINGE("hip_hinge", "站姿髋铰链", "hip_flexion_deg", "hinging", "bottom", "extension",
            "固定侧面，全身入镜；从站立开始，屈髋后回到站立。不区分硬拉负重或评价脊柱姿态。");

    public static final String RULE_VERSION = "research-protocol-1";
    public final String id, label, metric, instructions;
    public final List<String> phases;
    Exercise(String id, String label, String metric, String first, String peak, String last, String instructions) {
        this.id = id; this.label = label; this.metric = metric; this.instructions = instructions;
        this.phases = Collections.unmodifiableList(Arrays.asList(first, peak, last));
    }
    private static boolean missing(Map<String, Double> row, String key) {
        return row.get(key) == null || !Double.isFinite(row.get(key));
    }
    /** Null means this posture gate passed, not that the frame/action is fully eligible. */
    public String postureReason(Map<String, Double> row) {
        if (row == null) throw new IllegalArgumentException("Metrics are required");
        String[] required = this == PUSHUP ? new String[]{"trunk_lean_deg", "knee_flexion_deg", "hip_flexion_deg"}
                : this == BICEPS_CURL ? new String[]{"trunk_lean_deg", "upper_arm_tilt_deg"}
                : this == HIP_HINGE ? new String[]{"knee_flexion_deg"} : new String[0];
        for (String key : required) if (missing(row, key)) return "missing_protocol_landmarks";
        if (this == PUSHUP && !(Math.abs(row.get("trunk_lean_deg")) >= 55 &&
                Math.abs(row.get("trunk_lean_deg")) <= 125 && row.get("knee_flexion_deg") <= 40 &&
                row.get("hip_flexion_deg") <= 45)) return "not_standard_horizontal_support";
        if (this == BICEPS_CURL && (Math.abs(row.get("trunk_lean_deg")) > 30 ||
                Math.abs(row.get("upper_arm_tilt_deg")) > 35)) return "not_upright_upper_arm_protocol";
        if (this == HIP_HINGE && row.get("knee_flexion_deg") > 50)
            return "excessive_knee_flexion_for_hinge_protocol";
        return null;
    }
    /** Adds main-signal and view checks; tracking/visibility/time must be gated by the caller. */
    public String signalReason(Map<String, Double> row, boolean sideView) {
        if (row == null) throw new IllegalArgumentException("Metrics are required");
        if (!sideView) return "not_eligible_front_view";
        if (missing(row, metric)) return "missing_signal";
        return postureReason(row);
    }
}
