# Phát hành

## Khóa ký

Bản chính thức dùng khóa ở `.local/signing/pdmvos-release.p12`, mật khẩu trong `.local/signing/password`. Chứng chỉ công khai được ghim tại `release-signing.json` để kiểm tra các bản phát hành.

Sao lưu riêng thư mục ký bằng phương thức bảo mật của người duy trì. Các bản cập nhật dùng cùng chứng chỉ để Android chấp nhận nâng cấp và giữ dữ liệu.

Máy phát hành của dự án cần khôi phục khóa đã có trước khi build. Với một fork sử dụng danh tính phân phối mới, thay pin công khai rồi khởi tạo khóa của fork bằng `tools/init-release-key.sh`. Công cụ tạo pin từ chứng chỉ vừa tạo và kiểm tra pin khi chạy lại.

## Dựng release cục bộ

Cài JDK, Android SDK/NDK và các target Rust theo [hướng dẫn phát triển](development.md). Thiết lập `JAVA_HOME` và `ANDROID_HOME`, sau đó chạy các lệnh sau.

```sh
./tools/build-release.sh
./tools/package-release-kit.sh
./tools/package-release-source.sh
```

Các tệp được tạo trong `dist/` theo phiên bản ở `version.properties`.

| Tệp | Nội dung |
| --- | --- |
| `pdmvOS-VERSION.apk` | APK release đã ký |
| `pdmvOS-VERSION.apk.sha256` | SHA-256 của APK |
| `pdmvOS-VERSION.release.json` | Phiên bản, hash, chứng chỉ và thông tin build |
| `pdmvOS-VERSION-operating-kit.zip` | APK, công cụ cài/khôi phục, tài liệu và giấy phép |
| `pdmvOS-VERSION-operating-kit.zip.sha256` | SHA-256 của ZIP |
| `pdmvOS-VERSION-source.tar.gz` | Mã nguồn đúng commit và toàn bộ phụ thuộc Rust đã khóa phiên bản |
| `pdmvOS-VERSION-source.tar.gz.sha256` | SHA-256 của bộ mã nguồn |

Bộ ZIP được kiểm tra danh sách tệp trước khi phát hành. Thông tin ký riêng và bản lưu thiết bị thuộc vùng dữ liệu cục bộ của người duy trì.

## Công bố lên GitHub

1. Tăng `version.properties`, cập nhật CHANGELOG và `docs/release-notes.md`.
2. Commit thay đổi vào nhánh chuẩn bị phát hành.
3. Đăng nhập GitHub CLI bằng tài khoản có quyền ghi repo.
4. Chạy lệnh phát hành từ checkout sạch.

```sh
./tools/publish-release.sh
```

Lệnh dựng và kiểm tra APK, đóng gói ZIP, đẩy commit/tag, tạo draft, tải các tệp lên rồi công bố release. Có thể chạy lại để hoàn tất một draft bị gián đoạn. Phiên bản đã công bố được giữ cố định; bản tiếp theo cần số phiên bản mới.

CI dựng debug và chạy bộ kiểm tra trên pull request và nhánh main. Khi release được công bố, workflow kiểm tra lại checksum, metadata và chứng chỉ của APK bằng pin trong tag tương ứng. Khóa ký được dùng trên máy phát hành.

## Dựng lại với thư viện AirPlay đã chỉnh sửa

Giải nén bộ mã nguồn của bản phát hành. Thư mục `vendor/shairplay/` chứa đúng phiên bản thư viện dùng trong APK, cùng giấy phép và ghi chú nguồn gốc FairPlay. Để sửa thư viện, sao chép nó ra ngoài thư mục phụ thuộc đã khóa và thêm bản sao làm nguồn ưu tiên:

```sh
cp -R vendor/shairplay shairplay-local
cat >> rust/Cargo.toml <<'TOML'

[patch.crates-io]
shairplay = { path = "../shairplay-local" }
TOML
# Sửa mã trong shairplay-local/, rồi cập nhật khóa phụ thuộc.
cargo update --offline --manifest-path rust/Cargo.toml -p shairplay
./gradlew :app:assembleDebug
```

Dùng JDK, Android SDK/NDK và bộ công cụ Rust theo [hướng dẫn phát triển](development.md). APK debug nằm ở `app/build/outputs/apk/debug/app-debug.apk` và dùng chữ ký debug cục bộ. Có thể cài bằng `adb install app/build/outputs/apk/debug/app-debug.apk` trên thiết bị thử hoặc máy mô phỏng. Android yêu cầu gỡ APK cùng tên có chữ ký khác trước khi cài, thao tác này xóa dữ liệu của bản đã gỡ.
