package fitness.mobile.core;

final class LiveCounterTest {
    private static void require(boolean ok) { if (!ok) throw new AssertionError("Live counter regression"); }
    private static LiveCounter.Update feed(LiveCounter c, long t, Double angle) {
        return c.accept(new LiveCounter.Observation(t, 0, angle, null));
    }
    private static void ready(LiveCounter c, long start) {
        for (long t = start; t <= start + 300; t += 100) feed(c, t, 10.0);
    }
    private static LiveCounter.Update cycle(LiveCounter c, long start) {
        double[] values = {40, 70, 80, 80, 50, 20, 15, 10};
        LiveCounter.Update result = null;
        for (int i = 0; i < values.length; i++) result = feed(c, start + i * 100, values[i]);
        return result;
    }
    static void run() {
        LiveCounter c = new LiveCounter(); ready(c, 0);
        LiveCounter.Update first = cycle(c, 400);
        require(first.count == 1 && first.event != null && first.event.evidenceStartMs == 400 &&
                first.event.evidenceEndMs == 1100 && first.event.expiresAtMs == 2600);
        require(cycle(c, 1200).count == 2);
        c = new LiveCounter(); require(cycle(c, 0).count == 0); // started mid-motion
        c = new LiveCounter(); ready(c, 0); feed(c, 400, 80.0); feed(c, 500, null);
        require(cycle(c, 600).count == 0); // never bridges missing data
        c = new LiveCounter(); ready(c, 0); feed(c, 400, 80.0);
        require(cycle(c, 900).count == 0); // long time gap
        c = new LiveCounter(); ready(c, 0); feed(c, 400, 80.0);
        require("invalid_timestamp".equals(feed(c, 400, 80.0).reason));
        require(cycle(c, 500).count == 0);
        c = new LiveCounter(); ready(c, 0); feed(c, 400, 80.0);
        c.accept(new LiveCounter.Observation(500, 1, 80.0, null));
        require(cycle(c, 600).count == 0);
        c = new LiveCounter(); ready(c, 0); feed(c, 400, 40.0); feed(c, 500, 70.0);
        feed(c, 600, 20.0); feed(c, 700, 20.0);
        require(feed(c, 800, 20.0).count == 0); // one-frame peak is insufficient
        c = new LiveCounter(); ready(c, 0); c.pause("background");
        require(cycle(c, 400).count == 0);
        c = new LiveCounter(); ready(c, 0);
        require(feed(c, 400, Double.NaN).phase == LiveCounter.Phase.UNAVAILABLE);
        c = new LiveCounter(); ready(c, 0);
        for (long t = 400; t <= 20500; t += 100) feed(c, t, 80.0);
        require(cycle(c, 20600).count == 0); // overlong cycle cannot complete
        c = new LiveCounter(); ready(c, 0);
        require("invalid_timestamp".equals(feed(c, 200, 10.0).reason));
        require(cycle(c, 400).count == 0); // out-of-order frame resets qualification
        LiveCounter full = new LiveCounter(); ready(full, 0);
        double[] causal = {40, 70, 80, 80, 50, 20, 15, 10};
        for (int prefix = 0; prefix < causal.length; prefix++) {
            LiveCounter.Update observed = feed(full, 400 + prefix * 100, causal[prefix]);
            LiveCounter replay = new LiveCounter(); ready(replay, 0);
            LiveCounter.Update replayed = null;
            for (int i = 0; i <= prefix; i++) replayed = feed(replay, 400 + i * 100, causal[i]);
            require(observed.count == replayed.count && observed.phase == replayed.phase);
            require(prefix == causal.length - 1 || observed.event == null);
        }
        System.out.println("PASS: live causal counter regression scenarios");
    }
}
