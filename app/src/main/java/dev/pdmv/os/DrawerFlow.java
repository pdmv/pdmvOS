package dev.pdmv.os;

/** Coupled damped masses: velocity survives reversals, each row follows its predecessor. */
final class DrawerFlow {
    final SpringValue dock=new SpringValue(0f,360f,30f);
    final SpringValue[] rows={new SpringValue(0f,650f,43f),new SpringValue(0f,650f,43f),new SpringValue(0f,650f,43f)};
    private final float[] state=new float[8];
    boolean update(float target,float elapsed){
        // Clear the lower rows before the dock descends into their space.
        // Change only coupling: every mass retains its velocity on reversal.
        state[0]=dock.value;state[1]=dock.velocity;
        for(int i=0;i<3;i++){state[2+i*2]=rows[i].value;state[3+i*2]=rows[i].velocity;}
        boolean busy=NativeCore.drawerFlowUpdate(state,target,elapsed);
        dock.value=state[0];dock.velocity=state[1];
        for(int i=0;i<3;i++){rows[i].value=state[2+i*2];rows[i].velocity=state[3+i*2];}
        if(target>0f){
            dock.aim(target);float lead=dock.value;
            for(SpringValue row:rows){row.aim(lead);lead=row.value;}
        }else{
            float lead=target;
            for(int row=rows.length-1;row>=0;row--){rows[row].aim(lead);lead=rows[row].value;}
            dock.aim(lead);
        }
        return busy;
    }
    boolean busy(){if(dock.busy())return true;for(SpringValue row:rows)if(row.busy())return true;return false;}
    float rowProgress(float relativeRow){
        return NativeCore.drawerFlowRowProgress(rows[0].value,rows[1].value,rows[2].value,relativeRow);
    }
}
