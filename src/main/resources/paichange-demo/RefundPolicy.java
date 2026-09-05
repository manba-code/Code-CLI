/** Offline fixture: policy and its deterministic acceptance checks, no dependencies. */
public class RefundPolicy {
    static boolean manualReview(long hours) {
        return hours > 48;
    }

    static boolean autoCancel(boolean expired) {
        return expired;
    }

    public static void main(String[] args) {
        if (manualReview(23) || manualReview(24) || !manualReview(25)) {
            throw new AssertionError("refund boundary: 23=false, 24=false, 25=true");
        }
        if (!autoCancel(true) || autoCancel(false)) {
            throw new AssertionError("automatic cancellation must remain unchanged");
        }
        System.out.println("PASS: refund boundaries 23/24/25 and automatic cancellation");
    }
}
