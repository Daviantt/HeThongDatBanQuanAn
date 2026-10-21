# Học qua 6 lỗi đã sửa

Phạm vi: sửa lỗi 1–6 trong lần review repo. Chưa tách lại các service/repository theo đề xuất kiến trúc ở lỗi 7.

## 1. Tên món bị hiểu thành HTML

**Nguyên nhân:** `innerHTML` yêu cầu trình duyệt phân tích chuỗi như HTML. Dù Thymeleaf đã escape tên món trong thuộc tính `data-name`, JavaScript đọc lại thuộc tính sẽ nhận chuỗi gốc. Đưa chuỗi đó vào `innerHTML` sẽ tạo một điểm thực thi HTML mới.

**Cách sửa trong `src/main/resources/static/js/takeaway.js`:**

```js
const node = document.createElement("h3");
node.textContent = item.name;
```

Các thẻ được tạo bằng `createElement`; dữ liệu được đặt bằng `textContent`. Ảnh chỉ nhận đường dẫn thuộc `/images/` đúng định dạng, sai định dạng dùng ảnh mặc định. Dữ liệu giỏ trong localStorage được kiểm tra kiểu, mã món, số lượng và mã trùng trước khi sử dụng.

**Bài học:** bảo vệ tại nơi dữ liệu được đưa vào DOM. Không cấm tên món có dấu tiếng Việt hoặc ký tự `&`; cũng không dùng cách xóa vài chuỗi như `<script>` vì còn nhiều dạng HTML khác.

**Kiểm thử:** tên `<img ... onerror=...><b>Cơm</b>` vẫn hiện nguyên văn cả trước và sau khi nhận báo giá; không tạo thẻ con hoặc thuộc tính xử lý sự kiện.

## 2. Đơn mang về chưa có luồng vận hành

**Cách sửa:** trang `/staff` có danh sách đơn mang về, giờ lấy, người nhận, món/số lượng và tình trạng thu tiền. Trang chi tiết có các thao tác phù hợp trạng thái:

| Trạng thái hiện tại | Thao tác | Trạng thái sau |
| --- | --- | --- |
| Chờ chuyển khoản | Xác nhận QR demo hợp lệ | Chờ khách đến lấy |
| Chờ khách đến lấy | Nhân viên bắt đầu chuẩn bị | Đang chuẩn bị |
| Đang chuẩn bị | Nhân viên xác nhận món sẵn sàng | Sẵn sàng giao món |
| Sẵn sàng giao món | Giao món; xác nhận thu đủ tiền nếu là tiền mặt | Đã giao món |
| Đơn đang mở, chưa trả tiền | Quán hủy | Đã hủy |
| Đơn đang mở, đã trả tiền demo | Quán hủy | Chờ hoàn tiền |
| Chờ hoàn tiền | Nhập mã ghi nhận hoàn demo | Đã hủy |

`TakeawayService.staffAction` kiểm tra quyền và trạng thái trên server. Các POST `/staff/takeaway-orders/{id}/action` vẫn được Spring Security bảo vệ vai trò và CSRF. Thời gian/mã thu tiền được lưu khi giao đơn tiền mặt. Cột `refund_reference` lưu mã hoàn demo, không xóa thông tin thanh toán cũ.

Các thao tác dùng `SELECT ... FOR UPDATE` trong transaction. Nhờ đó, hai request xử lý cùng một đơn phải chờ nhau; request đến sau đọc trạng thái đã cập nhật. Không ghi nhận thu tiền lần nữa nếu đơn đã hoàn tất.

**Bài học:** ẩn nút trên giao diện giúp người dùng thao tác đúng; kiểm tra trong service mới bảo vệ được nghiệp vụ khi có request trực tiếp.

## 3. Giá hiển thị khác giá chốt đơn

**Cách sửa:**

1. Trang chốt đơn gọi `POST /api/takeaway-orders/quote` với mã món và số lượng.
2. Server trả tên, giá hiện tại, số lượng và tổng tiền. Trình duyệt cập nhật giỏ; chưa có báo giá hợp lệ thì chưa bật nút xác nhận.
3. Khi khách xác nhận, request gửi thêm `expectedUnitPrices`: giá từng món mà khách vừa xem.
4. Server đọc lại giá trong database. Nếu không khớp, trả HTTP `409` kèm báo giá mới, **chưa lưu đơn**.
5. Trang hiển thị giá mới và chờ khách bấm xác nhận lại. Không tự gửi lại đơn.

```json
{
  "items": { "1": 2 },
  "expectedUnitPrices": { "1": 65000 }
}
```

Giá khách gửi chỉ dùng để đối chiếu, không dùng làm giá bán. Server luôn tính tổng từ database. Kiểm tra theo từng món để phát hiện cả trường hợp một món tăng, một món giảm làm tổng tiền tình cờ giữ nguyên.

Tạo đơn dùng cùng khóa với thao tác sửa thực đơn, nên giá không thể bị thay đổi giữa bước đối chiếu và bước lưu. Trình duyệt dùng số phiên yêu cầu (`quoteVersion`) để bỏ qua báo giá cũ trả về chậm sau khi khách đã đổi số lượng.

