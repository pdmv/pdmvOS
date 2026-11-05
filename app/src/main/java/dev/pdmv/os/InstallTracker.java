package dev.pdmv.os;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Log;
import java.util.*;

/** Observes actual system sessions. Never estimates download or installation progress. */
final class InstallTracker implements AutoCloseable {
    static final class State {
        final int sessionId;final String packageName,label;final Bitmap icon;final float progress;
        State(PackageInstaller.SessionInfo info){
            sessionId=info.getSessionId();packageName=info.getAppPackageName();
            CharSequence title=info.getAppLabel();label=title==null?null:title.toString();icon=info.getAppIcon();
            progress=Math.max(0f,Math.min(1f,info.getProgress()));
        }
    }
    interface Listener {void changed(Map<String,State> states);default void finished(String packageName,int sessionId,boolean success){}}
    private final PackageInstaller installer;private final Handler handler;private final Listener listener;
    private Map<String,State> last=Collections.emptyMap();
    private final LinkedHashMap<Integer,String> observed=new LinkedHashMap<Integer,String>(){protected boolean removeEldestEntry(Map.Entry<Integer,String> e){return size()>128;}};
    private final HashSet<Integer> finished=new HashSet<>();private boolean registered,closed,active,refreshScheduled;
    private long lastRefresh=-10000;
    private final Runnable pendingRefresh=()->{refreshScheduled=false;refresh();};
    private void scheduleRefresh(){
        if(!active||closed||refreshScheduled)return;
        refreshScheduled=true;handler.postDelayed(pendingRefresh,100);
    }
    void setActive(boolean active){
        this.active=active;
        if(!active){handler.removeCallbacks(pendingRefresh);refreshScheduled=false;}
    }
    void poll(){
        if(android.os.SystemClock.uptimeMillis()-lastRefresh>=10000)refresh();
    }
    private final PackageInstaller.SessionCallback callback=new PackageInstaller.SessionCallback(){
        public void onCreated(int id){finished.remove(id);scheduleRefresh();}
        public void onBadgingChanged(int id){scheduleRefresh();}
        public void onActiveChanged(int id,boolean active){scheduleRefresh();}
        public void onProgressChanged(int id,float progress){scheduleRefresh();}
        public void onFinished(int id,boolean success){
            String name=observed.remove(id);if(name!=null)listener.finished(name,id,success);
            finished.add(id);scheduleRefresh();
        }
    };
    InstallTracker(Context context,Handler handler,Listener listener){
        installer=context.getPackageManager().getPackageInstaller();this.handler=handler;this.listener=listener;
        try{installer.registerSessionCallback(callback,handler);registered=true;}
        catch(RuntimeException unavailable){Log.w("pdmvOS.Install","Session callbacks unavailable",unavailable);}
    }
    void refresh(){
        if(closed||!active)return;
        handler.removeCallbacks(pendingRefresh);refreshScheduled=false;
        lastRefresh=android.os.SystemClock.uptimeMillis();
        HashMap<String,State> states=new HashMap<>();
        try{
            HashSet<Integer> current=new HashSet<>();
            for(PackageInstaller.SessionInfo info:installer.getAllSessions()){
                current.add(info.getSessionId());
                if(finished.contains(info.getSessionId())||info.getAppPackageName()==null||!Float.isFinite(info.getProgress()))continue;
                observed.put(info.getSessionId(),info.getAppPackageName());
                State state=new State(info),previous=states.get(state.packageName),prior=last.get(state.packageName);
                // Session IDs are random, not timestamps. Keep the observed session
                // stable while it exists; otherwise select a real available one.
                if(previous==null||prior!=null&&state.sessionId==prior.sessionId
                    ||(prior==null||previous.sessionId!=prior.sessionId)&&state.progress>previous.progress)
                    states.put(state.packageName,state);
            }
            finished.retainAll(current);
        }catch(RuntimeException unavailable){Log.w("pdmvOS.Install","Session details unavailable",unavailable);return;}
        boolean changed=states.size()!=last.size();
        if(!changed)for(Map.Entry<String,State> entry:states.entrySet()){
            State old=last.get(entry.getKey()),next=entry.getValue();
            if(old==null||old.sessionId!=next.sessionId||old.progress!=next.progress||!Objects.equals(old.label,next.label)||(old.icon==null)!=(next.icon==null)){changed=true;break;}
        }
        if(changed){last=Collections.unmodifiableMap(states);listener.changed(last);}
    }
    public void close(){closed=true;handler.removeCallbacks(pendingRefresh);if(registered){installer.unregisterSessionCallback(callback);registered=false;}}
}
