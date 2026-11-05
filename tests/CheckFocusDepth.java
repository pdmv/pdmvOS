package dev.pdmv.os;

public final class CheckFocusDepth {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        FocusDepth a=new FocusDepth(),b=new FocusDepth();
        a.navigate(1,0);b.navigate(1,0);
        check(a.x()<0 && a.y()==0,"right navigation should nudge highlight left");
        for(int i=0;i<60;i++)a.update(1f/60f);
        for(int i=0;i<30;i++)b.update(1f/30f);
        check(Math.abs(a.x()-b.x())<.008f,"frame rate must not change parallax settle");
        check(Math.abs(a.x())<.01f,"directional highlight must settle");
        b.navigate(-1,1);b.navigate(1,-1);
        for(int i=0;i<70;i++)b.update(1f/60f);
        check(!b.active(),"reversed impulses must settle without permanent rendering");
        System.out.println("PASS: directional light recoil, 30/60Hz parity, reversal and idle stop");
    }
}
