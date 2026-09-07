package fitness.mobile.core;

final class ChestPressCoachTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Chest review check " + checks); }
    static void run() {
        ChestPressCoach coach = new ChestPressCoach();
        ChestPressCoach.Update u = null;
        for (long t = 0; t <= 1200; t += 100) u = coach.accept(t, 0, -25.0, 90.0, null, true);
        check(u.baselineTrunk != null && u.baselineTrunk == -25.0); // reclined is a valid reference
        check(u.issue == null);
        for (long t = 1300; t < 2000; t += 100) {
            u = coach.accept(t, 0, -5.0, 65.0, null, true); check(u.issue == null);
        }
        u = coach.accept(2000, 0, -5.0, 65.0, null, true);
        check(u.issue != null && u.issue.evidenceStartMs == 1300);
        check(u.issue.reference == -25.0 && u.deviation == 20.0);
        check(u.issue.ruleVersion.equals(ChestPressCoach.VERSION));
        check(coach.accept(2100, 0, -25.0, 90.0, null, true).issue == null);
        check(coach.accept(2200, 0, -5.0, 65.0, "missing_landmarks", true).baselineTrunk == null);
        check(coach.accept(2300, 0, -5.0, 65.0, null, false).status.equals("camera_not_confirmed_fixed"));
        for (long t = 2400; t <= 3600; t += 100) u = coach.accept(t, 0, 25.0, 90.0, null, true);
        check(u.baselineTrunk == 25.0);
        check(coach.accept(4100, 0, 5.0, 65.0, null, true).issue == null); // gap resets
        check(coach.accept(4100, 0, 5.0, 65.0, null, true).baselineTrunk == null); // duplicate
        check(coach.accept(4200, 1, Double.NaN, 65.0, null, true).issue == null);
        System.out.println("PASS: " + checks + " local chest deviation checks");
    }
}
