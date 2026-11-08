package dev.pdmv.os;

/** Coalesces a Home return until both the window and its sound are ready. */
public final class ReturnSoundGate {
    private int bits;
    public void request(){bits=NativeCore.returnGateRequest(bits);}
    public void setActive(boolean value){bits=NativeCore.returnGateSetActive(bits,value);}
    public boolean pending(){return NativeCore.returnGatePending(bits);}
    public boolean canPlay(boolean loaded,boolean enabled){return NativeCore.returnGateCanPlay(bits,loaded,enabled);}
    public boolean retry(){
        long packed=NativeCore.returnGateRetry(bits);
        bits=(int)packed;
        return (packed>>>32)!=0;
    }
    public void consume(){bits=NativeCore.returnGateConsume(bits);}
}
