use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=src/airplay/eld.c");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    let target = std::env::var("TARGET").unwrap();
    let codec = PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap())
        .join("target/airplay-aac")
        .join(target);
    for path in [&codec, &codec.join("libavcodec"), &codec.join("libavutil")] {
        println!("cargo:rustc-link-search=native={}", path.display());
    }
    for archive in [
        "libpdmvos_eld.a",
        "libavcodec/libavcodec.a",
        "libavutil/libavutil.a",
    ] {
        println!("cargo:rerun-if-changed={}", codec.join(archive).display());
    }
    println!("cargo:rustc-link-lib=static=pdmvos_eld");
    println!("cargo:rustc-link-lib=static=avcodec");
    println!("cargo:rustc-link-lib=static=avutil");
    println!("cargo:rustc-link-lib=m");
}
