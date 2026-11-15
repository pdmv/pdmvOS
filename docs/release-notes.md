## pdmvOS 4.1.7

- AirPlay giữ độ phân giải phản chiếu 1080p và hỗ trợ tối đa 60 fps. Khi một khung đã quá trễ, bộ nhận chỉ bỏ khung để bắt kịp nếu đã có khung mới hơn được giải mã và sẵn sàng hiển thị.
- Giảm công việc đọc gói video trong Rust và đánh thức luồng giải mã khi hàng đợi nhận khung mới.
- Ghi riêng thời gian chờ nạp video vào bộ giải mã, thời gian lấy đầu ra MediaCodec và số khung bỏ vì trễ để dễ đối chiếu các phiên phản chiếu.
