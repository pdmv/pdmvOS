# Đóng góp cho pdmvOS

Issue và pull request nên mô tả tình huống thực tế, kết quả mong muốn và cách kiểm tra. Với thay đổi giao diện, hãy kèm ảnh hoặc video trước/sau. Với chuyển động, ghi lại model, Android, kịch bản thao tác và số liệu đo nếu có.

## Chạy trước khi gửi

```sh
./tools/validate.sh
./gradlew :app:assembleDebug
```

Các thay đổi cài đặt cần kiểm tra trường hợp lỗi và khôi phục Home. Các thay đổi renderer cần thử trên thiết bị thật, đặc biệt khi có cache bitmap, upload texture hoặc đổi vòng đời.

Giữ thay đổi tập trung vào vấn đề đang xử lý. Tài nguyên mới cần có nguồn, giấy phép và thông tin ghi công trong NOTICE. Khóa ký và bản lưu thiết bị được lưu riêng trên máy phát triển.

Xem [tài liệu phát triển](docs/development.md) để biết cấu trúc và cách chạy bộ thử thiết bị.
