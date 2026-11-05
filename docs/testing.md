# Kiểm thử và tương thích

## Thiết bị đã thử

| Thiết bị | Android | Các nhóm đã chạy |
| --- | --- | --- |
| Sony BRAVIA 4K GB ATV3 | 9 | Dock/app list, menu, giữ phím, mở/về ứng dụng, hủy mở, thiếu RAM |
| Xiaomi Mi Box 4 | 9 | Dock/app list, menu, giữ phím, mở/về ứng dụng, hủy mở, thiếu RAM |

Các phép đo UI được thực hiện ngày 05/11/2026 trên bộ mã giao diện 3.1.2. Bản 3.1.3 hoàn thiện việc phân phối công khai, tài liệu và công cụ cài đặt.

## Chuỗi thao tác

Mỗi thiết bị chạy 24 vòng trước và 6 vòng sau khi mở ứng dụng, tổng 1.020 sự kiện nhấn/nhả trong hai nhóm. Các bước gồm đổi hướng nhanh, đảo app list, mở/đóng menu, giữ OK và điều hướng hàng điều khiển.

Bộ kiểm thử xác nhận chuyển động về đúng ô sau khi trở lại từ Play Store và Play Games, bốn ca hủy mở bằng Back/Home ở độ trễ 20/60 ms, dừng frame khi launcher ở nền và phục hồi ảnh chuyển tiếp khi RAM đủ sau tín hiệu thiếu RAM.

| Thiết bị | p95 nhịp cập nhật trước/sau mở app | p95 tổng thời gian frame trước/sau mở app | Chức năng |
| --- | --- | --- | --- |
| Sony BRAVIA | 17,31 / 17,10 ms | 48,21 / 45,68 ms | Đạt |
| Xiaomi Mi Box | 16,67 / 16,67 ms | 36,89 / 38,26 ms | Đạt |

Nhịp cập nhật đo khoảng cách giữa các callback VSYNC khi có hoạt ảnh. Tổng thời gian frame lấy từ FrameMetrics. Hai chỉ số phản ánh các phần khác nhau của pipeline, cần được xem cùng nhau khi đánh giá độ mượt.

## Giới hạn

- Một số frame vẫn trễ khi nhiều hiệu ứng chồng nhau hoặc khi chuyển ứng dụng.
- Có một lượt Mi Box báo chưa nghỉ ở thời điểm kiểm tra cố định. Hai lượt xác nhận tiếp theo có ghi nhận từng lò xo đã đạt và hiện tượng chưa tái hiện.
- Thử thiếu RAM dùng tín hiệu `RUNNING_CRITICAL`, sau đó xác nhận bộ đệm được phục hồi khi hệ thống còn đủ RAM.
- Dữ liệu thời điểm trình bày của SurfaceFlinger không khả dụng trong lượt đo này.
- Chưa có thử nhiều ngày liên tục hoặc xác nhận trên mọi firmware và phiên bản Android. Ngủ/đánh thức, HDMI và reboot cần được kiểm tra trên từng model.

Kết quả chức năng đạt cùng các số liệu trên là phạm vi đã kiểm tra. Báo lỗi hoặc bổ sung kết quả thiết bị mới bằng issue, kèm model và phiên bản Android.
