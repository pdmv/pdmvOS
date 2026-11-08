#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for shell in gradlew tools/*.sh; do bash -n "$shell"; done
./tests/check-default-launcher.sh
python3 tests/check_wallpaper_tool.py
python3 tests/check_commands.py
python3 tests/check_release_assets.py
python3 tests/check_bootstrap.py
bash -n pdmvos
export PATH="/opt/homebrew/opt/rustup/bin:/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$HOME/.cargo/bin:$PATH"
cargo test --manifest-path rust/Cargo.toml --quiet
cargo build --release --manifest-path rust/Cargo.toml --quiet
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" \
  app/src/main/java/dev/pdmv/os/{NativeCore,PixelBlur,DrawerFlow,SlotLayout,DisplayPolicy,UiGeometry,SpringValue,MotionProfile,FocusMotion,FocusDepth,ArtworkLayout,ArtworkDetector,SurfaceMorph,ArtworkTransform,HomeReturnMotion,ElasticBoundary,WifiFeedback,MenuMotion,ReturnSoundGate}.java \
  tests/{CheckPixelBlur,CheckDrawerFlow,CheckSlotLayout,CheckDisplayPolicy,CheckGeometry,CheckMotion,CheckFocusDepth,CheckFocusMotion,CheckArtworkLayout,CheckArtworkDetector,CheckDrawerMotion,CheckSurfaceMorph,CheckArtworkTransform,CheckHomeReturnMotion,CheckElasticBoundary,CheckWifiFeedback,CheckMenuMotion,CheckReturnSoundGate}.java
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckPixelBlur
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckDrawerFlow
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckSlotLayout
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckDisplayPolicy
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckGeometry
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckMotion
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckFocusDepth
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckFocusMotion
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckArtworkLayout
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckArtworkDetector
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckDrawerMotion
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckSurfaceMorph
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckArtworkTransform
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckHomeReturnMotion
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckElasticBoundary
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckWifiFeedback
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckMenuMotion
java -Djava.library.path=rust/target/release -cp "$OUT" dev.pdmv.os.CheckReturnSoundGate
if grep -R --include='*.java' -n 'disable-user\|pm uninstall\|pm clear' app/src/main/java; then
    echo 'Review dangerous package modifications above.' >&2;exit 1
fi
if grep -R --include='*.java' -n 'setFontFeatureSettings\|setElegantTextHeight\|setWordSpacing' app/src/main/java; then
    echo 'Unsupported/risky Android 9 typography call detected.' >&2;exit 1
fi
if grep -n 'prepareVisibleTiles' app/src/main/java/dev/pdmv/os/MainActivity.java >/dev/null; then
    echo 'Blocking full-catalog artwork preparation detected.' >&2;exit 1
fi
if grep -n 'loadNow' app/src/main/java/dev/pdmv/os/WallpaperStore.java >/dev/null; then
    echo 'Synchronous wallpaper decoding detected.' >&2;exit 1
fi
python3 - <<'PY'
from pathlib import Path
m=Path('app/src/main/AndroidManifest.xml').read_text()
assert 'targetSdk 35' in Path('app/build.gradle').read_text()
assert '--target-sdk-version 35' in Path('tools/build-offline.sh').read_text()
assert '<queries>' in m and 'android.media.tv.TvInputService' in m
assert 'android:exported="true"' in m
assert 'android.intent.category.HOME' in m
assert 'android.intent.category.LEANBACK_LAUNCHER' in m
assert 'android:launchMode="singleTask"' in m
s=Path('app/src/main/java/dev/pdmv/os/MainActivity.java').read_text()
for needle in ['scheduleArtworkWarmup()', 'showStartupFallback', 'render.failed', 'startup.complete',
               'prepareTileBitmap', 'drawArtworkLabelFallback', 'postOnAnimation(artworkStep)',
               'c.drawRoundRect(x,y,x+w,y+h,capsuleRadius,capsuleRadius,p)']:
    assert needle in s, needle
