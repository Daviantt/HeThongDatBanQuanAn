# Khuyến mãi, phân quyền và tồn món

## Cách dùng

1. Đăng nhập quản lý, mở **Quản lý → Khuyến mãi theo nhóm**. Cấu hình số khách tối thiểu và mức giảm, rồi bật nếu muốn áp dụng. Mặc định khuyến mãi tắt, các ô 4 khách/10% chỉ là giá trị gợi ý.
2. Khi khách đặt bàn, phần tóm tắt hiển thị tiền món, khoản giảm, tổng tiền món sau giảm và cọc. Chỉ khuyến mãi món đặt trước tại quán; đơn mang về không áp dụng quy tắc số khách.
3. Nhân viên/quản lý đăng nhập sẽ tới **Vận hành**. Không còn nút đặt bàn/lịch hẹn cá nhân; vẫn mở và xử lý chi tiết lượt đặt của khách.
4. Trong **Vận hành → Danh sách đặt bàn**, cột **Tổng tiền** là tiền món sau khuyến mãi. Tiền cọc ở cột riêng; chưa bao gồm các món khách gọi thêm trực tiếp tại quán.
5. Trong **Thực đơn**, nhân viên chỉ xem số phần tồn, ngưỡng đang áp dụng và cảnh báo. Chỉ quản lý thấy biểu mẫu để sửa số tồn, ngưỡng cảnh báo và tình trạng bán. Món mới/cũ chưa kiểm kê hiển thị **Chưa nhập tồn**, không tự giả định một số lượng có sẵn.

Tồn món là số phần do quản lý cập nhật thủ công sau kiểm kê. Bản này chưa tự giữ/trừ/hoàn tồn theo vòng đời đơn. Khi số tồn bằng 0, hệ thống chặn đặt mới món đó; khi bổ sung, quản lý nhập số lượng và bật cho phép bán. Món đã đặt trước vẫn được lưu trong lịch sử.

## Cách triển khai để học

### Phân quyền có hai lớp

Template `fragments.html` ẩn điều hướng đặt bàn/lịch hẹn cho nhân viên và quản lý. `SecurityConfig` chặn các trang/API đặt mới bằng vai trò `CUSTOMER`. Service cũng kiểm tra vai trò trước khi tạo đơn, nên gọi trực tiếp service không vượt được quy tắc.

Đường dẫn chi tiết lượt đặt vẫn phục vụ nhân viên để vận hành. Không chặn toàn bộ `/bookings/**` ở phương thức GET vì sẽ làm mất chức năng xử lý đơn của khách. Các POST dành cho nhân viên tiếp tục nằm dưới `/staff/**`.

Riêng tồn món, `staff-menu.html` dùng `th:if="${isAdmin}"` cho cả tiêu đề cột và ô chứa biểu mẫu cập nhật. Nhân viên vẫn đọc được số tồn và ngưỡng cảnh báo, nhưng không có ô nhập hoặc nút lưu. Đường dẫn cập nhật là `POST /admin/menu/{id}/stock`, được quy tắc `/admin/**` trong `SecurityConfig` bảo vệ; nhân viên gửi request trực tiếp sẽ nhận HTTP 403. `BookingService.updateStock` gọi `requireAdmin` trước khi sửa dữ liệu để bảo vệ cả trường hợp gọi service trực tiếp. Đường dẫn cập nhật cũ dưới `/staff/menu/` đã bị gỡ.

### Tiền món và tiền cọc là hai khái niệm khác nhau

```text
Tiền món gốc = tổng (đơn giá đã lưu × số lượng)
Giảm giá = làm tròn xuống (tiền món gốc × phần trăm / 100)
Tổng tiền món = tiền món gốc − giảm giá
Cọc món = làm tròn lên (tổng tiền món / 5)
Tổng cọc = số bàn × cọc mỗi bàn + cọc món
```

Ví dụ cấu hình giảm 10% từ 4 khách: nhóm 4 khách đặt món 200.000đ được giảm 20.000đ, tổng tiền món 180.000đ. Với một bàn cọc 100.000đ, tổng cọc là 136.000đ. Không cộng cọc 136.000đ vào tổng tiền món lần nữa.

`group_promotion` lưu cấu hình hiện tại. Hai cột `promotion_minimum_guests` và `promotion_percent` trong `reservation` lưu chính sách được chốt khi đặt. Tắt/chỉnh khuyến mãi không thay đổi chính sách của lượt cũ. Nếu sửa món, tiền giảm tính theo món mới và chính sách đã lưu; nếu đổi số khách, xét lại điều kiện số khách theo chính sách đó. Cọc đã thanh toán giữ nguyên để đối soát.

JavaScript tính bản xem trước; server kiểm tra lại. Request mang theo phần trăm khách vừa xem. Nếu quản lý đã thay đổi mức áp dụng, server yêu cầu tải lại trước khi tạo lượt đặt, tránh chốt tiền theo một ưu đãi khác với giao diện. Thao tác cập nhật cấu hình và tạo lượt đặt dùng cùng khóa transaction.

### Không dùng số 0 cho dữ liệu chưa biết

`stock_quantity` cho phép NULL để phân biệt **chưa kiểm kê** với **đã hết món**. Ngưỡng cảnh báo mặc định là 5 phần và có thể sửa từng món.

- NULL: chưa nhập tồn.
- 0: hết món; không nhận đặt mới.
- Từ 1 đến ngưỡng: sắp hết.
- Cao hơn ngưỡng: đủ món.

`dishRow` kết hợp trạng thái bán với số tồn: món chỉ khả dụng khi đang cho phép bán và tồn chưa được khai báo hoặc còn lớn hơn 0. Cùng một quy tắc được dùng cho thực đơn khách, báo giá mang về và đặt món trước. Service giới hạn đầu vào và chỉ cho `ADMIN` cập nhật; `STAFF` chỉ xem, khách không được xem dữ liệu tồn nội bộ hoặc gọi API cập nhật tồn.

## Kiểm thử

Test kiểm tra điều hướng/quyền truy cập của cả nhân viên và quản lý, giữ quyền xử lý chi tiết đơn, ranh giới 3–4 khách, cọc sau giảm, chính sách đã lưu, khuyến mãi thay đổi trong lúc đặt, quyền cấu hình, tồn chưa nhập/sắp hết/hết và việc chặn đặt mới món hết tồn. Test JavaScript đối chiếu tổng tiền hiển thị và phần trăm gửi lên server.

Kết quả sau khi sửa quyền tồn kho: 67 kiểm thử Java đều đạt. Các test xác nhận nhân viên xem được cảnh báo nhưng không có biểu mẫu sửa, request cập nhật của nhân viên bị chặn với HTTP 403, gọi service trực tiếp cũng bị từ chối và dữ liệu giữ nguyên. Quản lý vẫn cập nhật được số tồn, ngưỡng và trạng thái bán. Kiểm thử dùng database H2 trong bộ nhớ, không ghi vào database đang dùng của quán.

```powershell
.\run.ps1 -Test
node --test src/test/js/*.test.cjs
```

Khởi động lại ứng dụng để các cột/bảng mới được tạo tự động. Không cần xóa database đang có.
