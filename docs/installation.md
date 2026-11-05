# Cài đặt và khôi phục

## Chuẩn bị

Cần Python 3.9 trở lên, Android Platform Tools và kết nối ADB được TV cho phép. Cài adb từ trang [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools).

1. Bật tùy chọn nhà phát triển trên TV bằng cách nhấn nhiều lần vào mục Build trong phần Giới thiệu.
2. Bật USB debugging hoặc Network/Wireless debugging theo firmware.
3. Tìm IP và cổng kết nối của TV. Máy tính cần truy cập được địa chỉ này.
4. Chấp nhận yêu cầu gỡ lỗi trên TV khi kết nối lần đầu.

Với gỡ lỗi không dây có mã ghép nối, ghép nối trước rồi sử dụng **cổng kết nối**, theo thông tin hiển thị trên TV.

```sh
adb pair 192.168.1.100:37001
python3 install.py 192.168.1.100:39501
```

Cổng ghép nối và cổng kết nối có thể khác nhau. Địa chỉ IP không kèm cổng được công cụ hiểu là cổng 5555. USB serial cũng được chấp nhận.

## Trình cài tự động

```sh
python3 install.py 192.168.1.100
python3 install.py 192.168.1.100 --version 3.1.3
python3 install.py 192.168.1.100 --restore
```

Trình cài tải bộ release cùng checksum từ GitHub, kiểm tra SHA-256 trước khi giải nén, rồi gọi công cụ vận hành đi kèm. Các bản đã tải và bản lưu Home nằm trong thư mục dữ liệu của người dùng.

| Hệ điều hành | Thư mục dữ liệu mặc định |
| --- | --- |
| macOS, Linux | `~/.local/share/pdmvos/` hoặc `$XDG_DATA_HOME/pdmvos/` |
| Windows | `%LOCALAPPDATA%/pdmvos/` |

Bản lưu thiết bị nằm trong thư mục `devices/` ở đây. Lệnh `--restore` sử dụng bộ cài đã lưu nếu có, hỗ trợ khôi phục khi máy tính đang kết nối được với TV trong mạng nội bộ.

## Công cụ trong repo hoặc ZIP

```sh
./pdmvos devices
./pdmvos connect 192.168.1.100
./pdmvos install 192.168.1.100
./pdmvos status 192.168.1.100
```

Trên Windows, thay `./pdmvos` bằng `py tools/pdmvos.py`. Với bộ ZIP, giải nén trước rồi mở terminal trong thư mục chứa `pdmvos`.

`install` cài hoặc cập nhật APK, lưu Home gốc và đặt pdmvOS làm Home. Các thông tin lưu theo mã phần cứng và Android user, có một bản trên máy tính và một bản trên TV.

Để chọn APK cụ thể hoặc áp dụng lại bản đã cài, dùng các lệnh sau.

```sh
./pdmvos install 192.168.1.100 --apk "/duong/dan/pdmvOS.apk"
./pdmvos apply 192.168.1.100
./pdmvos restore 192.168.1.100
```

Bản lưu của repo/ZIP nằm ở `.local/devices/`. Có thể đặt `PDMVOS_DEVICE_STATE_DIR` để dùng thư mục khác. Cần giữ riêng thư mục này khi chuyển máy hoặc dọn bộ cài.

## Firmware ưu tiên Home gốc

Một số firmware ưu tiên launcher của hãng dù đã chọn Home mới. Khi cần tắt đúng gói Home đã lưu, dùng tùy chọn sau.

```sh
./pdmvos install 192.168.1.100 --disable-stock
# Hoặc dùng trình cài tự động
python3 install.py 192.168.1.100 --disable-stock
```

Tùy chọn này vô hiệu hóa gói Home của user hiện tại sau khi đã lưu thông tin khôi phục. Công cụ kiểm tra gói được bảo vệ và UID trước khi thực hiện. Mức độ phụ thuộc vào Home gốc của từng firmware cần được kiểm tra trên chính thiết bị đó.

Khi bước áp dụng gặp lỗi, công cụ cố gắng bật lại và chọn Home gốc, sau đó báo kết quả. `restore` khôi phục Home đã lưu, bao gồm trạng thái bật gốc của gói đó.

## Hình nền và nhật ký

```sh
./pdmvos wallpaper 192.168.1.100 "/duong/dan/anh.jpg"
./pdmvos wallpaper-reset 192.168.1.100
./pdmvos logs 192.168.1.100
```

Ảnh được gửi vào vùng lưu trữ của launcher, kiểm tra toàn vẹn rồi giải mã trước khi thay nền hiện tại. Nhấn Ctrl+C để dừng xem log.

## Khi gặp lỗi

| Thông báo hoặc hiện tượng | Cách xử lý |
| --- | --- |
| `unauthorized` | Chấp nhận yêu cầu gỡ lỗi trên TV, sau đó chạy lại lệnh |
| `offline`, `closed`, mất kết nối | Chạy `./pdmvos connect DIA_CHI_TV` để làm mới riêng kết nối đó |
| Sai cổng không dây | Xem lại cổng kết nối trên TV sau khi ghép nối |
| Không nhận Home mới | Kiểm tra firmware và cân nhắc `--disable-stock` sau khi đọc phần trên |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Kiểm tra nguồn và khóa ký của APK đang cài, dùng bản cập nhật cùng chứng chỉ |
| Không tìm thấy bản lưu | Dùng máy/bộ cài có `.local/devices/` hoặc thư mục dữ liệu của trình cài; công cụ cũng thử đọc bản lưu trên TV |

Khi gửi issue, cung cấp model, phiên bản Android, phiên bản pdmvOS và bước tái hiện. Kiểm tra log trước khi gửi để giữ riêng thông tin thiết bị của bạn.
