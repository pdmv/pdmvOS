package dev.pdmv.os;

/** Coupled damped masses: velocity survives reversals, each row follows its predecessor. */
final class DrawerFlow {
    final SpringValue dock=new SpringValue(0f,360f,30f);
    final SpringValue[] rows={new SpringValue(0f,650f,43f),new SpringValue(0f,650f,43f),new SpringValue(0f,650f,43f)};
    boolean update(float target,float elapsed){
        int steps=Math.max(1,(int)Math.ceil(Math.min(.25f,elapsed)*120f));
        float dt=Math.min(.25f,elapsed)/steps;
        for(int i=0;i<steps;i++){
            if(target>0f){
                dock.aim(target);dock.update(dt);float lead=dock.value;
                for(SpringValue row:rows){row.aim(lead);row.update(dt);lead=row.value;}
            }else{
                // Clear the lower rows before the dock descends into their space.
                // Change only coupling: every mass retains its velocity on reversal.
                float lead=target;
                for(int row=rows.length-1;row>=0;row--){
                    rows[row].aim(lead);rows[row].update(dt);lead=rows[row].value;
                }
                dock.aim(lead);dock.update(dt);
            }
        }
        return busy();
    }
    boolean busy(){if(dock.busy())return true;for(SpringValue row:rows)if(row.busy())return true;return false;}
    float rowProgress(float relativeRow){
        float position=Math.max(0f,Math.min(2f,relativeRow));int i=(int)position;
        return i==2?rows[2].value:rows[i].value+(rows[i+1].value-rows[i].value)*(position-i);
    }
}
