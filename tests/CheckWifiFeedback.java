package dev.pdmv.os;
public final class CheckWifiFeedback {
    static void expect(int expected,int actual){if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
    public static void main(String[] args){
        WifiFeedback f=new WifiFeedback(0);
        expect(-1,f.observe(0,true,0));expect(1,f.observe(1,true,10));expect(-1,f.observe(1,true,20));
        expect(2,f.observe(2,true,30));expect(-1,f.observe(2,true,40));
        expect(-1,f.observe(1,true,100));expect(-1,f.observe(1,true,699));expect(3,f.observe(1,true,700));expect(-1,f.observe(1,true,900));
        expect(2,f.observe(2,true,1000));expect(-1,f.observe(1,true,1100));expect(-1,f.observe(2,true,1200));
        expect(-1,f.observe(1,true,1300));expect(0,f.observe(0,true,1400));expect(-1,f.observe(0,true,2000));
        expect(2,f.observe(2,true,2100));expect(-1,f.observe(0,false,2200));expect(-1,f.observe(1,false,2300));expect(-1,f.observe(2,true,2400));
        expect(-1,f.observe(0,false,2500));expect(0,f.observe(0,true,2600));expect(-1,f.observe(0,true,2700));
        expect(-1,f.observe(-1,true,2800));expect(-1,f.observe(4,true,2900));
        System.out.println("PASS: Wi-Fi deduplication, visible-only alerts, transient loss suppression, deliberate off, latest background state");
    }
}
