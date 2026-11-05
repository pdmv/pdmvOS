package dev.pdmv.os;

import android.content.*;
import android.content.pm.*;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import java.io.InputStream;
import java.util.*;
import android.util.Log;

/** Catalog, stable custom grid order, and persistent dock order. No package disabling. */
final class TileRepository {
    private static final String TAG="pdmvOS.Catalog";
    private final Context context;
    private final SharedPreferences prefs;
    final ArrayList<Tile> dock=new ArrayList<>();
    final ArrayList<Tile> grid=new ArrayList<>();
    final ArrayList<Tile> all=new ArrayList<>();
    TileRepository(Context context,SharedPreferences prefs){
        this.context=context;this.prefs=prefs;refresh();
    }
    private Tile shortcut(String id,String label){return new Tile(id,label);}
    void refresh(){
        final PackageManager pm=context.getPackageManager();
        HashMap<String,Tile> previous=new HashMap<>();
        for(Tile tile:all)previous.putIfAbsent(tile.id,tile);
        dock.clear();grid.clear();all.clear();
        Intent leanback=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> info;
        try{info=pm.queryIntentActivities(leanback,0);}catch(Throwable queryError){
            Log.e(TAG,"Cannot query Leanback activities",queryError);info=Collections.emptyList();
        }
        if(info==null)info=Collections.emptyList();
        HashSet<String> seen=new HashSet<>();
        for(ResolveInfo r:info){
            try{
                if(r==null||r.activityInfo==null)continue;
                String name=r.activityInfo.packageName;
                if(name==null||name.equals(context.getPackageName())||!seen.add(name))continue;
                CharSequence label;
                try{label=r.loadLabel(pm);}catch(Throwable labelError){label=name;}
                String title=label==null?name:label.toString();
                Tile tile=previous.get("app:"+name);if(tile==null)tile=new Tile("app:"+name,title);
                long revision=pm.getPackageInfo(name,0).lastUpdateTime;
                boolean reload=tile.packageRevision!=revision||(!tile.preparationAttempted&&tile.artwork==null)||!tile.label.equals(title);
                tile.label=title;tile.packageName=name;tile.retired=false;tile.installPlaceholder=false;
                tile.packageRevision=revision;tile.presence.aim(1f);
                if(reload){tile.artwork=null;tile.banner=false;tile.preparationAttempted=false;
                tile.uninstallable=(r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)==0;
                // Decode banners near the prepared 504px texture size, not 4x density. A broken resource in
                // one third-party package must never prevent the launcher from starting.
                if(r.activityInfo.banner!=0){
                    try{
                        Resources res=pm.getResourcesForApplication(name);
                        tile.artwork=res.getDrawableForDensity(r.activityInfo.banner,320,null);
                    }catch(Throwable ignored){}
                }
                if(tile.artwork==null)try{tile.artwork=r.activityInfo.loadBanner(pm);}catch(Throwable ignored){}
                if(tile.artwork==null)try{tile.artwork=pm.getApplicationBanner(name);}catch(Throwable ignored){}
                if(tile.artwork!=null)tile.banner=true;
                if(tile.artwork==null)try{tile.artwork=r.loadIcon(pm);}catch(Throwable ignored){}
                if(tile.artwork==null)try{tile.artwork=pm.getApplicationIcon(name);}catch(Throwable ignored){}
                try(InputStream input=context.getAssets().open("artwork/"+name+".png")){
                    Bitmap image=BitmapFactory.decodeStream(input);
                    if(image!=null){tile.artwork=new BitmapDrawable(context.getResources(),image);tile.banner=true;}
                }catch(Throwable ignored){}
                }
                all.add(tile);previous.remove(tile.id);
            }catch(Throwable packageError){
                Log.w(TAG,"Skipping broken launcher entry",packageError);
            }
        }
        boolean removed=false;
        for(Tile tile:previous.values())if(!tile.isEmpty()){
            if(!(tile.installPlaceholder&&tile.installSessionId>=0)){
                removed=removed||!tile.retired;tile.retired=true;tile.installSessionId=-1;
            }
            all.add(tile);
        }
        all.sort((a,b)->a.label.compareToIgnoreCase(b.label));
        String defaults="app:com.google.android.youtube.tv|app:com.apple.atve.androidtv.appletv|app:com.viettel.tv360.tv";
        for(String id:prefs.getString("dock",defaults).split("\\|")){
            Tile tile=lookup(id);
            if(tile!=null&&!tile.retired&&!dock.contains(tile)&&dock.size()<UiGeometry.COLUMNS)dock.add(tile);
        }
        for(int i=0;i<6;i++){
            Tile empty=previous.get("empty:"+i);all.add(empty==null?shortcut("empty:"+i,"Ghim ứng dụng"):empty);
        }
        fillEmptySlots();
        refreshGrid();
        if(removed)prefs.edit().putString("dock",join(dock)).putString("grid_order",join(grid)).apply();
        packageMarker=computePackageMarker();
        Log.i(TAG,"slots=6 dock="+join(dock)+" grid="+join(grid));
    }
    private int packageMarker=-1;
    private int computePackageMarker(){
        int marker=0;
        for(PackageInfo info:context.getPackageManager().getInstalledPackages(0))marker+=31*info.packageName.hashCode()+Long.valueOf(info.lastUpdateTime).hashCode();
        return marker;
    }
    boolean packagesChanged(){return packageMarker!=computePackageMarker();}
    boolean refreshIfPackageChanged(){if(!packagesChanged())return false;refresh();return true;}
    Tile lookup(String id){
        Tile retired=null;for(Tile tile:all)if(tile.id.equals(id)){if(!tile.retired)return tile;retired=tile;}return retired;
    }
    void clearRetired(){all.removeIf(tile->tile.retired&&!tile.exiting);}
    Tile addInstallPlaceholder(String name,String title,Bitmap icon){
        Tile tile=new Tile("app:"+name,title);tile.packageName=name;tile.installPlaceholder=true;
        if(icon!=null)tile.artwork=new BitmapDrawable(context.getResources(),icon);
        all.add(tile);grid.add(tile);return tile;
    }
    private void fillEmptySlots(){
        dock.removeIf(Tile::isEmpty);
        for(int i=dock.size();i<6;i++)dock.add(lookup("empty:"+i));
    }
    int firstEmptySlot(){for(int i=0;i<dock.size();i++)if(dock.get(i).isEmpty())return i;return -1;}
    private SlotLayout<Tile> layout(){
        ArrayList<Tile> pinned=new ArrayList<>();
        for(Tile t:dock)if(!t.isEmpty())pinned.add(t);
        return new SlotLayout<>(pinned,grid);
    }
    private void apply(SlotLayout<Tile> state){
        dock.clear();
        for(int i=0;i<6;i++)dock.add(state.dock.get(i)==null?lookup("empty:"+i):state.dock.get(i));
        grid.clear();grid.addAll(state.grid);
        prefs.edit().putString("dock",join(dock)).putString("grid_order",join(grid)).apply();
        Log.i(TAG,"slots=6 dock="+join(dock)+" grid="+join(grid));
    }
    boolean unpin(int slot){SlotLayout<Tile> s=layout();if(!s.unpin(slot))return false;apply(s);return true;}
    boolean pin(Tile tile){
        int slot=firstEmptySlot();if(slot<0)return false;
        return moveGridToDock(grid.indexOf(tile),slot);
    }
    boolean moveGridToDock(int from,int to){SlotLayout<Tile> s=layout();if(!s.toDock(from,to))return false;apply(s);return true;}
    boolean moveDockToGrid(int from,int to){SlotLayout<Tile> s=layout();if(!s.toGrid(from,to))return false;apply(s);return true;}
    boolean replaceAt(int index,Tile tile){
        int existing=dock.indexOf(tile);
        if(existing>=0)return moveDock(existing,index);
        return moveGridToDock(grid.indexOf(tile),index);
    }
    boolean moveDock(int a,int b){SlotLayout<Tile> s=layout();if(!s.moveDock(a,b))return false;apply(s);return true;}
    boolean moveGrid(int a,int b){SlotLayout<Tile> s=layout();if(!s.moveGrid(a,b))return false;apply(s);return true;}
    private void refreshGrid(){
        grid.clear();
        HashMap<String,Tile> candidates=new HashMap<>();
        for(Tile item:all)if(!item.isEmpty()&&!item.retired&&!dock.contains(item))candidates.put(item.id,item);
        for(String id:prefs.getString("grid_order","").split("\\|")){
            Tile item=candidates.remove(id);if(item!=null)grid.add(item);
        }
        for(Tile item:all)if(candidates.remove(item.id)!=null)grid.add(item);
    }
    private String join(ArrayList<Tile> tiles){StringBuilder b=new StringBuilder();for(Tile t:tiles){if(t.isEmpty()||t.installPlaceholder)continue;if(b.length()>0)b.append('|');b.append(t.id);}return b.toString();}
}
