package dev.pdmv.os;

/** Coalesces a Home return until both the window and its sound are ready. */
public final class ReturnSoundGate {
    private boolean pending,active;
    private int retries;
    public void request(){if(!pending)retries=0;pending=true;}
    public void setActive(boolean value){active=value;}
    public boolean pending(){return pending;}
    public boolean canPlay(boolean loaded,boolean enabled){return pending&&active&&loaded&&enabled;}
    public boolean retry(){return pending&&++retries<=3;}
    public void consume(){pending=false;retries=0;}
}
