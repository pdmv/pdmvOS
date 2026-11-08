# Changelog

## 4.0.0

- Chuyển toàn bộ nhân tính toán vật lý lò xo, động học chuyển tiếp, hình học bố cục, xử lý ảnh/blur và chính sách miền sang nhân Rust gốc (`pdmvos-core`) qua cầu nối JNI không phụ thuộc ngoài (`NativeCore`), giữ lớp Android/HWUI gọn nhẹ.
- Khử răng cưa góc bo superellipse của ô ứng dụng bằng trường khoảng cách có dấu (SDF) phân tích và tham số hóa độ dài cung đồng đều.
- Bổ sung hiệu ứng lăn chữ từng phần cho chữ số giờ và ngày tháng trên pill đồng hồ khi thời gian thay đổi.
- Đồng bộ viền sáng kính (`GlassMaterial`), độ lún khi nhấn dòng trong menu/dropdown và sự phối hợp liên hoàn giữa các hiệu ứng (menu, dropdown, hộp thoại, ngăn ứng dụng, chế độ sắp xếp).
- Tối ưu hóa việc cấp phát bộ nhớ trên từng khung hình và tự động giải phóng bộ đệm tùy chọn khi hệ thống thu hồi RAM.

## 3.1.6

- Nút Apps mở danh sách bằng hiệu ứng trượt lên. Nếu đang trong ứng dụng, pdmvOS hiện Home trước khi mở danh sách.
- Thêm tùy chọn hỗ trợ phím Apps cho TV chuyển phím này cho launcher hệ thống.
- Khi về từ ứng dụng, khôi phục Home hoặc danh sách cùng ô Dock đã dùng để mở.

## 3.1.5

- Cập nhật từ GitHub Releases ngay trong launcher. Kiểm tra dung lượng, phiên bản, SHA-256 và chữ ký, giữ tệp hợp lệ để dùng lại, rồi mở trình cài Android để xác nhận.
- Tách focus giữa nút Đầu vào và các dòng trong dropdown. Thêm xác nhận khi chuyển đầu vào hoặc tắt Wi-Fi.
- Đồng bộ dropdown và hộp thoại về nền kính, bo góc, khoảng cách và chuyển động focus.
- Thanh tải tăng liên tục và chiếm trọn bề ngang nội dung. Thu vùng vẽ lại khi mở hộp thoại.

## 3.1.4

- Chuẩn bị Android SDK rõ ràng trong CI và workflow kiểm chứng release.
- Hoàn thiện hướng dẫn khôi phục khi cài đặt gặp lỗi và bổ sung wrapper khôi phục trong ZIP.
- Kiểm thử trình cài bằng các tệp tải từ release GitHub thật.

## 3.1.3

Bản phân phối công khai đầu tiên.

- Thêm trình cài Python tải release, kiểm tra SHA-256 và lưu thông tin khôi phục theo thiết bị.
- Lệnh `install` trong repo tự lấy APK đúng phiên bản khi cần.
- Tách tùy chọn `--disable-stock`, bổ sung kiểm tra UID trước khi tắt Home của hãng.
- Chuẩn hóa Gradle Wrapper, CI, bộ release, tài liệu, ảnh giới thiệu và ghi nguồn tài nguyên.
- Kế thừa bộ giao diện và hoạt ảnh đã kiểm tra của 3.1.2.

## 3.1.2

- Bỏ phép tính cho lò xo và biến dạng đã cân bằng, dùng chung kết quả biến dạng cuộn.
- Dời bảo trì sau resume tới lúc hoạt ảnh nghỉ.
- Bộ đệm menu 4 MiB, chuẩn bị texture trước khi vẽ.
- Phục hồi ảnh làm mờ tùy chọn khi Home nghỉ và RAM đủ sau khi bộ đệm bị dọn.
- Thử liên hoàn trên Sony BRAVIA và Xiaomi Mi Box chạy Android 9.

## 3.1.1

- Tái sử dụng vùng cắt ảnh, vùng vẽ kính và Paint tạo mặt nạ.
- Tái sử dụng bộ định dạng đồng hồ theo ngôn ngữ và múi giờ hệ thống.
- Hoàn thiện việc dọn bộ đệm khi Android báo thiếu RAM.
