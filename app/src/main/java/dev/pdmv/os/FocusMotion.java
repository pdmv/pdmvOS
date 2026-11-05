package dev.pdmv.os;

/**
 * Single focus coordinator: never queues or waits for a previous tile to shrink.
 * Both springs are assigned their opposing targets in the SAME frame.
 * Repeated requests for the same item are no-ops, preserving velocity/settling.
 */
public final class FocusMotion {
    private final float inK, inD, outK, outD, enterVelocity, leaveVelocity;
    private SpringValue selected;

    public FocusMotion(float inK, float inD, float outK, float outD,
                       float enterVelocity, float leaveVelocity) {
        this.inK=inK;this.inD=inD;this.outK=outK;this.outD=outD;
        this.enterVelocity=enterVelocity;this.leaveVelocity=leaveVelocity;
    }

    public boolean select(SpringValue next) {
        if (selected==next) return false;
        SpringValue previous=selected;
        selected=next;
        // There must be no completion listener, delay or sequential chain here.
        if(previous!=null){
            previous.aim(0f,outK,outD);
            if(previous.velocity==0f)previous.velocity=-leaveVelocity;
        }
        if(next!=null){
            next.aim(1f,inK,inD);
            if(next.velocity==0f)next.velocity=enterVelocity;
        }
        return true;
    }
    public SpringValue selected(){return selected;}
}
