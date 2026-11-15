## pdmvOS 4.1.6

- AirPlay đợi gỡ đăng ký cũ trước khi quảng bá lại, giảm trường hợp tên thiết bị có hậu tố `(2)`.
- Luồng video riêng nhận playlist `mlhls` linh hoạt hơn và xử lý kênh điều khiển đến muộn.
- Phản chiếu khai báo đúng chu kỳ làm tươi và bắt kịp luồng trực tiếp từ khung đồng bộ khi bộ giải mã bị dồn khung.
- Giảm hoạt động nền của bộ nhận AirPlay khi rảnh, giảm truy vấn danh sách ứng dụng và tái sử dụng vùng đệm làm mờ trong Rust. Hình nền mới hiện trước khi các khung chuyển cảnh được tạo xong.
