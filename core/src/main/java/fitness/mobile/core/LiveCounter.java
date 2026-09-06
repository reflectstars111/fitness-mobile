package fitness.mobile.core;

/** Causal research counter. One instance per exercise/set, called serially. */
public final class LiveCounter {
    public static final String RULE_VERSION = "live-candidate-1";
    public static final long MAX_GAP_MS = 350;
    public enum Phase { UNAVAILABLE, PREPARING, READY, MOVING, PEAK, RETURNING }
    public static final class Observation {
        public final long timestampMs, trackingEpoch;
        public final Double flexion;
        public final String rejection;
        public Observation(long timestampMs, long trackingEpoch, Double flexion, String rejection) {
            this.timestampMs = timestampMs; this.trackingEpoch = trackingEpoch;
            this.flexion = flexion; this.rejection = rejection;
        }
    }
    public static final class Event {
        public final int count;
        public final long evidenceStartMs, evidenceEndMs, expiresAtMs;
        public final String ruleVersion = RULE_VERSION;
        Event(int count, long start, long end) {
            this.count = count; evidenceStartMs = start; evidenceEndMs = end;
            expiresAtMs = end + 1500;
        }
    }
    public static final class Update {
        public final Phase phase;
        public final int count;
        public final String reason;
        public final Event event;
        Update(Phase phase, int count, String reason, Event event) {
            this.phase = phase; this.count = count; this.reason = reason; this.event = event;
        }
    }
    private long last = -1, epoch = -1, standingSince = -1, start = -1, peakSince = -1, returnSince = -1;
    private int count;
    private boolean armed, reachedPeak;
    private Phase phase = Phase.UNAVAILABLE;
    public Update pause(String reason) {
        armed = reachedPeak = false;
        standingSince = start = peakSince = returnSince = -1;
        phase = Phase.UNAVAILABLE;
        return new Update(phase, count, reason, null);
    }
    public Update accept(Observation observation) {
        long t = observation.timestampMs;
        if (t < 0 || t <= last) return pause("invalid_timestamp");
        boolean gap = last >= 0 && t - last > MAX_GAP_MS;
        boolean switched = epoch >= 0 && epoch != observation.trackingEpoch;
        last = t; epoch = observation.trackingEpoch;
        if (gap || switched) pause(gap ? "observation_gap" : "tracking_reset");
        if (observation.rejection != null) return pause(observation.rejection);
        Double angle = observation.flexion;
        if (angle == null || !Double.isFinite(angle) || angle < 0 || angle > 180)
            return pause("missing_signal");
        if (!armed) {
            phase = Phase.PREPARING;
            if (angle <= 25) {
                if (standingSince < 0) standingSince = t;
                if (t - standingSince >= 300) { armed = true; phase = Phase.READY; }
            } else standingSince = -1;
            return new Update(phase, count, null, null);
        }
        if (start < 0) {
            if (angle <= 25) return new Update(Phase.READY, count, null, null);
            start = t;
        }
        if (t - start > 20000) return pause("duration_exceeded");
        if (angle >= 65) {
            if (peakSince < 0) peakSince = t;
            if (t - peakSince >= 120) reachedPeak = true;
        } else peakSince = -1;
        phase = reachedPeak ? (angle >= 65 ? Phase.PEAK : Phase.RETURNING) : Phase.MOVING;
        if (angle <= 25) {
            if (returnSince < 0) returnSince = t;
            if (t - returnSince >= 200) {
                Event event = reachedPeak && t - start >= 600 ? new Event(++count, start, t) : null;
                String reason = event == null ? "incomplete_excursion" : null;
                start = peakSince = returnSince = -1;
                reachedPeak = false; phase = Phase.READY;
                return new Update(phase, count, reason, event);
            }
        } else returnSince = -1;
        return new Update(phase, count, null, null);
    }
}
