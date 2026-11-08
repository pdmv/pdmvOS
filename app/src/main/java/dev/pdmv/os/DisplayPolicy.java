package dev.pdmv.os;

/** Pure selection policy: maximize pixels without sacrificing the current UI refresh rate. */
final class DisplayPolicy {
    private DisplayPolicy() { }
    static int select(int[] widths, int[] heights, float[] rates, int current) {
        return NativeCore.displayPolicySelect(widths, heights, rates, current);
    }
}
