package dev.pdmv.os;

/** Deduplicated visible Wi-Fi transitions; no alerts for a brief reassociation. */
final class WifiFeedback {
    static final int NONE=-1,OFF=0,ON=1,CONNECTED=2,LOST=3;
    private int announced;
    private long pendingLoss=-1;
    WifiFeedback(int initial){announced=initial;}
    int observe(int current,boolean visible,long now){
        if(current<0||current>2)return NONE;
        if(!visible){pendingLoss=-1;return NONE;}
        if(current==announced){pendingLoss=-1;return NONE;}
        if(announced==2&&current==1){
            if(pendingLoss<0){pendingLoss=now;return NONE;}
            if(now-pendingLoss<600)return NONE;
        }
        int event=current==2?CONNECTED:current==0?OFF:announced==2?LOST:ON;
        announced=current;pendingLoss=-1;return event;
    }
    static String label(int event){return event==CONNECTED?"Đã kết nối":event==OFF?"Đã tắt":event==LOST?"Mất kết nối":"Đã bật";}
}
