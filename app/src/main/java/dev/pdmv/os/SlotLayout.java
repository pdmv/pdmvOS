package dev.pdmv.os;

import java.util.ArrayList;
import java.util.List;

/** Durable ordering rules, independent of Android and animation state. Null is a vacant slot. */
public final class SlotLayout<T> {
    public static final int CAPACITY=6;
    public final ArrayList<T> dock=new ArrayList<>(), grid=new ArrayList<>();
    public SlotLayout(List<T> pinned,List<T> applications){
        for(T item:pinned)if(item!=null && !dock.contains(item) && dock.size()<CAPACITY)dock.add(item);
        for(T item:applications)if(item!=null && !dock.contains(item) && !grid.contains(item))grid.add(item);
        pad();
    }
    private void pad(){while(dock.size()<CAPACITY)dock.add(null);}
    public int firstEmpty(){return dock.indexOf(null);}
    public int occupied(){int i=firstEmpty();return i<0?CAPACITY:i;}
    public boolean unpin(int slot){return toGrid(slot,0);}
    public boolean toGrid(int slot,int destination){
        if(slot<0||slot>=CAPACITY||dock.get(slot)==null)return false;
        T item=dock.remove(slot);pad();
        grid.add(Math.max(0,Math.min(destination,grid.size())),item);return true;
    }
    public boolean toDock(int source,int destination){
        if(source<0||source>=grid.size()||destination<0||destination>=CAPACITY)return false;
        T item=grid.remove(source);
        int empty=firstEmpty();
        if(empty>=0 && destination>=empty){dock.set(empty,item);}
        else{
            T displaced=dock.set(destination,item);
            if(displaced!=null)grid.add(Math.min(source,grid.size()),displaced);
        }
        return true;
    }
    public boolean moveDock(int from,int to){
        if(from<0||from>=occupied()||to<0||to>=CAPACITY)return false;
        to=Math.min(to,occupied()-1);
        if(from==to)return false;
        T item=dock.remove(from);dock.add(to,item);return true;
    }
    public boolean moveGrid(int from,int to){
        if(from<0||from>=grid.size()||to<0||to>=grid.size()||from==to)return false;
        T item=grid.remove(from);grid.add(to,item);return true;
    }
}
