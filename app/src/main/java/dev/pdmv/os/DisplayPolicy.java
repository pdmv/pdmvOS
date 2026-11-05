package dev.pdmv.os;

/** Pure selection policy: maximize pixels without sacrificing the current UI refresh rate. */
final class DisplayPolicy {
    private DisplayPolicy() { }
    static int select(int[] widths, int[] heights, float[] rates, int current) {
        if (widths.length != heights.length || widths.length != rates.length
                || current < 0 || current >= widths.length) throw new IllegalArgumentException("modes");
        int best=current;
        float fastest=rates[current];
        double currentAspect=(double)widths[current]/heights[current];
        for(int i=0;i<rates.length;i++)if(widths[i]>0&&heights[i]>0&&Math.abs((double)widths[i]/heights[i]-currentAspect)<=.02)fastest=Math.max(fastest,rates[i]);
        float floor=Math.min(60f,fastest)-.6f;
        double aspect=(double)widths[current]/heights[current];
        for (int i=0;i<widths.length;i++) {
            if (widths[i]<=0 || heights[i]<=0 || rates[i]<floor
                    || Math.abs((double)widths[i]/heights[i]-aspect)>.02) continue;
            long pixels=(long)widths[i]*heights[i], previous=(long)widths[best]*heights[best];
            if (rates[best]<floor || pixels>previous || (pixels==previous && rates[i]>rates[best]+.6f)) best=i;
        }
        return best;
    }
}
