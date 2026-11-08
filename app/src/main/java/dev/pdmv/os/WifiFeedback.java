package dev.pdmv.os;

/** Deduplicated visible Wi-Fi transitions; no alerts for a brief reassociation. */
final class WifiFeedback {
    static final int NONE=-1,OFF=0,ON=1,CONNECTED=2,LOST=3;
    private final int[] announced=new int[1];
    private final long[] pendingLoss=new long[]{-1L};
    WifiFeedback(int initial){announced[0]=initial;}
    int observe(int current,boolean visible,long now){
        return NativeCore.wifiFeedbackObserve(announced,pendingLoss,current,visible,now);
    }
    static String label(int event){return event==CONNECTED?"Đã kết nối":event==OFF?"Đã tắt":event==LOST?"Mất kết nối":"Đã bật";}
}
