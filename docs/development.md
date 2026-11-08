# Phát triển

## Môi trường

- JDK 17.
- Android SDK platform 35, build-tools 34.0.0 và Android NDK 27.
- Rust (`rustup`, `cargo`) kèm các target Android (`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`).
- Python 3.9 trở lên.
- Android Platform Tools để làm việc với thiết bị.

Đặt `JAVA_HOME`, `ANDROID_HOME` theo môi trường của bạn. Gradle Wrapper ghim Gradle 8.9 và kiểm tra checksum của bản phân phối.

```sh
./tools/validate.sh
./gradlew :app:assembleDebug
# Windows
# gradlew.bat :app:assembleDebug
```

APK debug nằm ở `app/build/outputs/apk/debug/app-debug.apk`. Khóa debug dành cho môi trường phát triển; bản release chính thức dùng chứng chỉ ký riêng.

## Cấu trúc

| Đường dẫn | Nội dung |
| --- | --- |
| `app/src/main/java/dev/pdmv/os/` | Activity, renderer, cầu nối JNI (`NativeCore`) và trạng thái hệ thống Android |
| `rust/` | Nhân Rust (`pdmvos-core`): xử lý ảnh/blur, vật lý lò xo, hình học bố cục và chính sách miền |
| `app/src/main/assets/` | Font, hình nền, nguồn biểu tượng và thông tin giấy phép |
| `app/src/main/res/` | Icon Android, âm thanh, manifest resources |
| `tools/` | Build, ký, vận hành ADB và phát hành |
| `tests/` | Kiểm tra Java thuần, Python và bộ kiểm thử thiết bị |
| `docs/` | Hướng dẫn và ảnh chụp công khai |
| `version.properties` | Phiên bản dùng chung cho Gradle, build offline và bộ cài |

`MainActivity` phối hợp input và vòng đời. `NativeSurfaceView` dùng Choreographer và render node của Android. Các lớp `SpringValue`, `MotionProfile`, `DrawerFlow`, `FocusMotion` xử lý chuyển động; `WallpaperStore` chuẩn bị ảnh trên worker; `UiSounds` phát hiệu ứng; `TileRepository` đọc và sắp xếp ứng dụng.

## Kiểm tra

`tools/validate.sh` chạy kiểm tra Python, mô hình chuyển động Java và các ràng buộc tài nguyên/giao diện. CI cũng dựng APK debug từ Gradle.

Bộ kiểm thử thiết bị dùng instrumentation và cần chữ ký phù hợp với APK đích.

```sh
./tools/build-tv-tests.sh
adb -s SERIAL install -r app/build/tv-instrumentation/tv-tests.apk
adb -s SERIAL shell am instrument -w -e mode rapid-sequence -e cycles 24 dev.pdmv.os.tests/.TvAcceptance
```

Để thử APK release bằng khóa phát hành, đặt `PDMVOS_TEST_SIGNING=release` khi dựng test. Mode `interaction-soak` chạy chuỗi phím trước/sau mở ứng dụng, hủy mở và thử thiếu RAM. `-e expect-recovery true` bật xác nhận phục hồi bộ đệm; `-e run-id TEN_LUOT` lưu báo cáo trong thư mục riêng.

Các phép thử có thể thay đổi trạng thái UI tạm thời. Bộ kiểm thử sao lưu và khôi phục cấu hình khi kết thúc. Nên dùng thiết bị thử và lưu riêng cấu hình cần giữ.

## Fork và bản ký riêng

Một fork có thể dựng debug ngay với Gradle. Để phát hành bằng khóa của fork, chọn danh tính ứng dụng/phân phối phù hợp và thay `release-signing.json` bằng chứng chỉ của fork theo quy trình tại [releasing.md](releasing.md). `PDMVOS_RELEASE_REPOSITORY=OWNER/REPO` chọn nơi tải release cho công cụ cài đặt.
