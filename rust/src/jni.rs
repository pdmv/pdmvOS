//! Zero-dependency JNI bindings for `dev.pdmv.os.NativeCore`.
//! Uses the standard JNI 1.6 `JNINativeInterface_` function table directly.

#![allow(non_snake_case, non_camel_case_types)]

use core::ffi::{c_char, c_void};

pub type jboolean = u8;
pub type jint = i32;
pub type jlong = i64;
pub type jfloat = f32;
pub type jdouble = f64;
pub type jsize = i32;
pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jarray = jobject;
pub type jintArray = jarray;
pub type jlongArray = jarray;
pub type jfloatArray = jarray;

pub type JNIEnv = *mut *const *const c_void;

const JNI_FALSE: jboolean = 0;
const JNI_TRUE: jboolean = 1;
const JNI_ABORT: jint = 2;

// Exact vtable indices in struct JNINativeInterface_ (JNI 1.6 specification).
const IDX_FIND_CLASS: usize = 6;
const IDX_THROW_NEW: usize = 14;
const IDX_NEW_STRING_UTF: usize = 167;
const IDX_GET_ARRAY_LENGTH: usize = 171;
const IDX_GET_INT_ARRAY_ELEMENTS: usize = 187;
const IDX_RELEASE_INT_ARRAY_ELEMENTS: usize = 195;
const IDX_GET_INT_ARRAY_REGION: usize = 203;
const IDX_GET_LONG_ARRAY_REGION: usize = 204;
const IDX_GET_FLOAT_ARRAY_REGION: usize = 205;
const IDX_SET_INT_ARRAY_REGION: usize = 211;
const IDX_SET_LONG_ARRAY_REGION: usize = 212;
const IDX_SET_FLOAT_ARRAY_REGION: usize = 213;

type FnFindClass = unsafe extern "system" fn(JNIEnv, *const c_char) -> jclass;
type FnThrowNew = unsafe extern "system" fn(JNIEnv, jclass, *const c_char) -> jint;
type FnNewStringUTF = unsafe extern "system" fn(JNIEnv, *const c_char) -> jstring;
type FnGetArrayLength = unsafe extern "system" fn(JNIEnv, jarray) -> jsize;
type FnGetIntArrayElements =
    unsafe extern "system" fn(JNIEnv, jintArray, *mut jboolean) -> *mut jint;
type FnReleaseIntArrayElements =
    unsafe extern "system" fn(JNIEnv, jintArray, *mut jint, jint);
type FnGetIntArrayRegion =
    unsafe extern "system" fn(JNIEnv, jintArray, jsize, jsize, *mut jint);
type FnGetLongArrayRegion =
    unsafe extern "system" fn(JNIEnv, jlongArray, jsize, jsize, *mut jlong);
type FnGetFloatArrayRegion =
    unsafe extern "system" fn(JNIEnv, jfloatArray, jsize, jsize, *mut jfloat);
type FnSetIntArrayRegion =
    unsafe extern "system" fn(JNIEnv, jintArray, jsize, jsize, *const jint);
type FnSetLongArrayRegion =
    unsafe extern "system" fn(JNIEnv, jlongArray, jsize, jsize, *const jlong);
type FnSetFloatArrayRegion =
    unsafe extern "system" fn(JNIEnv, jfloatArray, jsize, jsize, *const jfloat);

#[inline]
unsafe fn vtable_fn<F: Copy>(env: JNIEnv, index: usize) -> F {
    let table = *env;
    let slot = *table.add(index);
    core::mem::transmute_copy(&slot)
}

unsafe fn throw_illegal_argument(env: JNIEnv, msg_cstr: &[u8]) {
    let find_class: FnFindClass = vtable_fn(env, IDX_FIND_CLASS);
    let throw_new: FnThrowNew = vtable_fn(env, IDX_THROW_NEW);
    let cls = find_class(
        env,
        b"java/lang/IllegalArgumentException\0".as_ptr() as *const c_char,
    );
    if !cls.is_null() {
        throw_new(env, cls, msg_cstr.as_ptr() as *const c_char);
    }
}