assert 'getWindow().getInsetsController()' not in s, 'Decor must exist before obtaining insets controller on Android 11'
assert 'ArtworkLayout.fit(' in s, 'Aspect-preserving banner layout missing'
assert 'ArtworkDetector.hasDetail(' in s, 'Flat banner fallback missing'
assert 'tile.shader.setLocalMatrix' not in s, 'Regression: vendor-specific shader matrix returned'
assert 'ArtworkTransform.sourceRadius(' in s, 'Image corners must share a transform with source texture'
assert 'maskArtwork(bitmap)' in s and 'drawSquircle(c,0f,0f,TILE_BITMAP_W,TILE_BITMAP_H,sourceRadius,paint)' in s, 'Missing cached antialiased texture preparation'
assert 'c.drawBitmap(tile.image,null,bounds,paint)' in s, 'Tile rendering must use the prepared texture' 
assert 'if(!moveMode)' in s, 'Modal menu must preserve background focus visual'
assert '!motionWasActive' in s and 'Math.min(elapsed,.25f)' in s, 'Real elapsed animation time missing'
assert 'drawHeader' in s and 'for(int i:new int[]{0,2,3})' in s, 'Grouped header controls missing'
assert 'bluetoothPresence' not in s, 'Bluetooth button returned'
assert 'listBlur.update(dt)' in s, 'Animated app-list blur missing'
launch=s[s.index('boolean queueExternalLaunch('):s.index('void resetLaunchMotion(')]
assert 'pendingLaunch=action' in launch and 'launchReveal.aim(1f' in launch, 'Launch must wait for full-screen morph'
assert 'Di chuyển để sắp xếp ứng dụng' in s and 'Không tìm thấy đầu vào nào' in s, 'Action feedback missing'
assert 'drawControlCenter' not in s, 'Obsolete Control Center returned'
assert 'if(drawer.value>1f)' in s, 'Drawer overshoot guard missing'
assert 'tileFocus.select(' in s and 'labelFocus.select(' in s, 'Parallel focus handoff missing'
assert 'for(Tile t:catalog.all){\n                t.scale.aim(0f' not in s, 'Per-frame focus reset detected'

# Home entry and row wrapping are checked behaviorally by navigation-launcher.
assert 'replaceSlot=dockFocus;enterDrawer(false);gridFocus=0;' in s, 'Pin chooser must start from first available app'
native=Path('app/src/main/java/dev/pdmv/os/NativeSurfaceView.java').read_text()
assert 'lockHardwareCanvas' not in native, 'Blocking secondary buffer queue returned'
assert 'extends ViewGroup' in native and 'attachSceneNode(' in native and 'overlayViewport()' in native, 'Independent HWUI layers missing'
assert 'menuContent.update(dt)' in s and 't.tilt.update(dt)' in s, 'Independent physical channels missing'
assert 'setLinearText(true)' in s and 'Paint.HINTING_OFF' in s, 'Stable fractional typography missing'
assert 'MotionProfile.arrangeAngle(' in s and 'active=moveMode||active' in s, 'Arrange ring must animate only in move mode'
assert 'MENU_SELECTION_K' in Path('app/src/main/java/dev/pdmv/os/MotionProfile.java').read_text()
assert 'menuSelection.aim(row)' in s, 'Dropdown focus selection must spring between rows'
assert 'm.sourceHeader=2;m.selected=-1' in s, 'Output must open with focus on its pill button'
assert 'int wholeTile=c.save()' in s and 'c.restoreToCount(wholeTile)' in s, 'Shortcut icon/text must share press transform'
assert 'private static final int TILE_BITMAP_W=504, TILE_BITMAP_H=284' in s, 'Two-times native tile texture size missing'
assert 'drawArtworkMorph(' in s and 'currentTileBounds(' in s and 'queueExternalLaunch(' in s, 'Source-anchored transitions missing'
assert 'google_sans_flex_regular.ttf' in s, 'Static font support missing on Android 9'
assert 'return 0f;' in s, 'Manual typography tracking should be disabled'
assert 'size()>96' in s, 'Dynamic path cache must stay bounded'
assert 'drawReturnMorph(c)' in s and 'onReturnToHome()' in s, 'Return-to-Home animation missing'
assert 'externalLaunchInProgress=true' in s and 'externalLaunchInProgress)leftForExternalApp=true' in s, 'Handoff lifecycle tracking missing'
print('PASS: startup, GPU artwork AA, parallel focus, popup spring, shared press transforms, return morph, typography')
PY
echo 'PASS: scripts, layout geometry, contextual frame-independent motion model, startup safety, no launcher disabling in app code'

python3 - <<'PY'
from pathlib import Path
for source, target in [("LICENSE", "MIT.txt"), ("NOTICE.md", "NOTICE.txt"), ("ASSETS_LICENSE.md", "ASSETS_LICENSE.txt")]:
    assert Path(source).read_bytes() == (Path("app/src/main/assets/licenses") / target).read_bytes(), source
PY
