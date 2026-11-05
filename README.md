# pdmvOS

[![Kiểm tra](https://github.com/pdmv/pdmvOS/actions/workflows/ci.yml/badge.svg)](https://github.com/pdmv/pdmvOS/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/pdmv/pdmvOS)](https://github.com/pdmv/pdmvOS/releases/latest)

pdmvOS là launcher native dành cho Android TV và Google TV, với Dock sáu ô, danh sách ứng dụng và giao diện kính lấy màu từ hình nền. Điều khiển bằng remote, các chuyển động phóng to, nhấn, mở ứng dụng và trở về Home nối tiếp nhau bằng lò xo có quán tính.

![Màn hình chính pdmvOS trên Sony BRAVIA](docs/images/home.png)

## Giao diện và thao tác

- **Dock sáu ô** để ghim ứng dụng thường dùng. Ô trống có nút thêm, các ứng dụng còn lại nằm trong app list.
- **Focus và chuyển cảnh** phản hồi ngay khi đổi hướng. Dock và từng hàng ứng dụng có quán tính riêng, menu mở từ vị trí đang chọn.
- **Menu nhấn giữ** để mở, sắp xếp, ghim, bỏ ghim, xem thông tin hoặc gọi hộp thoại gỡ ứng dụng của Android.
- **Hình nền cá nhân** có thể thay bằng lệnh. Các lớp kính và làm mờ được tạo trên thiết bị.
- **Hàng điều khiển trên cùng** hiển thị giờ, ngày, Wi-Fi, nguồn vào và cài đặt hệ thống.
- **Phản hồi âm thanh** cho di chuyển, nhấn và mở/về ứng dụng, theo thiết lập âm thanh thao tác của Android.
- **Tiến trình cài đặt** xuất hiện khi Android cung cấp phiên cài đặt phù hợp cho launcher.

| Danh sách ứng dụng | Menu nhấn giữ |
| --- | --- |
| ![App list](docs/images/apps.png) | ![Menu ứng dụng](docs/images/menu.png) |

| Phím | Thao tác |
| --- | --- |
| D-pad | Chọn ứng dụng và điều khiển |
| Xuống từ Dock | Mở app list |
| OK | Mở hoặc xác nhận |
| Giữ OK | Mở menu ứng dụng |
| Back | Đóng menu, thoát chế độ sắp xếp, trở về Dock |
| Home | Quay lại launcher |
| Giữ Wi-Fi hoặc nguồn vào | Mở trang cài đặt tương ứng của Android |

## Cài đặt

Máy tính cần **Python 3.9+** và **adb** trong PATH. Bật gỡ lỗi trên TV, để máy tính và TV kết nối được với nhau, rồi chấp nhận yêu cầu gỡ lỗi trên màn hình TV. Có thể tải adb từ [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools).

Trên macOS hoặc Linux, tải trình cài rồi chạy với địa chỉ TV.

```sh
curl -fsSL https://raw.githubusercontent.com/pdmv/pdmvOS/main/install.py -o pdmvos-install.py
python3 pdmvos-install.py 192.168.1.100
```

Trên Windows, dùng PowerShell.

```powershell
Invoke-WebRequest https://raw.githubusercontent.com/pdmv/pdmvOS/main/install.py -OutFile pdmvos-install.py
py pdmvos-install.py 192.168.1.100
```

Trình cài lấy bản release ổn định, kiểm tra SHA-256, lưu Home gốc và chọn pdmvOS làm Home. Lệnh khôi phục được in sau khi cài thành công.

```sh
python3 pdmvos-install.py 192.168.1.100 --restore
```

Cũng có thể tải APK hoặc bộ cài ZIP ở [Releases](https://github.com/pdmv/pdmvOS/releases/latest). Hướng dẫn ghép nối ADB, cài từ mã nguồn, tùy chọn `--disable-stock` và xử lý lỗi nằm trong [hướng dẫn cài đặt](docs/installation.md).

## Dùng từ repo hoặc bộ cài ZIP

```sh
git clone https://github.com/pdmv/pdmvOS.git
cd pdmvOS
./pdmvos install 192.168.1.100
./pdmvos status 192.168.1.100
./pdmvos wallpaper 192.168.1.100 "/duong/dan/anh.jpg"
./pdmvos restore 192.168.1.100
```

Lệnh `install` tự tải APK đúng phiên bản khi thư mục `dist/` chưa có bản đã kiểm tra. Trên Windows có thể dùng `py tools/pdmvos.py` thay cho `./pdmvos`.

## Tương thích

Dự án thiết kế cho **Android 8 trở lên, API 26**, với remote D-pad. Các lượt thử thực tế được thực hiện trên Sony BRAVIA và Xiaomi Mi Box chạy Android 9. Việc chọn Home, Wi-Fi và danh sách nguồn vào phụ thuộc firmware và quyền Android cung cấp.

Bộ kiểm thử đã chạy chuỗi thao tác nhanh, giữ phím, đảo chiều hoạt ảnh, mở/về ứng dụng và tín hiệu thiếu RAM. Vẫn có khung hình trễ trong một số chuyển cảnh. Phạm vi đo và các giới hạn được ghi trong [kiểm thử và tương thích](docs/testing.md).

## Phát triển và phát hành

Mã nguồn Java, giao diện vẽ qua Android Canvas/HWUI. Cần JDK 17, Android SDK platform 35 và build-tools 34.0.0.

```sh
./tools/validate.sh
./gradlew :app:assembleDebug
```

Hướng dẫn cấu trúc mã nguồn, dựng APK release và phát hành bằng khóa ký của dự án nằm trong [tài liệu phát triển](docs/development.md) và [quy trình release](docs/releasing.md).

Đóng góp qua issue hoặc pull request theo [CONTRIBUTING.md](CONTRIBUTING.md). Báo lỗi bảo mật qua [SECURITY.md](SECURITY.md).

## Giấy phép và ghi nguồn

Mã nguồn và tài liệu của dự án dùng [MIT](LICENSE). Google Sans Flex dùng SIL OFL 1.1, Solar Icons dùng CC BY 4.0. Ảnh nền và âm thanh được phân phối cùng pdmvOS theo xác nhận của chủ dự án, với điều khoản riêng tại [ASSETS_LICENSE.md](ASSETS_LICENSE.md).

Nguồn tài nguyên và thông tin tác giả được ghi trong [NOTICE.md](NOTICE.md). Biểu tượng và tên ứng dụng trong ảnh chụp thuộc các chủ sở hữu tương ứng.
