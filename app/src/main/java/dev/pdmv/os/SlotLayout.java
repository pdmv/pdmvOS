package dev.pdmv.os;

import java.util.ArrayList;
import java.util.List;

/** Durable ordering rules, independent of Android and animation state. Null is a vacant slot. */
public final class SlotLayout<T> {
    public static final int CAPACITY=6;
    public final ArrayList<T> dock=new ArrayList<>(), grid=new ArrayList<>();
    private final ArrayList<T> pool=new ArrayList<>();
    private final int[] dockIds=new int[]{-1,-1,-1,-1,-1,-1};
    private int[] gridIds;
    private int gridLen;

    public SlotLayout(List<T> pinned,List<T> applications){
        int pCount=0;
        for(T item:pinned)if(item!=null&&pCount<CAPACITY)dockIds[pCount++]=idOf(item);
        gridIds=new int[Math.max(16,pinned.size()+applications.size()+CAPACITY)];
        int gCount=0;
        for(T item:applications)if(item!=null){
            if(gCount>=gridIds.length)gridIds=java.util.Arrays.copyOf(gridIds,gridIds.length*2);
            gridIds[gCount++]=idOf(item);
        }
        gridLen=NativeCore.slotLayoutOp(0,0,0,dockIds,gridIds,gCount);
        syncLists();
    }
    private int idOf(T item){
        int idx=pool.indexOf(item);
        if(idx>=0)return idx;
        pool.add(item);
        return pool.size()-1;
    }
    private void syncLists(){
        dock.clear();
        for(int i=0;i<CAPACITY;i++)dock.add(dockIds[i]<0?null:pool.get(dockIds[i]));
        grid.clear();
        for(int i=0;i<gridLen;i++)grid.add(pool.get(gridIds[i]));
    }
    private boolean exec(int op,int a,int b){
        if(gridLen+CAPACITY>=gridIds.length)gridIds=java.util.Arrays.copyOf(gridIds,Math.max(16,(gridLen+CAPACITY)*2));
        int nextLen=NativeCore.slotLayoutOp(op,a,b,dockIds,gridIds,gridLen);
        if(nextLen<0)return false;
        gridLen=nextLen;
        syncLists();
        return true;
    }
    public int firstEmpty(){return dock.indexOf(null);}
    public int occupied(){int i=firstEmpty();return i<0?CAPACITY:i;}
    public boolean unpin(int slot){return exec(2,slot,0);}
    public boolean toGrid(int slot,int destination){return exec(3,slot,destination);}
    public boolean toDock(int source,int destination){return exec(4,source,destination);}
    public boolean moveDock(int from,int to){return exec(5,from,to);}
    public boolean moveGrid(int from,int to){return exec(6,from,to);}
}
