package dev.pdmv.os;
public final class CheckDrawerFlow {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        DrawerFlow f=new DrawerFlow();
        for(int i=0;i<9;i++)f.update(1,1f/60);
        check(f.dock.value>f.rows[0].value&&f.rows[0].value>f.rows[1].value&&f.rows[1].value>f.rows[2].value,"rows must trail preceding mass");
        float velocity=f.dock.velocity;f.update(0,1f/120);
        check(velocity>0&&f.dock.velocity>0,"reversal must retain momentum");
        for(int i=0;i<360;i++)f.update(0,1f/60);
        check(!f.busy()&&f.dock.value==0&&f.rows[2].value==0,"all masses must settle");
        DrawerFlow closing=new DrawerFlow();
        for(int i=0;i<240;i++)closing.update(1,1f/60);
        for(int i=0;i<12;i++){
            closing.update(0,1f/60);
            check(closing.dock.value>=closing.rows[0].value&&closing.rows[0].value>=closing.rows[1].value&&closing.rows[1].value>=closing.rows[2].value,"close must clear bottom rows before dock follows");
        }
        for(int i=0;i<300;i++)closing.update(0,1f/60);
        check(!closing.busy(),"closing chain must settle");
        DrawerFlow a=new DrawerFlow(),b=new DrawerFlow();
        for(int i=0;i<30;i++)a.update(1,1f/30);
        for(int i=0;i<60;i++)b.update(1,1f/60);
        check(Math.abs(a.rows[2].value-b.rows[2].value)<.003,"frame rate parity");
        for(int i=0;i<360;i++)a.update(i%40<20?1:0,1f/60);
        check(a.dock.value>-.04&&a.dock.value<1.04,"interrupted motion remains bounded");
        System.out.println("PASS: staggered physical rows, momentum on reversal, settling and frame-rate parity");
    }
}
