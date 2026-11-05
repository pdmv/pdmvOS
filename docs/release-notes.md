## pdmvOS 3.1.4

Bản hoàn thiện bộ cài và quy trình phát hành công khai của pdmvOS, gồm APK release đã ký và bộ cài cho máy tính.

- Dock sáu ô, app list, kính theo hình nền và hoạt ảnh lò xo có quán tính.
- Menu giữ OK, ghim/bỏ ghim, sắp xếp và truy cập thao tác hệ thống.
- Bộ cài Python tự tải release, kiểm tra SHA-256, lưu Home gốc và hỗ trợ khôi phục.
- Tùy chọn `--disable-stock` dành cho firmware cần tắt Home gốc để dùng launcher mới.
- Tài liệu, ảnh giao diện, MIT cho mã nguồn và ghi nguồn tài nguyên được đóng gói đầy đủ.

### Tải và cài

Tải APK để cài thủ công, hoặc dùng `install.py` trong repo. Máy tính cần Python 3.9+ và adb, TV cần bật gỡ lỗi và cho phép kết nối.

```sh
python3 install.py DIA_CHI_TV
```

Bộ ZIP chứa công cụ `pdmvos`, APK và tài liệu. Tệp `.sha256` dùng tên tệp tương đối để kiểm tra ngay trong thư mục tải về.

### Phạm vi kiểm thử

Giao diện 3.1.2 kế thừa trong bản này đã qua thử liên hoàn trên Sony BRAVIA và Mi Box chạy Android 9, mỗi máy 30 vòng và 1.020 sự kiện phím. Các phép đo vẫn ghi nhận đỉnh trễ khi nhiều hiệu ứng chồng nhau hoặc khi chuyển ứng dụng. Xem `docs/testing.md` trong repo để biết phạm vi và giới hạn.
