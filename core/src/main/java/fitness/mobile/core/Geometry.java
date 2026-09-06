/* Derived from Good-Badminton motion/geometry.py (Apache-2.0).
 * Copyright 2026 Good-Badminton contributors.
 * Modified for Fitness Mobile: Java port, named points, explicit input validation.
 * See provenance/source-manifest.json and licenses/Good-Badminton-Apache-2.0.txt.
 */
package fitness.mobile.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Image-plane measurements only. Coordinates must share the same pixel scale. */
public final class Geometry {
    private Geometry() {}
    public enum Side { LEFT, RIGHT }
    public enum Joint {
        NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR,
        LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW,
        LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP,
        LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE
    }
    public static final class Point {
        public final double x, y, confidence;
        public Point(double x, double y, double confidence) {
            this.x = x; this.y = y; this.confidence = confidence;
        }
    }

    public static Double planarAngleDegrees(Point first, Point vertex, Point third) {
        if (!finite(first) || !finite(vertex) || !finite(third)) return null;
        double ax = first.x - vertex.x, ay = first.y - vertex.y;
        double bx = third.x - vertex.x, by = third.y - vertex.y;
        double na = Math.hypot(ax, ay), nb = Math.hypot(bx, by);
        if (!Double.isFinite(na) || !Double.isFinite(nb) || Math.min(na, nb) <= 1e-9) return null;
        double cosine = (ax / na) * (bx / nb) + (ay / na) * (by / nb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
    }
    private static boolean finite(Point p) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y);
    }
    private static Joint joint(Side side, String name) {
        return Joint.valueOf(side.name() + "_" + name);
    }
    private static boolean separated(Point a, Point b) {
        return a != null && b != null && Math.hypot(a.x - b.x, a.y - b.y) > 1e-6;
    }

    /** Missing/invalid points produce null metrics; coverage uses the COCO17 subset. */
    public static Map<String, Double> frameMetrics(Map<Joint, Point> input, Side side,
                                                    double confidence, int width, int height) {
        if (input == null || side == null || width <= 0 || height <= 0 ||
                !Double.isFinite(confidence) || confidence <= 0 || confidence > 1)
            throw new IllegalArgumentException("Invalid frame dimensions, side or confidence");
        Map<Joint, Point> points = new HashMap<>();
        for (Joint key : Joint.values()) {
            Point p = input.get(key);
            if (finite(p) && Double.isFinite(p.confidence) && p.confidence >= confidence &&
                    p.confidence <= 1 && p.x >= 0 && p.x < width && p.y >= 0 && p.y < height)
                points.put(key, p);
        }
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("coverage", points.size() / 17.0);
        for (String name : new String[]{"knee_flexion_deg", "hip_flexion_deg", "trunk_lean_deg",
                "hip_height_image_ratio", "knee_span_ankle_span_ratio", "shoulder_tilt_deg",
                "hip_tilt_deg", "elbow_flexion_deg", "upper_arm_tilt_deg"}) result.put(name, null);
        String[][] angles = {{"knee_flexion_deg", "HIP", "KNEE", "ANKLE"},
                {"hip_flexion_deg", "SHOULDER", "HIP", "KNEE"},
                {"elbow_flexion_deg", "SHOULDER", "ELBOW", "WRIST"}};
        for (String[] spec : angles) {
            Double angle = planarAngleDegrees(points.get(joint(side, spec[1])),
                    points.get(joint(side, spec[2])), points.get(joint(side, spec[3])));
            result.put(spec[0], angle == null ? null : 180 - angle);
        }
        Point shoulder = points.get(joint(side, "SHOULDER"));
        Point hip = points.get(joint(side, "HIP"));
        Point elbow = points.get(joint(side, "ELBOW"));
        if (separated(shoulder, elbow)) result.put("upper_arm_tilt_deg",
                Math.toDegrees(Math.atan2(elbow.x - shoulder.x, elbow.y - shoulder.y)));
        if (hip != null) result.put("hip_height_image_ratio", 1 - hip.y / height);
        if (separated(shoulder, hip)) result.put("trunk_lean_deg",
                Math.toDegrees(Math.atan2(shoulder.x - hip.x, hip.y - shoulder.y)));
        for (String name : new String[]{"SHOULDER", "HIP"}) {
            Point a = points.get(joint(Side.LEFT, name)), b = points.get(joint(Side.RIGHT, name));
            if (a != null && b != null && Math.abs(a.x - b.x) > 1)
                result.put(name.equals("HIP") ? "hip_tilt_deg" : "shoulder_tilt_deg",
                        Math.toDegrees(Math.atan((b.y - a.y) / (b.x - a.x))));
        }
        Point la = points.get(Joint.LEFT_ANKLE), ra = points.get(Joint.RIGHT_ANKLE);
        Point lk = points.get(Joint.LEFT_KNEE), rk = points.get(Joint.RIGHT_KNEE);
        if (la != null && ra != null && lk != null && rk != null && Math.abs(la.x - ra.x) >= width * .02)
            result.put("knee_span_ankle_span_ratio", Math.abs(lk.x - rk.x) / Math.abs(la.x - ra.x));
        return result;
    }
}
