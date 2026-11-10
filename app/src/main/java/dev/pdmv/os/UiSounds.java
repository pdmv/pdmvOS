package dev.pdmv.os;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/** Preloaded, bounded navigation feedback; never takes audio focus from media. */
final class UiSounds implements AutoCloseable {
    private static final float NAVIGATION_VOLUME = 0.8f;
    private static final float TRANSITION_VOLUME = 1.0f;
    private static final boolean PRESS_FEEDBACK_ENABLED = false;

    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ReturnSoundGate returnGate=new ReturnSoundGate();
    private Runnable returnConsumed=()->{};
    private final Runnable playPendingReturn=this::flushReturn;
    private SoundPool pool;
    private int sample,menuSample,pressSample,openSample,returnSample;
    private final java.util.HashSet<Integer> loaded=new java.util.HashSet<>();
    private int focusStream,pressStream,transitionStream,lastEvent;
    private long lastOpen=-1000,lastReturn=-1000;
    private boolean ready,enabled,observing,closed;
    private long lastPlayed=-1000,lastMenuFocus=-1000,lastPress=-1000;
    private final ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())){
        @Override public void onChange(boolean selfChange){refreshPreference();}
    };
    UiSounds(Context context){
        this.context=context.getApplicationContext();refreshPreference();
        try{
            pool=new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((p,id,status)->{if(!closed&&status==0){loaded.add(id);ready=loaded.contains(sample)&&loaded.contains(menuSample)&&loaded.contains(pressSample)&&loaded.contains(openSample)&&loaded.contains(returnSample);scheduleReturn();}});
            sample=load("focus_change_app_icon");menuSample=load("focus_change_keyboard");pressSample=load("keyboard_press_normal");
            openSample=load("navigation_push");returnSample=load("navigation_pop");
            this.context.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.SOUND_EFFECTS_ENABLED),false,observer);observing=true;
        }catch(RuntimeException unavailable){Log.w("pdmvOS.Sound","Navigation feedback unavailable",unavailable);}
    }
    private int load(String name){return pool.load(context,context.getResources().getIdentifier(name,"raw",context.getPackageName()),1);}
    void refreshPreference(){
        try{enabled=Settings.System.getInt(context.getContentResolver(),Settings.System.SOUND_EFFECTS_ENABLED,1)!=0;}
        catch(RuntimeException unavailable){enabled=false;}
        if(!enabled&&returnGate.pending())consumeReturn();
    }
    void navigate(){if(playFocus(sample,lastPlayed)){lastPlayed=SystemClock.uptimeMillis();lastEvent=1;}}
    void navigateMenu(){if(playFocus(menuSample,lastMenuFocus)){lastMenuFocus=SystemClock.uptimeMillis();lastEvent=4;}}
    private boolean playFocus(int id,long previous){
        if(closed||!ready||!enabled||SystemClock.uptimeMillis()-previous<90)return false;
        if(focusStream!=0)pool.stop(focusStream);
        focusStream=pool.play(id,NAVIGATION_VOLUME,NAVIGATION_VOLUME,1,0,1f);return focusStream!=0;
    }
    void press(){
        if(!PRESS_FEEDBACK_ENABLED||closed||!ready||!enabled)return;
        if(focusStream!=0){pool.stop(focusStream);focusStream=0;}
        if(pressStream!=0)pool.stop(pressStream);
        pressStream=pool.play(pressSample,NAVIGATION_VOLUME,NAVIGATION_VOLUME,3,0,1f);
        if(pressStream!=0){lastPress=SystemClock.uptimeMillis();lastEvent=5;}
    }
    void openApp(){if(playTransition(openSample)){lastOpen=SystemClock.uptimeMillis();lastEvent=2;}}
    void setReturnConsumedListener(Runnable listener){returnConsumed=listener;}
    void setHomeActive(boolean active){
        returnGate.setActive(active);handler.removeCallbacks(playPendingReturn);
        if(active)scheduleReturn();
    }
    void returnHome(){
        if(closed)return;
        returnGate.request();
        if(!enabled){consumeReturn();return;}
        scheduleReturn();
    }
    private void scheduleReturn(){
        handler.removeCallbacks(playPendingReturn);
        if(!closed&&returnGate.canPlay(loaded.contains(returnSample),enabled))handler.postDelayed(playPendingReturn,60);
    }
    private void consumeReturn(){
        returnGate.consume();handler.removeCallbacks(playPendingReturn);returnConsumed.run();
    }
    private void flushReturn(){
        if(closed||!returnGate.canPlay(loaded.contains(returnSample),enabled))return;
        if(playTransition(returnSample)){
            lastReturn=SystemClock.uptimeMillis();lastEvent=3;consumeReturn();
            Log.i("pdmvOS.Sound","return.played");
        }else if(returnGate.retry())handler.postDelayed(playPendingReturn,60);
    }
    private boolean playTransition(int id){
        if(closed||!loaded.contains(id)||!enabled)return false;
        if(focusStream!=0){pool.stop(focusStream);focusStream=0;}
        if(transitionStream!=0)pool.stop(transitionStream);
        transitionStream=pool.play(id,TRANSITION_VOLUME,TRANSITION_VOLUME,2,0,1f);
        return transitionStream!=0;
    }
    public void close(){
        closed=true;ready=false;returnGate.consume();handler.removeCallbacks(playPendingReturn);
        if(observing){context.getContentResolver().unregisterContentObserver(observer);observing=false;}
        if(pool!=null){pool.release();pool=null;}
    }
}