## 4. QR của đơn quá giờ vẫn thanh toán được

**Quy tắc áp dụng:** chỉ đơn `AWAITING_PAYMENT` chưa trả tiền mới chuyển sang `EXPIRED` khi `now >= pickupAt`. Đơn tiền mặt và đơn đã trả tiền không bị hết hạn bởi quy tắc này.

Việc kiểm tra chạy khi đọc đơn/trạng thái thanh toán, trước thao tác thanh toán và theo lịch 30 giây. Câu UPDATE xác nhận tiền cũng có điều kiện `pickup_at > now`, nên không phụ thuộc vào việc tác vụ định kỳ đã chạy hay chưa. Trang QR đóng sẽ ẩn nút xác nhận và mã QR; nếu mất mạng, nút cũng bị khóa trong lúc chờ kiểm tra lại.

**Kiểm thử biên:** trước giờ lấy 1 giây còn được trả tiền; đúng giờ lấy bị chặn; sang ngày hôm sau vẫn bị chặn; đơn đã trả tiền giữ nguyên trạng thái.

## 5. Chuyển khoản bị tắt nhưng API vẫn tạo đơn

`TakeawayService.create` kiểm tra `demoPayments.enabled()` trước khi lưu đơn `TRANSFER`. Điều kiện này tính cả cấu hình tắt demo và trường hợp VNPAY đã được cấu hình khiến demo tự tắt.

Đơn mang về hiện chỉ hỗ trợ tiền mặt hoặc QR demo, chưa có VNPAY cho đơn mang về. Nếu demo không khả dụng, API yêu cầu chọn tiền mặt. Đơn chuyển khoản cũ còn chờ sẽ hiển thị hướng dẫn liên hệ quán; nhân viên có thể hủy để khách đặt lại bằng tiền mặt.

**Bài học:** kiểm tra khả năng thanh toán ở lúc tạo đơn, không đợi tới bước mở cổng thanh toán mới phát hiện.

## 6. Nhiều truy vấn và danh sách không phân trang

**Trước đây:** đọc N lượt đặt rồi, bên trong mapper của mỗi lượt, lại truy vấn ID bàn, mã bàn và món: khoảng `1 + 3N` câu SQL.

**Sau khi sửa trong `RestaurantRepository`:**

- Lấy các lượt đặt của một trang.
- Lấy tất cả bàn và mã bàn cho các ID đó bằng `IN (:ids)`.
- Lấy tất cả món cho các ID đó bằng `IN (:ids)`.
- Gom kết quả theo ID bằng `Map`, rồi ghép thành đối tượng `Booking`.

Mỗi trang có tối đa 20 bản ghi. Một trang đặt bàn không rỗng dùng 4 truy vấn kể cả `COUNT`; một trang mang về không rỗng dùng 3 truy vấn kể cả `COUNT`. Số câu SQL không tăng theo số bản ghi trong trang. Truy vấn `IN` vẫn dùng tham số, không ghép dữ liệu người dùng trực tiếp vào SQL.

`busyTableIds` chỉ lấy ID bàn có lịch giao nhau, không tải tên khách hoặc món ăn. Điều kiện giao nhau giữ nguyên biên thời gian dọn bàn:

```text
giờ bắt đầu cũ < giờ kết thúc mới + thời gian dọn
và giờ kết thúc chiếm bàn cũ > giờ bắt đầu mới - thời gian dọn
```

Lượt đã hoàn tất dùng giờ trả bàn thực tế; lượt đang ngồi quá giờ dùng ít nhất thời điểm hiện tại. Thống kê nhân viên được tính bằng SQL trên toàn bộ dữ liệu, không đếm riêng trang đang mở. Tìm kiếm đặt bàn cũng chạy trên server để không bỏ sót kết quả ở trang khác.

**Kiểm thử:** bộ kiểm thử bọc kết nối database và đếm câu `prepareStatement`, xác nhận trang 20 lượt chỉ dùng 4 truy vấn; kiểm tra bàn bận dùng 1 truy vấn không đọc bảng món. Kiểm tra phân trang, thứ tự, quyền xem lịch sử và tìm kiếm qua nhiều trang.

## Tự chạy và trải nghiệm

```powershell
.\run.ps1 -Test
node --test src/test/js/*.test.cjs
```

Test Java dùng H2 trong bộ nhớ; không sửa dữ liệu đang dùng của quán. Các test mới nằm trong `BookingIntegrationTest`, `DemoPaymentIntegrationTest` và `takeaway-checkout.test.cjs`.

Sau khi khởi động lại ứng dụng, thử đặt mang về bằng tiền mặt, đăng nhập nhân viên, mở `/staff` và xử lý qua ba bước chuẩn bị → sẵn sàng → giao/thu tiền. Thử thêm giỏ rồi đổi giá từ tài khoản quản lý: trang chốt đơn sẽ cập nhật báo giá; nếu đổi sau khi trang đã tải, lần xác nhận đầu sẽ yêu cầu kiểm tra giá mới.

Cấu trúc database cũ được bổ sung bằng `ADD COLUMN IF NOT EXISTS`, không yêu cầu xóa database. Thanh toán và hoàn tiền demo vẫn chỉ là mô phỏng.
