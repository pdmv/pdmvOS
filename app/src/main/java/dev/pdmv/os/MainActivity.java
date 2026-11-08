package dev.pdmv.os;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.TextView;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.io.InputStream;
import android.graphics.drawable.BitmapDrawable;

/** Native TV launcher: cached glass, independent motion and source-anchored return transitions. */
public final class MainActivity extends Activity {
    private static final String TAG="pdmvOS";
    private static final int INSTALL_UPDATE_REQUEST=61;
    private UiSounds uiSounds;
    private SharedPreferences prefs,returnFeedback;
    private TileRepository catalog;
    private WallpaperStore wallpapers;
    private LauncherView view;
    private InstallTracker installTracker;
    private LauncherUpdate updater;
    private java.util.Map<String,InstallTracker.State> installStates=java.util.Collections.emptyMap();
    private boolean resumed,packageDirty,packageEventsRegistered;
    private final Runnable packageRefresh=()->{
        if(resumed&&view!=null){packageDirty=false;view.refreshPackages(true);view.applyInstallStates();}
    };
    private final android.content.BroadcastReceiver packageEvents=new android.content.BroadcastReceiver(){
        @Override public void onReceive(android.content.Context context,Intent intent){
            if(Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())&&intent.getBooleanExtra(Intent.EXTRA_REPLACING,false))return;
            if(intent.getData()!=null&&getPackageName().equals(intent.getData().getSchemeSpecificPart()))return;
            packageDirty=true;
            if(resumed&&view!=null){view.handler.removeCallbacks(packageRefresh);view.handler.postDelayed(packageRefresh,50);}
        }
    };
    private void observePackages(){
        android.content.IntentFilter filter=new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);filter.addAction(Intent.ACTION_PACKAGE_CHANGED);filter.addDataScheme("package");
        registerReceiver(packageEvents,filter);packageEventsRegistered=true;
        installTracker=new InstallTracker(this,new Handler(Looper.getMainLooper()),new InstallTracker.Listener(){
            public void changed(java.util.Map<String,InstallTracker.State> states){
                installStates=states;if(resumed&&view!=null)view.applyInstallStates();
            }
            public void finished(String packageName,int sessionId,boolean success){
                if(success&&view!=null){Tile tile=catalog.lookup("app:"+packageName);
                    if(tile!=null&&!tile.retired&&(tile.installSessionId<0||tile.installSessionId==sessionId)){tile.installCompleting=true;tile.installSweep.aim(1f);view.invalidate();}}
            }
        });
    }
    private Typeface regular, semibold;
    
    private TvControls controls;
    private final HashMap<String,Drawable> solar=new HashMap<>();
    private void loadSolarAsync(){
        new Thread(()->{
            final HashMap<String,Drawable> loaded=new HashMap<>();
            for(String name:new String[]{"settings", "check", "info", "move", "apps", "wifi", "input", "plus"}){
                try{
                    int resource=getResources().getIdentifier("solar_"+name,"drawable",getPackageName());
                    Drawable icon=getResources().getDrawable(resource,getTheme()).mutate();
                    if(icon!=null)loaded.put(name,icon);
                }catch(Throwable error){Log.w(TAG,"Solar asset skipped: "+name,error);}
            }
            runOnUiThread(()->{
                solar.putAll(loaded);
                if(catalog!=null)for(Tile t:catalog.all)if(t.packageName==null)t.dispose();
                if(view!=null){for(LauncherView.TileNode node:view.tileNodes.values())node.invalidate();view.scheduleArtworkWarmup();view.invalidateScene();}
            });
        },"pdmvOS-solar").start();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG,"startup.begin "+installedVersion());
        try{
            enterImmersiveMode();
            returnFeedback=getSharedPreferences("pdmvos_return_feedback",MODE_PRIVATE);
            uiSounds=new UiSounds(this);
            uiSounds.setReturnConsumedListener(()->returnFeedback.edit().remove("left_at").remove("boot").remove("boot_epoch").apply());
            prefs=getSharedPreferences("pdmvos_settings",MODE_PRIVATE);
            clearRemovedFeatures();
            controls=new TvControls(this);
            updater=new LauncherUpdate(this,prefs,()->{if(view!=null)view.onUpdateChanged();});
            // API 28 may have inconsistent shaping with some variable-font binaries.
            // Prefer static instances generated by tools/install-font.sh; fall back safely.
            try {
                regular=Typeface.createFromAsset(getAssets(),"fonts/google_sans_flex_regular.ttf");
                semibold=Typeface.createFromAsset(getAssets(),"fonts/google_sans_flex_semibold.ttf");
                Log.i(TAG,"typography.static_instances.ready");
            } catch(Throwable staticFontError) {
                try {
                    regular=Typeface.createFromAsset(getAssets(),"fonts/google_sans_flex.ttf");
                    semibold=Typeface.create(regular,Typeface.BOLD);
                    Log.w(TAG,"Static instances missing; using variable font on Android 9");
                } catch(Throwable fontError) {
                    Log.w(TAG,"Google Sans Flex unavailable; using Android sans",fontError);
                    regular=Typeface.create("sans-serif",Typeface.NORMAL);
                    semibold=Typeface.create("sans-serif-medium",Typeface.BOLD);
                }
            }
            if(regular==null)regular=Typeface.DEFAULT;
            if(semibold==null)semibold=Typeface.create(regular,Typeface.BOLD);

            wallpapers=new WallpaperStore(this);
            // Wallpaper loading is asynchronous; the first frame may use
            // the lightweight gradient fallback rather than blocking Activity startup.
            wallpapers.start();
            Log.i(TAG,"startup.assets.deferred");

            catalog=new TileRepository(this,prefs);
            try{UiGeometry.assertInvariants();}
            catch(AssertionError geometryError){
                // Geometry is validated in tools/validate.sh. Never brick the TV Home if a
                // development invariant accidentally slips into a packaged build.
                Log.e(TAG,"Geometry invariant failed; continuing with safe layout",geometryError);
            }
            Log.i(TAG,"startup.catalog.ready count="+catalog.all.size());

            view=new LauncherView();
            observePackages();
            wallpapers.setListener(()->{ if(view!=null){ view.invalidatePreviewCache(); view.menuTextures.evictAll();view.headerStamp="";for(LauncherView.TileNode node:view.tileNodes.values())node.invalidate();view.invalidateScene(); } });
            setContentView(view);
            view.setFocusableInTouchMode(true);view.requestFocus();
            // Do expensive app-art rasterization only after Home has produced its first frame.
            view.scheduleArtworkWarmup();
            AirPlayReceiver receiver=AirPlayReceiver.getInstance();
            receiver.setListener(view::updateAirPlayState);
            view.post(()->{
                if(!isDestroyed())receiver.start(this,prefs.getInt("airplay_mode",AirPlayReceiver.MODE_ALL));
            });
            loadSolarAsync();
            Log.i(TAG,"startup.complete");
            handleWallpaperIntent(getIntent());
            if(Intent.ACTION_ALL_APPS.equals(getIntent().getAction()))view.requestApps();
        }catch(RuntimeException | LinkageError | AssertionError startupError){
            Log.e(TAG,"startup.failed",startupError);
            showStartupFallback(startupError);
        }
    }
    private void handleWallpaperIntent(Intent intent){
        if(intent==null||!WallpaperCommand.ACTION.equals(intent.getAction()))return;
        if(view!=null)view.showHomeFromSystem();
        WallpaperCommand.accept(this,wallpapers,intent,this::toast);
        // A consumed one-shot upload must not replay after Activity recreation.
        setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(getComponentName()));
    }
    private void enterImmersiveMode(){
        if(android.os.Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();
            if(controller!=null){
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private void showStartupFallback(Throwable error){
        TextView recovery=new TextView(this);
        recovery.setBackgroundColor(0xff101014);
        recovery.setTextColor(0xfff5f5f7);
        recovery.setTextSize(20f);
        recovery.setGravity(Gravity.CENTER);
        recovery.setPadding(72,48,72,48);
        recovery.setText("pdmvOS không thể hoàn tất khởi động.\n\n"
            +error.getClass().getSimpleName()+"\n"
            +(error.getMessage()==null?"":error.getMessage())
            +"\n\nChạy tools/diagnose-start.sh trên Mac để lấy log chi tiết.");
        setContentView(recovery);
    }
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        if(focused){enterImmersiveMode();if(view!=null){view.lastFrame=0L;view.deferReturnUntilVisible();view.invalidate();}}else if(view!=null)view.cancelHeldInput();
        if(uiSounds!=null)uiSounds.setHomeActive(focused&&resumed);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(wallpapers!=null)handleWallpaperIntent(intent);
        if(view!=null&&Intent.ACTION_ALL_APPS.equals(intent.getAction()))view.requestApps();
        else if(view!=null&&Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_HOME))view.showHomeFromSystem();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(view!=null&&view.airPlayDisplayVisible){
            if(event.getKeyCode()==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())
                view.leaveAirPlayDisplay();
            return true;
        }
        if(view!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ALL_APPS){
            if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())view.requestApps();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(view!=null&&view.airPlayDisplayVisible)return true;
        return super.dispatchTouchEvent(event);
    }
    @Override public void onEnterAnimationComplete(){
        super.onEnterAnimationComplete();
        if(view!=null){
            view.returnWindowEntered=true;
            view.returnVisibleAt=android.os.SystemClock.uptimeMillis()+32;
            view.handler.postDelayed(view.returnWindowReady,32);
        }
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;if(installTracker!=null)installTracker.setActive(true);if(view!=null)view.returnWindowEntered=false;if(uiSounds!=null)uiSounds.refreshPreference();
        // The origin is remembered across the external Activity. Do not merely
        // remove the launch surface: let it shrink to its matching tile on Home.
        if(view!=null){view.setRenderingActive(true);view.configureDisplay();view.onReturnToHome();view.resumePolling();view.invalidate();
            view.scheduleAirPlayEntranceFallback();
            // Package-manager and installer IPC must not block the first visible
            // frames while the app surface is shrinking back into its tile.
            view.handler.removeCallbacks(resumeMaintenance);
            view.handler.postDelayed(resumeMaintenance,800);
        }
        if(uiSounds!=null)uiSounds.setHomeActive(hasWindowFocus());
    }
    private final Runnable resumeMaintenance=new Runnable(){
        @Override public void run(){
            if(!resumed||view==null)return;
            if(view.motionWasActive||view.returningTile!=null||view.launchingTile!=null){view.handler.postDelayed(this,120);return;}
            view.refreshControlStates();
            if(catalog!=null){view.refreshPackages(packageDirty);packageDirty=false;}
            if(installTracker!=null){installTracker.refresh();view.applyInstallStates();}
            if(wallpapers!=null)wallpapers.restoreOptionalTransitions();
        }
    };
    @Override protected void onPause(){
        if(view!=null)view.handler.removeCallbacks(resumeMaintenance);
        resumed=false;if(installTracker!=null)installTracker.setActive(false);if(uiSounds!=null)uiSounds.setHomeActive(false);
        if(view!=null){view.pausePolling();view.onLeaveForExternalApp();view.setRenderingActive(false);}
        super.onPause();
    }
    private int currentBoot(){
        try{return Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1);}
        catch(RuntimeException unavailable){return -1;}
    }
    private void saveReturnFeedback(Tile origin){
        if(returnFeedback==null)return;
        boolean pending=returnFeedback.contains("left_at");
        long elapsed=android.os.SystemClock.elapsedRealtime();
        SharedPreferences.Editor saved=returnFeedback.edit().putLong("left_at",elapsed).putInt("boot",currentBoot())
            .putLong("boot_epoch",System.currentTimeMillis()-elapsed);
        if(origin!=null){
            saved.putString("origin_id",origin.id).putInt("origin_row",view.gridFirstRow)
                .putBoolean("origin_drawer",view.lastExternalInDrawer).putBoolean("origin_dock",view.lastExternalDock);
        }else if(!pending)saved.remove("origin_id").remove("origin_row").remove("origin_drawer").remove("origin_dock");
        saved.apply();
    }
    @Override protected void onStop(){
        super.onStop();
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if(!isFinishing()&&!isChangingConfigurations()&&power!=null&&power.isInteractive())
            saveReturnFeedback(view!=null&&view.externalLaunchInProgress?view.lastExternalTile:null);
    }
    private boolean pendingReturnFeedback(){
        if(returnFeedback==null||!returnFeedback.contains("left_at"))return false;
        long left=returnFeedback.getLong("left_at",-1),now=android.os.SystemClock.elapsedRealtime();
        int boot=currentBoot(),saved=returnFeedback.getInt("boot",-1);
        boolean sameBoot=boot>=0&&saved>=0?boot==saved:
            Math.abs((System.currentTimeMillis()-now)-returnFeedback.getLong("boot_epoch",0))<120000;
        if(left>=0&&now>=left&&sameBoot)return true;
        returnFeedback.edit().clear().apply();return false;
    }
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND||level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW||level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL){
            trimOptionalResources();
        }
    }
    @Override public void onLowMemory(){
        super.onLowMemory();trimOptionalResources();
    }
    private void trimOptionalResources(){
        if(wallpapers!=null)wallpapers.trimOptionalMemory();
        if(view!=null){
            view.menuTextures.evictAll();view.textTextures.evictAll();view.squircleCache.clear();
            view.normalizedTexts.evictAll();view.fittedTexts.evictAll();view.clockMetrics.evictAll();
            view.tileSourcePixels=null;view.tileMaskedPixels=null;
            if(view.dialogAction==null&&!view.dialogReveal.busy()){
                view.dialogBackdrop=null;view.dialogBackdropArea=null;view.dialogBackdropKey=Long.MIN_VALUE;
            }
            if(view.returningTile==null&&view.launchingTile==null){
                view.morphTexture=null;view.morphTextureSource=null;view.morphBitmap=null;view.morphShader=null;
            }
            view.invalidateToastBackdrop();view.glassMaterials.trimMemory();view.invalidateScene();
        }
        if(resumed&&view!=null){
            view.handler.removeCallbacks(resumeMaintenance);view.handler.postDelayed(resumeMaintenance,3000);
        }
    }
    @Override public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){
        super.dump(prefix,fd,writer,args);
        if(view==null)return;
        if(args!=null&&java.util.Arrays.asList(args).contains("pacing-reset"))view.resetPacing();
        writer.printf(Locale.ROOT,"%spdmvOS.state resumed=%b page=%d dock=%d/%d grid=%d/%d drawer=%.4f drawer_target=%.4f menu=%b closing_menu=%b launching=%b returning=%b pending_launch=%b active=%b wallpaper_frames=%b%n",
            prefix,resumed,view.page,view.dockFocus,catalog.dock.size(),view.gridFocus,catalog.grid.size(),
            view.drawer.value,view.drawer.target,view.menu!=null,view.closingMenu!=null,
            view.launchingTile!=null,view.returningTile!=null,view.pendingLaunch!=null,view.motionWasActive,
            wallpapers!=null&&wallpapers.optionalTransitionsReady());
        writer.printf(Locale.ROOT,"%spdmvOS.focus target=%s dialog=%s wifi=%d drawer_dock=%b%n",
            prefix,view.focusSoundTarget(),view.dialogAction==null?"":view.dialogTitle,controls.wifi,view.focusDrawerDock);
        if(updater!=null)writer.printf(Locale.ROOT,"%spdmvOS.update state=%s version=%s progress=%d%n",
            prefix,updater.state(),updater.version(),updater.progress());
        int count=(int)Math.min(view.pacingSamples,view.pacingIntervals.length);
        if(count==0){writer.println(prefix+"pdmvOS.pacing samples=0");return;}
        long[] intervals=java.util.Arrays.copyOf(view.pacingIntervals,count);java.util.Arrays.sort(intervals);
        writer.printf(Locale.ROOT,"%spdmvOS.pacing samples=%d p50_ms=%.3f p95_ms=%.3f over_25ms=%d scene_draws=%d%n",
            prefix,view.pacingSamples,intervals[(count-1)/2]/1e6,
            intervals[(int)Math.ceil(count*.95)-1]/1e6,view.pacingMisses,view.sceneDrawCount);
    }
    @Override protected void onDestroy(){resumed=false;AirPlayReceiver.getInstance().stop();AirPlayReceiver.getInstance().setListener(null);if(uiSounds!=null)uiSounds.close();if(packageEventsRegistered){unregisterReceiver(packageEvents);packageEventsRegistered=false;}if(installTracker!=null)installTracker.close();if(updater!=null)updater.close();if(view!=null)view.detach();if(wallpapers!=null)wallpapers.close();super.onDestroy();}
    private void open(Tile tile){
        if(tile==null)return;
        if(tile.installPlaceholder){toast("Ứng dụng đang được cài đặt");return;}
        if(tile.isEmpty()){view.choosePinSlot();return;}
        if(view!=null && view.queueExternalLaunch(tile,()->openImmediately(tile)))return;
        openImmediately(tile);
    }
    private void openImmediately(Tile tile){
        try{
            Intent launch=getPackageManager().getLeanbackLaunchIntentForPackage(tile.packageName);
            if(launch==null)launch=getPackageManager().getLaunchIntentForPackage(tile.packageName);
            if(launch==null)throw new ActivityNotFoundException(tile.label);
            startActivity(launch);
            saveReturnFeedback(tile);
            if(uiSounds!=null){uiSounds.setHomeActive(false);uiSounds.openApp();}
        }catch(Exception e){
            if(view!=null){view.externalLaunchInProgress=false;view.lastExternalTile=null;view.resetLaunchMotion();}
            toast("Không thể mở "+tile.label);
        }
    }
    private String installedVersion(){
        try{
            android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
            long code=android.os.Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
            return "pdmvOS "+info.versionName+" · Build "+code;
        }catch(android.content.pm.PackageManager.NameNotFoundException unavailable){return "pdmvOS";}
    }
    private void installLauncherUpdate(){
        if(!getPackageManager().canRequestPackageInstalls()){
            launchSystem(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+getPackageName())),
                "Mở Cài đặt TV và cho phép pdmvOS cài ứng dụng từ nguồn này");
            return;
        }
        if(view==null)return;
        int dismissals=view.dialogDismissals;
        updater.prepareInstall(uri->{
            if(!resumed||!hasWindowFocus()||view.dialogDismissals!=dismissals||!view.updateDialog)return;
            Intent install=new Intent(Intent.ACTION_INSTALL_PACKAGE,uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT,true);
            try{
                startActivityForResult(install,INSTALL_UPDATE_REQUEST);
                view.dismissDialog();
                updater.installerOpened();
            }catch(RuntimeException error){
                toast("Không mở được trình cài đặt Android");
            }
        });
        view.renderUpdateDialog();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=INSTALL_UPDATE_REQUEST||updater==null)return;
        updater.installerClosed();
        if(resultCode==RESULT_CANCELED)toast("Đã hủy cài đặt. Bạn có thể mở mục cập nhật để thử lại.");
        else if(resultCode!=RESULT_OK)toast("Android không cài được bản cập nhật. Hãy thử lại.");
    }
    private void systemSettings(){
        try{startActivity(new Intent(Settings.ACTION_SETTINGS));}
        catch(Exception e){toast("Không mở được cài đặt Android TV");}
    }
    private void appInfo(Tile tile){
        if(tile.packageName==null){toast("Không có thông tin cho lối tắt này");return;}
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+tile.packageName)));}
        catch(Exception e){toast("Không mở được thông tin ứng dụng");}
    }
    private void uninstall(Tile tile){
        if(tile==null || !tile.uninstallable || tile.packageName==null)return;
        try{startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+tile.packageName)));}
        catch(ActivityNotFoundException error){toast("TV không cung cấp trình gỡ cài đặt");}
    }
    private void toast(String s){if(view!=null)view.showToast(s);}
    private void launchSystem(Intent intent,String failure){
        try{startActivity(intent);}catch(RuntimeException error){toast(failure);}
    }
    private void clearRemovedFeatures(){
        Intent old=new Intent().setComponent(new android.content.ComponentName(this,"dev.pdmv.os.SleepTimer"));
        android.app.PendingIntent alarm=android.app.PendingIntent.getBroadcast(this,31,old,android.app.PendingIntent.FLAG_NO_CREATE|android.app.PendingIntent.FLAG_IMMUTABLE);
        if(alarm!=null){((android.app.AlarmManager)getSystemService(ALARM_SERVICE)).cancel(alarm);alarm.cancel();}
        prefs.edit().remove("sleep_deadline").remove("sleep_wall").remove("sleep_boot").remove("theme").remove("hidden").apply();
    }

    private final class LauncherView extends NativeSurfaceView {
        private final AmbientBackground ambientBackground=new AmbientBackground();
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        private final RectF bounds=new RectF();
        private final Rect wallpaperSource=new Rect();
        private final RectF wallpaperDestination=new RectF(0,0,UiGeometry.WIDTH,UiGeometry.HEIGHT);
        private final Paint artworkMaskPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Path clip=new Path();
        private final Handler handler=new Handler(Looper.getMainLooper());
        
        private final SpringValue drawer=new SpringValue(0f,MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D);
        private final SpringValue gridScroll=new SpringValue(0f,MotionProfile.SCROLL_K,MotionProfile.SCROLL_D);
        
        private final SpringValue tilePress=new SpringValue(0f,MotionProfile.PRESS_IN_K,MotionProfile.PRESS_IN_D);
        private final SpringValue press=new SpringValue(0f,MotionProfile.PRESS_IN_K,MotionProfile.PRESS_IN_D);
        private final SpringValue headerFocusAnim=new SpringValue(0f,MotionProfile.HEADER_IN_K,MotionProfile.HEADER_IN_D);
        private final SpringValue menuReveal=new SpringValue(0f,MotionProfile.MENU_OPEN_K,MotionProfile.MENU_OPEN_D);
        // Directional highlight provides a subtle tvOS-like sense of material depth,
        // without perpetual idle animation or costly per-frame bitmap composition.
        private final FocusDepth focusDepth=new FocusDepth();
        private final RadialGradient focusHighlight=new RadialGradient(255f,70f,515f,
            new int[]{0x2bffffff,0x0cffffff,0x00ffffff},
            new float[]{0f,.46f,1f},Shader.TileMode.CLAMP);
        private final Matrix highlightMatrix=new Matrix();
        private final SpringValue menuContent=new SpringValue(0f,720f,52f);
        private final SpringValue menuSelection=new SpringValue(0f,MotionProfile.MENU_SELECTION_K,MotionProfile.MENU_SELECTION_D);
        private final SpringValue menuRowEmphasis=new SpringValue(0f,440f,42f);
        private final SpringValue inputMenuChange=new SpringValue(1f,440f,42f);
        private final RectF inputMenuOrigin=new RectF();
        private Menu inputMenuOutgoing;
        private AirPlaySurface airPlaySurface;
        private View airPlayMatte;
        private boolean airPlayDisplayVisible,airPlayFrameVisible;
        private boolean airPlayFrameReady,airPlayReturningFromApp;
        private long airPlayEntranceDeadline;
        private final Runnable airPlayEntranceFallback=()->invalidate();
        private final SpringValue airPlayEntrance=new SpringValue(0f,MotionProfile.HEADER_IN_K,MotionProfile.HEADER_IN_D);
        private int airPlaySession;
        private int airPlayWidth=1920,airPlayHeight=1080;
        private android.net.wifi.WifiManager.WifiLock airPlayWifiLock;
        private static final float AIRPLAY_TOAST_TRAVEL=144f,AIRPLAY_TOAST_SPACE=174f;
        private final SpringValue launchReveal=new SpringValue(0f,MotionProfile.LAUNCH_K,MotionProfile.LAUNCH_D);
        // A shared progress spring drives backdrop dimming, position and scale together
        // when a third-party application hands control back to pdmvOS.
        private final SpringValue returnReveal=new SpringValue(0f,MotionProfile.RETURN_K,MotionProfile.RETURN_D);
        private Tile returningTile, lastExternalTile;
        private boolean returnWaiting,returnWindowEntered;
        private int returnDraws;
        private long returnWaitingSince,returnVisibleAt;
        private final Runnable returnReadyTimeout=()->invalidate(),returnWindowReady=()->invalidate(),returnEntryFallback=()->invalidate();
        private float lastExternalCx,lastExternalCy;
        private boolean lastExternalDock,lastExternalInDrawer,leftForExternalApp,externalLaunchInProgress;
        private boolean appsRequested,appsNeedsHome;
        private Tile launchingTile;
        private Runnable pendingLaunch;
        private boolean launchDispatched;
        // Context-driven focus transitions: outgoing/incoming scale springs run in
        // parallel, retain velocities, and never get reconfigured on every frame.
        private final FocusMotion tileFocus=new FocusMotion(
            MotionProfile.FOCUS_IN_K,MotionProfile.FOCUS_IN_D,
            MotionProfile.FOCUS_OUT_K,MotionProfile.FOCUS_OUT_D,
            MotionProfile.FOCUS_ENTER_VELOCITY,MotionProfile.FOCUS_LEAVE_VELOCITY);
        private final FocusMotion labelFocus=new FocusMotion(
            MotionProfile.LABEL_IN_K,MotionProfile.LABEL_IN_D,
            MotionProfile.LABEL_OUT_K,MotionProfile.LABEL_OUT_D,
            MotionProfile.LABEL_ENTER_VELOCITY,MotionProfile.LABEL_LEAVE_VELOCITY);
        private int page=0; // 0 home, 1 application drawer
        private int dockFocus=0, gridFocus=0, headerSelection=3, gridFirstRow=0;
        private boolean focusDrawerDock=false,focusHeader=false;
        private int replaceSlot=-1;
        private Menu menu, closingMenu;
        private final java.util.LinkedHashMap<String,Path> squircleCache=new java.util.LinkedHashMap<String,Path>(96, .75f, true){
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Path> eldest){
                return size()>96;
            }
        };
        private final android.text.TextPaint measurePaint=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private String clockCache="";
        private boolean moveMode, moveDock;
        private boolean pressStartedInMenu, pressStartedInDialog;
        private boolean holdingOK, longPressed;
        private float canvasScale=1f,canvasLeft=0f,canvasTop=0f;
        private static final float MENU_WIDTH=440f, MENU_PAD=UiGeometry.POPUP_PADDING, MENU_ROW=UiGeometry.POPUP_PITCH;
        private static final float MENU_HEADER=UiGeometry.POPUP_HEADER, MENU_ITEM_HEIGHT=UiGeometry.POPUP_ROW_HEIGHT;
        private Bitmap previewCache;
        private Throwable renderFailure;
        private long lastFrame=0L;
        private boolean motionWasActive;
        private final Runnable holdAction=()->{
            if(holdingOK&&menu==null){
                longPressed=true;
                if(focusHeader)headerHold();else openTileMenu();
            }
        };
        private final Runnable clockTick=new Runnable(){
            @Override public void run(){
                if(!resumed)return;
                String next=formatClock(new Date());
                if(!next.equals(clockCache)){clockCache=next;invalidatePreviewCache();invalidate();}
                long now=System.currentTimeMillis();
                long delay=Math.max(500L,60_000L-(now%60_000L)+25L);
                handler.postDelayed(this,Math.min(delay,10_000L));
            }
        };
        LauncherView(){
            super(MainActivity.this);
            artworkMaskPaint.setColor(Color.WHITE);
            artworkMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            // Scene and popup own separate HWUI render nodes.
            setFocusable(true);
            // Polling begins in onResume and stops while another app owns the screen.
            clockCache=formatClock(new Date());
        }
        void resumePolling(){
            pausePolling();
            clockTick.run();
            handler.postDelayed(statusTick,1000);
            if(artworkWarmScheduled)postOnAnimation(artworkStep);
        }
        void pausePolling(){
            handler.removeCallbacks(clockTick);
            handler.removeCallbacks(statusTick);
            removeCallbacks(artworkStep);
            handler.removeCallbacks(artworkStep);
        }
        void detach(){
            toastWorkerClosed=true;invalidateToastBackdrop();if(toastWorker!=null)toastWorker.shutdownNow();
            handler.removeCallbacksAndMessages(null);
            artworkWarmScheduled=false;
            removeCallbacks(artworkStep);
            invalidatePreviewCache();
        }
        private int fg(){return 0xfff5f5f7;}
        private int secondary(){return 0xffd4d9e1;}
        private int row(){return 0x24ffffff;}
        private int stroke(){return 0x50ffffff;}
        private int accent(){return 0xff70b8ff;}
        private final GlassMaterial glassMaterials=new GlassMaterial(wallpapers,(canvas,x,y,w,h,r,p)->drawSquircle(canvas,x,y,w,h,r,p),(w,h,r)->{
            if(r>=Math.min(w,h)/2f){Path path=new Path();path.addRoundRect(0,0,w,h,r,r,Path.Direction.CW);return path;}
            return squirclePath(w,h,r);
        });
        private void glass(Canvas c,float x,float y,float w,float h,float radius){material(c,x,y,w,h,radius,0);}
        private void drawGlassMaterial(Canvas c,Bitmap bitmap,float x,float y,float w,float h,float radius){material(c,x,y,w,h,radius,0);}
        private void material(Canvas c,float x,float y,float w,float h,float radius,int level){materialAt(c,x,y,w,h,radius,level,0,0);}
        private void materialAt(Canvas c,float x,float y,float w,float h,float radius,int level,float sampleX,float sampleY){
            glassMaterials.draw(c,x,y,w,h,radius,level,sampleX,sampleY);
        }
        // Focus is conveyed by size, not by a large blurry halo. The surrounding glass
        // already supplies contrast and the previous shadow asset caused GPU overdraw.
        private void roundMask(Canvas c,float x,float y,float w,float h,float radius){
            // DST_IN on an isolated layer produces anti-aliased coverage on API 28, unlike
            // hardware clipPath which can quantize edges during rotation and size changes.
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,artworkMaskPaint);
        }
        private void drawSolar(Canvas c,String name,float cx,float cy,float size,int tint){
            Drawable icon=solar.get(name);if(icon==null)return;
            int save=c.save();c.translate(cx-size/2,cy-size/2);
            icon.setBounds(0,0,Math.round(size),Math.round(size));icon.setTint(tint);icon.setAlpha(Color.alpha(tint));icon.draw(c);
            c.restoreToCount(save);
        }
        private float ease(float t){t=UiGeometry.clamp(t,0,1);return t*t*(3-2*t);}
        /** Cached continuous-corner superellipse. Exponent 3.8 and generous radii
         * preserve the rounded tvOS character on landscape app tiles. The special
         * capsule case is a circular-end pill, not a square-ish superellipse.
         * Cache paths by raster size and scale to the exact floating canvas bounds. */
        private final float[] squircleVertices=new float[266];
        private Path squirclePath(float w,float h,float r){
            int iw=Math.max(1,Math.round(w)), ih=Math.max(1,Math.round(h));
            int ir=Math.max(0,Math.round(Math.min(r,Math.min(iw,ih)*.5f)));
            String key=iw+"x"+ih+"r"+ir;
            Path cached=squircleCache.get(key);if(cached!=null)return cached;
            UiGeometry.squircleVertices(0f,0f,iw,ih,ir,3.05f,squircleVertices);
            Path p=new Path();p.moveTo(squircleVertices[0],squircleVertices[1]);
            for(int i=2;i<266;i+=2)p.lineTo(squircleVertices[i],squircleVertices[i+1]);
            p.close();squircleCache.put(key,p);return p;
        }
        private void drawSquircle(Canvas c,float x,float y,float w,float h,float r,Paint p){
            if(w<=0 || h<=0)return;
            // A capsule must have circular ends; n>2 superellipses become squarish at h/2.
            if(r>=Math.min(w,h)*.5f-0.25f){
                float capsuleRadius=Math.min(w,h)*.5f;
                c.drawRoundRect(x,y,x+w,y+h,capsuleRadius,capsuleRadius,p);
                return;
            }
            // Quantize only the cached path, never the rendered bounds. Smooth floating
            // transforms prevent the edge from snapping between pixels during animation.
            int iw=Math.max(1,Math.round(w)),ih=Math.max(1,Math.round(h));
            int save=c.save();c.translate(x,y);c.scale(w/iw,h/ih);
            c.drawPath(squirclePath(w,h,r),p);c.restoreToCount(save);
        }
        private void rect(Canvas c,float x,float y,float w,float h,float r,int color){
            paint.reset();paint.setAntiAlias(true);paint.setDither(true);paint.setColor(color);paint.setStyle(Paint.Style.FILL);
            if(r<=0)c.drawRect(x,y,x+w,y+h,paint);else drawSquircle(c,x,y,w,h,r,paint);
        }
        private void line(Canvas c,float x,float y,float w,float h,float radius,int color,float width){
            paint.reset();paint.setAntiAlias(true);paint.setDither(true);paint.setColor(color);paint.setStrokeWidth(width);
            paint.setStyle(Paint.Style.STROKE);drawSquircle(c,x,y,w,h,radius,paint);
            paint.setStyle(Paint.Style.FILL);
        }
        private float trackingFor(float size){
            // Static Google Sans Flex already contains its intended kerning/spacing.
            // Artificial tracking in the previous version caused conspicuously loose text.
            return 0f;
        }
        private final Locale textLocale=new Locale("vi","VN");
        private final android.util.LruCache<String,String> normalizedTexts=new android.util.LruCache<>(256);
        private final android.util.LruCache<String,String> fittedTexts=new android.util.LruCache<>(256);
        private String normalizedText(String value){
            if(value==null)return "";
            String cached=normalizedTexts.get(value);
            if(cached==null){cached=Normalizer.normalize(value,Normalizer.Form.NFC);normalizedTexts.put(value,cached);}
            return cached;
        }
        private String fittedText(String value,float size,boolean bold,float maxWidth){
            String normalized=normalizedText(value);
            String key=Float.floatToIntBits(size)+":"+bold+":"+Float.floatToIntBits(maxWidth)+":"+normalized;
            String cached=fittedTexts.get(key);
            if(cached==null){
                configureTextPaint(measurePaint,size,bold);
                cached=android.text.TextUtils.ellipsize(normalized,measurePaint,maxWidth,android.text.TextUtils.TruncateAt.END).toString();
                fittedTexts.put(key,cached);
            }
            return cached;
        }
        private void configureTextPaint(Paint target,float size,boolean bold){
            target.reset();target.setAntiAlias(true);target.setSubpixelText(true);target.setDither(true);
            target.setHinting(Paint.HINTING_OFF);target.setLinearText(true);
            target.setTextLocale(textLocale);
            target.setTypeface(bold?semibold:regular);target.setFakeBoldText(false);target.setTextSize(size);target.setTextScaleX(1f);
            if(android.os.Build.VERSION.SDK_INT>=21){
                // Android 9 vendor text stacks vary. Keep only the stable public tracking
                // control and never use optional font-feature strings during startup/draw.
                try{target.setLetterSpacing(trackingFor(size));}
                catch(RuntimeException typographyError){Log.w(TAG,"letterSpacing unsupported",typographyError);}
            }
        }
        private float textOpacity=1f;
        private void text(Canvas c,String value,float x,float y,float size,int color,boolean bold,Paint.Align align){
            configureTextPaint(paint,size,bold);paint.setColor(color);paint.setAlpha(Math.round(Color.alpha(color)*textOpacity));paint.setTextAlign(align);
            c.drawText(normalizedText(value),x,y,paint);
        }
        private void clipped(Canvas c,float x,float y,float w,float h,float radius){
            clip.reset();bounds.set(x,y,x+w,y+h);
            clip.addRoundRect(bounds,radius,radius,Path.Direction.CW);
            c.clipPath(clip);
        }
        private final int[] cropScratch=new int[4];
        private final float[] rectScratch=new float[4];
        private void image(Canvas c,Bitmap bmp){image(c,bmp,1f);}
        private void image(Canvas c,Bitmap bmp,float opacity){
            if(bmp==null)return;
            ArtworkLayout.centerCropRect(bmp.getWidth(),bmp.getHeight(),UiGeometry.WIDTH,UiGeometry.HEIGHT,cropScratch);
            paint.reset();paint.setAlpha(Math.round(255*opacity));paint.setFilterBitmap(true);paint.setAntiAlias(true);paint.setColor(0xffffffff);
            wallpaperSource.set(cropScratch[0],cropScratch[1],cropScratch[2],cropScratch[3]);
            c.drawBitmap(bmp,wallpaperSource,wallpaperDestination,paint);
        }
        private void wallpaper(Canvas c){
            float blur=UiGeometry.clamp(listBlur.value,0,1);
            Bitmap transition=wallpapers.transition(blur);if(transition!=null){image(c,transition);return;}
            if(blur>=.999f&&wallpapers.frost(2)!=null){image(c,wallpapers.frost(2));return;}
            if(wallpapers.full()!=null)image(c,wallpapers.full());else{c.drawColor(0xff121215);ambientBackground.draw(c,false);}
            blurScreen(c,blur);
        }
        private final SpringValue listBlur=new SpringValue(0f,110f,22f);
        private void blurScreen(Canvas c,float alpha){
            if(alpha>.002f)image(c,wallpapers.frost(2),UiGeometry.clamp(alpha,0,1));
        }
        private final SpringValue startupReveal=new SpringValue(0f,360f,30f);
        private final DrawerFlow drawerFlow=new DrawerFlow();
        private final SpringValue dockSpring=drawerFlow.dock;
        private boolean startupStarted;
        private float startupWait;
        private float dockRecoil(){return UiGeometry.dockTop(dockSpring.value)-UiGeometry.dockTop(drawer.value);}
        private float docTop(){return UiGeometry.dockTop(dockSpring.value)+260f*(1f-startupReveal.value);}
        private float rowOffset(int row){return UiGeometry.gridTransitionOffset(drawerFlow.rowProgress(row-gridScroll.value));}
        private float gridClipTop(){return docTop()+UiGeometry.DOCK_PADDING+UiGeometry.TILE_MAX_HEIGHT+12f;}
        private boolean updateMenuSelection(float dt){
            menuSelection.value=MotionProfile.stepExponential(menuSelection.value,menuSelection.target,dt,.055f,.001f);
            menuSelection.velocity=0;
            return menuSelection.value!=menuSelection.target;
        }
        private float tileWidth(boolean focused){return UiGeometry.TILE_IDLE_WIDTH + (UiGeometry.TILE_MAX_WIDTH-UiGeometry.TILE_IDLE_WIDTH)*(focused?1:0);}
        private float tileHeight(boolean focused){return UiGeometry.TILE_IDLE_HEIGHT + (UiGeometry.TILE_MAX_HEIGHT-UiGeometry.TILE_IDLE_HEIGHT)*(focused?1:0);}
        private final long[] pacingIntervals=new long[4096];
        private long pacingSamples;
        private long pacingMisses;
        private void resetPacing(){pacingSamples=0;pacingMisses=0;}
        private void frame(long now){
            if(motionWasActive && lastFrame>0){
                long interval=now-lastFrame;
                pacingIntervals[(int)(pacingSamples++%pacingIntervals.length)]=interval;
                if(interval>25_000_000L)pacingMisses++;
            }
            float elapsed=lastFrame==0L?1f/60f:(now-lastFrame)/1_000_000_000f;
            // Idle starts at one VSYNC. During motion, integrate actual elapsed time,
            // including dropped frames; never slow simulated time to disguise a stall.
            float dt=(!motionWasActive || lastFrame==0L)?1f/60f:Math.min(elapsed,.25f);
            lastFrame=now;

            // Resolve ONE focused tile based on the current input state. Both the
            // retiring tile and its successor receive their targets immediately.
            // No wait, cancel/restart chain, or resetting every tile each frame.
            Tile nextTile=null, nextLabel=null;
            // A context menu owns the input, not the selected tile's focus visual.
            // Keep the underlying tile enlarged until the menu closes.
            if(!moveMode){
                if(page==0 && !focusHeader && !catalog.dock.isEmpty()){
                    nextTile=catalog.dock.get(Math.min(dockFocus,catalog.dock.size()-1));
                }else if(page==1){
                    if(focusDrawerDock && !catalog.dock.isEmpty()){
                        nextTile=catalog.dock.get(Math.min(dockFocus,catalog.dock.size()-1));
                    }else if(!catalog.grid.isEmpty()){
                        nextTile=catalog.grid.get(Math.min(gridFocus,catalog.grid.size()-1));
                        nextLabel=nextTile;
                    }
                }
            }
            if(moveMode){
                ArrayList<Tile> moving=moveDock?catalog.dock:catalog.grid;
                int picked=moveDock?dockFocus:gridFocus;
                if(!moving.isEmpty())nextTile=moving.get(Math.min(picked,moving.size()-1));
                if(!moveDock)nextLabel=nextTile;
            }
            tileFocus.select(nextTile==null?null:nextTile.scale);
            labelFocus.select(nextLabel==null?null:nextLabel.labelAlpha);
            boolean active=focusDepth.update(dt);
            startupWait+=dt;
            if(!startupStarted){
                boolean ready=wallpapers.full()!=null;
                for(Tile tile:catalog.dock)if(!tile.isEmpty()&&tile.image==null)ready=false;
                if(ready||startupWait>=1.5f){startupStarted=true;startupReveal.aim(1f);}
                active=true;
            }
            active=startupReveal.update(dt)|active;
            if(startupStarted&&!startupReveal.busy()&&returningTile==null)wallpapers.prepareOptionalTransitions();
            active=drawerFlow.update(drawer.target,dt)|active;
            active=edgeElastic.update(dt)|active;
            if(edgeX.busy()||edgeY.busy()||edgeWasMoving){
                if(edgeRegion==1||lastEdgeRegion==1)invalidateScene();
                if((edgeRegion==2||lastEdgeRegion==2) && headerNode!=null)headerNode.invalidate();
            }
            edgeWasMoving=edgeX.busy()||edgeY.busy();
            if(!edgeWasMoving)lastEdgeRegion=edgeRegion;
            tilePress.aim(holdingOK&&!pressStartedInMenu&&!focusHeader?1f:0f,holdingOK?MotionProfile.PRESS_IN_K:MotionProfile.PRESS_OUT_K,holdingOK?MotionProfile.PRESS_IN_D:MotionProfile.PRESS_OUT_D);
            clockPillWidth();
            if(!shownClock.equals(clock())){previousClock=shownClock;shownClock=clock();clockChange.value=0;clockChange.velocity=0;clockChange.aim(1);}
            clockWidth.aim(desiredClockWidth());active=clockWidth.update(dt)|clockChange.update(dt)|active;
            listBlur.aim(drawer.target,drawer.target==0f?900f:110f,drawer.target==0f?60f:22f);boolean blurWasMoving=listBlur.busy();
            active=listBlur.update(dt)|active;if(blurWasMoving)invalidateScene();
            gridFirstRow=Math.max(0,Math.min(gridFirstRow,Math.max(0,(catalog.grid.size()+5)/6-UiGeometry.VISIBLE_ROWS)));gridScroll.aim(gridFirstRow);
            active=tilePress.update(dt)|drawer.update(dt)|gridScroll.update(dt)|press.update(dt)|headerFocusAnim.update(dt)|menuReveal.update(dt)|menuContent.update(dt)|updateMenuSelection(dt)|launchReveal.update(dt)|((returnWaiting||!resumed||!hasWindowFocus())?false:returnReveal.update(dt))|active;
            if(returningTile!=null && !returnWaiting && !returnReveal.busy()){
                Log.i(TAG,"return.motion.end source="+returningTile.id+" visible_frames="+returnDraws);returningTile=null;
            }
            // Handoff once the source tile has visibly expanded. Android owns the
            // following cross-application transition; no promise of full tvOS morph.
            if(pendingLaunch!=null && !launchDispatched && launchReveal.value>=.9995f){
                launchDispatched=true;
                Runnable action=pendingLaunch;
                pendingLaunch=null;
                post(()->{
                    if(airPlayDisplayVisible){resetLaunchMotion();return;}
                    // startActivity() can return before Android invokes onPause().
                    // Keep this flag separate from launchDispatched, which is reset
                    // by the visual handoff immediately after startActivity returns.
                    externalLaunchInProgress=true;
                    action.run();handler.postDelayed(launchSafety,1500);
                });
            }
            if(launchingTile!=null&&pendingLaunch==null&&!launchDispatched&&!launchReveal.busy())resetLaunchMotion();
            // Spatial movement stays monotone, so grid never dips below its dock row.
            if(drawer.value>1f){drawer.value=1f;drawer.velocity=0f;}
            if(drawer.value<0f){drawer.value=0f;drawer.velocity=0f;}
            for(Tile t:catalog.all){
                if(t.installCompleting && t.installSweep.value>=.999f && !t.installPlaceholder && t.image!=null){
                    t.installReveal.aim(0f);t.installCompleting=false;
                }
                active=t.installSweep.update(dt)|t.installReveal.update(dt)|active;
                active=t.labelAlpha.update(dt)|active;
                active=t.scale.update(dt)|active;
                active=t.offsetX.update(dt)|active;
                active=t.offsetY.update(dt)|active;
                t.tilt.aim(moveMode?MotionProfile.reorderTilt(t.offsetX.velocity,t.offsetY.velocity,t==nextTile):0f);
                t.editLift.aim(moveMode&&t==nextTile?1f:0f);
                t.ring.aim(moveMode&&!t.isEmpty()?MotionProfile.arrangeAngle(now/1_000_000_000.0,t.id.hashCode(),t==nextTile):0f);
                active=t.tilt.update(dt)|t.editLift.update(dt)|t.ring.update(dt)|t.presence.update(dt)|active;
                if(!t.offsetX.busy()&&!t.offsetY.busy())t.traveling=false;
            }
            if(inputMenuChange.update(dt))active=true;
            else inputMenuOutgoing=null;
            if(menu==null && closingMenu!=null && !menuReveal.busy() && menuReveal.value==0f){
                closingMenu=null;
                if(dialogReveal.value>.001f||toastReveal.value>.001f)active=true;
            }
            if(dialogAction==null && !dialogReveal.busy() && dialogReveal.value==0f && dialogBackdrop!=null){
                dialogBackdrop=null;dialogBackdropArea=null;dialogBackdropKey=Long.MIN_VALUE;
            }
            for(int i=0;i<headerButtonFocus.length;i++){headerButtonFocus[i].aim(page==0&&focusHeader&&headerSelection==i&&!popupOwnsHeaderFocus()?1f:0f);active=headerButtonFocus[i].update(dt)|active;}
            headerPress.aim(focusHeader&&!popupOwnsHeaderFocus()&&holdingOK&&!pressStartedInMenu?1f:0f);active=headerPress.update(dt)|radioChange.update(dt)|active;
            Menu animatedMenu=menu!=null?menu:closingMenu;
            menuRowEmphasis.aim(animatedMenu!=null&&animatedMenu.selected>=0&&(menu==null||dialogAction==null)?1f:0f);
            active=menuRowEmphasis.update(dt)|active;
            if(animatedMenu!=null)active=animatedMenu.frostMix.update(dt)|active;
            dialogChoice.aim(dialogConfirm?1f:0f);
            active=airPlayEntrance.update(dt)|toastWidth.update(dt)|toastChange.update(dt)|toastReveal.update(dt)|dialogReveal.update(dt)|dialogChoice.update(dt)|active;
            active=advanceDialogProgress(dt)|active;
            active=moveMode||active;
            motionWasActive=active;
            if(active)postInvalidateOnAnimation();
        }
        private final HashMap<String,TileNode> tileNodes=new HashMap<>();
        private boolean nativeTiles;
        private final Rect popupViewport=new Rect();
        private final Rect localTileClip=new Rect();
        private final class TileNode extends View {
            final Tile tile;int seenGeneration;boolean dock;int materialSlot=-2;int tint=-1;float label=-1f,progress=-1f,reveal=-1f;int session=-1;Bitmap rendered;
            TileNode(Tile tile){super(MainActivity.this);this.tile=tile;setPivotX(138f);setPivotY(83f);setLayerType(View.LAYER_TYPE_NONE,null);}
            @Override protected void onDraw(Canvas c){
                int padded=c.save();c.translate(12f,12f);
                glassMaterials.beginSurface(c);
                float z=UiGeometry.clamp(tile.scale.value,0f,1f);
                paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
                paint.setColorFilter(focusTints[Math.round(z*64)]);
                if(tile.isEmpty())drawShortcut(c,tile,126,71,0,0,252,142);
                else if(tile.image!=null){bounds.set(-1,-1,253,143);c.drawBitmap(tile.image,null,bounds,paint);}
                else if(tile.packageName!=null){
                    material(c,0,0,252,142,UiGeometry.TILE_RADIUS,1);
                    drawFittedText(c,tile.label,126,80,25,fg(),220);
                }else drawShortcut(c,tile,126,71,0,0,252,142);
                paint.setColorFilter(null);
                drawInstallProgress(c,tile,0,0,252,142);
                if(!dock && tile.labelAlpha.value>.003f){
                    float alpha=UiGeometry.clamp(tile.labelAlpha.value,0f,1f);
                    menuBitmap(c,tileLabelBitmap(tile.label),0f,UiGeometry.LABEL_BASELINE-24f+MotionProfile.LABEL_START_Y*(1f-alpha),alpha);
                }
                c.restoreToCount(padded);
            }
        }
        private int tileGeneration;
        private final float[] tileTransform=new float[8];
        private final java.util.IdentityHashMap<Tile,Integer> tileSlots=new java.util.IdentityHashMap<>();
        private final ArrayList<Tile> indexedDock=new ArrayList<>(),indexedGrid=new ArrayList<>();
        private void syncTileNodes(){
            if(getWidth()==0 || getHeight()==0)return;
            canvasScale=Math.min(getWidth()/UiGeometry.WIDTH,getHeight()/UiGeometry.HEIGHT);
            canvasLeft=(getWidth()-UiGeometry.WIDTH*canvasScale)/2f;canvasTop=(getHeight()-UiGeometry.HEIGHT*canvasScale)/2f;
            int generation=++tileGeneration;
            // Rebuild positions only after install/remove/reorder, never per animation frame.
            if(!indexedDock.equals(catalog.dock)||!indexedGrid.equals(catalog.grid)){
                indexedDock.clear();indexedDock.addAll(catalog.dock);
                indexedGrid.clear();indexedGrid.addAll(catalog.grid);
                tileSlots.clear();
                for(int i=0;i<catalog.grid.size();i++)tileSlots.put(catalog.grid.get(i),i);
                for(int i=0;i<catalog.dock.size();i++)tileSlots.put(catalog.dock.get(i),-i-1);
            }
            float scrollStrain=MotionProfile.strain(gridScroll.velocity,7f,.012f);
            for(Tile tile:catalog.all){
                Integer slot=tileSlots.get(tile);
                boolean dock=slot!=null&&slot<0;
                int index=slot==null?-1:dock?-slot-1:slot;
                if(index<0 && !tile.exiting)continue;
                if(tile.exiting && tile.presence.value<.001f && !tile.presence.busy()){tile.exiting=false;continue;}
                TileNode node=tileNodes.get(tile.id);
                if(node==null || node.tile!=tile){
                    if(node!=null)removeSceneNode(node);
                    node=new TileNode(tile);tileNodes.put(tile.id,node);attachSceneNode(node,276,UiGeometry.TILE_NODE_HEIGHT+24);
                }
                node.seenGeneration=generation;
                if(tile.exiting)dock=tile.exitDock;
                boolean labelsChanged=node.dock!=dock||tile.isEmpty()&&node.materialSlot!=index;node.dock=dock;node.materialSlot=index;
                float x=tile.exiting?tile.exitX:UiGeometry.centerX(index%6)+tile.offsetX.value;
                float y=tile.exiting?tile.exitY:(dock?docTop()+UiGeometry.DOCK_HEIGHT/2f:UiGeometry.rowTop(index/6,gridScroll.value)+UiGeometry.TILE_MAX_HEIGHT/2f+rowOffset(index/6))+tile.offsetY.value;
                boolean shown=true;
                if(!dock && !tile.traveling && (y+112<gridClipTop()||y-71>UiGeometry.gridClipBottom(drawer.value)))shown=false;
                node.setVisibility(shown?View.VISIBLE:View.INVISIBLE);
                boolean selected=moveMode?(dock==moveDock && index==(dock?dockFocus:gridFocus)):
                    (dock?(page==0&&!focusHeader||page==1&&focusDrawerDock)&&index==dockFocus:page==1&&!focusDrawerDock&&index==gridFocus);
                float pushed=selected?tilePress.value:0f;
                MotionProfile.resolveTileTransform(
                    x,y,tile.scale.value,pushed,tile.presence.value,
                    tile.retired,tile.exiting,selected,tile.traveling,
                    dock,tile==edgeTile && edgeRegion==1,
                    canvasLeft,canvasTop,canvasScale,
                    edgeX.value,edgeY.value,edgeAnchorX,edgeAnchorY,
                    tile.offsetX.velocity,tile.offsetY.velocity,scrollStrain,
                    tile.offsetX.value,tile.offsetY.value,drawer.value,
                    tile==launchingTile||tile==returningTile,tileTransform);
                float sy=tileTransform[4];
                // Commit each property once; intermediate base values used to
                // dirty render nodes even when their final transform was stable.
                node.setX(tileTransform[0]);node.setY(tileTransform[1]);
                node.setScaleX(tileTransform[2]);node.setScaleY(tileTransform[3]);
                node.setRotation(tile.tilt.value+tile.ring.value);
                node.setAlpha(tileTransform[5]);
                node.setTranslationZ(tileTransform[6]);
                if(!dock && !tile.traveling && sy>.00001f){
                    int top=(int)Math.max(0,71f+(gridClipTop()-y)/sy);
                    int bottom=(int)Math.min(UiGeometry.TILE_NODE_HEIGHT,71f+(UiGeometry.gridClipBottom(drawer.value)-y)/sy);
                    if(top==0 && bottom==UiGeometry.TILE_NODE_HEIGHT)node.setClipBounds(null);
                    else{localTileClip.set(0,top+12,276,Math.max(top,bottom)+12);node.setClipBounds(localTileClip);}
                }else node.setClipBounds(null);
                int tint=(int)tileTransform[7];
                if(node.tint!=tint || node.label!=tile.labelAlpha.value || node.rendered!=tile.image || node.session!=tile.installSessionId || node.progress!=tile.installSweep.value || node.reveal!=tile.installReveal.value || labelsChanged){
                    node.tint=tint;node.label=tile.labelAlpha.value;node.rendered=tile.image;node.session=tile.installSessionId;node.progress=tile.installSweep.value;node.reveal=tile.installReveal.value;node.invalidate();
                }
            }
            java.util.Iterator<java.util.Map.Entry<String,TileNode>> iterator=tileNodes.entrySet().iterator();
            while(iterator.hasNext()){
                java.util.Map.Entry<String,TileNode> entry=iterator.next();
                if(entry.getValue().seenGeneration!=generation){removeSceneNode(entry.getValue());iterator.remove();}
            }
            catalog.clearRetired();
            cacheStaticScene(!sceneMoving()&&!(edgeRegion==1&&edgeDeformed()));
            if(!nativeTiles){nativeTiles=true;invalidateScene();}
        }
        private View headerNode;
        private String headerClock="",headerStamp="";
        private int headerWifi=-1,headerSolar=-1;
        private boolean headerFocusSuppressed;
        private float headerStartup=-1f;
        private void syncHeader(){
            if(headerNode==null){
                headerNode=new View(MainActivity.this){@Override protected void onDraw(Canvas c){drawHeader(c,1f);}};
                headerNode.setPivotX(0);headerNode.setPivotY(0);attachSceneNode(headerNode,1920,150);
            }
            headerNode.setScaleX(canvasScale);headerNode.setScaleY(canvasScale);
            headerNode.setX(canvasLeft);headerNode.setY(canvasTop-150f*(1f-startupReveal.value)*canvasScale);
            headerNode.setAlpha((1-UiGeometry.clamp(drawer.value,0,1))*UiGeometry.clamp(startupReveal.value,0,1));
            boolean suppressed=popupOwnsHeaderFocus();
            boolean changed=suppressed!=headerFocusSuppressed||headerStamp.isEmpty()||!clock().equals(headerClock)||headerWifi!=controls.wifi||headerSolar!=solar.size()||headerStartup!=startupReveal.value;
            if(changed||clockWidth.busy()||clockChange.busy()||radioChange.busy()||headerButtonsBusy()||(focusHeader&&press.busy())||headerFocusAnim.busy()){
                headerFocusSuppressed=suppressed;headerClock=clock();headerWifi=controls.wifi;headerSolar=solar.size();headerStartup=startupReveal.value;headerStamp=headerClock+":"+headerWifi+":"+headerSolar+":"+headerStartup;headerNode.invalidate();
            }
        }
        @Override protected void advanceFrame(long frameTimeNanos){
            startVisibleReturn();frame(frameTimeNanos);
            if(airPlayFrameReady&&!airPlayFrameVisible&&resumed&&hasWindowFocus()&&returningTile==null
                &&(!airPlayReturningFromApp||returnWindowEntered||
                    android.os.SystemClock.uptimeMillis()>=airPlayEntranceDeadline)){
                airPlayFrameVisible=true;
                handler.removeCallbacks(airPlayEntranceFallback);
                airPlayEntrance.aim(1f);
                showToast("AirPlay · Nhấn Back để dừng");
                airPlayMatte.setVisibility(View.VISIBLE);
                airPlaySurface.setTranslationX(0f);
            }
            if(airPlayFrameVisible)updateAirPlayTransform();
            syncTileNodes();syncHeader();openRequestedApps();
        }
        @Override protected Rect overlayViewport(){
            if(airPlayFrameVisible)return toastReveal.value>.001f?overlayBounds(new RectF(390,890,1530,1080)):null;
            RectF box=null;
            Tile moving=returningTile!=null?returningTile:launchingTile;
            if(moving!=null){
                if(returningTile!=null)readTileBounds(moving,morphOrigin);
                float p=SurfaceMorph.artworkProgress(returningTile!=null?returnReveal.value:launchReveal.value);
                morphDamage.set(morphOrigin.left*(1-p),morphOrigin.top*(1-p),
                    morphOrigin.right+(1920-morphOrigin.right)*p,morphOrigin.bottom+(1080-morphOrigin.bottom)*p);
                // Bound damage to the moving surface, including the prepared tile's
                // transparent gutter and antialiasing. View.layout damages both old
                // and new bounds, so the old image is erased during shrink/rebound.
                morphDamage.inset(-6f,-6f);box=morphDamage;
            }

            if(dialogReveal.value>.001f){
                RectF panel=dialogRect();panel.inset(-28f,-28f);
                if(box==null)box=panel;else box.union(panel);
            }
            Menu m=menu!=null?menu:closingMenu;
            if(m!=null){RectF menuBox=menuVisualRect(m);menuBox.inset(-32,-32);if(box==null)box=menuBox;else box.union(menuBox);}
            if(toastReveal.value>.001f){if(box==null)box=new RectF(390,890,1530,1040);else box.union(390,890,1530,1040);}
            return box==null?null:overlayBounds(box);
        }
        private Rect overlayBounds(RectF box){
            popupViewport.set(Math.max(0,(int)(canvasLeft+box.left*canvasScale)),
                Math.max(0,(int)(canvasTop+box.top*canvasScale)),
                Math.min(getWidth(),(int)Math.ceil(canvasLeft+box.right*canvasScale)),
                Math.min(getHeight(),(int)Math.ceil(canvasTop+box.bottom*canvasScale)));
            return popupViewport;
        }
        @Override protected boolean sceneMoving(){
            return drawer.busy()||listBlur.busy()||!startupStarted||startupReveal.busy()||dockSpring.busy();
        }
        @Override protected void drawOverlay(Canvas c){
            int saved=c.save();c.translate(canvasLeft,canvasTop);c.scale(canvasScale,canvasScale);
            glassMaterials.beginSurface(c);
            try{
                if(airPlayFrameVisible){
                    if(airPlayFrameVisible)c.translate(0,AIRPLAY_TOAST_TRAVEL*(1-airPlayToastProgress()));
                    drawToast(c);return;
                }
                if(menu!=null||closingMenu!=null)drawMenu(c);
                drawToast(c);drawDialog(c);
                if(launchingTile!=null && launchReveal.value>.005f)drawLaunchMorph(c);
                if(returningTile!=null)drawReturnMorph(c);
            }catch(RuntimeException error){Log.e(TAG,"render.failed.popup",error);}
            finally{textOpacity=1f;c.restoreToCount(saved);}
        }
        private long sceneDrawCount;
        private float sceneDrawnDrawer,sceneDrawnBlur;
        @Override protected void drawScene(Canvas real){
            sceneDrawCount++;sceneDrawnDrawer=drawer.value;sceneDrawnBlur=listBlur.value;
            if(renderFailure!=null){drawRenderFailure(real,renderFailure);return;}
            try{
                canvasScale=Math.min(real.getWidth()/UiGeometry.WIDTH,real.getHeight()/UiGeometry.HEIGHT);
                canvasLeft=(real.getWidth()-UiGeometry.WIDTH*canvasScale)/2f;
                canvasTop=(real.getHeight()-UiGeometry.HEIGHT*canvasScale)/2f;
                int saved=real.save();real.translate(canvasLeft,canvasTop);real.scale(canvasScale,canvasScale);
                wallpaper(real);
                drawHomeAndDrawer(real);
                real.restoreToCount(saved);
                // Targets may change during drawing, so never miss the first VSYNC after focus changes.
                if(tilePress.busy()||drawer.busy()||gridScroll.busy()||press.busy()||headerFocusAnim.busy()||menuReveal.busy()||menuContent.busy()||menuSelection.busy()||launchReveal.busy()||returnReveal.busy())
                    postInvalidateOnAnimation();
            }catch(RuntimeException | LinkageError | AssertionError drawError){
                renderFailure=drawError;
                Log.e(TAG,"render.failed",drawError);
                drawRenderFailure(real,drawError);
            }
        }
        private void drawRenderFailure(Canvas c,Throwable error){
            c.drawColor(0xff101014);
            Paint fallback=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
            fallback.setTypeface(regular!=null?regular:Typeface.DEFAULT);
            fallback.setColor(Color.WHITE);fallback.setTextAlign(Paint.Align.CENTER);fallback.setTextSize(24f);
            c.drawText("pdmvOS gặp lỗi khi dựng giao diện",c.getWidth()/2f,c.getHeight()/2f-20f,fallback);
            fallback.setColor(Color.WHITE);fallback.setTextSize(16f);
            String name=error==null?"Unknown":error.getClass().getSimpleName();
            c.drawText(name+" · chạy tools/diagnose-start.sh",c.getWidth()/2f,c.getHeight()/2f+20f,fallback);
        }
        private void drawHomeAndDrawer(Canvas c){
            glassMaterials.beginSurface(c);
            float progress=UiGeometry.clamp(drawer.value,0f,1f);
            glassMaterials.setSceneBlur(UiGeometry.clamp(listBlur.value,0,1));
            drawGrid(c,progress);
            drawDock(c,docTop(),progress);

        }
        private final SpringValue toastWidth=new SpringValue(0f,420f,38f),toastChange=new SpringValue(1f,440f,38f);
        private String previousToast="";
        private final SpringValue toastReveal=new SpringValue(0f,340f,29f);
        private final SpringValue dialogChoice=new SpringValue(0f,440f,38f);
        private final SpringValue dialogReveal=new SpringValue(0f,360f,31f);
        
        private final SpringValue radioChange=new SpringValue(1f,380f,36f);
        private int previousWifi=controls.wifi;
        private final WifiFeedback wifiFeedback=new WifiFeedback(controls.wifi);
        private final SpringValue[] headerButtonFocus={new SpringValue(0,440,34),new SpringValue(0,440,34),new SpringValue(0,440,34),new SpringValue(0,440,34)};
        private final SpringValue headerPress=new SpringValue(0,440,34);
        private boolean headerButtonsBusy(){if(headerPress.busy())return true;for(SpringValue v:headerButtonFocus)if(v.busy())return true;return false;}
        private String toastMessage="",dialogTitle="",dialogBody="",dialogButton="";
        private Runnable dialogAction;
        private boolean dialogConfirm,updateDialog,destructiveDialog;
        private int dialogDismissals;
        private static final float POPUP_TITLE_SIZE=25f,POPUP_TEXT_SIZE=23f;
        private static final float DIALOG_WIDTH=480f,DIALOG_LINE_HEIGHT=31f;
        private static final float DIALOG_BODY_TOP=84f,DIALOG_PROGRESS_HEIGHT=36f;
        private int dialogProgress=-1;
        private float shownDialogProgress;
        private final Runnable finishUpdateProgress=()->{
            if(updateDialog&&updater.state()==LauncherUpdate.State.READY)renderUpdateDialog();
        };
        private boolean advanceDialogProgress(float dt){
            if(dialogProgress<0||shownDialogProgress>=dialogProgress)return false;
            shownDialogProgress=MotionProfile.stepExponential(shownDialogProgress,dialogProgress,dt,.16f,.2f);
            if(shownDialogProgress<dialogProgress)return true;
            if(dialogProgress==100&&updateDialog)handler.postDelayed(finishUpdateProgress,140);
            return true;
        }
        private final Runnable hideToast=()->{toastReveal.aim(0f);invalidate();};
        private void refreshControlStates(){
            int oldWifi=controls.wifi;controls.refresh();
            presentWifiState(controls.wifi);
            
            if(oldWifi!=controls.wifi){
                previousWifi=oldWifi;radioChange.value=0;radioChange.velocity=0;radioChange.aim(1);
                
                invalidateScene();invalidate();
            }
        }
        private void presentWifiState(int current){
            int event=wifiFeedback.observe(current,resumed&&hasWindowFocus(),android.os.SystemClock.elapsedRealtime());
            if(event==WifiFeedback.NONE)return;
            showToast("Wi-Fi · "+WifiFeedback.label(event));

        }
        private final Runnable statusTick=new Runnable(){
            @Override public void run(){
                if(!resumed)return;
                if(launchingTile!=null||returningTile!=null||drawer.busy()||gridScroll.busy()||tilePress.busy()||menuReveal.busy()||tileFocus.selected()!=null&&tileFocus.selected().busy()){
                    handler.postDelayed(this,250);return;
                }
                refreshControlStates();if(installTracker!=null)installTracker.poll();
                if(hasWindowFocus()&&menu==null&&dialogAction==null&&updater!=null){
                    updater.checkIfDue();
                    if(updater.shouldAnnounce())showToast("Có pdmvOS "+updater.version()+" · Nhấn giữ Cài đặt");
                }
                handler.postDelayed(this,2000);
            }
        };
        private RectF glassCaptureArea(RectF bounds){
            // Three box passes at radius 3 and 1/8 scale need 72 screen pixels
            // of context; keep 80 so cropped edges never clamp into visible glass.
            RectF area=new RectF(bounds);area.inset(-80f,-80f);
            area.intersect(0,0,UiGeometry.WIDTH,UiGeometry.HEIGHT);return area;
        }
        private Bitmap captureGlassRegion(RectF area,int radius,int overlays){
            Bitmap sample=sampleGlassRegion(area,radius,overlays);
            float divisor=radius==6?8f:4f;
            try{return WallpaperStore.blur(sample,Math.max(1,Math.round(radius*4f/divisor)));}
            finally{sample.recycle();}
        }
        private Bitmap sampleGlassRegion(RectF area,int radius,int overlays){
            // Popup glass is deliberately low-frequency: sample at 1/8 scale
            // with the same screen-space blur, instead of blurring four times
            // as many pixels for detail that the material immediately removes.
            float divisor=radius==6?8f:4f;
            int width=Math.max(1,(int)Math.ceil(area.width()/divisor)),height=Math.max(1,(int)Math.ceil(area.height()/divisor));
            Bitmap sample=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            paintGlassRegion(new Canvas(sample),area,width,height,overlays);return sample;
        }
        private Picture recordGlassRegion(RectF area,int radius,int overlays){
            float divisor=radius==6?8f:4f;
            int width=Math.max(1,(int)Math.ceil(area.width()/divisor)),height=Math.max(1,(int)Math.ceil(area.height()/divisor));
            Picture picture=new Picture();Canvas recording=picture.beginRecording(width,height);
            try{paintGlassRegion(recording,area,width,height,overlays);}finally{picture.endRecording();}
            return picture;
        }
        private void paintGlassRegion(Canvas scene,RectF area,int width,int height,int overlays){
            scene.scale(width/area.width(),height/area.height());scene.translate(-area.left,-area.top);
            boolean previous=nativeTiles;
            try{
                nativeTiles=true;wallpaper(scene);drawHomeAndDrawer(scene);
                int saved=scene.save();scene.scale(1f/canvasScale,1f/canvasScale);scene.translate(-canvasLeft,-canvasTop);
                drawBackdropNodes(scene);scene.restoreToCount(saved);
                if(overlays>=1 && (menu!=null||closingMenu!=null)){glassMaterials.beginSurface(scene);drawMenu(scene);}
                if(overlays>=2){glassMaterials.beginSurface(scene);drawToast(scene);}
            }finally{nativeTiles=previous;}
        }
        private void showToast(String message){
            // Resolve the first backdrop in drawToast without delaying the message.
            // Subsequent motion updates use one bounded pixel-blur job.
            invalidateToastBackdrop();
            if(!toastMessage.equals(message)){previousToast=toastMessage;toastMessage=message;toastChange.value=0;toastChange.velocity=0;toastChange.aim(1f);}
            configureTextPaint(measurePaint,24,false);float desired=Math.min(1100,measurePaint.measureText(normalizedText(message))+88);
            if(toastReveal.value<.001f){toastWidth.value=desired;toastWidth.velocity=0;}
            toastWidth.aim(desired);toastReveal.aim(1f);handler.removeCallbacks(hideToast);handler.postDelayed(hideToast,3200);invalidate();
        }
        private long toastSceneKey=Long.MIN_VALUE;
        private long underlyingSceneKey(){
            return underlyingSceneKey(null);
        }
        private final RectF sceneKeyBounds=new RectF();
        private long mixSceneValue(long key,float value){return key*31+Float.floatToIntBits(value);}
        private long underlyingSceneKey(RectF area){
            long key=System.identityHashCode(wallpapers.full());
            key=key*31+System.identityHashCode(wallpapers.frost(2));
            key=mixSceneValue(key,drawer.value);key=mixSceneValue(key,listBlur.value);key=mixSceneValue(key,gridScroll.value);
            if(edgeRegion==1||(edgeRegion==2&&(area==null||area.top<150))){
                key=mixSceneValue(key,edgeX.value);key=mixSceneValue(key,edgeY.value);
            }
            key=mixSceneValue(key,startupReveal.value);key=mixSceneValue(key,dockSpring.value);
            if(headerNode!=null&&(area==null||area.top<150)){
                key=key*31+headerStamp.hashCode();
                key=mixSceneValue(key,clockWidth.value);key=mixSceneValue(key,clockChange.value);
                key=mixSceneValue(key,radioChange.value);key=mixSceneValue(key,headerPress.value);
                key=mixSceneValue(key,headerFocusAnim.value);
                for(SpringValue focus:headerButtonFocus)key=mixSceneValue(key,focus.value);
            }
            for(Tile tile:catalog.all){
                TileNode node=tileNodes.get(tile.id);
                if(area!=null && !moveMode){
                    if(node==null||node.getVisibility()!=VISIBLE)continue;
                    // Exclude only the transparent antialiasing gutter. Its
                    // transform cannot change any pixel behind the popup.
                    RectF bounds=sceneKeyBounds;bounds.set(11,11,node.getWidth()-11,node.getHeight()-11);
                    node.getMatrix().mapRect(bounds);bounds.offset(node.getLeft()-canvasLeft,node.getTop()-canvasTop);
                    bounds.set(bounds.left/canvasScale,bounds.top/canvasScale,bounds.right/canvasScale,bounds.bottom/canvasScale);
                    if(!RectF.intersects(area,bounds))continue;
                }
                key=key*31+System.identityHashCode(tile);
                key=key*31+System.identityHashCode(tile.image);
                key=key*31+tile.installSessionId;key=key*31+Float.floatToIntBits(tile.installSweep.value);key=key*31+Float.floatToIntBits(tile.installReveal.value);
                key=mixSceneValue(key,tile.scale.value);key=mixSceneValue(key,tile.offsetX.value);key=mixSceneValue(key,tile.offsetY.value);
                key=mixSceneValue(key,tile.labelAlpha.value);key=mixSceneValue(key,tile.presence.value);key=mixSceneValue(key,tile.tilt.value);key=mixSceneValue(key,tile.ring.value);
                key=key*31+Float.floatToIntBits(tilePress.value);
                if(node!=null){
                    key=mixSceneValue(key,node.getX());key=mixSceneValue(key,node.getY());key=mixSceneValue(key,node.getScaleX());
                    key=mixSceneValue(key,node.getScaleY());key=mixSceneValue(key,node.getRotation());key=mixSceneValue(key,node.getAlpha());
                    key=key*31+node.getVisibility();
                }
            }
            return key;
        }
        private final RectF toastCaptureArea=new RectF();
        private RectF toastCaptureBounds(){
            float half=Math.max(88f,Math.max(toastWidth.value,toastWidth.target))/2f;
            // Three radius-12 box passes at quarter scale reach 144 world pixels.
            // Keep 160 pixels of context and the original 4-pixel sample grid.
            float left=Math.max(0f,(float)Math.floor((960f-half-160f)/16f)*16f);
            float right=Math.min(1920f,(float)Math.ceil((960f+half+160f)/16f)*16f);
            toastCaptureArea.set(left,864f,right,1080f);return toastCaptureArea;
        }
        private long toastSceneKey(){
            RectF area=toastCaptureBounds();long key=sceneWithMenuKey(area);
            return mixSceneValue(mixSceneValue(key,area.left),area.right);
        }
        private long sceneWithMenuKey(RectF area){
            long key=underlyingSceneKey(area);
            Menu behind=menu!=null?menu:closingMenu;
            if(behind!=null){
                RectF bounds=menuVisualRect(behind);bounds.inset(-28,-52);
                if(RectF.intersects(bounds,area)){
                    key=key*31+System.identityHashCode(behind);
                    key=mixSceneValue(key,menuReveal.value);key=mixSceneValue(key,menuContent.value);
                    key=mixSceneValue(key,menuSelection.value);key=mixSceneValue(key,menuRowEmphasis.value);
                    key=mixSceneValue(key,press.value);key=mixSceneValue(key,behind.frostMix.value);
                    if(edgeRegion==3){key=mixSceneValue(key,edgeX.value);key=mixSceneValue(key,edgeY.value);}
                }
            }
            return key;
        }
        private java.util.concurrent.ExecutorService toastWorker;
        private boolean toastWorkPending;
        private volatile boolean toastWorkerClosed;
        private int toastBackdropEpoch;
        private void invalidateToastBackdrop(){++toastBackdropEpoch;toastSceneKey=Long.MIN_VALUE;}
        private void refreshToastBackdrop(){
            long key=toastSceneKey();if(key==toastSceneKey||toastWorkerClosed)return;
            RectF area=new RectF(toastCaptureBounds());
            // A new message has a correct backdrop in its first visible frame.
            if(toastSceneKey==Long.MIN_VALUE){
                Bitmap blurred=captureGlassRegion(area,12,1);
                glassMaterials.setToastBackdrop(blurred,area);toastSceneKey=key;return;
            }
            // Record View/Drawable commands on UI. Replay the completed immutable
            // picture and blur pixels off-thread; never queue old animation frames.
            if(toastWorkPending)return;
            if(toastWorker==null){
                java.util.concurrent.ThreadPoolExecutor executor=new java.util.concurrent.ThreadPoolExecutor(
                    0,1,15,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.LinkedBlockingQueue<>(),
                    r->new Thread(()->{
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();
                    },"pdmvOS-toast"));
                toastWorker=executor;
            }
            Picture picture=recordGlassRegion(area,12,1);int epoch=toastBackdropEpoch;toastWorkPending=true;
            toastWorker.execute(()->{
                Bitmap result=null,sample=null;
                try{
                    sample=Bitmap.createBitmap(picture.getWidth(),picture.getHeight(),Bitmap.Config.ARGB_8888);
                    new Canvas(sample).drawPicture(picture);result=WallpaperStore.blur(sample,12);result.prepareToDraw();
                }catch(RuntimeException|OutOfMemoryError error){Log.w(TAG,"Toast blur unavailable",error);}
                finally{if(sample!=null)sample.recycle();}
                Bitmap ready=result;
                if(toastWorkerClosed){if(ready!=null)ready.recycle();return;}
                handler.post(()->{
                    toastWorkPending=false;
                    if(toastWorkerClosed||epoch!=toastBackdropEpoch){if(ready!=null)ready.recycle();return;}
                    if(ready!=null){glassMaterials.setToastBackdrop(ready,area);toastSceneKey=key;invalidate();}
                });
            });
        }
        private void drawToast(Canvas c){
            float q=UiGeometry.clamp(toastReveal.value,0,1);if(q<.001f)return;
            refreshToastBackdrop();
            // Offscreen node capture changes the material's sampling frame.
            // Rebind it to this canvas before the toast slide transform is applied.
            glassMaterials.beginSurface(c);
            int save=q==1f?c.save():c.saveLayerAlpha(400,890,1520,1035,(int)(255*q),Canvas.ALL_SAVE_FLAG);
            c.translate(0,24*(1-toastReveal.value));
            configureTextPaint(measurePaint,24,false);
            Paint.FontMetrics metrics=measurePaint.getFontMetrics();
            float h=Math.max(28,metrics.descent-metrics.ascent)+32;
            float w=Math.max(88,toastWidth.value);
            material(c,960-w/2,930,w,h,h/2,4);
            drawSolar(c,"info",960-w/2+38,930+h/2,28,accent());
            float content=UiGeometry.clamp(toastChange.value,0,1);
            float baseline=h/2-(metrics.ascent+metrics.descent)/2;
            int textWidth=Math.max(1,Math.round(w-88)),textHeight=Math.max(1,Math.round(h));
            if(!previousToast.isEmpty()&&1-content>.001f)
                menuBitmap(c,toastLabelBitmap(previousToast,textWidth,textHeight,baseline),960-w/2+64,930,1-content);
            if(!toastMessage.isEmpty()&&content>.001f)
                menuBitmap(c,toastLabelBitmap(toastMessage,textWidth,textHeight,baseline),960-w/2+64,930,content);
            textOpacity=1f;
            c.restoreToCount(save);
        }
        private void showDialog(String title,String body,String button,Runnable action){
            clearEdgeOnContextSwitch();
            closeMenu();holdingOK=false;pressStartedInDialog=false;handler.removeCallbacks(holdAction);settleReturnState(press,0f);
            dialogTitle=title;dialogBody=body;dialogButton=button;dialogAction=action;dialogConfirm=false;
            settleReturnState(dialogChoice,0);
            handler.removeCallbacks(finishUpdateProgress);
            updateDialog=false;destructiveDialog=false;dialogProgress=-1;shownDialogProgress=0f;
            dialogBackdropKey=Long.MIN_VALUE;dialogReveal.aim(1f);invalidate();
        }
        private Bitmap dialogBackdrop;
        private RectF dialogBackdropArea;
        private long dialogBackdropKey=Long.MIN_VALUE;
        private void refreshDialogBackdrop(RectF panel){
            RectF area=glassCaptureArea(panel);long key=sceneWithMenuKey(area);
            if(dialogBackdrop==null||key!=dialogBackdropKey||!area.equals(dialogBackdropArea)){
                dialogBackdrop=captureGlassRegion(area,6,1);dialogBackdropArea=area;dialogBackdropKey=key;
            }
            glassMaterials.setMenuBackdrop(dialogBackdrop,dialogBackdropArea);
        }
        private void dismissDialog(){
            clearEdgeOnContextSwitch();
            dialogDismissals++;
            handler.removeCallbacks(finishUpdateProgress);
            dialogAction=null;updateDialog=false;dialogReveal.aim(0f);holdingOK=false;pressStartedInDialog=false;press.aim(0f,MotionProfile.PRESS_OUT_K,MotionProfile.PRESS_OUT_D);invalidate();
        }
        private String measuredDialogBody;
        private ArrayList<String> measuredDialogLines;
        private ArrayList<String> dialogLines(){
            if(dialogBody.equals(measuredDialogBody)&&measuredDialogLines!=null)return measuredDialogLines;
            configureTextPaint(measurePaint,POPUP_TEXT_SIZE,false);ArrayList<String> lines=new ArrayList<>();String line="";
            for(String word:normalizedText(dialogBody).split(" ")){String next=line.isEmpty()?word:line+" "+word;
                if(measurePaint.measureText(next)>DIALOG_WIDTH-2*UiGeometry.POPUP_TITLE_INSET&&!line.isEmpty()){lines.add(line);line=word;}else line=next;}
            lines.add(line);measuredDialogBody=dialogBody;measuredDialogLines=lines;return lines;
        }
        private RectF dialogRect(){
            UiGeometry.dialogRect(dialogLines().size(),dialogProgress>=0,rectScratch);
            return new RectF(rectScratch[0],rectScratch[1],rectScratch[2],rectScratch[3]);
        }
        private RectF dialogButtonRect(RectF panel,int index){
            UiGeometry.dialogButtonRect(panel.left,panel.right,panel.bottom,!dialogButton.isEmpty(),index,rectScratch);
            return new RectF(rectScratch[0],rectScratch[1],rectScratch[2],rectScratch[3]);
        }
        private void drawDialog(Canvas c){
            float q=UiGeometry.clamp(dialogReveal.value,0,1);if(q<.001f)return;
            RectF r=dialogRect();
            refreshDialogBackdrop(r);glassMaterials.beginSurface(c);
            int save=q==1f?c.save():c.saveLayerAlpha(r.left-28,r.top-28,r.right+28,r.bottom+28,(int)(255*q),Canvas.ALL_SAVE_FLAG);
            glassMaterials.setLiveRims(edgeRegion==4 && edgeDeformed());
            if(edgeRegion==4)stretchEdge(c,r,.009f);
            c.scale(.94f+.06f*q,.94f+.06f*q,960,540);
            material(c,r.left,r.top,r.width(),r.height(),UiGeometry.POPUP_RADIUS,3);
            glassMaterials.setLiveRims(false);
            float left=r.left+UiGeometry.POPUP_PADDING,right=r.right-UiGeometry.POPUP_PADDING;
            menuBitmap(c,dialogContentBitmap(Math.round(r.height())),r.left,r.top,1f);
            if(dialogProgress>=0){
                float width=right-left,top=r.bottom-UiGeometry.POPUP_PADDING-UiGeometry.POPUP_ROW_HEIGHT-UiGeometry.POPUP_GAP-8f;
                rect(c,left,top,width,8,4,0x36ffffff);
                if(shownDialogProgress>0)rect(c,left,top,width*shownDialogProgress/100f,8,4,accent());
            }
            int buttonCount=dialogButton.isEmpty()?1:2;
            float pressed=pressStartedInDialog?press.value:0f;
            for(int i=0;i<buttonCount;i++){
                float selected=UiGeometry.clamp(i==0?1f-dialogChoice.value:dialogChoice.value,0,1);
                RectF buttonRect=dialogButtonRect(r,i);
                float x=buttonRect.left,by=buttonRect.top,w=buttonRect.width(),radius=UiGeometry.POPUP_ROW_RADIUS;
                float zoom=UiGeometry.popupItemScale(selected,pressed);
                int button=c.save();
                c.scale(zoom,zoom,buttonRect.centerX(),buttonRect.centerY());
                if(i==1&&!destructiveDialog){
                    rect(c,x,by,w,UiGeometry.POPUP_ROW_HEIGHT,radius,0xff0063cc);
                    glassMaterials.drawRim(c,x,by,w,UiGeometry.POPUP_ROW_HEIGHT,radius,1);
                }else glassMaterials.drawItem(c,x,by,w,UiGeometry.POPUP_ROW_HEIGHT,radius,1f);
                int ink=i==1&&destructiveDialog?0xffff494f:fg();
                String label=i==0?(buttonCount==1?"Đóng":"Hủy"):dialogButton;
                menuBitmap(c,dialogButtonBitmap(label,Math.round(w),ink),x,by,1f);
                c.restoreToCount(button);
            }
            c.restoreToCount(save);
        }
        private int connectionColor(int state){return state==2?accent():state==1?0xffffcd72:0xffb9c0cb;}
        private String connectionLabel(int state){return state==2?"Đã kết nối":state==1?"Chưa kết nối":"Đã tắt";}
        private void headerHold(){
            if(uiSounds!=null)uiSounds.press();
            if(headerSelection==0)launchSystem(new Intent(Settings.ACTION_WIFI_SETTINGS),"Không mở được cài đặt Wi-Fi");
            
            else if(headerSelection==2)launchSystem(new Intent(android.media.tv.TvInputManager.ACTION_SETUP_INPUTS),"Hệ thống không cung cấp cài đặt đầu vào");
            else openUpdateDialog();
        }
        private void activateHeader(){
            if(uiSounds!=null)uiSounds.press();
            if(headerSelection==0){
                refreshControlStates();
                if(controls.wifi!=0){
                    showDialog("Tắt Wi-Fi?", "TV sẽ ngắt kết nối với mạng Wi-Fi.", "Tắt Wi-Fi", ()->setWifi(false));
                    destructiveDialog=true;
                }else setWifi(true);
            }
            
            else if(headerSelection==2){if(menu!=null&&menu.sourceHeader==2)closeMenu();else openInputMenu();}
            else systemSettings();
            refreshControlStates();invalidate();
        }
        private void openUpdateDialog(){
            if(updater.state()==LauncherUpdate.State.IDLE||updater.state()==LauncherUpdate.State.CHECK_FAILED
                ||updater.state()==LauncherUpdate.State.CURRENT)updater.check();
            renderUpdateDialog();
        }
        private void onUpdateChanged(){
            if(!updateDialog)return;
            if(dialogProgress>=0){
                if(updater.state()==LauncherUpdate.State.DOWNLOADING){
                    dialogProgress=updater.progress();invalidate();return;
                }
                if(updater.state()==LauncherUpdate.State.READY){
                    dialogProgress=100;invalidate();return;
                }
            }
            renderUpdateDialog();
        }
        private void renderUpdateDialog(){
            String title="Cập nhật pdmvOS",body,button="";
            Runnable action=()->{};
            switch(updater.state()){
                case IDLE, CHECKING -> body="Đang kiểm tra bản phát hành…";
                case CURRENT -> body="Chưa có bản mới hơn "+installedVersion()+".";
                case AVAILABLE -> {
                    body="Đã có bản "+updater.version()+".";
                    button="Tải xuống";
                    action=()->{updater.download();renderUpdateDialog();};
                }
                case DOWNLOADING -> body="Đang tải bản "+updater.version()+"…";
                case VERIFYING -> body="Đang kiểm tra lại tệp trước khi cài…";
                case INSTALLING -> body="Đang chờ trình cài đặt Android.";
                case READY -> {
                    title="Sẵn sàng cài đặt";
                    if(getPackageManager().canRequestPackageInstalls()){
                        body="Bản "+updater.version()+" đã được xác minh. Xác nhận cài đặt trên màn hình Android.";
                        button="Cài đặt";
                    }else{
                        body="Trong Cài đặt TV, cho phép pdmvOS cài ứng dụng rồi quay lại mục cập nhật.";
                        button="Mở cài đặt";
                    }
                    action=MainActivity.this::installLauncherUpdate;
                }
                case CHECK_FAILED -> {
                    body=updater.failure();
                    button="Thử lại";
                    action=()->{updater.check();renderUpdateDialog();};
                }
                case DOWNLOAD_FAILED -> {
                    body=updater.failure();
                    button="Kiểm tra lại";
                    action=()->{updater.check();renderUpdateDialog();};
                }
                default -> throw new IllegalStateException();
            }
            showDialog(title,body,button,action);
            updateDialog=true;
            if(updater.state()==LauncherUpdate.State.DOWNLOADING)dialogProgress=updater.progress();
        }
        private void setWifi(boolean enabled){
            if(!controls.setWifiEnabled(enabled))showToast("Nhấn giữ để mở cài đặt Wi-Fi của hệ thống");
            refreshControlStates();
        }
        private boolean popupOwnsHeaderFocus(){
            return dialogAction!=null||dialogReveal.value>.001f
                ||(menu!=null&&menu.sourceHeader>=0&&menu.selected>=0);
        }
        private void moveHeader(int direction){
            int next=Math.max(0,Math.min(3,headerSelection+direction));
            if(next==1)next+=direction;
            next=Math.max(0,Math.min(3,next));
            if(next!=headerSelection&&menu!=null&&menu.sourceHeader>=0){cancelHeldInput();closeMenu();}
            headerSelection=next;updateFocus();
        }
        private void openInputMenu(){
            openInputMenu(false);
        }
        private void openInputMenu(boolean selectAirPlay){
            RectF button=headerRect(2);
            Menu m=new Menu("Đầu vào",button.centerX(),button.centerY());m.sourceHeader=2;m.selected=-1;
            if(selectAirPlay)m.selected=0;
            m.add("AirPlay",this::openAirPlayModeMenu);
            for(android.media.tv.TvInputInfo input:controls.discoverInputs()){
                String name=input.loadLabel(MainActivity.this).toString();
                CharSequence custom=input.loadCustomLabel(MainActivity.this);if(custom!=null&&custom.length()>0)name=custom.toString();
                String inputName=name;
                m.add(inputName,()->showDialog("Chuyển đầu vào?", "TV sẽ chuyển sang "+inputName+".", "Chuyển",
                    ()->launchSystem(controls.inputIntent(input),"Không mở được đầu vào")));
            }
            if(m.items.isEmpty()){showToast("Không tìm thấy đầu vào nào");return;}
            showMenu(m);
        }
        private static final String[] AIRPLAY_MODES={"Màn hình và âm thanh","Chỉ màn hình","Chỉ âm thanh","Tắt"};

        private void openAirPlayModeMenu(){
            RectF button=headerRect(2);
            Menu m=new Menu("Chế độ AirPlay",button.centerX(),button.centerY());
            m.sourceHeader=2;
            m.isAirPlayModeMenu=true;
            int curMode=AirPlayReceiver.getInstance().getCurrentMode();
            m.checked=curMode;
            for(int i=0;i<AIRPLAY_MODES.length;i++){
                int mode=i;
                m.add(AIRPLAY_MODES[i],()->setAirPlayMode(mode));
            }
            m.selected=curMode;
            showMenu(m);
        }
        private void setAirPlayMode(int mode){
            if(!AirPlayReceiver.getInstance().start(MainActivity.this,mode)){
                showToast("AirPlay · Không thể bật");
                return;
            }
            prefs.edit().putInt("airplay_mode",mode).apply();
            showToast("AirPlay · "+AIRPLAY_MODES[mode]);
            invalidate();
        }
        void updateAirPlayState(int state,int width,int height,int session){
            if(state==AirPlayReceiver.CONNECTING||state==AirPlayReceiver.PLAYING){
                if(!airPlayDisplayVisible||session!=airPlaySession){
                    closeAirPlayDisplay();
                    airPlaySession=session;
                    openAirPlayDisplay();
                    if(!resumed){
                        airPlayReturningFromApp=true;
                        // SurfaceView can only sit above its own window. Bring the
                        // launcher task forward once when this session starts.
                        Intent showAirPlay=new Intent(MainActivity.this,MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(showAirPlay);
                    }
                }
                if(state==AirPlayReceiver.PLAYING){
                    if(width>0&&height>0){airPlayWidth=width;airPlayHeight=height;}
                    airPlayFrameReady=true;
                    invalidate();
                }
            }else{
                closeAirPlayDisplay();
                if(state==AirPlayReceiver.FAILED){
                    AirPlayReceiver.getInstance().disconnect(MainActivity.this);
                    showToast("AirPlay · Không nhận được hình");
                }
            }
        }
        private void openAirPlayDisplay(){
            airPlayDisplayVisible=true;
            holdAirPlayWifi();
            appsRequested=false;appsNeedsHome=false;
            cancelHeldInput();
            menu=null;closingMenu=null;
            if(dialogAction!=null)dismissDialog();
            settleReturnState(menuReveal,0f);settleReturnState(menuContent,0f);
            clearToast();
            if(airPlaySurface==null){
                airPlayMatte=new View(MainActivity.this);
                airPlayMatte.setBackgroundColor(Color.BLACK);
                airPlayMatte.setVisibility(View.GONE);
                addView(airPlayMatte,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
                airPlaySurface=new AirPlaySurface();
                airPlaySurface.getHolder().addCallback(new SurfaceHolder.Callback(){
                    @Override public void surfaceCreated(SurfaceHolder holder){
                        if(airPlayDisplayVisible)AirPlayReceiver.getInstance().setSurface(holder.getSurface());
                    }
                    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
                    @Override public void surfaceDestroyed(SurfaceHolder holder){
                        AirPlayReceiver.getInstance().setSurface(null);
                    }
                });
                addView(airPlaySurface,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            }
            airPlaySurface.setTranslationX(getWidth());
            airPlaySurface.setVisibility(View.VISIBLE);
        }
        void leaveAirPlayDisplay(){
            closeAirPlayDisplay();
            AirPlayReceiver.getInstance().disconnect(MainActivity.this);
        }
        void closeAirPlayDisplay(){
            if(!airPlayDisplayVisible)return;
            airPlayDisplayVisible=false;airPlayFrameVisible=false;
            airPlayFrameReady=false;airPlayReturningFromApp=false;
            handler.removeCallbacks(airPlayEntranceFallback);
            settleReturnState(airPlayEntrance,0f);
            if(airPlayWifiLock!=null){
                airPlayWifiLock.release();
                airPlayWifiLock=null;
            }
            clearToast();
            airPlaySurface.setVisibility(View.GONE);
            airPlayMatte.setVisibility(View.GONE);
            AirPlayReceiver.getInstance().setSurface(null);
            invalidate();
        }
        private void holdAirPlayWifi(){
            android.net.wifi.WifiManager wifi=getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            if(wifi==null)return;
            int mode=android.os.Build.VERSION.SDK_INT>=29?android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            try{
                android.net.wifi.WifiManager.WifiLock lock=wifi.createWifiLock(mode,"pdmvOS:AirPlay");
                lock.acquire();
                airPlayWifiLock=lock;
            }catch(RuntimeException unavailable){Log.w("AirPlayReceiver","AirPlay Wi-Fi lock unavailable",unavailable);}
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            super.onMeasure(widthSpec,heightSpec);
            if(airPlaySurface!=null){
                int w=MeasureSpec.makeMeasureSpec(getMeasuredWidth(),MeasureSpec.EXACTLY);
                int h=MeasureSpec.makeMeasureSpec(getMeasuredHeight(),MeasureSpec.EXACTLY);
                airPlayMatte.measure(w,h);
                airPlaySurface.measure(w,h);
            }
        }
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
            super.onLayout(changed,left,top,right,bottom);
            if(airPlaySurface!=null){
                airPlayMatte.layout(0,0,right-left,bottom-top);
                airPlaySurface.layout(0,0,right-left,bottom-top);
                if(!airPlayFrameVisible)airPlaySurface.setTranslationX(right-left);
                updateAirPlayTransform();
            }
        }
        private void scheduleAirPlayEntranceFallback(){
            if(!airPlayReturningFromApp)return;
            handler.removeCallbacks(airPlayEntranceFallback);
            airPlayEntranceDeadline=android.os.SystemClock.uptimeMillis()+500;
            handler.postAtTime(airPlayEntranceFallback,airPlayEntranceDeadline);
        }
        private float airPlayToastProgress(){return UiGeometry.clamp(toastReveal.value,0f,1f);}
        private final class AirPlaySurface extends SurfaceView {
            private final Path corners=new Path();
            private final float[] vertices=new float[266];
            private float frameWidth,frameHeight,cornerRadius;
            private int surfaceWidth,surfaceHeight;
            AirPlaySurface(){
                super(MainActivity.this);
                setWillNotDraw(false);
            }
            void updateCorners(float width,float height,float radius){
                int w=getWidth(),h=getHeight();
                if(width==frameWidth&&height==frameHeight&&radius==cornerRadius&&w==surfaceWidth&&h==surfaceHeight)return;
                frameWidth=width;frameHeight=height;cornerRadius=radius;
                surfaceWidth=w;surfaceHeight=h;
                if(radius>0f){
                    UiGeometry.squircleVertices(0f,0f,width,height,radius,3.05f,vertices);
                    float sx=w/width,sy=h/height;
                    corners.rewind();
                    corners.moveTo(vertices[0]*sx,vertices[1]*sy);
                    for(int i=2;i<vertices.length;i+=2)corners.lineTo(vertices[i]*sx,vertices[i+1]*sy);
                    corners.close();
                }
                invalidate();
            }
            @Override public void draw(Canvas canvas){
                // Shape the window's opening while video stays on its own surface.
                if(cornerRadius<=0f){super.draw(canvas);return;}
                int saved=canvas.save();
                canvas.clipPath(corners);
                super.draw(canvas);
                canvas.restoreToCount(saved);
            }
        }
        private void updateAirPlayTransform(){
            if(airPlaySurface==null)return;
            float w=airPlaySurface.getWidth(),h=airPlaySurface.getHeight();
            if(w<=0||h<=0)return;
            float progress=airPlayToastProgress();
            float compactProgress=airPlayEntrance.busy()?1f:progress;
            airPlayMatte.setAlpha(1f-compactProgress);
            // Interpolate one aspect-preserving scale with the toast spring.
            float available=h*(1-2*AIRPLAY_TOAST_SPACE/UiGeometry.HEIGHT);
            float expanded=Math.min(w/airPlayWidth,h/airPlayHeight);
            float compact=Math.min(w/airPlayWidth,available/airPlayHeight);
            float scale=expanded+(compact-expanded)*compactProgress;
            float videoW=scale*airPlayWidth,videoH=scale*airPlayHeight;
            float radius=Math.min(UiGeometry.TILE_RADIUS*canvasScale,Math.min(videoW,videoH)*.5f);
            airPlaySurface.updateCorners(videoW,videoH,radius*compactProgress);
            // Scale the view on the render thread without invalidating its texture.
            airPlaySurface.setScaleX(videoW/w);
            airPlaySurface.setScaleY(videoH/h);
            airPlaySurface.setTranslationY(-(h+videoH)*.5f*(1f-airPlayEntrance.value));
        }
        private void clearToast(){
            handler.removeCallbacks(hideToast);
            settleReturnState(toastReveal,0f);
            toastMessage="";previousToast="";
            invalidateToastBackdrop();
        }
        private final SpringValue clockWidth=new SpringValue(0,240,31),clockChange=new SpringValue(1,180,26.8f);
        private String shownClock="",previousClock="";
        private final float[] clockAnim=new float[7];
        private static final float HEADER_BUTTON=54f,HEADER_PAD=8f,HEADER_TOP=UiGeometry.HEADER_TOP,HEADER_HEIGHT=70f,NETWORK_WIDTH=130f;
        private static final float CLOCK_TEXT_PADDING=22.5f,SETTINGS_WIDTH=2f*HEADER_PAD+HEADER_BUTTON;
        private final Rect clockGlyphBounds=new Rect();
        private final Path clockInkPath=new Path();
        private final RectF clockInkBounds=new RectF();
        private final class ClockMetrics {
            String text="";
            final Rect glyph=new Rect();
            final RectF ink=new RectF();
            float[] advances=new float[0];
        }
        private final android.util.LruCache<String,ClockMetrics> clockMetrics=new android.util.LruCache<>(8);
        private ClockMetrics measureClock(String value){
            String norm=normalizedText(value);
            ClockMetrics cached=clockMetrics.get(norm);
            if(cached==null){
                cached=new ClockMetrics();cached.text=norm;configureTextPaint(measurePaint,32,false);
                measurePaint.getTextBounds(norm,0,norm.length(),cached.glyph);
                clockInkPath.reset();measurePaint.getTextPath(norm,0,norm.length(),0,0,clockInkPath);
                clockInkPath.computeBounds(cached.ink,true);
                int n=norm.length();
                cached.advances=new float[n+1];
                for(int i=1;i<=n;i++)cached.advances[i]=measurePaint.measureText(norm,0,i);
                clockMetrics.put(norm,cached);
            }
            clockGlyphBounds.set(cached.glyph);clockInkBounds.set(cached.ink);
            return cached;
        }
        private float desiredClockWidth(){
            measureClock(clock());
            return clockGlyphBounds.width()+2f*CLOCK_TEXT_PADDING;
        }
        private void drawClockTime(Canvas c,String value,float left,float right){
            // Identical glyph/ink geometry; construct outlines only when the text changes.
            measureClock(value);
            float origin=(left+right-clockInkBounds.width())*.5f-clockInkBounds.left;
            text(c,value,origin,100,32,clockInk,false,Paint.Align.LEFT);
        }
        private void drawClockRun(Canvas c,String text,int start,int end,float x,float y,float scale,float alpha,float pivotX){
            if(start>=end||alpha<=.002f)return;
            configureTextPaint(paint,32,false);
            paint.setColor(clockInk);
            paint.setAlpha(Math.round(Color.alpha(clockInk)*UiGeometry.clamp(alpha*textOpacity,0f,1f)));
            paint.setTextAlign(Paint.Align.LEFT);
            if(Math.abs(scale-1f)<.001f){
                c.drawText(text,start,end,x,y,paint);
            }else{
                int s=c.save();
                c.scale(scale,scale,pivotX,y-11f);
                c.drawText(text,start,end,x,y,paint);
                c.restoreToCount(s);
            }
        }
        private void drawChangedClockRun(
                Canvas c,
                String a,int aStart,int aEnd,float oldOrigin,ClockMetrics oldM,
                String b,int bStart,int bEnd,float newOrigin,ClockMetrics newM,
                float[] anim){
            float posT=anim[0];
            float oldLeft=oldOrigin+oldM.advances[aStart];
            float oldW=oldM.advances[aEnd]-oldM.advances[aStart];
            float newLeft=newOrigin+newM.advances[bStart];
            float newW=newM.advances[bEnd]-newM.advances[bStart];
            float center=(oldLeft+oldW*.5f)+((newLeft+newW*.5f)-(oldLeft+oldW*.5f))*posT;
            if(aEnd>aStart&&anim[3]>.002f){
                drawClockRun(c,a,aStart,aEnd,center-oldW*.5f,100f+anim[1],anim[2],anim[3],center);
            }
            if(bEnd>bStart&&anim[6]>.002f){
                drawClockRun(c,b,bStart,bEnd,center-newW*.5f,100f+anim[4],anim[5],anim[6],center);
            }
        }
        private void drawClockTransition(Canvas c,String prevRaw,String nextRaw,float left,float right,float progress){
            if(prevRaw==null||prevRaw.isEmpty()||prevRaw.equals(nextRaw)||progress>=.999f){
                textOpacity=1f;drawClockTime(c,nextRaw,left,right);return;
            }
            if(progress<=.001f){
                textOpacity=1f;drawClockTime(c,prevRaw,left,right);return;
            }
            ClockMetrics oldM=measureClock(prevRaw);
            float oldOrigin=(left+right-oldM.ink.width())*.5f-oldM.ink.left;
            ClockMetrics newM=measureClock(nextRaw);
            float newOrigin=(left+right-newM.ink.width())*.5f-newM.ink.left;
            String a=oldM.text,b=newM.text;
            int aLen=a.length(),bLen=b.length();
            int aTimeEnd=0;while(aTimeEnd<aLen&&a.charAt(aTimeEnd)!=' ')aTimeEnd++;
            int bTimeEnd=0;while(bTimeEnd<bLen&&b.charAt(bTimeEnd)!=' ')bTimeEnd++;
            int aDateStart=aTimeEnd;while(aDateStart<aLen&&a.charAt(aDateStart)==' ')aDateStart++;
            int bDateStart=bTimeEnd;while(bDateStart<bLen&&b.charAt(bDateStart)==' ')bDateStart++;
            int aDateLen=aLen-aDateStart,bDateLen=bLen-bDateStart;
            int dPref=0,maxPref=Math.min(aDateLen,bDateLen);
            while(dPref<maxPref&&a.charAt(aDateStart+dPref)==b.charAt(bDateStart+dPref))dPref++;
            int dSuf=0,maxSuf=Math.min(aDateLen-dPref,bDateLen-dPref);
            while(dSuf<maxSuf&&a.charAt(aLen-1-dSuf)==b.charAt(bLen-1-dSuf))dSuf++;
            int aMidLen=aDateLen-dPref-dSuf,bMidLen=bDateLen-dPref-dSuf;
            int totalSlots=0;
            if(aTimeEnd==bTimeEnd){
                for(int i=0;i<aTimeEnd;i++)if(a.charAt(i)!=b.charAt(i))totalSlots++;
            }else{
                totalSlots++;
            }
            if(aMidLen>0||bMidLen>0){
                if(aMidLen==bMidLen&&aMidLen<=4){
                    for(int k=0;k<aMidLen;k++)if(a.charAt(aDateStart+dPref+k)!=b.charAt(bDateStart+dPref+k))totalSlots++;
                }else{
                    totalSlots++;
                }
            }
            totalSlots=Math.max(1,totalSlots);
            int slot=0;
            MotionProfile.clockCharTransition(progress,0,totalSlots,20f,clockAnim);
            float posT=clockAnim[0];
            textOpacity=1f;
            if(aTimeEnd==bTimeEnd){
                for(int i=0;i<aTimeEnd;i++){
                    if(a.charAt(i)==b.charAt(i)){
                        int j=i+1;
                        while(j<aTimeEnd&&a.charAt(j)==b.charAt(j))j++;
                        float oldX=oldOrigin+oldM.advances[i],newX=newOrigin+newM.advances[i];
                        drawClockRun(c,b,i,j,oldX+(newX-oldX)*posT,100f,1f,1f,0f);
                        i=j-1;
                    }else{
                        MotionProfile.clockCharTransition(progress,slot++,totalSlots,20f,clockAnim);
                        drawChangedClockRun(c,a,i,i+1,oldOrigin,oldM,b,i,i+1,newOrigin,newM,clockAnim);
                    }
                }
            }else{
                MotionProfile.clockCharTransition(progress,slot++,totalSlots,20f,clockAnim);
                drawChangedClockRun(c,a,0,aTimeEnd,oldOrigin,oldM,b,0,bTimeEnd,newOrigin,newM,clockAnim);
            }
            if(aMidLen==0&&bMidLen==0){
                if(bDateLen>0){
                    float oldX=oldOrigin+oldM.advances[aDateStart],newX=newOrigin+newM.advances[bDateStart];
                    drawClockRun(c,b,bDateStart,bLen,oldX+(newX-oldX)*posT,100f,1f,1f,0f);
                }
            }else{
                if(dPref>0){
                    float oldX=oldOrigin+oldM.advances[aDateStart],newX=newOrigin+newM.advances[bDateStart];
                    drawClockRun(c,b,bDateStart,bDateStart+dPref,oldX+(newX-oldX)*posT,100f,1f,1f,0f);
                }
                if(aMidLen==bMidLen&&aMidLen<=4){
                    for(int k=0;k<aMidLen;k++){
                        int ai=aDateStart+dPref+k,bi=bDateStart+dPref+k;
                        if(a.charAt(ai)==b.charAt(bi)){
                            float oldX=oldOrigin+oldM.advances[ai],newX=newOrigin+newM.advances[bi];
                            drawClockRun(c,b,bi,bi+1,oldX+(newX-oldX)*posT,100f,1f,1f,0f);
                        }else{
                            MotionProfile.clockCharTransition(progress,slot++,totalSlots,20f,clockAnim);
                            drawChangedClockRun(c,a,ai,ai+1,oldOrigin,oldM,b,bi,bi+1,newOrigin,newM,clockAnim);
                        }
                    }
                }else{
                    MotionProfile.clockCharTransition(progress,slot++,totalSlots,20f,clockAnim);
                    drawChangedClockRun(c,a,aDateStart+dPref,aLen-dSuf,oldOrigin,oldM,b,bDateStart+dPref,bLen-dSuf,newOrigin,newM,clockAnim);
                }
                if(dSuf>0){
                    int ai=aLen-dSuf,bi=bLen-dSuf;
                    float oldX=oldOrigin+oldM.advances[ai],newX=newOrigin+newM.advances[bi];
                    drawClockRun(c,b,bi,bLen,oldX+(newX-oldX)*posT,100f,1f,1f,0f);
                }
            }
        }
        private float clockPillWidth(){if(clockWidth.value<=0){clockWidth.value=desiredClockWidth();clockWidth.aim(clockWidth.value);shownClock=clock();}return clockWidth.value;}
        private RectF headerRect(int index){
            float right=UiGeometry.DOCK_LEFT+UiGeometry.DOCK_WIDTH;
            float networkLeft=right-SETTINGS_WIDTH-18-NETWORK_WIDTH;
            float x=index==0?networkLeft+HEADER_PAD:index==2?networkLeft+HEADER_PAD+60:right-HEADER_PAD-HEADER_BUTTON;
            return new RectF(x,HEADER_TOP+HEADER_PAD,x+HEADER_BUTTON,HEADER_TOP+HEADER_PAD+HEADER_BUTTON);
        }
        private int headerHit(float x,float y){for(int i:new int[]{0,2,3})if(headerRect(i).contains(x,y))return i;return -1;}
        private Bitmap headerContrastSource;
        private final int networkInk=Color.WHITE,clockInk=Color.WHITE;
        private final int[] controlFocusTint={0xff8c9198,0xff8c9198,0xff8c9198,0xff8c9198};
        private final int[] controlSamples=new int[5],morphSamples=new int[32];
        private int contrastClockWidth=-1;
        private void updateControlMaterial(Bitmap source,int index){
            RectF r=headerRect(index);
            for(int i=0;i<5;i++){
                float dx=i==1?-12:i==2?12:0,dy=i==3?-12:i==4?12:0;
                int x=Math.max(0,Math.min(source.getWidth()-1,Math.round((r.centerX()+dx)*source.getWidth()/1920f)));
                int y=Math.max(0,Math.min(source.getHeight()-1,Math.round((r.centerY()+dy)*source.getHeight()/1080f)));
                controlSamples[i]=source.getPixel(x,y);
            }
            controlFocusTint[index]=ArtworkDetector.controlFocusTint(controlSamples);
        }
        private int headerWifiColor(int state,float focus){return connectionColor(state);}
        private void drawHeader(Canvas c,float opacity){
            glassMaterials.setNativeOffsetY(-150f*(1f-startupReveal.value));
            try{drawHeaderContent(c,opacity);}finally{glassMaterials.setNativeOffsetY(0);}
        }
        private void drawHeaderContent(Canvas c,float opacity){
            glassMaterials.beginSurface(c);
            int saved=opacity>=.999f?c.save():c.saveLayerAlpha(0,0,1920,170,(int)(255*opacity),Canvas.ALL_SAVE_FLAG);

            float right=UiGeometry.DOCK_LEFT+UiGeometry.DOCK_WIDTH,cw=clockPillWidth(),clockLeft=UiGeometry.DOCK_LEFT;
            RectF wifi=headerRect(0);
            Bitmap contrast=wallpapers.full();
            if(contrast!=null&&contrast!=headerContrastSource){
                headerContrastSource=contrast;contrastClockWidth=Math.round(cw);
                Bitmap local=wallpapers.frost(1)!=null?wallpapers.frost(1):contrast;
                for(int index:new int[]{0,2,3})updateControlMaterial(local,index);
            }
            stretchHeaderPill(c,wifi.left-HEADER_PAD,NETWORK_WIDTH,edgeHeader==0||edgeHeader==2);
            stretchHeaderPill(c,clockLeft,cw,false);
            stretchHeaderPill(c,right-SETTINGS_WIDTH,SETTINGS_WIDTH,edgeHeader==3);
            int clockText=c.save();c.clipRect(clockLeft+8f,HEADER_TOP+6f,clockLeft+cw-8f,HEADER_TOP+HEADER_HEIGHT-6f);
            drawClockTransition(c,previousClock,shownClock,clockLeft,clockLeft+cw,UiGeometry.clamp(clockChange.value,0f,1f));
            textOpacity=1;c.restoreToCount(clockText);
            for(int i:new int[]{0,2,3}){
                RectF r=headerRect(i);int button=c.save();
                glassMaterials.setLiveRims(edgeRegion==2 && edgeHeader==i && edgeDeformed());
                if(edgeRegion==2 && edgeHeader==i)stretchEdge(c,r,.035f);
                float z=popupOwnsHeaderFocus()?0f:UiGeometry.clamp(headerButtonFocus[i].value,0,1),scale=1+.12f*z-.065f*headerPress.value*z;
                c.scale(scale,scale,r.centerX(),r.centerY());
                material(c,r.left,r.top,HEADER_BUTTON,HEADER_BUTTON,HEADER_BUTTON/2f,1);
                glassMaterials.drawFocusedControl(c,r.left,r.top,HEADER_BUTTON,z,controlFocusTint[i]);
                String icon=i==0?"wifi":i==2?"input":"settings";
                float change=UiGeometry.clamp(radioChange.value,0,1);
                if(i==0&&change<.999f)drawSolar(c,icon,r.centerX(),r.centerY(),29,withOpacity(headerWifiColor(previousWifi,z),1-change));
                drawSolar(c,icon,r.centerX(),r.centerY(),29,i==0?withOpacity(headerWifiColor(controls.wifi,z),change):Color.WHITE);
                glassMaterials.setLiveRims(false);c.restoreToCount(button);
            }
            c.restoreToCount(saved);
        }
        private Locale clockLocale;
        private SimpleDateFormat clockFormatter;
        private String formatClock(Date now){
            Locale locale=Locale.getDefault();
            if(clockFormatter==null||!locale.equals(clockLocale)){
                String datePattern=android.text.format.DateFormat.getBestDateTimePattern(locale,"EEE d MMM");
                clockFormatter=new SimpleDateFormat("HH:mm   "+datePattern,locale);clockLocale=locale;
            }
            clockFormatter.setTimeZone(java.util.TimeZone.getDefault());
            return clockFormatter.format(now);
        }
        private String clock(){return clockCache;}
        private void drawDock(Canvas c,float top,float progress){
            float left=UiGeometry.DOCK_LEFT,width=UiGeometry.DOCK_WIDTH;
            float height=UiGeometry.DOCK_HEIGHT,r=UiGeometry.DOCK_RADIUS;
            float pad=UiGeometry.DOCK_PADDING;
            int dockSave=c.save();
            glassMaterials.setLiveRims(edgeRegion==1 && edgeTile!=null && catalog.dock.contains(edgeTile) && edgeDeformed());
            if(edgeRegion==1 && edgeTile!=null && catalog.dock.contains(edgeTile))
                stretchEdge(c,new RectF(left,top,left+width,top+height),.0045f);
            glass(c,left,top,width,height,r);
            glassMaterials.setLiveRims(false);c.restoreToCount(dockSave);
            boolean dockSelected=(page==0&&!focusHeader)||(page==1&&focusDrawerDock);
            if(nativeTiles)return;
            for(int i=0;i<catalog.dock.size();i++){
                Tile tile=catalog.dock.get(i);
                float cx=UiGeometry.centerX(i)+tile.offsetX.value;
                float cy=top+pad+UiGeometry.TILE_MAX_HEIGHT/2f+tile.offsetY.value;
                if(tile.traveling)continue;
                drawTile(c,tile,cx,cy,tile.scale.value,
                    (dockSelected&&i==dockFocus)?tilePress.value:0f,true);
            }
        }
        private static final int TILE_BITMAP_W=504, TILE_BITMAP_H=284;
        private final ArrayList<Tile> artworkWarmOrder=new ArrayList<>();

        /** Draw Android Drawable on the UI thread in bounded increments, after the first
         * frame. Many vendor/animated/vector banner implementations are not thread-safe;
         * rendering them on a background thread yielded background-only app tiles on Sony.
         * Preprocessing is progressive and never part of the drawing loop. */
        private Bitmap prepareTileBitmap(Tile tile){
            Bitmap out=Bitmap.createBitmap(TILE_BITMAP_W,TILE_BITMAP_H,Bitmap.Config.ARGB_8888);
            Canvas surface=new Canvas(out);
            if(tile.packageName==null){
                int saved=surface.save();surface.scale(TILE_BITMAP_W/UiGeometry.TILE_MAX_WIDTH,TILE_BITMAP_H/UiGeometry.TILE_MAX_HEIGHT);
                drawShortcut(surface,tile,UiGeometry.TILE_MAX_WIDTH/2f,UiGeometry.TILE_MAX_HEIGHT/2f,0,0,UiGeometry.TILE_MAX_WIDTH,UiGeometry.TILE_MAX_HEIGHT);
                surface.restoreToCount(saved);return out;
            }
            surface.drawColor(0xff34343d);
            boolean detail=drawArtworkDrawable(surface,out,tile.artwork,tile.banner,tile.packageName);
            if(!detail && tile.packageName!=null){
                try{
                    Drawable icon=getPackageManager().getApplicationIcon(tile.packageName);
                    if(icon!=null){
                        // The primary banner often contains just a flat brand background.
                        // Replace it with a known, independent application icon instead.
                        surface.drawColor(0xff34343d);
                        detail=drawArtworkDrawable(surface,out,icon,false,tile.packageName);
                    }
                }catch(Throwable e){Log.w(TAG,"Icon fallback unavailable: "+tile.id,e);}
            }
            if(!detail)drawArtworkLabelFallback(surface,out,tile.label);
            Log.d(TAG,"artwork.ready id="+tile.id+" detail="+detail);
            return out; // Source stays rectangular until its one-time antialiased texture preparation.
        }
        private boolean drawArtworkDrawable(Canvas surface,Bitmap output,Drawable supplied,boolean requestedBanner,String tileResourcePackage){
            if(supplied==null)return false;
            try{
                Drawable drawable=supplied;
                Drawable.ConstantState state=supplied.getConstantState();
                if(state!=null){
                    try{drawable=state.newDrawable(
                        getPackageManager().getResourcesForApplication(tileResourcePackage)).mutate();}
                    catch(Throwable cloneError){drawable=state.newDrawable().mutate();}
                }
                int iw=drawable.getIntrinsicWidth(),ih=drawable.getIntrinsicHeight();
                boolean wide=requestedBanner || (iw>0 && ih>0 && (float)iw/ih>1.35f);
                ArtworkLayout.Bounds fit=ArtworkLayout.fit(iw,ih,TILE_BITMAP_W,TILE_BITMAP_H,wide);
                float cx=TILE_BITMAP_W/2f, cy=TILE_BITMAP_H/2f;
                float dw=fit.width(),dh=fit.height();
                drawable.setBounds(Math.round(cx-dw/2f),Math.round(cy-dh/2f),
                    Math.round(cx+dw/2f),Math.round(cy+dh/2f));
                if(drawable instanceof BitmapDrawable){
                    ((BitmapDrawable)drawable).setFilterBitmap(true);
                    ((BitmapDrawable)drawable).setAntiAlias(true);
                }
                drawable.draw(surface);
                return hasVisibleCentralArtwork(output);
            }catch(Throwable error){Log.w(TAG,"Cannot render drawable",error);return false;}
        }
        /** Detect a logo in the middle rather than mistaking a non-uniform/gradient
         * banner background for a successful logo. Based on light/color contrast against
         * the four outside corners; intentionally conservative to show a text fallback. */
        private boolean hasVisibleCentralArtwork(Bitmap bitmap){
            return ArtworkDetector.hasDetail(bitmap.getWidth(),bitmap.getHeight(),bitmap::getPixel);
        }
        private void drawArtworkLabelFallback(Canvas canvas,Bitmap image,String title){
            Paint label=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
            label.setTypeface(semibold);
            int textColor=Color.WHITE;
            label.setColor(textColor);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(49f);
            label.setShadowLayer(2.5f,0f,1f,0xb3000000);
            String safe=title==null?"Ứng dụng":Normalizer.normalize(title,Normalizer.Form.NFC);
            if(safe.length()>28)safe=safe.substring(0,27)+"…";
            while(label.measureText(safe)>TILE_BITMAP_W-58 && label.getTextSize()>22f)
                label.setTextSize(label.getTextSize()-2f);
            canvas.drawText(safe,TILE_BITMAP_W/2f,TILE_BITMAP_H/2f-(label.ascent()+label.descent())/2f,label);
        }
        private void rebuildArtworkWarmOrder(){
            artworkWarmOrder.clear();
            if(returningTile!=null)artworkWarmOrder.add(returningTile);
            for(Tile tile:catalog.dock)if(!artworkWarmOrder.contains(tile))artworkWarmOrder.add(tile);
            for(Tile tile:catalog.grid)if(!artworkWarmOrder.contains(tile))artworkWarmOrder.add(tile);
            for(Tile tile:catalog.all)if(tile.isEmpty()&&!artworkWarmOrder.contains(tile))artworkWarmOrder.add(tile);
        }
        private int artworkCursor=0;
        private boolean artworkWarmScheduled=false;
        private final Runnable artworkStep=new Runnable(){
            @Override public void run(){
                if(!resumed||!artworkWarmScheduled||catalog==null)return;
                SpringValue focused=tileFocus.selected();
                if(launchingTile!=null||returningTile!=null&&(!returnWaiting||returningTile.image!=null)){
                    // While waiting for the Home window, only the source texture
                    // is essential. Other drawable decoding can occupy the same
                    // VSYNC that starts the return and dirty its backdrop cache.
                    handler.postDelayed(this,120);return;
                }
                if(startupStarted&&startupReveal.busy()||holdingOK||moveMode||menu!=null||closingMenu!=null||drawer.busy()||gridScroll.busy()||tilePress.busy()||focused!=null&&focused.busy()){
                    handler.postDelayed(this,120);return;
                }
                while(artworkCursor<artworkWarmOrder.size()){
                    Tile tile=artworkWarmOrder.get(artworkCursor++);
                    if(tile.isEmpty()||tile.retired||tile.image!=null&&tile.preparationAttempted)continue;
                    Bitmap bitmap=null;
                    try{bitmap=prepareTileBitmap(tile);}
                    catch(Throwable e){Log.e(TAG,"Artwork failed for "+tile.id,e);}
                    if(bitmap!=null){
                        tile.image=maskArtwork(bitmap);
                        if(tile.image!=bitmap)bitmap.recycle();
                        tile.image.prepareToDraw();
                        tile.preparationAttempted=true;
                        // The prepared texture owns the pixels now. Do not keep the
                        // package's original high-density drawable alive as well.
                        tile.artwork=null;
                        if(tile==returningTile&&returnWaiting)captureMorphOrigin(tile);
                    }
                    invalidateScene();
                    // Prepare one resource per vsync to avoid blocking the renderer.
                    postOnAnimation(this);
                    return;
                }
                artworkWarmScheduled=false;
                tileSourcePixels=null;tileMaskedPixels=null;
            }
        };
        void scheduleArtworkWarmup(){
            rebuildArtworkWarmOrder();
            artworkCursor=0;
            artworkWarmScheduled=true;
            removeCallbacks(artworkStep);
            postOnAnimation(artworkStep);
            Log.i(TAG,"artwork.queue count="+artworkWarmOrder.size());
        }
        /** One preallocated material gradient drawn through the same squircle path.
         * The glow has no independent border or additional alpha-masked bitmap; it
         * makes the *focused surface* gently respond to navigation direction.
         */
        private void drawInstallProgress(Canvas c,Tile tile,float x,float y,float w,float h){
            float reveal=UiGeometry.clamp(tile.installReveal.value,0f,1f);
            if(reveal<.001f||tile.retired)return;
            paint.reset();paint.setAntiAlias(true);paint.setColor(((int)(85*reveal))<<24);
            drawSquircle(c,x,y,w,h,UiGeometry.TILE_RADIUS*w/252f,paint);
            float cx=x+w/2f,cy=y+h/2f,r=25f*w/252f*(.7f+.3f*reveal);
            paint.setColor(((int)(179*reveal))<<24);c.drawCircle(cx,cy,r,paint);
            paint.setColor(((int)(238*reveal)<<24)|0xffffff);c.drawArc(cx-r,cy-r,cx+r,cy+r,-90f,360f*UiGeometry.clamp(tile.installSweep.value,0f,1f),true,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f*w/252f);paint.setColor(((int)(221*reveal)<<24)|0xffffff);c.drawCircle(cx,cy,r,paint);paint.setStyle(Paint.Style.FILL);
        }
        private int[] tileSourcePixels,tileMaskedPixels;
        private Bitmap maskArtwork(Bitmap source){
            int mw=TILE_BITMAP_W+4,mh=TILE_BITMAP_H+4;
            Bitmap masked=Bitmap.createBitmap(mw,mh,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(masked);c.translate(2f,2f);
            paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
            paint.setShader(new BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
            float sourceRadius=ArtworkTransform.sourceRadius(UiGeometry.TILE_RADIUS,TILE_BITMAP_W,UiGeometry.TILE_MAX_WIDTH);
            drawSquircle(c,0f,0f,TILE_BITMAP_W,TILE_BITMAP_H,sourceRadius,paint);
            paint.setShader(null);
            if(tileSourcePixels==null)tileSourcePixels=new int[TILE_BITMAP_W*TILE_BITMAP_H];
            if(tileMaskedPixels==null)tileMaskedPixels=new int[mw*mh];
            source.getPixels(tileSourcePixels,0,TILE_BITMAP_W,0,0,TILE_BITMAP_W,TILE_BITMAP_H);
            masked.getPixels(tileMaskedPixels,0,mw,0,0,mw,mh);
            ArtworkTransform.smoothCorners(tileSourcePixels,tileMaskedPixels,TILE_BITMAP_W,TILE_BITMAP_H,2,sourceRadius,3.05f);
            masked.setPixels(tileMaskedPixels,0,mw,0,0,mw,mh);
            masked.setHasMipMap(true);return masked;
        }
        private final android.graphics.ColorFilter[] focusTints=createFocusTints();
        private android.graphics.ColorFilter[] createFocusTints(){
            android.graphics.ColorFilter[] result=new android.graphics.ColorFilter[65];
            for(int i=0;i<result.length;i++){
                float f=.96f+.04f*i/(result.length-1);
                result[i]=new ColorMatrixColorFilter(new float[]{f,0,0,0,0, 0,f,0,0,0, 0,0,f,0,0, 0,0,0,1,0});
            }
            return result;
        }
        private void drawTile(Canvas c,Tile tile,float cx,float cy,float focused,float pressed,boolean dockContext){
            float z=UiGeometry.clamp(focused,0f,1.025f);
            float w=UiGeometry.TILE_IDLE_WIDTH+(UiGeometry.TILE_MAX_WIDTH-UiGeometry.TILE_IDLE_WIDTH)*z;
            float h=UiGeometry.TILE_IDLE_HEIGHT+(UiGeometry.TILE_MAX_HEIGHT-UiGeometry.TILE_IDLE_HEIGHT)*z;
            // Focus has two readable phases: quick engagement followed by a soft physical settle.
            // A slight lift makes the transition visible without using a heavy shadow.
            float lift=0f;
            float compression=1f-MotionProfile.PRESS_DEPTH*UiGeometry.clamp(pressed,0,1f);
            // Scaling and edit feedback remain centered in the same layout cell.
            // Scale the entire layer around its center, not only its background.
            // Icons, HDMI labels and squircle share exactly the same press transform.
            int wholeTile=c.save();
            c.rotate(tile.tilt.value+tile.ring.value,cx,cy);
            float visible=UiGeometry.clamp(tile.presence.value,0f,1f);
            float editScale=tile.retired?visible:.94f+.06f*visible;
            c.scale(compression*editScale*(1f+UiGeometry.FOCUS_GAIN*z),compression*editScale*(1f+UiGeometry.FOCUS_GAIN*z),cx,cy);
            float x=cx-w/2f,y=cy-h/2f;
            // No shadow bitmap, nested offscreen layers, CLEAR or DST_IN: these were
            // causing high render-thread cost and aliasing during move animation.
            if(tile.image!=null && !tile.image.isRecycled()){
                paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
                paint.setAlpha(Math.round(255*visible));
                paint.setColorFilter(focusTints[Math.round(UiGeometry.clamp(z,0f,1f)*64)]);
                bounds.set(x-w/252f,y-h/142f,x+w+w/252f,y+h+h/142f);
                c.drawBitmap(tile.image,null,bounds,paint);
                paint.setColorFilter(null);
                c.restoreToCount(wholeTile);
                return;
            }
            if(tile.packageName!=null){
                // A slow/invalid vendor banner must never display as an empty color tile.
                material(c,x,y,w,h,Math.min(UiGeometry.TILE_RADIUS,Math.min(w,h)*.42f),1);
                drawFittedText(c,tile.label,cx,cy+9,27,fg(),w-34);

                c.restoreToCount(wholeTile);
                return;
            }
            drawShortcut(c,tile,cx,cy,x,y,w,h);

            c.restoreToCount(wholeTile);
        }
        private void drawShortcut(Canvas c,Tile tile,float cx,float cy,float x,float y,float w,float h){
            int slot=catalog.dock.indexOf(tile);float sampleX=slot<0?0:UiGeometry.centerX(slot)-126;
            float sampleY=slot<0?0:docTop()+UiGeometry.DOCK_HEIGHT/2f-71;
            if(slot<0&&tile.exiting){sampleX=tile.exitX-126f;sampleY=tile.exitY-71f;}
            materialAt(c,x,y,w,h,UiGeometry.TILE_RADIUS,1,sampleX,sampleY);
            if(tile.isEmpty()){
                drawSolar(c,"plus",cx,cy,48,fg());
            }
        }
        private void drawGrid(Canvas c,float progress){
            if(nativeTiles)return;
            // Grid is attached to Dock's lower edge throughout the *same* slide trajectory.
            
            float clipTop=gridClipTop();
            float clipBottom=UiGeometry.gridClipBottom(progress);
            if(clipTop>=clipBottom)return;
            int saved=c.save();c.clipRect(0,clipTop,UiGeometry.WIDTH,clipBottom);
            int count=catalog.grid.size();
            if(count==0 && progress>0.9f)text(c,"Chưa có ứng dụng",960,550,32,fg(),false,Paint.Align.CENTER);
            int totalRows=(count+5)/6;
            for(int rowIndex=0;rowIndex<totalRows;rowIndex++){
                float top=UiGeometry.rowTop(rowIndex,gridScroll.value)+rowOffset(rowIndex);
                if(top+UiGeometry.TILE_MAX_HEIGHT+42<clipTop || top>clipBottom)continue;
                for(int col=0;col<6;col++){
                    int index=rowIndex*6+col;if(index>=count)break;
                    Tile item=catalog.grid.get(index);
                    boolean focused=page==1&&!focusDrawerDock&&index==gridFocus;
                    float center=UiGeometry.centerX(col);
                    float cx=center+item.offsetX.value;
                    float cy=top+UiGeometry.TILE_MAX_HEIGHT/2f+item.offsetY.value;
                    if(item.traveling)continue;
                    drawTile(c,item,cx,cy,item.scale.value,focused?tilePress.value:0,false);
                    if(item.labelAlpha.value>.005f || (moveMode&&!moveDock&&index==gridFocus)){
                        float labelOpacity=item.labelAlpha.value;
                        float labelProgress=UiGeometry.clamp(labelOpacity,0f,1f);
                        textOpacity=labelProgress;
                        // tvOS-like reveal: the title begins slightly ABOVE its resting
                        // baseline and descends into place while fading in.
                        drawFittedText(c,item.label,center,
                            top+UiGeometry.LABEL_BASELINE+MotionProfile.LABEL_START_Y*(1f-labelProgress),
                            UiGeometry.APP_LABEL_SIZE,fg(),239f);
                        textOpacity=1f;
                    }
                }
            }
            c.restoreToCount(saved);
        }
        private void drawFittedText(Canvas c,String content,float x,float y,float size,int color,float maxWidth){
            text(c,fittedText(content,size,true,maxWidth),x,y,size,color,true,Paint.Align.CENTER);
        }
        private String truncate(String s,int max){return s.length()>max?s.substring(0,max-1)+"…":s;}
        void invalidatePreviewCache(){
            if(previewCache!=null){previewCache.recycle();previewCache=null;}
        }
        private void drawSettingsText(Canvas c,String value,float x,float y,float size,int color,boolean bold,float maxWidth){
            text(c,fittedText(value,size,bold,maxWidth),x,y,size,color,bold,Paint.Align.LEFT);
        }
        private void enterDrawer(boolean firstApplication){
            page=1;focusHeader=false;focusDrawerDock=false;
            if(catalog.grid.isEmpty()){drawer.aim(1f,MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D);invalidate();return;}
            // Home Down explicitly chooses the first application; internal callers
            // may retain a source-column entry when needed.
            gridFocus=firstApplication?0:UiGeometry.belowDock(dockFocus,0,catalog.grid.size());
            gridFirstRow=0;gridScroll.aim(0f);drawer.aim(1f,MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D);invalidate();
        }
        private void requestApps(){
            if(airPlayDisplayVisible)return;
            cancelHeldInput();closeMenu();stopMove();replaceSlot=-1;
            if(dialogAction!=null)dismissDialog();
            if(launchingTile!=null&&!launchDispatched)resetLaunchMotion();
            if(!resumed||!hasWindowFocus()){
                appsNeedsHome=true;
                exitDrawer();
                settleDrawerPage();
            }
            appsRequested=true;invalidate();
        }
        private void openRequestedApps(){
            if(!appsRequested||!resumed||!hasWindowFocus()||!startupStarted
                    ||startupReveal.busy()||launchingTile!=null||returningTile!=null)return;
            if(appsNeedsHome&&android.os.SystemClock.uptimeMillis()<returnVisibleAt){
                postInvalidateOnAnimation();return;
            }
            appsRequested=false;appsNeedsHome=false;
            if(page==0){enterDrawer(true);updateFocus();}
        }
        private void showHomeFromSystem(){
            if(airPlayDisplayVisible)leaveAirPlayDisplay();
            appsRequested=false;appsNeedsHome=false;
            cancelHeldInput();closeMenu();stopMove();
            if(dialogAction!=null)dismissDialog();
            if(returningTile!=null||leftForExternalApp||(pendingReturnFeedback()&&returnFeedback.contains("origin_id"))){updateFocus();return;}
            focusHeader=false;exitDrawer();updateFocus();
        }
        private void exitDrawer(){page=0;focusDrawerDock=false;replaceSlot=-1;
            drawer.aim(0f,MotionProfile.DRAWER_CLOSE_K,MotionProfile.DRAWER_CLOSE_D);invalidate();}
        private void updateFocus(){
            if(focusHeader&&page==0)headerFocusAnim.aim(1f,MotionProfile.HEADER_IN_K,MotionProfile.HEADER_IN_D);
            else headerFocusAnim.aim(0f,MotionProfile.HEADER_OUT_K,MotionProfile.HEADER_OUT_D);
            invalidate();
        }
        private void back(){
            if(menu!=null){
                boolean returnToInputs=menu.isAirPlayModeMenu;
                cancelHeldInput();closeMenu();
                if(returnToInputs)openInputMenu(true);
                return;
            }
            if(moveMode){stopMove();return;}
            if(page==1){exitDrawer();return;}
            if(focusHeader){cancelHeldInput();focusHeader=false;updateFocus();return;}
            // Stay on Home. Never unexpectedly open Sony settings or disable stock launcher.
        }
        private void shortPress(){
            if(menu!=null){if(menu.selected<0)activateHeader();else selectMenuItem();return;}
            if(moveMode){stopMove();return;}
            if(page==0){if(focusHeader)activateHeader();
                else if(!catalog.dock.isEmpty())open(catalog.dock.get(dockFocus));return;}
            if(focusDrawerDock){open(catalog.dock.get(dockFocus));return;}
            if(catalog.grid.isEmpty())return;
            Tile tile=catalog.grid.get(gridFocus);
            if(replaceSlot>=0){
                int to=replaceSlot;replaceSlot=-1;
                animateCatalogChange(()->catalog.replaceAt(to,tile));
                dockFocus=Math.max(0,catalog.dock.indexOf(tile));focusDrawerDock=true;
                return;
            }
            open(tile);
        }
        private void navigationCue(int code){
            if(code==KeyEvent.KEYCODE_DPAD_LEFT)focusDepth.navigate(-1,0);
            else if(code==KeyEvent.KEYCODE_DPAD_RIGHT)focusDepth.navigate(1,0);
            else if(code==KeyEvent.KEYCODE_DPAD_UP)focusDepth.navigate(0,-1);
            else if(code==KeyEvent.KEYCODE_DPAD_DOWN)focusDepth.navigate(0,1);
        }
        // Bounded elasticity belongs only to the focused floating surface. Repeats maintain force,
        // hold a bounded force; progressive stiffness resists extension near the limit.
        private final ElasticBoundary edgeElastic=new ElasticBoundary();
        private final ElasticBoundary.Axis edgeX=edgeElastic.x,edgeY=edgeElastic.y;
        private int edgeKey,edgeRegion,lastEdgeRegion,edgeHeader;
        private float edgeAnchorX=1f,edgeAnchorY=1f;
        private final ElasticBoundary.Approach edgeApproach=new ElasticBoundary.Approach();
        private boolean edgeWasMoving;
        private Tile edgeTile;
        private boolean edgeDeformed(){return edgeX.busy()||edgeY.busy()||Math.abs(edgeX.value)>.00001f||Math.abs(edgeY.value)>.00001f;}
        private float stretchScaleX(float strength){return 1f+strength*edgeX.value*edgeAnchorX;}
        private float stretchScaleY(float strength){return 1f+strength*edgeY.value*edgeAnchorY;}
        private void stretchEdge(Canvas c,RectF r,float strength){
            c.scale(stretchScaleX(strength),stretchScaleY(strength),
                edgeAnchorX<0?r.right:r.left,edgeAnchorY<0?r.bottom:r.top);
        }
        private void clearEdgeOnContextSwitch(){
            if(edgeDeformed()){
                if(edgeRegion==1||lastEdgeRegion==1)invalidateScene();
                if((edgeRegion==2||lastEdgeRegion==2)&&headerNode!=null)headerNode.invalidate();
            }
            edgeKey=0;edgeX.aim(0f);edgeX.value=0f;edgeX.velocity=0f;
            edgeY.aim(0f);edgeY.value=0f;edgeY.velocity=0f;edgeWasMoving=false;
        }
        private void stretchHeaderPill(Canvas c,float left,float width,boolean selected){
            int save=c.save();
            glassMaterials.setLiveRims(edgeRegion==2 && selected && edgeDeformed());
            if(edgeRegion==2 && selected)stretchEdge(c,new RectF(left,HEADER_TOP,left+width,HEADER_TOP+HEADER_HEIGHT),.025f);
            glassMaterials.drawPillShadow(c,left,HEADER_TOP,width,HEADER_HEIGHT);
            material(c,left,HEADER_TOP,width,HEADER_HEIGHT,HEADER_HEIGHT/2f,2);
            glassMaterials.setLiveRims(false);c.restoreToCount(save);
        }
        private Tile focusedTileForEdge(){
            boolean dock=moveMode?moveDock:page==0||focusDrawerDock;
            ArrayList<Tile> items=dock?catalog.dock:catalog.grid;
            int index=dock?dockFocus:gridFocus;
            return index>=0&&index<items.size()?items.get(index):null;
        }
        private String navigationState(){
            return page+":"+focusHeader+":"+headerSelection+":"+dockFocus+":"+gridFocus
                +":"+focusDrawerDock+":"+moveDock+":"+(menu==null?-2:menu.selected)+":"+dialogConfirm;
        }
        private String focusSoundTarget(){
            if(dialogAction!=null)return "dialog:"+dialogConfirm;
            if(menu!=null&&menu.selected>=0)return "menu:"+System.identityHashCode(menu)+":"+menu.selected;
            if(closingMenu!=null||dialogReveal.value>.001f)return null;
            if(page==0&&focusHeader)return headerSelection==0||headerSelection==2||headerSelection==3?"pill:"+headerSelection:null;
            Tile tile=focusedTileForEdge();return tile!=null?tile.id:null;
        }
        private void playFocusChange(String previous){
            String next=focusSoundTarget();
            if(next!=null&&!next.equals(previous)&&uiSounds!=null){
                if(next.startsWith("menu:")||next.startsWith("dialog:"))uiSounds.navigateMenu();else uiSounds.navigate();
            }
        }
        private boolean directionKey(int code){
            return code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT
                ||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN;
        }
        private void releaseEdge(){edgeKey=0;edgeX.aim(0f);edgeY.aim(0f);invalidate();}
        @Override public boolean onKeyDown(int code,KeyEvent event){
            if(launchingTile!=null){
                if(code==KeyEvent.KEYCODE_BACK&&!launchDispatched){pendingLaunch=null;launchReveal.aim(0f);invalidate();}
                return true;
            }
            if(!directionKey(code))return handleKeyDown(code,event);
            String before=navigationState(),beforeSound=focusSoundTarget();
            boolean handled=handleKeyDown(code,event);
            if(before.equals(navigationState())){
                int nextRegion=dialogAction!=null?4:menu!=null&&menu.selected>=0?3:focusHeader&&page==0?2:1;
                if(nextRegion!=edgeRegion)clearEdgeOnContextSwitch();
                lastEdgeRegion=edgeRegion;edgeRegion=nextRegion;
                edgeKey=code;
                edgeHeader=headerSelection;
                edgeTile=edgeRegion==1?focusedTileForEdge():null;
                float dx=code==KeyEvent.KEYCODE_DPAD_LEFT?-1f:code==KeyEvent.KEYCODE_DPAD_RIGHT?1f:0f;
                float dy=code==KeyEvent.KEYCODE_DPAD_UP?-1f:code==KeyEvent.KEYCODE_DPAD_DOWN?1f:0f;
                if(dx!=0f){if(dx!=edgeAnchorX){edgeX.value=0f;edgeX.velocity=0f;}edgeAnchorX=dx;}
                if(dy!=0f){if(dy!=edgeAnchorY){edgeY.value=0f;edgeY.velocity=0f;}edgeAnchorY=dy;}
                float incoming=edgeApproach.speed(code,event.getEventTime());
                edgeX.press(dx,incoming);edgeY.press(dy,incoming);
                invalidate();
            }else{edgeApproach.moved(code,event.getEventTime());releaseEdge();playFocusChange(beforeSound);}
            return handled;
        }
        private boolean handleKeyDown(int code,KeyEvent event){
            if(dialogAction!=null){
                if(code==KeyEvent.KEYCODE_BACK){dismissDialog();return true;}
                if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){
                    dialogConfirm=code==KeyEvent.KEYCODE_DPAD_RIGHT&&!dialogButton.isEmpty();invalidate();return true;
                }
                if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER){if(event.getRepeatCount()==0){holdingOK=true;pressStartedInDialog=true;press.aim(1f,MotionProfile.PRESS_IN_K,MotionProfile.PRESS_IN_D);}invalidate();return true;}
                return true;
            }
            if(moveMode){
                if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){holdingOK=true;pressStartedInMenu=false;return true;}
                if(code==KeyEvent.KEYCODE_BACK){stopMove();return true;}
                if(handleMoveKey(code)){invalidate();return true;}
                return true;
            }
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER){
                if(event.getRepeatCount()>0){
                    if(holdingOK&&!longPressed&&menu==null){
                        handler.removeCallbacks(holdAction);
                        holdAction.run();
                    }
                    return true;
                }
                if(!holdingOK){
                    holdingOK=true;longPressed=false;
                    pressStartedInMenu=menu!=null&&menu.selected>=0;
                    press.aim(1f,MotionProfile.PRESS_IN_K,MotionProfile.PRESS_IN_D);
                    if(menu==null)handler.postDelayed(holdAction,440);
                    invalidate();
                }
                return true;
            }
            if(code==KeyEvent.KEYCODE_BACK){back();return true;}
            if(code==KeyEvent.KEYCODE_MENU){if(menu==null)openTileMenu();else closeMenu();return true;}
            if(menu!=null){
                if(menu.sourceHeader>=0&&(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT)){
                    moveHeader(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);return true;
                }
                if(code==KeyEvent.KEYCODE_DPAD_UP){
                    int first=menu.sourceHeader>=0?-1:0;
                    focusMenuRow(Math.max(first,menu.selected-1));return true;
                }
                if(code==KeyEvent.KEYCODE_DPAD_DOWN){focusMenuRow(Math.min(menu.items.size()-1,menu.selected+1));return true;}
                return true;
            }
            if(page==0){
                if(!focusHeader)navigationCue(code);
                if(code==KeyEvent.KEYCODE_DPAD_UP){focusHeader=true;updateFocus();return true;}
                if(code==KeyEvent.KEYCODE_DPAD_DOWN){if(focusHeader){focusHeader=false;updateFocus();}
                    else enterDrawer(true);return true;}
                if(code==KeyEvent.KEYCODE_DPAD_LEFT){if(focusHeader){moveHeader(-1);}
                    else dockFocus=Math.max(0,dockFocus-1);invalidate();return true;}
                if(code==KeyEvent.KEYCODE_DPAD_RIGHT){if(focusHeader){moveHeader(1);}
                    else dockFocus=Math.min(5,dockFocus+1);
                    invalidate();return true;}
                return true;
            }
            if(page==1 && menu==null && !moveMode){
                navigationCue(code);
                if(focusDrawerDock){
                    if(code==KeyEvent.KEYCODE_DPAD_UP){exitDrawer();return true;}
                    if(code==KeyEvent.KEYCODE_DPAD_DOWN){
                        int below=UiGeometry.belowDock(dockFocus,gridFirstRow,catalog.grid.size());
                        if(below>=0){gridFocus=below;focusDrawerDock=false;gridScroll.aim(gridFirstRow);}
                        invalidate();return true;
                    }
                    if(code==KeyEvent.KEYCODE_DPAD_LEFT)dockFocus=Math.max(0,dockFocus-1);
                    if(code==KeyEvent.KEYCODE_DPAD_RIGHT){
                        if(dockFocus<5)dockFocus++;
                        else if(!catalog.grid.isEmpty()){
                            gridFocus=Math.min(gridFirstRow*6,catalog.grid.size()-1);
                            focusDrawerDock=false;gridScroll.aim(gridFirstRow);
                        }
                    }
                    invalidate();return true;
                }
                if(catalog.grid.isEmpty()){if(code==KeyEvent.KEYCODE_DPAD_UP){focusDrawerDock=true;invalidate();}return true;}
                int next=gridFocus;
                if(code==KeyEvent.KEYCODE_DPAD_LEFT){
                    if(next>0)next--;
                    else{focusDrawerDock=true;dockFocus=5;invalidate();return true;}
                }
                if(code==KeyEvent.KEYCODE_DPAD_RIGHT && next+1<catalog.grid.size())next++;
                if(code==KeyEvent.KEYCODE_DPAD_DOWN && next/6<(catalog.grid.size()-1)/6)next=Math.min(catalog.grid.size()-1,next+6);
                if(code==KeyEvent.KEYCODE_DPAD_UP){
                    if(gridFocus<6){focusDrawerDock=true;dockFocus=gridFocus;invalidate();return true;}
                    next=Math.max(0,next-6);
                }
                gridFocus=next;
                int rowIndex=next/6;
                if(rowIndex<gridFirstRow)gridFirstRow=rowIndex;
                if(rowIndex>=gridFirstRow+UiGeometry.VISIBLE_ROWS)gridFirstRow=rowIndex-UiGeometry.VISIBLE_ROWS+1;
                gridScroll.aim(gridFirstRow);invalidate();return true;
            }
            return super.onKeyDown(code,event);
        }
        @Override public boolean onKeyUp(int code,KeyEvent event){
            if(code==edgeKey){releaseEdge();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER){
                if(dialogAction!=null){
                    if(!holdingOK)return true; // A dialog opened by this hold waits for a new press.
                    if(uiSounds!=null)uiSounds.press();
                    boolean fire=dialogConfirm;Runnable action=dialogAction;
                    holdingOK=false;handler.removeCallbacks(holdAction);dismissDialog();if(fire)action.run();return true;
                }
                if(moveMode){holdingOK=false;press.aim(0f,MotionProfile.PRESS_OUT_K,MotionProfile.PRESS_OUT_D);stopMove();return true;}
                if(holdingOK){
                    holdingOK=false;handler.removeCallbacks(holdAction);press.aim(0f,MotionProfile.PRESS_OUT_K,MotionProfile.PRESS_OUT_D);
                    if(pressStartedInMenu){if(menu!=null&&!longPressed)selectMenuItem();}
                    else if(!longPressed&&(menu==null||menu.selected<0))shortPress();
                    pressStartedInMenu=false;
                    longPressed=false;invalidate();
                }
                return true;
            }
            return super.onKeyUp(code,event);
        }
        private static final class Item {
            final String title;final Runnable perform;
            Item(String title,Runnable perform){this.title=title;this.perform=perform;}
        }
        private final class Menu {
            String title;final ArrayList<Item> items=new ArrayList<>();int selected=0;
            float left,top;
            boolean nearTile;
            int sourceHeader=-1;
            boolean isAirPlayModeMenu;
            int checked=-1;
            boolean anchored(){return nearTile||sourceHeader>=0;}
            Tile sourceTile;
            final MenuMotion.Frame motion=new MenuMotion.Frame();
            Bitmap backdrop;RectF backdropArea;long backdropKey=Long.MIN_VALUE;
            final SpringValue frostMix=new SpringValue(1f,420f,38f);
            Bitmap surface,header;final ArrayList<Bitmap> labels=new ArrayList<>();
            Menu(String title,float anchorX,float anchorY){this.title=title;this.left=anchorX;this.top=anchorY;}
            void add(String title,Runnable action){items.add(new Item(title,action));}
        }
        private void choosePinSlot(){
            replaceSlot=dockFocus;enterDrawer(false);gridFocus=0;showToast("Chọn ứng dụng để ghim vào Dock");
        }
        private HashMap<String,float[]> allPositions(){
            HashMap<String,float[]> result=visualPositions(true);result.putAll(visualPositions(false));return result;
        }
        private void applyInstallStates(){
            boolean add=false,refresh=false;
            for(Tile tile:catalog.all){
                InstallTracker.State state=tile.packageName==null?null:installStates.get(tile.packageName);
                if(state==null){
                    if(tile.installPlaceholder&&tile.installSessionId>=0)refresh=true;
                    tile.installSessionId=-1;tile.installProgress=0f;
                    if(!tile.installCompleting)tile.installReveal.aim(0f);
                }else if(!tile.retired){
                    if(tile.installSessionId!=state.sessionId){tile.installCompleting=false;tile.installSweep.velocity=0f;tile.installSweep.value=0f;}
                    tile.installSessionId=state.sessionId;tile.installProgress=state.progress;
                    tile.installSweep.aim(state.progress);tile.installReveal.aim(1f);
                }
            }
            for(InstallTracker.State state:installStates.values()){
                if(getPackageName().equals(state.packageName))continue;
                Tile tile=catalog.lookup("app:"+state.packageName);
                if((tile==null||tile.retired)&&state.label!=null&&!state.label.trim().isEmpty()){
                    try{
                        getPackageManager().getApplicationInfo(state.packageName,0);
                        if(getPackageManager().getLeanbackLaunchIntentForPackage(state.packageName)==null)continue;
                    }catch(android.content.pm.PackageManager.NameNotFoundException installingNewPackage){}
                    tile=catalog.addInstallPlaceholder(state.packageName,state.label,state.icon);
                    tile.installSessionId=state.sessionId;tile.installProgress=state.progress;tile.installSweep.aim(state.progress);tile.installReveal.aim(1f);add=true;
                }
            }
            if(refresh)refreshPackages(true);
            if(add)scheduleArtworkWarmup();invalidate();
        }
        private void refreshPackages(boolean force){
            if(!force&&!catalog.packagesChanged())return;
            animateCatalogChange(catalog::refresh);scheduleArtworkWarmup();
        }
        private void animateCatalogChange(Runnable change){
            HashMap<String,float[]> before=allPositions();
            change.run();
            gridFocus=Math.max(0,Math.min(gridFocus,catalog.grid.size()-1));
            gridFirstRow=Math.min(gridFirstRow,Math.max(0,(catalog.grid.size()+5)/6-UiGeometry.VISIBLE_ROWS));
            gridScroll.aim(gridFirstRow);
            HashMap<String,float[]> after=allPositions();
            for(java.util.Map.Entry<String,float[]> entry:before.entrySet()){
                if(after.containsKey(entry.getKey()))continue;
                Tile tile=catalog.lookup(entry.getKey());if(tile==null)continue;
                float[] position=entry.getValue();tile.exiting=true;tile.exitX=position[0];tile.exitY=position[1];tile.exitDock=position[2]>0f;
                tile.presence.aim(0f);
            }
            reflow(true,before);reflow(false,before);invalidateScene();
        }
        private void openTileMenu(){
            boolean dockMode=page==0||focusDrawerDock;
            Tile tile;
            if(dockMode){if(catalog.dock.isEmpty())return;tile=catalog.dock.get(dockFocus);}
            else{if(catalog.grid.isEmpty())return;tile=catalog.grid.get(gridFocus);}
            if(tile.installPlaceholder){showToast("Ứng dụng đang được cài đặt");return;}
            float cx=UiGeometry.centerX(dockMode?dockFocus:gridFocus%6);
            float cy=dockMode?docTop()+UiGeometry.DOCK_HEIGHT/2f:
                UiGeometry.rowTop(gridFocus/6,gridScroll.value)+UiGeometry.TILE_MAX_HEIGHT/2f;
            cy+=dockMode?tile.offsetY.value:rowOffset(gridFocus/6)+tile.offsetY.value;cx+=tile.offsetX.value;
            Menu m=new Menu(tile.label,cx,cy);m.nearTile=true;m.sourceTile=tile;
            if(tile.isEmpty()){
                m.add("Ghim ứng dụng",this::choosePinSlot);
            }else{
                m.add("Mở",()->open(tile));
                m.add("Sắp xếp ứng dụng",()->startMove(dockMode));
                if(dockMode){
                    m.add("Bỏ ghim khỏi Dock",()->animateCatalogChange(()->catalog.unpin(dockFocus)));
                }else{
                    if(catalog.firstEmptySlot()>=0)m.add("Ghim vào Dock",()->animateCatalogChange(()->catalog.pin(tile)));
                }
                if(tile.uninstallable)m.add("Gỡ cài đặt",()->uninstall(tile));
                if(tile.packageName!=null)m.add("Thông tin ứng dụng",()->appInfo(tile));
            }
            showMenu(m);
        }
        private void closeMenu(){
            if(menu!=null){
                clearEdgeOnContextSwitch();
                closingMenu=menu;menu=null;
                menuReveal.aim(0f,closingMenu.anchored()?MenuMotion.CLOSE_K:MotionProfile.MENU_CLOSE_K,closingMenu.anchored()?MenuMotion.CLOSE_D:MotionProfile.MENU_CLOSE_D);
                menuContent.aim(0f,900f,58f);
            }
            invalidate();
        }
        private void focusMenuRow(int row){
            if(row>=0){
                if(menu.selected<0)settleReturnState(menuSelection,row);
                else menuSelection.aim(row);
            }
            menu.selected=row;invalidate();
        }
        private void selectMenuItem(){
            if(menu==null||menu.selected<0||(inputMenuOutgoing!=null&&inputMenuChange.value<.5f))return;
            if(uiSounds!=null)uiSounds.press();
            clearEdgeOnContextSwitch();
            Item item=menu.items.get(menu.selected);
            closingMenu=menu;menu=null;
            menuReveal.aim(0f,closingMenu.anchored()?MenuMotion.CLOSE_K:MotionProfile.MENU_CLOSE_K,closingMenu.anchored()?MenuMotion.CLOSE_D:MotionProfile.MENU_CLOSE_D);
            menuContent.aim(0f,900f,58f);
            item.perform.run();invalidate();
        }
        private boolean sameMenu(Menu a,Menu b){
            if(a==null||a.sourceTile!=b.sourceTile||a.sourceHeader!=b.sourceHeader||a.nearTile!=b.nearTile||!a.title.equals(b.title)||a.items.size()!=b.items.size())return false;
            for(int i=0;i<a.items.size();i++)if(!a.items.get(i).title.equals(b.items.get(i).title))return false;
            return true;
        }
        private void showMenu(Menu m){
            clearEdgeOnContextSwitch();
            Menu previous=closingMenu;boolean reversing=sameMenu(previous,m);
            if(previous!=null&&previous.sourceHeader==2&&m.sourceHeader==2&&previous.isAirPlayModeMenu!=m.isAirPlayModeMenu){
                inputMenuOrigin.set(menuPanelRect(previous));
                inputMenuOutgoing=previous;
                settleReturnState(inputMenuChange,0f);
                inputMenuChange.aim(1f);
            }else{
                inputMenuOutgoing=null;
                settleReturnState(inputMenuChange,1f);
            }
            if(reversing){
                if(m.sourceHeader<0)m.selected=Math.min(previous.selected,m.items.size()-1);
                m.surface=previous.surface;m.header=previous.header;m.labels.addAll(previous.labels);
                if(previous.backdrop!=null&&menuRect(previous).equals(menuRect(m))){
                    m.backdrop=previous.backdrop;m.backdropArea=new RectF(previous.backdropArea);m.backdropKey=previous.backdropKey;
                }
            }else prepareMenu(m);
            closingMenu=null;menu=m;
            longPressed=holdingOK;pressStartedInMenu=false;settleReturnState(press,0f);
            m.frostMix.value=1;m.frostMix.velocity=0;m.frostMix.aim(1f);
            if(reversing&&m.sourceHeader<0){
                menuSelection.aim(m.selected);
                menuRowEmphasis.aim(m.selected>=0?1f:0f);
            }else{
                settleReturnState(menuSelection,Math.max(0,m.selected));
                settleReturnState(menuRowEmphasis,m.selected>=0?1f:0f);
            }
            settleReturnState(menuContent,1f);
            menuReveal.aim(1f,m.anchored()?MenuMotion.OPEN_K:MotionProfile.MENU_OPEN_K,m.anchored()?MenuMotion.OPEN_D:MotionProfile.MENU_OPEN_D);
            invalidate();
        }
        private void refreshMenuBackdrop(Menu m){
            // The flight crosses the source tile as its press spring releases.
            // Capture that corridor once; re-rasterizing its moving artwork at
            // every VSYNC stalls older TVs. Reuse it throughout the flight even
            // when rapid input reverses the drawer underneath; refresh at rest.
            if(m.anchored()&&m.backdrop!=null&&(menuReveal.busy()||inputMenuOutgoing!=null)){
                glassMaterials.setMenuBackdrop(m.backdrop,m.backdropArea);return;
            }
            RectF region=menuRect(m);
            if(inputMenuOutgoing!=null)region.union(inputMenuOrigin);
            if(m.anchored()){region.union(m.left-150f,m.top-100f,m.left+150f,m.top+100f);region.inset(-24f,-24f);}
            RectF area=glassCaptureArea(region);long key=underlyingSceneKey(area);
            if(m.backdrop==null||key!=m.backdropKey||!area.equals(m.backdropArea)){
                m.backdrop=captureGlassRegion(area,6,0);m.backdropArea=area;m.backdropKey=key;
            }
            glassMaterials.setMenuBackdrop(m.backdrop,m.backdropArea);
        }
        private int withOpacity(int color,float opacity){return (Math.round(Color.alpha(color)*opacity)<<24)|(color&0x00ffffff);}
        private float menuHeight(Menu m){
            return UiGeometry.popupHeight(m.items.size());
        }
        private RectF menuRect(Menu m){
            UiGeometry.menuRect(m.items.size(),m.sourceHeader,m.nearTile,m.left,m.top,rectScratch);
            return new RectF(rectScratch[0],rectScratch[1],rectScratch[2],rectScratch[3]);
        }
        private MenuMotion.Frame menuMotion(Menu m,RectF target){
            float x=m.left,y=m.top;
            if(m.sourceHeader>=0){RectF button=headerRect(m.sourceHeader);x=button.centerX();y=button.centerY();}
            TileNode source=m.sourceTile==null?null:tileNodes.get(m.sourceTile.id);
            if(source!=null&&canvasScale>0f){
                x=(source.getX()+source.getPivotX()-canvasLeft)/canvasScale;
                y=(source.getY()+source.getPivotY()-canvasTop)/canvasScale;
            }
            if(m.sourceHeader>=0)MenuMotion.resolveFromScale(menuReveal.value,menuReveal.velocity,x,y,target.centerX(),target.centerY(),Math.min(44f/MENU_WIDTH,44f/target.height()),m.motion);
            else MenuMotion.resolve(menuReveal.value,menuReveal.velocity,x,y,target.centerX(),target.centerY(),target.height(),m.motion);
            return m.motion;
        }
        private RectF menuVisualRect(Menu m){
            RectF r=menuPanelRect(m);
            if(!m.anchored())return r;
            MenuMotion.Frame f=menuMotion(m,r);
            float w=r.width()*f.scaleX/2f,h=r.height()*f.scaleY/2f;
            r.set(f.x-w,f.y-h,f.x+w,f.y+h);return r;
        }
        private RectF menuPanelRect(Menu m){
            RectF r=menuRect(m);
            if(inputMenuOutgoing!=null){
                float progress=UiGeometry.clamp(inputMenuChange.value,0f,1f);
                r.top=inputMenuOrigin.top+(r.top-inputMenuOrigin.top)*progress;
                r.bottom=inputMenuOrigin.bottom+(r.bottom-inputMenuOrigin.bottom)*progress;
            }
            return r;
        }
        private final android.util.LruCache<String,Bitmap> menuTextures=new android.util.LruCache<String,Bitmap>(4*1024*1024){
            @Override protected int sizeOf(String key,Bitmap image){return image.getAllocationByteCount();}
            // Eviction only releases cache ownership. A live/closing Menu can
            // still own the same texture, so never recycle it from this cache.
        };
        private final android.util.LruCache<String,Bitmap> textTextures=new android.util.LruCache<String,Bitmap>(2*1024*1024){
            @Override protected int sizeOf(String key,Bitmap image){return image.getAllocationByteCount();}
        };
        private Bitmap tileLabelBitmap(String label){
            String key="tile-lbl|"+label;
            Bitmap cached=textTextures.get(key);if(cached!=null)return cached;
            Bitmap image=Bitmap.createBitmap(252,36,Bitmap.Config.ARGB_8888);
            Canvas surface=new Canvas(image);
            textOpacity=1f;
            drawFittedText(surface,label,126f,24f,UiGeometry.APP_LABEL_SIZE,fg(),239f);
            image.prepareToDraw();textTextures.put(key,image);return image;
        }
        private Bitmap toastLabelBitmap(String message,int width,int height,float baseline){
            String key="toast|"+width+"|"+height+"|"+message;
            Bitmap cached=textTextures.get(key);if(cached!=null)return cached;
            Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Canvas surface=new Canvas(image);
            textOpacity=1f;
            drawSettingsText(surface,message,0f,baseline,24,fg(),false,width);
            image.prepareToDraw();textTextures.put(key,image);return image;
        }
        private Bitmap dialogContentBitmap(int height){
            ArrayList<String> lines=dialogLines();
            String key="dlg-body|"+height+"|"+dialogTitle+"|"+dialogBody;
            Bitmap cached=textTextures.get(key);if(cached!=null)return cached;
            Bitmap image=Bitmap.createBitmap((int)DIALOG_WIDTH,Math.max(1,height),Bitmap.Config.ARGB_8888);
            Canvas surface=new Canvas(image);
            textOpacity=1f;
            configureTextPaint(measurePaint,POPUP_TITLE_SIZE,true);
            float textLeft=UiGeometry.POPUP_TITLE_INSET;
            text(surface,dialogTitle,textLeft,UiGeometry.POPUP_TITLE_INSET-measurePaint.ascent(),
                POPUP_TITLE_SIZE,fg(),true,Paint.Align.LEFT);
            float y=DIALOG_BODY_TOP+22;
            for(String line:lines){
                text(surface,line,textLeft,y,POPUP_TEXT_SIZE,fg(),false,Paint.Align.LEFT);
                y+=DIALOG_LINE_HEIGHT;
            }
            image.prepareToDraw();textTextures.put(key,image);return image;
        }
        private Bitmap dialogButtonBitmap(String label,int width,int ink){
            int w=Math.max(1,width);
            String key="dlg-btn|"+w+"|"+ink+"|"+label;
            Bitmap cached=textTextures.get(key);if(cached!=null)return cached;
            Bitmap image=Bitmap.createBitmap(w,(int)UiGeometry.POPUP_ROW_HEIGHT,Bitmap.Config.ARGB_8888);
            Canvas surface=new Canvas(image);
            textOpacity=1f;
            configureTextPaint(measurePaint,POPUP_TEXT_SIZE,false);
            float baseline=(UiGeometry.POPUP_ROW_HEIGHT-measurePaint.ascent()-measurePaint.descent())/2f;
            text(surface,fittedText(label,POPUP_TEXT_SIZE,false,w-2*UiGeometry.POPUP_TEXT_PADDING),
                w*.5f,baseline,POPUP_TEXT_SIZE,ink,false,Paint.Align.CENTER);
            image.prepareToDraw();textTextures.put(key,image);return image;
        }
        private Bitmap menuLabel(String label,float size,boolean bold,int height){
            return menuLabel(label,size,bold,height,0f);
        }
        private Bitmap menuLabel(String label,float size,boolean bold,int height,float rightPadding){
            String key="clear|"+size+"|"+bold+"|"+label+"|"+rightPadding;
            Bitmap cached=menuTextures.get(key);if(cached!=null)return cached;
            Bitmap image=Bitmap.createBitmap((int)MENU_WIDTH,height,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(image);
            configureTextPaint(measurePaint,size,bold);Paint.FontMetrics fm=measurePaint.getFontMetrics();
            float baseline=bold?UiGeometry.POPUP_TITLE_INSET-fm.ascent:(MENU_ITEM_HEIGHT-fm.ascent-fm.descent)/2f;
            float inset=bold?UiGeometry.POPUP_TITLE_INSET:MENU_PAD+UiGeometry.POPUP_TEXT_PADDING;
            drawSettingsText(c,label,inset,baseline,size,fg(),bold,MENU_WIDTH-2*inset-rightPadding);
            image.prepareToDraw();menuTextures.put(key,image);return image;
        }
        private void prepareMenu(Menu m){
            String key="clear-surface:"+m.items.size();
            m.surface=menuTextures.get(key);
            if(m.surface==null){
                m.surface=Bitmap.createBitmap((int)MENU_WIDTH,(int)menuHeight(m),Bitmap.Config.ARGB_8888);
                Canvas c=new Canvas(m.surface);
                RectF target=menuRect(m);c.translate(-target.left,-target.top);glassMaterials.drawStaticDecoration(c,target.left,target.top,MENU_WIDTH,menuHeight(m),UiGeometry.POPUP_RADIUS,3);
                m.surface.prepareToDraw();menuTextures.put(key,m.surface);
            }
            m.header=menuLabel(m.title,POPUP_TITLE_SIZE,true,(int)MENU_HEADER);
            for(Item item:m.items)m.labels.add(menuLabel(item.title,POPUP_TEXT_SIZE,false,(int)MENU_ROW,m.isAirPlayModeMenu?72f:0f));
        }
        private void menuBitmap(Canvas c,Bitmap bitmap,float x,float y,float opacity){
            paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);paint.setDither(true);
            paint.setAlpha(Math.round(255f*UiGeometry.clamp(opacity,0f,1f)));
            bounds.set(x,y,x+bitmap.getWidth(),y+bitmap.getHeight());c.drawBitmap(bitmap,null,bounds,paint);
        }
        private void drawMenu(Canvas c){
            Menu m=menu!=null?menu:closingMenu;if(m==null || m.surface==null)return;
            float q=UiGeometry.clamp(menuReveal.value,0f,1f);if(q<.001f)return;
            refreshMenuBackdrop(m);glassMaterials.beginSurface(c);
            RectF target=menuPanelRect(m);
            int transform=c.save();
            if(m.anchored()){
                MenuMotion.Frame f=menuMotion(m,target);
                c.translate(f.x-target.centerX(),f.y-target.centerY());
                c.scale(f.scaleX,f.scaleY,target.centerX(),target.centerY());
            }
            float slide=m.anchored()?0f:24f*(1f-q);
            int popup=q==1f?c.save():c.saveLayerAlpha(target.left-26,target.top-26,target.right+26,target.bottom+26,(int)(255*q),Canvas.ALL_SAVE_FLAG);float frost=UiGeometry.clamp(m.frostMix.value,0,1);
            glassMaterials.setLiveRims(edgeRegion==3 && edgeDeformed());
            if(edgeRegion==3)stretchEdge(c,target,.012f);
            glassMaterials.drawBackdrop(c,target.left,target.top+slide,MENU_WIDTH,target.height(),UiGeometry.POPUP_RADIUS,3,0,0,frost);
            c.translate(0,slide);
            glassMaterials.drawDecorationOnly(c,target.left,target.top,MENU_WIDTH,target.height(),UiGeometry.POPUP_RADIUS,3);
            glassMaterials.setLiveRims(false);
            float alpha=UiGeometry.clamp(menuContent.value,0f,1f);
            c.translate(0,(1f-alpha)*8f);
            if(inputMenuOutgoing!=null){
                c.clipRect(target.left,target.top,target.right,target.bottom-MENU_PAD);
                float mix=UiGeometry.clamp(inputMenuChange.value,0f,1f);
                if(mix<.5f)drawMenuRows(c,inputMenuOutgoing,target,alpha*(1f-2f*mix),inputMenuOutgoing.selected,1f,0f);
                else drawMenuRows(c,m,target,alpha*(2f*mix-1f),menuSelection.value,menuRowEmphasis.value,0f);
            }else{
                float emphasis=(m!=menu||dialogAction==null)?menuRowEmphasis.value:0f;
                float pressed=m==menu&&pressStartedInMenu?UiGeometry.clamp(press.value,0f,1f):0f;
                drawMenuRows(c,m,target,alpha,menuSelection.value,emphasis,pressed);
            }
            c.restoreToCount(popup);c.restoreToCount(transform);
        }
        private void drawMenuRows(Canvas c,Menu m,RectF target,float alpha,float selection,float emphasis,float pressed){
            menuBitmap(c,m.header,target.left,target.top,alpha);
            float firstRowY=target.top+MENU_HEADER;
            float highlight=selection>=0f?alpha*emphasis:0f;
            if(highlight>.001f){
                float y=firstRowY+selection*MENU_ROW;
                int shape=c.save();
                float pressScale=1f-.035f*pressed;
                c.scale(pressScale,pressScale,target.centerX(),y+MENU_ITEM_HEIGHT*.5f);
                glassMaterials.drawItem(c,target.left+MENU_PAD,y,MENU_WIDTH-2*MENU_PAD,
                    MENU_ITEM_HEIGHT,UiGeometry.POPUP_ROW_RADIUS,highlight);
                c.restoreToCount(shape);
            }
            for(int i=0;i<m.items.size();i++){
                float y=firstRowY+i*MENU_ROW;
                menuBitmap(c,m.labels.get(i),target.left,y,alpha);
                if(i==m.checked){
                    float checkX=target.right-MENU_PAD-UiGeometry.POPUP_TEXT_PADDING-17f;
                    drawSolar(c,"check",checkX,y+MENU_ITEM_HEIGHT*.5f,34f,withOpacity(fg(),alpha));
                }
            }
        }
        boolean queueExternalLaunch(Tile tile,Runnable action){
            if(launchingTile!=null)return true;
            if(action==null)return false;
            // Resolve the actual source once; the tile might be in the App List,
            // and its position must not change while Android switches Activities.
            lastExternalInDrawer=page==1;
            lastExternalDock=!lastExternalInDrawer||focusDrawerDock;
            lastExternalCx=UiGeometry.centerX(lastExternalDock?dockFocus:gridFocus%6);
            lastExternalCy=lastExternalDock?docTop()+UiGeometry.DOCK_HEIGHT*.5f:
                UiGeometry.rowTop(gridFocus/6,gridScroll.value)+UiGeometry.TILE_MAX_HEIGHT/2f;
            // Reopening the returning app retargets the existing motion instead
            // of teleporting it back to the icon and starting from rest.
            float continuingProgress=returningTile==tile&&!returnWaiting?returnReveal.value:0f;
            float continuingVelocity=returningTile==tile&&!returnWaiting?returnReveal.velocity:0f;
            lastExternalTile=tile;
            leftForExternalApp=false;
            externalLaunchInProgress=false;
            returningTile=null;returnWaiting=false;
            returnReveal.value=0f;returnReveal.target=0f;returnReveal.velocity=0f;
            launchingTile=tile;pendingLaunch=action;launchDispatched=false;
            launchReveal.value=continuingProgress;launchReveal.velocity=continuingVelocity;launchReveal.aim(1f,MotionProfile.LAUNCH_K,MotionProfile.LAUNCH_D);
            captureMorphOrigin(tile);invalidate();return true;
        }
        private final Runnable launchSafety=()->{if(resumed&&launchingTile!=null)resetLaunchMotion();};
        void resetLaunchMotion(){
            handler.removeCallbacks(launchSafety);
            launchingTile=null;pendingLaunch=null;launchDispatched=false;
            launchReveal.value=0f;launchReveal.target=0f;launchReveal.velocity=0f;
            invalidate();
        }
        void cancelHeldInput(){
            releaseEdge();
            holdingOK=false;longPressed=false;pressStartedInMenu=false;pressStartedInDialog=false;
            handler.removeCallbacks(holdAction);
            press.aim(0f,MotionProfile.PRESS_OUT_K,MotionProfile.PRESS_OUT_D);
            tilePress.aim(0f,MotionProfile.PRESS_OUT_K,MotionProfile.PRESS_OUT_D);invalidate();
        }
        void onLeaveForExternalApp(){
            returningTile=null;returnWaiting=false;settleReturnState(returnReveal,0f);
            startupStarted=true;startupReveal.value=1f;startupReveal.target=1f;startupReveal.velocity=0f;
            cancelHeldInput();
            // Do not consult launchDispatched here: the handoff runnable can reset
            // it before the lifecycle callback on Android 9.
            if(lastExternalTile!=null && externalLaunchInProgress)leftForExternalApp=true;
        }
        private void settleReturnState(SpringValue spring,float value){spring.value=value;spring.target=value;spring.velocity=0f;}
        private void restoreReturnContext(Tile tile){
            int dock=catalog.dock.indexOf(tile),grid=catalog.grid.indexOf(tile);
            focusHeader=false;
            boolean useDock=dock>=0&&(lastExternalDock||grid<0);
            page=useDock&&!lastExternalInDrawer?0:1;
            focusDrawerDock=useDock&&page==1;
            gridFirstRow=Math.max(0,Math.min(gridFirstRow,
                (catalog.grid.size()+5)/6-UiGeometry.VISIBLE_ROWS));
            if(useDock)dockFocus=dock;
            else if(grid>=0){
                gridFocus=grid;
                if(grid/6<gridFirstRow||grid/6>=gridFirstRow+UiGeometry.VISIBLE_ROWS)
                    gridFirstRow=Math.max(0,grid/6-UiGeometry.VISIBLE_ROWS+1);
            }
            settleReturnState(gridScroll,gridFirstRow);
            settleDrawerPage();
            startupStarted=true;settleReturnState(startupReveal,1f);updateFocus();
        }
        private void settleDrawerPage(){
            float progress=page==1?1f:0f;
            settleReturnState(drawer,progress);settleReturnState(listBlur,progress);
            settleReturnState(drawerFlow.dock,progress);
            for(SpringValue row:drawerFlow.rows)settleReturnState(row,progress);
        }
        private void deferReturnUntilVisible(){
            returnVisibleAt=android.os.SystemClock.uptimeMillis()+120;
            if(returnWaiting){handler.removeCallbacks(returnWindowReady);handler.postDelayed(returnWindowReady,120);}
        }
        private void startVisibleReturn(){
            if(!returnWaiting||returningTile==null||!resumed||!hasWindowFocus()||getWidth()==0||android.os.SystemClock.uptimeMillis()<returnVisibleAt)return;
            if(!returnWindowEntered&&android.os.SystemClock.uptimeMillis()-returnWaitingSince<900)return;
            if(returningTile.image==null&&android.os.SystemClock.uptimeMillis()-returnWaitingSince<1200)return;
            handler.removeCallbacks(returnReadyTimeout);handler.removeCallbacks(returnWindowReady);handler.removeCallbacks(returnEntryFallback);
            returnWaiting=false;captureMorphOrigin(returningTile);
            returnReveal.aim(0f,MotionProfile.RETURN_K,MotionProfile.RETURN_D);
            lastFrame=0L;
            if(uiSounds!=null)uiSounds.returnHome();
            Log.i(TAG,"return.motion.start source="+returningTile.id);
        }
        void onReturnToHome(){
            resetLaunchMotion();
            if(returningTile!=null)return;
            boolean ownLaunch=leftForExternalApp,durable=pendingReturnFeedback(),cameFromApp=ownLaunch||durable;
            leftForExternalApp=false;
            if(durable&&!ownLaunch){
                String origin=returnFeedback.getString("origin_id",null);
                lastExternalTile=origin==null?null:catalog.lookup(origin);
                gridFirstRow=returnFeedback.getInt("origin_row",0);
                lastExternalDock=returnFeedback.getBoolean("origin_dock",catalog.dock.contains(lastExternalTile));
                lastExternalInDrawer=returnFeedback.getBoolean("origin_drawer",!lastExternalDock);
            }
            if(lastExternalTile!=null&&(lastExternalTile.retired||lastExternalTile.packageName==null))lastExternalTile=null;
            if(cameFromApp&&lastExternalTile!=null&&!appsNeedsHome){
                overridePendingTransition(0,0);
                restoreReturnContext(lastExternalTile);
                returningTile=lastExternalTile;returnWaiting=true;returnDraws=0;
                returnWaitingSince=android.os.SystemClock.uptimeMillis();
                deferReturnUntilVisible();
                handler.postDelayed(returnReadyTimeout,1200);handler.postDelayed(returnEntryFallback,900);
                settleReturnState(returnReveal,1f);lastFrame=0L;
                // Flatten and queue the source upload while the window is still
                // waiting to enter, not on the VSYNC that begins the spring.
                if(lastExternalTile.image!=null)captureMorphOrigin(lastExternalTile);
                invalidateScene();
                scheduleArtworkWarmup();invalidate();
            }else if(cameFromApp&&uiSounds!=null)uiSounds.returnHome();
            externalLaunchInProgress=false;
        }
        private final RectF morphOrigin=new RectF(),morphBounds=new RectF(),morphCrop=new RectF(),morphDamage=new RectF();
        private final Matrix morphMatrix=new Matrix();
        private Bitmap morphBitmap,morphTexture,morphTextureSource;
        private BitmapShader morphShader;
        private int morphFill=0xff202024;
        private final android.graphics.PorterDuffColorFilter[] morphDarkFilters=new android.graphics.PorterDuffColorFilter[256];
        private RectF currentTileBounds(Tile tile){RectF result=new RectF();readTileBounds(tile,result);return result;}
        private void readTileBounds(Tile tile,RectF result){
            TileNode node=tileNodes.get(tile.id);
            if(node==null){result.set(lastExternalCx-126,lastExternalCy-71,lastExternalCx+126,lastExternalCy+71);return;}
            float cx=(node.getX()+node.getPivotX()-canvasLeft)/canvasScale,cy=(node.getY()+node.getPivotY()-canvasTop)/canvasScale;
            float w=252f*node.getScaleX()/canvasScale,h=142f*node.getScaleY()/canvasScale;
            result.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2);
        }
        private void captureMorphOrigin(Tile tile){
            readTileBounds(tile,morphOrigin);
            if(tile.image!=null&&morphTextureSource!=tile.image){
                Bitmap image=tile.image;int idx=0;
                for(int y=1;y<5;y++)for(int x=1;x<9;x++)morphSamples[idx++]=image.getPixel(image.getWidth()*x/9,image.getHeight()*y/5);
                morphFill=ArtworkDetector.dominantFillColor(morphSamples,morphFill);
                if(morphTextureSource!=image){
                    // Flatten the transparent tile gutter once. The moving surface
                    // is then one opaque texture pass instead of fill + alpha image.
                    Bitmap texture=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
                    Canvas surface=new Canvas(texture);surface.drawColor(morphFill);surface.drawBitmap(image,0f,0f,null);
                    texture.setHasAlpha(false);texture.prepareToDraw();
                    morphTexture=texture;morphTextureSource=image;
                    morphBitmap=texture;morphShader=new BitmapShader(texture,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                }
            }
        }
        private final float[] morphFrame=new float[12];
        private void drawArtworkMorph(Canvas c,Tile tile,RectF origin,float progress,boolean opening){
            Bitmap image=morphTextureSource==tile.image?morphTexture:tile.image;
            float imgW=image==null?508f:image.getWidth(),imgH=image==null?288f:image.getHeight();
            SurfaceMorph.resolveRenderFrame(origin.left,origin.top,origin.right,origin.bottom,progress,opening,morphFill,imgW,imgH,morphFrame);
            morphBounds.set(morphFrame[0],morphFrame[1],morphFrame[2],morphFrame[3]);
            float p=morphFrame[4],radius=morphFrame[5],dark=morphFrame[6];
            float w=morphBounds.width(),h=morphBounds.height();
            if(p<=0f&&tile.image!=null){
                // Use exactly the native tile's masked pixels, gutter and tint at
                // handoff and through the rebound. Signed spring displacement
                // continues moving/scaling these same pixels past equilibrium.
                bounds.set(morphBounds.left-w/252f,morphBounds.top-h/142f,morphBounds.right+w/252f,morphBounds.bottom+h/142f);
                paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
                paint.setColorFilter(focusTints[Math.round(UiGeometry.clamp(tile.scale.value,0f,1f)*64)]);
                c.drawBitmap(tile.image,null,bounds,paint);paint.setColorFilter(null);return;
            }
            int fill=Float.floatToRawIntBits(morphFrame[7]);
            paint.reset();paint.setAntiAlias(true);paint.setColor(fill);
            // HWUI has an analytic round-rect renderer. A changing full-screen
            // polygon otherwise tessellates and uploads a new mask every VSYNC.
            if(image==null)drawMorphShape(c,radius,p,paint);
            if(image!=null){
                if(morphBitmap!=image){morphBitmap=image;morphShader=new BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);}
                morphCrop.set(morphFrame[8],morphFrame[9],morphFrame[10],morphFrame[11]);
                morphMatrix.setRectToRect(morphCrop,morphBounds,Matrix.ScaleToFit.FILL);
                morphShader.setLocalMatrix(morphMatrix);paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);paint.setShader(morphShader);
                if(dark>0){
                    int alpha=Math.round(255*dark);
                    if(morphDarkFilters[alpha]==null)morphDarkFilters[alpha]=new android.graphics.PorterDuffColorFilter(alpha<<24,android.graphics.PorterDuff.Mode.SRC_ATOP);
                    paint.setColorFilter(morphDarkFilters[alpha]);
                }
                drawMorphShape(c,radius,p,paint);paint.setShader(null);paint.setColorFilter(null);
            }
        }
        private final Path morphPath=new Path();
        private void drawMorphShape(Canvas c,float radius,float progress,Paint brush){
            if(progress>=.18f){c.drawRoundRect(morphBounds,radius,radius,brush);return;}
            // Only the small endpoint surface needs a changing path. Keep the
            // inexpensive analytic round-rect for the large part of the motion.
            UiGeometry.squircleVertices(morphBounds.left,morphBounds.top,morphBounds.right,morphBounds.bottom,radius,SurfaceMorph.artworkCornerExponent(progress),squircleVertices);
            Path path=morphPath;path.rewind();path.moveTo(squircleVertices[0],squircleVertices[1]);
            for(int i=2;i<266;i+=2)path.lineTo(squircleVertices[i],squircleVertices[i+1]);
            path.close();c.drawPath(path,brush);
        }
        private void drawReturnMorph(Canvas c){
            if(returningTile==null||returnWaiting&&returningTile.image==null)return;
            if(!returnWaiting&&resumed&&hasWindowFocus())returnDraws++;
            readTileBounds(returningTile,morphOrigin);
            drawArtworkMorph(c,returningTile,morphOrigin,returnReveal.value,false);
        }
        private void drawLaunchMorph(Canvas c){
            if(launchingTile!=null)drawArtworkMorph(c,launchingTile,morphOrigin,launchReveal.value,true);
        }
        private void startMove(boolean dock){
            Tile picked=(dock?catalog.dock:catalog.grid).get(dock?dockFocus:gridFocus);
            if(picked.isEmpty())return;
            clearEdgeOnContextSwitch();
            moveMode=true;moveDock=dock;showToast("Di chuyển để sắp xếp ứng dụng");
            if(page==0){page=1;focusDrawerDock=true;drawer.aim(1f,MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D);}
            press.aim(0f,MotionProfile.PRESS_OUT_K,MotionProfile.PRESS_OUT_D);invalidate();
        }
        private void stopMove(){clearEdgeOnContextSwitch();moveMode=false;invalidate();}
        private HashMap<String,float[]> visualPositions(boolean dock){
            HashMap<String,float[]> result=new HashMap<>();
            ArrayList<Tile> source=dock?catalog.dock:catalog.grid;
            for(int i=0;i<source.size();i++){
                Tile tile=source.get(i);
                result.put(tile.id,new float[]{UiGeometry.centerX(i%6)+tile.offsetX.value,
                    (dock?docTop()+UiGeometry.DOCK_HEIGHT/2f:UiGeometry.rowTop(i/6,gridScroll.value)+UiGeometry.TILE_MAX_HEIGHT/2f+rowOffset(i/6))+tile.offsetY.value,dock?1f:0f});
            }
            return result;
        }
        private void reflow(boolean dock,HashMap<String,float[]> before){
            ArrayList<Tile> source=dock?catalog.dock:catalog.grid;
            for(int i=0;i<source.size();i++){
                Tile tile=source.get(i);
                float[] old=before.get(tile.id);
                tile.exiting=false;
                if(old==null){tile.presence.value=0f;tile.presence.velocity=0f;tile.presence.aim(1f);continue;}
                if(old.length>2 && old[2]!=(dock?1f:0f))tile.traveling=true;
                float x=UiGeometry.centerX(i%6);
                float y=dock?docTop()+UiGeometry.DOCK_HEIGHT/2f:UiGeometry.rowTop(i/6,gridScroll.value)+UiGeometry.TILE_MAX_HEIGHT/2f+rowOffset(i/6);
                tile.offsetX.value=old[0]-x;tile.offsetY.value=old[1]-y;
                tile.offsetX.aim(0f);tile.offsetY.aim(0f);
            }
        }
        private void drawTravelingTiles(Canvas c){
            for(Tile tile:catalog.all){
                if(!tile.traveling)continue;
                int index=catalog.dock.indexOf(tile);boolean dock=index>=0;
                if(!dock)index=catalog.grid.indexOf(tile);
                if(index<0)continue;
                float x=UiGeometry.centerX(index%6)+tile.offsetX.value;
                float y=(dock?docTop()+UiGeometry.DOCK_HEIGHT/2f:UiGeometry.rowTop(index/6,gridScroll.value)+UiGeometry.TILE_MAX_HEIGHT/2f+rowOffset(index/6))+tile.offsetY.value;
                drawTile(c,tile,x,y,tile.scale.value,0f,dock);
            }
        }
        private boolean handleMoveKey(int code){
            int position=moveDock?dockFocus:gridFocus;
            int count=moveDock?catalog.dock.size():catalog.grid.size();
            if(count==0)return true;
            Tile picked=(moveDock?catalog.dock:catalog.grid).get(position);
            HashMap<String,float[]> before=allPositions();
            int target=position;
            if(code==KeyEvent.KEYCODE_DPAD_UP && moveDock){
                if(page==0){page=1;drawer.aim(1f,MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D);}
                return true;
            }
            if(code==KeyEvent.KEYCODE_DPAD_UP && !moveDock && position<6){
                catalog.moveGridToDock(position,position%6);
                moveDock=true;focusDrawerDock=true;dockFocus=catalog.dock.indexOf(picked);
            }else if(code==KeyEvent.KEYCODE_DPAD_DOWN && moveDock){
                int to=Math.min(position,catalog.grid.size());
                catalog.moveDockToGrid(position,to);
                moveDock=false;focusDrawerDock=false;gridFocus=catalog.grid.indexOf(picked);
                page=1;drawer.aim(1f,MotionProfile.DRAWER_OPEN_K,MotionProfile.DRAWER_OPEN_D);
            }else{
                if(code==KeyEvent.KEYCODE_DPAD_LEFT && position>0)target--;
                else if(code==KeyEvent.KEYCODE_DPAD_RIGHT && position+1<count)target++;
                else if(code==KeyEvent.KEYCODE_DPAD_UP && !moveDock)target=Math.max(0,position-6);
                else if(code==KeyEvent.KEYCODE_DPAD_DOWN && !moveDock)target=Math.min(count-1,position+6);
                if(target==position)return true;
                if(moveDock){catalog.moveDock(position,target);dockFocus=catalog.dock.indexOf(picked);}
                else{catalog.moveGrid(position,target);gridFocus=catalog.grid.indexOf(picked);}
            }
            if(!moveDock){
                int row=gridFocus/6;
                if(row<gridFirstRow)gridFirstRow=row;
                if(row>=gridFirstRow+UiGeometry.VISIBLE_ROWS)gridFirstRow=row-UiGeometry.VISIBLE_ROWS+1;
                gridScroll.aim(gridFirstRow);
            }
            reflow(true,before);reflow(false,before);return true;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            String before=focusSoundTarget();boolean handled=handleTouchEvent(event);
            if(event.getAction()==MotionEvent.ACTION_DOWN)playFocusChange(before);
            return handled;
        }
        private boolean handleTouchEvent(MotionEvent event){
            if(event.getAction()==MotionEvent.ACTION_CANCEL){holdingOK=false;handler.removeCallbacks(holdAction);press.aim(0f);invalidate();return true;}
            if(event.getAction()!=MotionEvent.ACTION_UP&&event.getAction()!=MotionEvent.ACTION_DOWN)return true;
            float scale=Math.min(getWidth()/UiGeometry.WIDTH,getHeight()/UiGeometry.HEIGHT);
            if(scale<=0)return true;
            float x=(event.getX()-(getWidth()-UiGeometry.WIDTH*scale)/2f)/scale;
            float y=(event.getY()-(getHeight()-UiGeometry.HEIGHT*scale)/2f)/scale;
            if(event.getAction()==MotionEvent.ACTION_DOWN){
                if(dialogAction==null&&menu==null){
                    if(page==0&&headerHit(x,y)>=0){focusHeader=true;headerSelection=headerHit(x,y);updateFocus();}
                    else if(y>docTop()&&y<docTop()+UiGeometry.DOCK_HEIGHT){dockFocus=columnAt(x);focusHeader=false;if(page==1)focusDrawerDock=true;}
                    else if(page==1){int rowIndex=rowAt(y);int idx=Math.max(0,rowIndex)*6+columnAt(x);if(idx<catalog.grid.size()){gridFocus=idx;focusDrawerDock=false;}}
                }else if(menu!=null&&menuRowAt(menu,x,y)>=0){
                    focusMenuRow(menuRowAt(menu,x,y));
                }
                holdingOK=true;longPressed=false;pressStartedInDialog=dialogAction!=null;pressStartedInMenu=menu!=null&&menu.selected>=0;press.aim(1f);
                if(dialogAction==null&&menu==null)handler.postDelayed(holdAction,440);
                invalidate();return true;
            }
            holdingOK=false;handler.removeCallbacks(holdAction);press.aim(0f);invalidate();
            if(longPressed){longPressed=false;return true;}
            if(menu!=null){
                int row=menuRowAt(menu,x,y);
                int header=menu.sourceHeader>=0?headerHit(x,y):-1;
                if(row>=0){focusMenuRow(row);selectMenuItem();}
                else if(header>=0){if(header!=menu.sourceHeader)closeMenu();headerSelection=header;activateHeader();}
                else closeMenu();
                return true;
            }
            if(dialogAction!=null){
                RectF panel=dialogRect();
                boolean fire=!dialogButton.isEmpty()&&dialogButtonRect(panel,1).contains(x,y);
                if(fire||dialogButtonRect(panel,0).contains(x,y)){
                    if(uiSounds!=null)uiSounds.press();
                    Runnable action=dialogAction;dismissDialog();if(fire)action.run();
                }
                return true;
            }
            if(page==0&&headerHit(x,y)>=0){headerSelection=headerHit(x,y);activateHeader();return true;}
            float top=docTop();
            if(y>top&&y<top+UiGeometry.DOCK_HEIGHT){
                int idx=columnAt(x);dockFocus=idx;focusHeader=false;
                if(page==1)focusDrawerDock=true;
                shortPress();invalidate();return true;
            }
            if(page==1){
                int rowIndex=rowAt(y);
                int idx=Math.max(0,rowIndex)*6+columnAt(x);
                if(idx<catalog.grid.size()){
                    gridFocus=idx;focusDrawerDock=false;shortPress();invalidate();
                }
            }
            return true;
        }
        private int menuRowAt(Menu m,float x,float y){
            RectF rect=menuRect(m);
            float offset=y-rect.top-MENU_HEADER;
            if(x<rect.left+MENU_PAD||x>=rect.right-MENU_PAD||offset<0)return -1;
            int row=(int)(offset/MENU_ROW);
            return row<m.items.size()&&offset-row*MENU_ROW<MENU_ITEM_HEIGHT?row:-1;
        }
        private int rowAt(float y){
            int nearest=0;float distance=Float.MAX_VALUE;
            for(int row=0;row<(catalog.grid.size()+5)/6;row++){
                float center=UiGeometry.rowTop(row,gridScroll.value)+71+rowOffset(row);
                if(Math.abs(y-center)<distance){nearest=row;distance=Math.abs(y-center);}
            }
            return nearest;
        }
        private int columnAt(float x){
            int result=0;float distance=Float.MAX_VALUE;
            for(int i=0;i<6;i++){
                float d=Math.abs(x-UiGeometry.centerX(i));
                if(d<distance){distance=d;result=i;}
            }
            return result;
        }
    }
}
