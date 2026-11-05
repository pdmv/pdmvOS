package dev.pdmv.os;

import java.util.*;

public final class CheckSlotLayout {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static void invariant(SlotLayout<String> s,Set<String> original){
        check(s.dock.size()==6,"dock changed size");
        HashSet<String> actual=new HashSet<>();boolean empty=false;
        for(String item:s.dock){
            if(item==null)empty=true;
            else{check(!empty,"unpin left an interior hole");check(actual.add(item),"duplicate dock item");}
        }
        for(String item:s.grid)check(item!=null&&actual.add(item),"duplicate/lost grid item");
        check(actual.equals(original),"operation lost or invented an application");
    }
    public static void main(String[] args){
        List<String> dock=Arrays.asList("A","B","C","D","E","F");
        List<String> grid=Arrays.asList("G","H","I","J","K","L","M");
        HashSet<String> all=new HashSet<>(dock);all.addAll(grid);
        SlotLayout<String> s=new SlotLayout<>(dock,grid);
        check(s.unpin(2),"unpin failed");
        check(s.dock.equals(Arrays.asList("A","B","D","E","F",null)),"right neighbors did not compact");
        check(s.grid.get(0).equals("C"),"unpinned app vanished");
        check(s.toDock(0,s.firstEmpty()),"pin into plus slot failed");
        check(s.dock.get(5).equals("C"),"pin did not fill vacancy");
        check(s.toDock(2,1),"full dock transfer rejected");
        check(s.dock.get(1).equals("I")&&s.grid.get(2).equals("B"),"displaced app did not enter old grid position");
        check(s.toGrid(1,6),"dock-to-grid boundary blocked");
        invariant(s,all);
        Random random=new Random(26416);
        for(int i=0;i<10000;i++){
            switch(random.nextInt(4)){
                case 0:s.unpin(random.nextInt(6));break;
                case 1:if(!s.grid.isEmpty())s.toDock(random.nextInt(s.grid.size()),random.nextInt(6));break;
                case 2:s.toGrid(random.nextInt(6),random.nextInt(s.grid.size()+1));break;
                default:if(random.nextBoolean())s.moveDock(random.nextInt(6),random.nextInt(6));
                    else if(!s.grid.isEmpty())s.moveGrid(random.nextInt(s.grid.size()),random.nextInt(s.grid.size()));
            }
            invariant(s,all);
            SlotLayout<String> restored=new SlotLayout<>(s.dock,s.grid);
            check(s.dock.equals(restored.dock)&&s.grid.equals(restored.grid),"reload changed persisted order");
        }
        while(s.occupied()>0)s.unpin(0);
        check(s.dock.equals(Arrays.asList(null,null,null,null,null,null)),"all-empty dock invalid");
        invariant(s,all);
        check(!s.toDock(-1,0)&&!s.toDock(0,6)&&!s.unpin(6),"invalid index accepted");
        System.out.println("PASS: six slots, compaction, pin, full-dock displacement, cross-region sorting, persistence, 10000 mixed edits");
    }
}
