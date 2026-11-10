# Changelog

## 4.1.4

- Nhấn nút Cài đặt để mở cài đặt hệ thống. Nhấn giữ để mở cập nhật pdmvOS. Bỏ thông báo hướng dẫn thao tác cũ.
- Tạm tắt tiếng nhấn trong menu, hộp thoại và trên các nút của pill.

## 4.1.3

- Sửa lỗi khung phản chiếu iPhone bị chồng hình và xuất hiện vệt xanh khi phóng to trên Sony BRAVIA ATV3 chạy Android 9.
- Dùng góc vuông cho khung AirPlay thu nhỏ để bỏ xử lý bo góc và khử răng cưa ở mép.
- Dùng AVC cho phản chiếu trên Android 9 trở xuống. Khi bộ nhận loại bỏ một khung video lỗi, bộ giải mã tiếp tục từ khung đồng bộ kế tiếp.

## 4.1.2

- Sửa lỗi livestream AirPlay đứng khi thiết bị gửi yêu cầu tua một luồng không hỗ trợ tua.
- Cập nhật danh sách phân đoạn trong lúc phát livestream qua AirPlay.
- Dừng đúng phiên video khi ứng dụng gửi lệnh dừng, gỡ video khỏi danh sách phát hoặc đóng kênh điều khiển AirPlay.

## 4.1.0

- Phát video AirPlay HLS từ YouTube và nguồn HTTPS tương thích, ưu tiên chất lượng AVC cao nhất có thể phát và tự giảm khi mạng yếu.
- Phản chiếu HEVC khi TV có bộ giải mã phần cứng phù hợp. Hiện khung AirPlay sau khung hình đầu tiên để tránh nháy đen.
- Giảm sao chép khung phản chiếu và trạng thái qua JNI, phục hồi tốt hơn khi âm thanh mất gói hoặc HLS bị kẹt.
- Tối ưu blur và dò chi tiết ảnh ứng dụng trong Rust.
- Giữ focus trên nút Wi-Fi khi mở menu, đồng bộ với Đầu vào. Thanh tải cập nhật dùng cùng màu xanh với nút hành động.

## 4.0.5

- Thêm menu Wi-Fi với kết quả quét, mức sóng, trạng thái kết nối và mục Cài đặt hệ thống.
- Thêm switch AirPlay và Wi-Fi, đổi thao tác nút Cài đặt và hiển thị tên bộ thu AirPlay trong menu Đầu vào.
- Đồng bộ hiệu ứng nhấn hàng menu, dấu check và toast.
- Bỏ xử lý vẽ ô ngoài vùng hiển thị, giới hạn bộ đệm chữ Wi-Fi theo vùng cuộn và làm mới riêng spinner khi giao diện đã đứng yên.

## 4.0.4

- Khử răng cưa ở góc bo của khung AirPlay trong lúc thu nhỏ và chuyển sang toàn màn hình.
- Giữ video trên lớp giải mã riêng và tái sử dụng mặt nạ góc khi kích thước khung ổn định.

## 4.0.3

- Giữ phiên phản chiếu AirPlay khi Safari trên iPad chuyển tập và thay luồng âm thanh.
- Dừng đúng luồng AirPlay được yêu cầu và giải phóng các kết nối phụ khi phiên kết thúc hoặc mất kết nối điều khiển.

## 4.0.2

- AirPlay tự hiện khi đang mở ứng dụng. Khung chiếu trượt xuống sau hiệu ứng thu ứng dụng và co giãn cùng thông báo, giữ trọn hình theo tỷ lệ gốc.
- Phát hình qua lớp video riêng của Android và tối ưu nhịp phát khi mạng dao động. Hỗ trợ âm thanh AAC-ELD từ phiên phản chiếu.
- Khôi phục bộ giải mã ở khung đồng bộ tiếp theo khi lớp hiển thị được tạo lại.
- Bo góc khung AirPlay khi thu nhỏ, vuông dần khi phóng to và dùng lời nhắc “Nhấn Back để dừng”.
- Mở menu AirPlay bằng một lần nhấn, chuyển cảnh hai chiều với danh sách Đầu vào và giữ chữ cố định khi đổi focus trong menu.

## 4.0.1

- Thêm bộ thu AirPlay bằng Rust cho phản chiếu màn hình và phát âm thanh. TV xuất hiện với tên pdmvOS trên cùng mạng nội bộ.
- Rút gọn tên các chế độ, chừa khoảng riêng cho dấu chọn và đồng bộ chuyển động với chữ khi đổi tiêu điểm.
- Dùng dấu chấm giữa trong thông báo AirPlay.

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