unsafe fn get_int_vec(env: JNIEnv, arr: jintArray) -> Vec<i32> {
    if arr.is_null() {
        return Vec::new();
    }
    let get_len: FnGetArrayLength = vtable_fn(env, IDX_GET_ARRAY_LENGTH);
    let len = get_len(env, arr).max(0) as usize;
    let mut buf = vec![0i32; len];
    if len > 0 {
        let get_region: FnGetIntArrayRegion = vtable_fn(env, IDX_GET_INT_ARRAY_REGION);
        get_region(env, arr, 0, len as jsize, buf.as_mut_ptr());
    }
    buf
}

unsafe fn get_float_vec(env: JNIEnv, arr: jfloatArray) -> Vec<f32> {
    if arr.is_null() {
        return Vec::new();
    }
    let get_len: FnGetArrayLength = vtable_fn(env, IDX_GET_ARRAY_LENGTH);
    let len = get_len(env, arr).max(0) as usize;
    let mut buf = vec![0.0f32; len];
    if len > 0 {
        let get_region: FnGetFloatArrayRegion = vtable_fn(env, IDX_GET_FLOAT_ARRAY_REGION);
        get_region(env, arr, 0, len as jsize, buf.as_mut_ptr());
    }
    buf
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_pixelBlurApply(
    env: JNIEnv,
    _class: jclass,
    pixels: jintArray,
    scratch: jintArray,
    width: jint,
    height: jint,
    radius: jint,
) {
    if pixels.is_null() || scratch.is_null() || width <= 0 || height <= 0 || radius < 0 {
        return;
    }
    let w = width as usize;
    let h = height as usize;
    let r = radius as usize;
    let total = w.saturating_mul(h);

    let get_len: FnGetArrayLength = vtable_fn(env, IDX_GET_ARRAY_LENGTH);
    if (get_len(env, pixels) as usize) < total || (get_len(env, scratch) as usize) < total {
        return;
    }

    let get_elems: FnGetIntArrayElements = vtable_fn(env, IDX_GET_INT_ARRAY_ELEMENTS);
    let rel_elems: FnReleaseIntArrayElements = vtable_fn(env, IDX_RELEASE_INT_ARRAY_ELEMENTS);

    let pix_ptr = get_elems(env, pixels, core::ptr::null_mut());
    if pix_ptr.is_null() {
        return;
    }
    let scr_ptr = get_elems(env, scratch, core::ptr::null_mut());
    if scr_ptr.is_null() {
        rel_elems(env, pixels, pix_ptr, JNI_ABORT);
        return;
    }

    let pix_slice = core::slice::from_raw_parts_mut(pix_ptr, total);
    let scr_slice = core::slice::from_raw_parts_mut(scr_ptr, total);
    crate::blur::apply(pix_slice, scr_slice, w, h, r);

    rel_elems(env, scratch, scr_ptr, JNI_ABORT);
    rel_elems(env, pixels, pix_ptr, 0);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_pixelBlurBlend(
    env: JNIEnv,
    _class: jclass,
    low: jintArray,
    high: jintArray,
    out: jintArray,
    length: jint,
    alpha: jint,
) {
    if low.is_null() || high.is_null() || out.is_null() || length <= 0 {
        return;
    }
    let len = length as usize;
    let get_len: FnGetArrayLength = vtable_fn(env, IDX_GET_ARRAY_LENGTH);
    if (get_len(env, low) as usize) < len
        || (get_len(env, high) as usize) < len
        || (get_len(env, out) as usize) < len
    {
        return;
    }
    let get_elems: FnGetIntArrayElements = vtable_fn(env, IDX_GET_INT_ARRAY_ELEMENTS);
    let rel_elems: FnReleaseIntArrayElements = vtable_fn(env, IDX_RELEASE_INT_ARRAY_ELEMENTS);

    let low_ptr = get_elems(env, low, core::ptr::null_mut());
    if low_ptr.is_null() {
        return;
    }
    let high_ptr = get_elems(env, high, core::ptr::null_mut());
    if high_ptr.is_null() {
        rel_elems(env, low, low_ptr, JNI_ABORT);
        return;
    }
    let out_ptr = get_elems(env, out, core::ptr::null_mut());
    if out_ptr.is_null() {
        rel_elems(env, high, high_ptr, JNI_ABORT);
        rel_elems(env, low, low_ptr, JNI_ABORT);
        return;
    }

    let low_slice = core::slice::from_raw_parts(low_ptr, len);
    let high_slice = core::slice::from_raw_parts(high_ptr, len);
    let out_slice = core::slice::from_raw_parts_mut(out_ptr, len);
    crate::blur::blend_opaque(low_slice, high_slice, out_slice, alpha);

    rel_elems(env, out, out_ptr, 0);
    rel_elems(env, high, high_ptr, JNI_ABORT);
    rel_elems(env, low, low_ptr, JNI_ABORT);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkDominantFillColor(
    env: JNIEnv,
    _class: jclass,
    samples: jintArray,
    fallback: jint,
) -> jint {
    let data = get_int_vec(env, samples);
    crate::artwork::dominant_fill_color(&data, fallback)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkControlFocusTint(
    env: JNIEnv,
    _class: jclass,
    samples: jintArray,
) -> jint {
    let data = get_int_vec(env, samples);
    crate::artwork::control_focus_tint(&data)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkFallbackTextColor(
    _env: JNIEnv,
    _class: jclass,
    background_argb: jint,
) -> jint {
    crate::artwork::fallback_text_color(background_argb)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkStep(
    _env: JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
) -> jint {
    match crate::artwork::sample_bounds(width, height) {
        Some((_, _, _, _, step)) => step,
        None => 0,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkHasDetailTriplets(
    env: JNIEnv,
    _class: jclass,
    triplets: jintArray,
) -> jboolean {
    let data = get_int_vec(env, triplets);
    if crate::artwork::has_detail_triplets(&data) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkLayoutFit(
    env: JNIEnv,
    _class: jclass,
    source_width: jint,
    source_height: jint,
    box_width: jfloat,
    box_height: jfloat,
    banner: jboolean,
    out4: jfloatArray,
) {
    match crate::artwork::fit_layout(
        source_width,
        source_height,
        box_width,
        box_height,
        banner != JNI_FALSE,
    ) {
        Ok(b) => {
            let buf = [b.left, b.top, b.right, b.bottom];
            let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
            set_region(env, out4, 0, 4, buf.as_ptr());
        }
        Err(_) => {
            throw_illegal_argument(env, b"invalid canvas size\0");
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkTransformScale(
    env: JNIEnv,
    _class: jclass,
    destination_size: jfloat,
    source_size: jfloat,
) -> jfloat {
    match crate::artwork::transform_scale(destination_size, source_size) {
        Ok(s) => s,
        Err(_) => {
            throw_illegal_argument(env, b"Artwork dimensions must be positive\0");
            0.0
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkTransformSourceRadius(
    env: JNIEnv,
    _class: jclass,
    design_radius: jfloat,
    source_width: jfloat,
    destination_width: jfloat,
) -> jfloat {
    match crate::artwork::transform_source_radius(design_radius, source_width, destination_width) {
        Ok(r) => r,
        Err(_) => {
            throw_illegal_argument(env, b"Artwork dimensions must be positive\0");
            0.0
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkSmoothCorners(
    env: JNIEnv,
    _class: jclass,
    source: jintArray,
    masked: jintArray,
    width: jint,
    height: jint,
    gutter: jint,
    radius: jfloat,
    n: jfloat,
) {
    let src = get_int_vec(env, source);
    let mut dst = get_int_vec(env, masked);
    crate::artwork::smooth_squircle_corners(&src, &mut dst, width, height, gutter, radius, n);
    let set_region: FnSetIntArrayRegion = vtable_fn(env, IDX_SET_INT_ARRAY_REGION);
    set_region(env, masked, 0, dst.len() as jsize, dst.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_displayPolicySelect(
    env: JNIEnv,
    _class: jclass,
    widths: jintArray,
    heights: jintArray,
    rates: jfloatArray,
    current: jint,
) -> jint {
    let w = get_int_vec(env, widths);
    let h = get_int_vec(env, heights);
    let r = get_float_vec(env, rates);
    match crate::policy::select_display_mode(&w, &h, &r, current) {
        Ok(best) => best as jint,
        Err(_) => {
            throw_illegal_argument(env, b"modes\0");
            0
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_springUpdate(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    target: jfloat,
    velocity: jfloat,
    stiffness: jfloat,
    damping: jfloat,
    dt: jfloat,
) -> jlong {
    let (nv, nvel) = crate::spring::step_spring(value, target, velocity, stiffness, damping, dt);
    crate::spring::pack_f32_pair(nv, nvel)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_drawerFlowUpdate(
    env: JNIEnv,
    _class: jclass,
    state8: jfloatArray,
    target: jfloat,
    elapsed: jfloat,
) -> jboolean {
    let mut buf = [0.0f32; 8];
    let get_region: FnGetFloatArrayRegion = vtable_fn(env, IDX_GET_FLOAT_ARRAY_REGION);
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    get_region(env, state8, 0, 8, buf.as_mut_ptr());

    let mut flow = crate::motion::DrawerFlow::new();
    flow.dock.value = buf[0];
    flow.dock.velocity = buf[1];
    for i in 0..3 {
        flow.rows[i].value = buf[2 + i * 2];
        flow.rows[i].velocity = buf[3 + i * 2];
    }
    let busy = flow.update(target, elapsed);
    buf[0] = flow.dock.value;
    buf[1] = flow.dock.velocity;
    for i in 0..3 {
        buf[2 + i * 2] = flow.rows[i].value;
        buf[3 + i * 2] = flow.rows[i].velocity;
    }
    set_region(env, state8, 0, 8, buf.as_ptr());
    if busy {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_drawerFlowRowProgress(
    _env: JNIEnv,
    _class: jclass,
    r0: jfloat,
    r1: jfloat,
    r2: jfloat,
    relative_row: jfloat,
) -> jfloat {
    crate::motion::row_progress_from(r0, r1, r2, relative_row)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_elasticApproachMoved(
    _env: JNIEnv,
    _class: jclass,
    direction: jint,
    last_move: jlong,
    speed: jfloat,
    next: jint,
    time: jlong,
) -> jfloat {
    let mut app = crate::motion::elastic::Approach {
        direction,
        last_move,
        speed,
    };
    app.moved(next, time);
    app.speed
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_elasticApproachSpeed(
    _env: JNIEnv,
    _class: jclass,
    direction: jint,
    last_move: jlong,
    speed: jfloat,
    next: jint,
    time: jlong,
) -> jfloat {
    let app = crate::motion::elastic::Approach {
        direction,
        last_move,
        speed,
    };
    app.speed(next, time)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_elasticAxisAim(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    velocity: jfloat,
    direction: jfloat,
    next: jfloat,
) -> jlong {
    let mut axis = crate::motion::elastic::Axis {
        value,
        velocity,
        direction,
    };
    axis.aim(next);
    crate::spring::pack_f32_pair(axis.velocity, axis.direction)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_elasticAxisPress(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    velocity: jfloat,
    direction: jfloat,
    next: jfloat,
    approach_speed: jfloat,
) -> jlong {
    let mut axis = crate::motion::elastic::Axis {
        value,
        velocity,
        direction,
    };
    axis.press(next, approach_speed);
    crate::spring::pack_f32_pair(axis.velocity, axis.direction)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_elasticAxisUpdate(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    velocity: jfloat,
    direction: jfloat,
    dt: jfloat,
) -> jlong {
    let mut axis = crate::motion::elastic::Axis {
        value,
        velocity,
        direction,
    };
    axis.update(dt);
    crate::spring::pack_f32_pair(axis.value, axis.velocity)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_elasticAxisBusy(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    velocity: jfloat,
    direction: jfloat,
) -> jboolean {
    let axis = crate::motion::elastic::Axis {
        value,
        velocity,
        direction,
    };
    if axis.busy() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_focusDepthKickX(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    velocity: jfloat,
    dx: jint,
) -> jlong {
    let (v, vel) = crate::motion::kick_horizontal(value, velocity, dx);
    crate::spring::pack_f32_pair(v, vel)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_focusDepthKickY(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    velocity: jfloat,
    dy: jint,
) -> jlong {
    let (v, vel) = crate::motion::kick_vertical(value, velocity, dy);
    crate::spring::pack_f32_pair(v, vel)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_motionArrangeAngle(
    _env: JNIEnv,
    _class: jclass,
    seconds: jdouble,
    identity: jint,
    selected: jboolean,
) -> jfloat {
    crate::motion::profile::arrange_angle(seconds, identity, selected != JNI_FALSE)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_motionReorderTilt(
    _env: JNIEnv,
    _class: jclass,
    vx: jfloat,
    vy: jfloat,
    selected: jboolean,
) -> jfloat {
    crate::motion::profile::reorder_tilt(vx, vy, selected != JNI_FALSE)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_motionStrain(
    _env: JNIEnv,
    _class: jclass,
    velocity: jfloat,
    reference_speed: jfloat,
    maximum: jfloat,
) -> jfloat {
    crate::motion::profile::strain(velocity, reference_speed, maximum)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_motionStepExponential(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    target: jfloat,
    dt: jfloat,
    tau: jfloat,
    epsilon: jfloat,
) -> jfloat {
    crate::motion::profile::step_exponential(value, target, dt, tau, epsilon)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_motionClockCharTransition(
    env: JNIEnv,
    _class: jclass,
    progress: jfloat,
    slot: jint,
    total_slots: jint,
    travel_y: jfloat,
    out7: jfloatArray,
) {
    let mut buf = [0.0f32; 7];
    crate::motion::profile::clock_char_transition(progress, slot, total_slots, travel_y, &mut buf);
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out7, 0, 7, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_motionResolveTileTransform(
    env: JNIEnv,
    _class: jclass,
    x: jfloat,
    y: jfloat,
    scale_val: jfloat,
    pushed: jfloat,
    presence_val: jfloat,
    retired: jboolean,
    exiting: jboolean,
    selected: jboolean,
    traveling: jboolean,
    dock: jboolean,
    edge_stretched: jboolean,
    canvas_left: jfloat,
    canvas_top: jfloat,
    canvas_scale: jfloat,
    edge_x: jfloat,
    edge_y: jfloat,
    edge_anchor_x: jfloat,
    edge_anchor_y: jfloat,
    vx: jfloat,
    vy: jfloat,
    scroll_strain: jfloat,
    offset_x: jfloat,
    offset_y: jfloat,
    drawer_val: jfloat,
    hidden_for_morph: jboolean,
    out8: jfloatArray,
) {
    let mut buf = [0.0f32; 8];
    crate::motion::profile::resolve_tile_transform(
        x,
        y,
        scale_val,
        pushed,
        presence_val,
        retired != JNI_FALSE,
        exiting != JNI_FALSE,
        selected != JNI_FALSE,
        traveling != JNI_FALSE,
        dock != JNI_FALSE,
        edge_stretched != JNI_FALSE,
        canvas_left,
        canvas_top,
        canvas_scale,
        edge_x,
        edge_y,
        edge_anchor_x,
        edge_anchor_y,
        vx,
        vy,
        scroll_strain,
        offset_x,
        offset_y,
        drawer_val,
        hidden_for_morph != JNI_FALSE,
        &mut buf,
    );
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out8, 0, 8, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_menuMotionResolve(
    env: JNIEnv,
    _class: jclass,
    progress: jfloat,
    velocity: jfloat,
    source_x: jfloat,
    source_y: jfloat,
    target_x: jfloat,
    target_y: jfloat,
    height: jfloat,
    out6: jfloatArray,
) {
    let f = crate::motion::menu::resolve(
        progress, velocity, source_x, source_y, target_x, target_y, height,
    );
    let buf = [f.x, f.y, f.scale_x, f.scale_y, f.alpha, f.clarity];
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out6, 0, 6, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_menuMotionResolveFromScale(
    env: JNIEnv,
    _class: jclass,
    progress: jfloat,
    velocity: jfloat,
    source_x: jfloat,
    source_y: jfloat,
    target_x: jfloat,
    target_y: jfloat,
    start: jfloat,
    out6: jfloatArray,
) {
    let f = crate::motion::menu::resolve_from_scale(
        progress, velocity, source_x, source_y, target_x, target_y, start,
    );
    let buf = [f.x, f.y, f.scale_x, f.scale_y, f.alpha, f.clarity];
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out6, 0, 6, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_homeReturnPosition(
    _env: JNIEnv,
    _class: jclass,
    source: jfloat,
    destination: jfloat,
    p: jfloat,
) -> jfloat {
    crate::motion::home_return::position(source, destination, p)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_homeReturnScale(
    _env: JNIEnv,
    _class: jclass,
    p: jfloat,
) -> jfloat {
    crate::motion::home_return::scale(p)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_homeReturnBackdropOpacity(
    _env: JNIEnv,
    _class: jclass,
    p: jfloat,
) -> jfloat {
    crate::motion::home_return::backdrop_opacity(p)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_surfaceMorphArtworkProgress(
    _env: JNIEnv,
    _class: jclass,
    progress: jfloat,
) -> jfloat {
    crate::motion::surface_morph::artwork_progress(progress)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_surfaceMorphArtworkCornerExponent(
    _env: JNIEnv,
    _class: jclass,
    progress: jfloat,
) -> jfloat {
    crate::motion::surface_morph::artwork_corner_exponent(progress)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_surfaceMorphEase(
    _env: JNIEnv,
    _class: jclass,
    progress: jfloat,
) -> jfloat {
    crate::motion::surface_morph::ease(progress)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_surfaceMorphBetween(
    env: JNIEnv,
    _class: jclass,
    l0: jfloat,
    t0: jfloat,
    r0: jfloat,
    b0: jfloat,
    l1: jfloat,
    t1: jfloat,
    r1: jfloat,
    b1: jfloat,
    progress: jfloat,
    out4: jfloatArray,
) {
    let b = crate::motion::surface_morph::between(l0, t0, r0, b0, l1, t1, r1, b1, progress);
    let buf = [b.left, b.top, b.right, b.bottom];
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out4, 0, 4, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_surfaceMorphResolveRenderFrame(
    env: JNIEnv,
    _class: jclass,
    origin_l: jfloat,
    origin_t: jfloat,
    origin_r: jfloat,
    origin_b: jfloat,
    progress: jfloat,
    opening: jboolean,
    morph_fill: jint,
    image_w: jfloat,
    image_h: jfloat,
    out12: jfloatArray,
) {
    let mut buf = [0.0f32; 12];
    crate::motion::surface_morph::resolve_render_frame(
        origin_l,
        origin_t,
        origin_r,
        origin_b,
        progress,
        opening != JNI_FALSE,
        morph_fill,
        image_w,
        image_h,
        &mut buf,
    );
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out12, 0, 12, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_artworkCenterCropRect(
    env: JNIEnv,
    _class: jclass,
    src_w: jint,
    src_h: jint,
    dst_w: jfloat,
    dst_h: jfloat,
    out4: jintArray,
) {
    let mut buf = [0i32; 4];
    crate::artwork::center_crop_rect(src_w, src_h, dst_w, dst_h, &mut buf);
    let set_region: FnSetIntArrayRegion = vtable_fn(env, IDX_SET_INT_ARRAY_REGION);
    set_region(env, out4, 0, 4, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryMenuRect(
    env: JNIEnv,
    _class: jclass,
    item_count: jint,
    source_header: jint,
    near_tile: jboolean,
    anchor_x: jfloat,
    anchor_y: jfloat,
    out4: jfloatArray,
) {
    let mut buf = [0.0f32; 4];
    crate::geometry::menu_rect(
        item_count,
        source_header,
        near_tile != JNI_FALSE,
        anchor_x,
        anchor_y,
        &mut buf,
    );
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out4, 0, 4, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryDialogRect(
    env: JNIEnv,
    _class: jclass,
    line_count: jint,
    has_progress: jboolean,
    out4: jfloatArray,
) {
    let mut buf = [0.0f32; 4];
    crate::geometry::dialog_rect(line_count, has_progress != JNI_FALSE, &mut buf);
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out4, 0, 4, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryDialogButtonRect(
    env: JNIEnv,
    _class: jclass,
    panel_left: jfloat,
    panel_right: jfloat,
    panel_bottom: jfloat,
    two_buttons: jboolean,
    index: jint,
    out4: jfloatArray,
) {
    let mut buf = [0.0f32; 4];
    crate::geometry::dialog_button_rect(
        panel_left,
        panel_right,
        panel_bottom,
        two_buttons != JNI_FALSE,
        index,
        &mut buf,
    );
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out4, 0, 4, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryPopupItemScale(
    _env: JNIEnv,
    _class: jclass,
    focus: jfloat,
    press: jfloat,
) -> jfloat {
    crate::geometry::popup_item_scale(focus, press)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryPopupHeight(
    _env: JNIEnv,
    _class: jclass,
    count: jint,
) -> jfloat {
    crate::geometry::popup_height(count)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryBelowDock(
    _env: JNIEnv,
    _class: jclass,
    column: jint,
    first_visible_row: jint,
    item_count: jint,
) -> jint {
    crate::geometry::below_dock(column, first_visible_row, item_count)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryCenterX(
    env: JNIEnv,
    _class: jclass,
    col: jint,
) -> jfloat {
    match crate::geometry::center_x(col) {
        Ok(x) => x,
        Err(_) => {
            throw_illegal_argument(env, b"col out of bounds\0");
            0.0
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryRowTop(
    _env: JNIEnv,
    _class: jclass,
    row: jint,
    scroll_rows: jfloat,
) -> jfloat {
    crate::geometry::row_top(row, scroll_rows)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryGridTransitionOffset(
    _env: JNIEnv,
    _class: jclass,
    drawer_progress: jfloat,
) -> jfloat {
    crate::geometry::grid_transition_offset(drawer_progress)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryDrawerGridClipTop(
    _env: JNIEnv,
    _class: jclass,
    drawer_progress: jfloat,
) -> jfloat {
    crate::geometry::drawer_grid_clip_top(drawer_progress)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryGridClipBottom(
    _env: JNIEnv,
    _class: jclass,
    drawer_progress: jfloat,
) -> jfloat {
    crate::geometry::grid_clip_bottom(drawer_progress)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryDockTop(
    _env: JNIEnv,
    _class: jclass,
    drawer_progress: jfloat,
) -> jfloat {
    crate::geometry::dock_top(drawer_progress)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryClamp(
    _env: JNIEnv,
    _class: jclass,
    value: jfloat,
    low: jfloat,
    high: jfloat,
) -> jfloat {
    crate::geometry::clamp(value, low, high)
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometrySquircleVertices(
    env: JNIEnv,
    _class: jclass,
    left: jfloat,
    top: jfloat,
    right: jfloat,
    bottom: jfloat,
    radius: jfloat,
    n: jfloat,
    out266: jfloatArray,
) {
    let mut buf = [0.0f32; 266];
    crate::geometry::squircle_vertices(left, top, right, bottom, radius, n, &mut buf);
    let set_region: FnSetFloatArrayRegion = vtable_fn(env, IDX_SET_FLOAT_ARRAY_REGION);
    set_region(env, out266, 0, 266, buf.as_ptr());
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_geometryCheckInvariants(
    env: JNIEnv,
    _class: jclass,
) -> jstring {
    match crate::geometry::check_invariants() {
        Ok(()) => core::ptr::null_mut(),
        Err(msg) => {
            let mut bytes = msg.as_bytes().to_vec();
            bytes.push(0);
            let new_str: FnNewStringUTF = vtable_fn(env, IDX_NEW_STRING_UTF);
            new_str(env, bytes.as_ptr() as *const c_char)
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_wifiFeedbackObserve(
    env: JNIEnv,
    _class: jclass,
    state_announced: jintArray,
    state_pending_loss: jlongArray,
    current: jint,
    visible: jboolean,
    now: jlong,
) -> jint {
    let get_int: FnGetIntArrayRegion = vtable_fn(env, IDX_GET_INT_ARRAY_REGION);
    let set_int: FnSetIntArrayRegion = vtable_fn(env, IDX_SET_INT_ARRAY_REGION);
    let get_long: FnGetLongArrayRegion = vtable_fn(env, IDX_GET_LONG_ARRAY_REGION);
    let set_long: FnSetLongArrayRegion = vtable_fn(env, IDX_SET_LONG_ARRAY_REGION);

    let mut announced = [0i32; 1];
    let mut pending_loss = [-1i64; 1];
    get_int(env, state_announced, 0, 1, announced.as_mut_ptr());
    get_long(env, state_pending_loss, 0, 1, pending_loss.as_mut_ptr());

    let mut wf = crate::policy::wifi::WifiFeedback {
        announced: announced[0],
        pending_loss: pending_loss[0],
    };
    let event = wf.observe(current, visible != JNI_FALSE, now);
    announced[0] = wf.announced;
    pending_loss[0] = wf.pending_loss;
    set_int(env, state_announced, 0, 1, announced.as_ptr());
    set_long(env, state_pending_loss, 0, 1, pending_loss.as_ptr());
    event
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_returnGateRequest(
    _env: JNIEnv,
    _class: jclass,
    bits: jint,
) -> jint {
    let mut gate = crate::policy::ReturnSoundGate::from_bits(bits);
    gate.request();
    gate.to_bits()
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_returnGateSetActive(
    _env: JNIEnv,
    _class: jclass,
    bits: jint,
    active: jboolean,
) -> jint {
    let mut gate = crate::policy::ReturnSoundGate::from_bits(bits);
    gate.set_active(active != JNI_FALSE);
    gate.to_bits()
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_returnGatePending(
    _env: JNIEnv,
    _class: jclass,
    bits: jint,
) -> jboolean {
    if crate::policy::ReturnSoundGate::from_bits(bits).pending() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_returnGateCanPlay(
    _env: JNIEnv,
    _class: jclass,
    bits: jint,
    loaded: jboolean,
    enabled: jboolean,
) -> jboolean {
    if crate::policy::ReturnSoundGate::from_bits(bits)
        .can_play(loaded != JNI_FALSE, enabled != JNI_FALSE)
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_returnGateRetry(
    _env: JNIEnv,
    _class: jclass,
    bits: jint,
) -> jlong {
    let mut gate = crate::policy::ReturnSoundGate::from_bits(bits);
    let ok = if gate.retry() { 1u64 } else { 0u64 };
    let new_bits = (gate.to_bits() as u32) as u64;
    ((ok << 32) | new_bits) as jlong
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_returnGateConsume(
    _env: JNIEnv,
    _class: jclass,
    bits: jint,
) -> jint {
    let mut gate = crate::policy::ReturnSoundGate::from_bits(bits);
    gate.consume();
    gate.to_bits()
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_pdmv_os_NativeCore_slotLayoutOp(
    env: JNIEnv,
    _class: jclass,
    op: jint,
    arg1: jint,
    arg2: jint,
    dock6: jintArray,
    grid_buf: jintArray,
    grid_len: jint,
) -> jint {
    let get_int: FnGetIntArrayRegion = vtable_fn(env, IDX_GET_INT_ARRAY_REGION);
    let set_int: FnSetIntArrayRegion = vtable_fn(env, IDX_SET_INT_ARRAY_REGION);

    let mut dock_raw = [-1i32; 6];
    get_int(env, dock6, 0, 6, dock_raw.as_mut_ptr());
    let g_len = grid_len.max(0) as usize;
    let mut grid_raw = vec![0i32; g_len];
    if g_len > 0 {
        get_int(env, grid_buf, 0, g_len as jsize, grid_raw.as_mut_ptr());
    }

    let pinned: Vec<Option<i32>> = dock_raw
        .iter()
        .map(|&v| if v < 0 { None } else { Some(v) })
        .collect();
    let apps: Vec<Option<i32>> = grid_raw
        .iter()
        .map(|&v| if v < 0 { None } else { Some(v) })
        .collect();
    let mut layout = crate::policy::SlotLayout::new(&pinned, &apps);

    match op {
        // 0 = init/normalize, 2 = unpin, 3 = toGrid, 4 = toDock, 5 = moveDock, 6 = moveGrid
        0 => {}
        2 => {
            if !layout.unpin(arg1 as isize) {
                return -1;
            }
        }
        3 => {
            if !layout.to_grid(arg1 as isize, arg2 as isize) {
                return -1;
            }
        }
        4 => {
            if !layout.to_dock(arg1 as isize, arg2 as isize) {
                return -1;
            }
        }
        5 => {
            if !layout.move_dock(arg1 as isize, arg2 as isize) {
                return -1;
            }
        }
        6 => {
            if !layout.move_grid(arg1 as isize, arg2 as isize) {
                return -1;
            }
        }
        _ => return -1,
    }

    for (i, slot) in dock_raw.iter_mut().enumerate() {
        *slot = layout.dock.get(i).and_then(|&x| x).unwrap_or(-1);
    }
    set_int(env, dock6, 0, 6, dock_raw.as_ptr());

    let new_grid_len = layout.grid.len();
    if new_grid_len > 0 {
        set_int(
            env,
            grid_buf,
            0,
            new_grid_len as jsize,
            layout.grid.as_ptr(),
        );
    }
    new_grid_len as jint
}
