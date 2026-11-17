# Sprintly — Design system Android

Hệ thống thiết kế theo bản Orbix đã chốt ngày 17/11/2026. [Bản thiết kế Orbix](../../docs/Sprintly_Orbix_Design.html) · [Đặc tả màn hình](../../docs/Sprintly_UI_UX_Design.md)

## Hướng hình ảnh

Giao diện phẳng với nền trắng, cột xám nhạt và thẻ có viền mảnh. Xanh chanh dành cho nút thêm và hành động chính. Tím dành cho tab đang chọn, liên kết và viền focus. Bảng dự án dùng cụm avatar và thanh tiến độ. Biểu tượng dạng nét theo Material, kích thước 24dp ở vùng chạm 48dp. Avatar hiển thị chữ viết tắt khi thiếu ảnh. Màu cảnh báo đi kèm nhãn mô tả.

Dùng Roboto theo hệ thống Android. Chế độ sáng là phạm vi P0.

## Màu ngữ nghĩa

| Token | Hex | Dùng cho |
| --- | --- | --- |
| `surface_background` | #FFFFFF | Nền app và top app bar. |
| `surface` | #FFFFFF | Thẻ, nội dung biểu mẫu và sheet. |
| `surface_lane` | #F5F6F3 | Bề mặt cột trên board. |
| `on_surface` | #171A17 | Tiêu đề và văn bản chính. |
| `on_surface_variant` | #555B54 | Nội dung phụ, hạn, tên thành viên. |
| `primary` / `on_primary` | #A4ED65 / #172413 | Nút thêm, tạo công việc và các hành động xác nhận. |
| `selection` / `selection_container` | #4736C5 / #EEEBFF | Tab/chip được chọn, liên kết và viền focus. |
| `outline` | #787E75 | Ranh giới chức năng của ô nhập. |
| `divider` | #E4E6E1 | Đường phân cách và viền thẻ. |
| `error` / `error_container` | #842B2A / #FCE1DF | Lỗi, quá hạn và ưu tiên Khẩn cấp. |
| `warning` / `warning_container` | #684515 / #FFE7B1 | In Progress, offline và ưu tiên Cao. |
| `info` / `info_container` | #284B68 / #D9ECFA | To Do. |
| `success` / `success_container` | #21583C / #C6EED7 | Done và xác nhận thành công. |
| `neutral` / `neutral_container` | #364036 / #F0F2EE | Trạng thái trung tính. |

Ưu tiên luôn có chữ Thấp, Trung bình, Cao hoặc Khẩn cấp. Cột mô tả tiến độ bằng tên và số lượng. Chấm màu chỉ hỗ trợ nhận diện cột mặc định. Các cột tùy chỉnh giữ ngữ nghĩa từ tên người dùng đặt. Hiển thị ngày kèm nhãn Quá hạn khi có liên quan.

## Chữ và hình học

| Vai trò chữ | Cỡ / dòng | Trọng lượng |
| --- | --- | --- |
| Tên thương hiệu / tiêu đề đăng nhập | 32sp / 40sp | Bold |
| Tiêu đề trang | 24sp / 32sp | Bold |
| Tiêu đề top app bar / nhóm | 20sp / 28sp | Medium |
| Tiêu đề task / nhãn trường | 16sp / 24sp | Medium |
| Nội dung và nhập liệu | 16sp / 24sp | Regular |
| Nút, metadata và chip | 14sp / 20sp | Medium hoặc Regular |
| Chú thích phụ | 12sp / 16sp | Regular |

- Thang khoảng cách 4, 8, 12, 16, 24, 32dp. Gutter ngang 16dp ở màn hình 360–440dp. Bố cục biểu mẫu một cột và có thể cuộn.
- Bo góc thẻ 16dp, ô nhập 12dp, cột 20dp, nút hình viên thuốc. Sheet có góc trên 24dp và phần đệm tránh thanh điều hướng.
- Thanh trên tối thiểu 56dp. Vùng chạm tối thiểu 48×48dp cho cả biểu tượng và tay nắm kéo. Giữ thứ tự focus theo thứ tự đọc và focus luôn nhìn thấy.
- Cột board rộng khoảng 82% bề rộng ở portrait, giới hạn tối đa 340dp, chừa mép cột kế tiếp. Tăng vùng cuộn dọc khi nội dung phóng lớn. Tên dài có thể xuống dòng hoặc đọc đầy đủ trong chi tiết và nhãn hỗ trợ tiếp cận.
- Sử dụng ripple và focus rõ. Phản hồi ngắn khoảng 120ms, chuyển sheet khoảng 200ms và điều chỉnh theo thiết lập giảm chuyển động.

## Ánh xạ sang Android XML Views

| Mẫu giao diện | Thành phần |
| --- | --- |
| Top app bar và điều hướng | `MaterialToolbar`, Navigation Component với Fragments. |
| Field, nút và chọn nhanh | `TextInputLayout` / `TextInputEditText`, `MaterialButton`, `ChipGroup`, `TabLayout`. Dùng autofill và nhãn bền vững cho các ô xác thực. |
| Danh sách và board | `RecyclerView` với adapter theo ID. `MaterialCardView` làm bề mặt task. Cột ngang có cuộn dọc riêng. Tay nắm để kéo, menu và accessibility action dùng chung luồng Di chuyển. |
| Cửa sổ thao tác | `BottomSheetDialogFragment` cho nội dung ngắn, picker phù hợp cho ngày/giờ và `MaterialAlertDialogBuilder` cho xác nhận phá hủy hoặc bỏ thay đổi. |
| Hệ thống và bàn phím | Áp dụng insets cho thanh trên, danh sách, nút cố định và khung bình luận. Bảo đảm focus và lỗi trường hiển thị cạnh IME. |
| Phản hồi | `CircularProgressIndicator` hoặc khung tải theo nội dung, banner cho offline/quyền truy cập, `Snackbar` cho thao tác có thể thử lại, lỗi trường cạnh trường liên quan. |

Kiểm tra tương phản chữ thường tối thiểu 4.5:1 và chỉ báo điều khiển tối thiểu 3:1. Kết quả hiển thị cần được xác minh trên Android với TalkBack, phóng chữ, bàn phím và insets thực tế. [Android Views accessibility](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views) · [Android Views edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
