package fitness.mobile.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static fitness.mobile.core.Geometry.*;

/** Frozen upstream numeric parity plus missing-data and boundary regression tests. */
public final class CoreTest {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }
    private static void near(Double actual, Double expected) {
        check(expected == null ? actual == null : actual != null && Math.abs(actual - expected) < 1e-8);
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]);
        int frames = 0;
        for (String line : Files.readAllLines(fixture)) {
            String[] fields = line.split("\t");
            Map<Joint, Point> points = new HashMap<>();
            int cursor = 1;
            for (Joint joint : Joint.values()) points.put(joint, new Point(
                    Double.parseDouble(fields[cursor++]), Double.parseDouble(fields[cursor++]),
                    Double.parseDouble(fields[cursor++])));
            for (Double actual : frameMetrics(points, Side.valueOf(fields[0].toUpperCase(java.util.Locale.ROOT)), .5, 640, 480).values()) {
                String expected = fields[cursor++];
                near(actual, expected.equals("null") ? null : Double.valueOf(expected));
            }
            check(cursor == fields.length);
            frames++;
        }
        check(frames == 128);
        near(planarAngleDegrees(new Point(1, 0, 1), new Point(0, 0, 1), new Point(0, 1, 1)), 90.0);
        near(planarAngleDegrees(new Point(0, 0, 1), new Point(0, 0, 1), new Point(0, 1, 1)), null);
        near(planarAngleDegrees(new Point(Double.NaN, 0, 1), new Point(0, 0, 1), new Point(0, 1, 1)), null);
        Map<Joint, Point> bad = new HashMap<>();
        bad.put(Joint.LEFT_HIP, new Point(100, 100, Double.NaN));
        bad.put(Joint.LEFT_KNEE, new Point(640, 100, 1));
        Map<String, Double> metrics = frameMetrics(bad, Side.LEFT, .5, 640, 480);
        near(metrics.get("coverage"), 0.0);
        near(metrics.get("knee_flexion_deg"), null);
        try { frameMetrics(bad, Side.LEFT, .5, 0, 480); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        Map<String, Double> row = new HashMap<>();
        for (Exercise exercise : Exercise.values()) {
            check("missing_signal".equals(exercise.signalReason(row, true)));
            check("not_eligible_front_view".equals(exercise.signalReason(row, false)));
        }
        row.put("elbow_flexion_deg", 90.0);
        check("missing_protocol_landmarks".equals(Exercise.BICEPS_CURL.signalReason(row, true)));
        row.put("trunk_lean_deg", 30.0); row.put("upper_arm_tilt_deg", -35.0);
        check(Exercise.BICEPS_CURL.signalReason(row, true) == null);
        row.put("upper_arm_tilt_deg", 35.001);
        check("not_upright_upper_arm_protocol".equals(Exercise.BICEPS_CURL.signalReason(row, true)));
        row.put("upper_arm_tilt_deg", Double.POSITIVE_INFINITY);
        check("missing_protocol_landmarks".equals(Exercise.BICEPS_CURL.signalReason(row, true)));
        row.put("trunk_lean_deg", 55.0); row.put("knee_flexion_deg", 40.0); row.put("hip_flexion_deg", 45.0);
        check(Exercise.PUSHUP.signalReason(row, true) == null);
        row.put("trunk_lean_deg", 54.99);
        check("not_standard_horizontal_support".equals(Exercise.PUSHUP.signalReason(row, true)));
        row.put("knee_flexion_deg", 50.0);
        check(Exercise.HIP_HINGE.signalReason(row, true) == null);
        row.put("knee_flexion_deg", 50.01);
        check("excessive_knee_flexion_for_hinge_protocol".equals(Exercise.HIP_HINGE.signalReason(row, true)));
        System.out.println("PASS: " + checks + " checks, " + frames + " upstream reference frames");
    }
}
