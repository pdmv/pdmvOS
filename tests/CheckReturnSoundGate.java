package dev.pdmv.os;

public final class CheckReturnSoundGate {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        for(boolean loadFirst:new boolean[]{true,false}){
            ReturnSoundGate gate=new ReturnSoundGate();
            gate.request();gate.request();
            if(loadFirst){check(!gate.canPlay(true,true),"played before Home focus");gate.setActive(true);}
            else{gate.setActive(true);check(!gate.canPlay(false,true),"played before sample load");}
            check(gate.canPlay(true,true),"lost return while waiting for readiness/focus");
            gate.consume();gate.setActive(false);gate.setActive(true);
            check(!gate.canPlay(true,true),"duplicate sound after window focus changes");
            gate.request();check(!gate.canPlay(true,false),"ignored system mute preference");gate.consume();
            check(!gate.canPlay(true,true),"muted return replayed after enabling sounds");
            gate.request();gate.setActive(false);
            check(!gate.canPlay(true,true),"pending sound escaped into another app");
            gate.setActive(true);check(gate.canPlay(true,true),"temporary focus loss discarded pending return");
            check(gate.retry()&&gate.retry()&&gate.retry()&&!gate.retry(),"playback retries are unbounded");
            gate.consume();check(!gate.pending(),"shutdown retained return event");
        }
        System.out.println("PASS: return waits for load/focus in either order, coalesces duplicates, respects mute, retains temporary focus loss and bounds retries");
    }
}
