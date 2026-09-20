# Mộc — Website đặt bàn và đặt món trước

Đồ án Phân tích thiết kế hướng đối tượng, dành cho **một quán ăn**. Đây là phiên bản đầu chạy được bằng Java; tên quán và hình minh họa là dữ liệu mẫu.

## Chạy trên máy hiện tại

Mở PowerShell trong thư mục dự án:

```powershell
.\run.ps1
```

Truy cập **http://localhost:8080**. Dừng bằng `Ctrl+C` tại terminal đang chạy.

JDK 25 và Maven đã được chuẩn bị trong `.tools`, không thay Java mặc định của Windows. Nếu máy chặn chạy file `.ps1`, có thể mở dự án bằng IntelliJ IDEA, chọn JDK 25 và chạy `MocApplication`.

Trên máy Windows khác: chạy `./setup.ps1` một lần để tải JDK và Maven chính thức, có kiểm tra checksum, sau đó `./run.ps1`. Cần kết nối Internet cho lần tải dependency đầu tiên. Hoặc dùng JDK 25 và Maven cài sẵn: `mvn spring-boot:run`.

## Tài khoản trải nghiệm

Mật khẩu chung: **`MocDemo123!`**.

| Vai trò    | Email                |
| ---------- | -------------------- |
| Khách hàng | `khach@moc.local`    |
| Nhân viên  | `nhanvien@moc.local` |
| Quản lý    | `admin@moc.local`    |

Khách cũng có thể đăng ký tài khoản mới. Quản lý có thể tạo thêm tài khoản nhân viên. Mật khẩu được băm bằng BCrypt; đăng ký công khai luôn tạo vai trò khách hàng.

## Chức năng đã có

- Giao diện tiếng Việt, responsive, trang chủ và thực đơn có bộ lọc.
- Đăng ký/đăng nhập/đăng xuất; ba vai trò khách, nhân viên, quản lý.
- Chọn ngày, giờ đến, giờ kết thúc, 1–12 người.
- Sơ đồ 12 bàn, mỗi bàn 4 chỗ. Nhóm đông chọn tổ hợp 2–3 bàn được quán cho phép.
- Chọn món trước, số lượng, ghi chú; giá món và tiền cọc được lưu tại thời điểm đặt.
- Giữ bàn có thời hạn, chống đặt trùng bằng transaction và khóa trong database.
- Thanh toán demo chạy ngay, không thu tiền thật; tích hợp tạo URL và xác minh IPN VNPAY sandbox.
- Lịch sử đặt bàn, sửa món/ghi chú, hủy và theo dõi hoàn cọc.
- Khách gửi yêu cầu đổi bàn/giờ; nhân viên chấp nhận với lịch/bàn mới hoặc từ chối kèm lý do.
- Nhân viên nhận khách, hoàn tất phục vụ, ghi nhận không đến, quán hủy và ghi nhận đã hoàn cọc.
- Quản lý thực đơn, tạm ngừng bàn, tổ hợp ghép, cấu hình cọc/thời gian và tạo tài khoản nhân viên.
- Nhật ký thao tác của mỗi lượt đặt.
- Dữ liệu H2 lưu bền trong `data/moc.mv.db`, còn sau khi khởi động lại.

## Quy tắc đã thống nhất

| Quy tắc                      | Hành vi                                                              |
| ---------------------------- | -------------------------------------------------------------------- |
| Số bàn                       | `ceil(số khách / 4)`, tối đa 3 bàn                                   |
| Ghép bàn                     | Tổ hợp khai báo sẵn; mọi bàn phải khả dụng trong toàn bộ khoảng đặt  |
| Tiền cọc                     | Số bàn × cọc mỗi bàn; lưu số tiền riêng cho mỗi lượt đặt             |
| Hủy trước ít nhất 3 tiếng    | Hoàn toàn bộ cọc; đúng mốc 3 tiếng vẫn được hoàn                     |
| Hủy dưới 3 tiếng / không đến | Không hoàn cọc; không có ngoại lệ 15 phút sau thanh toán             |
| Quán hủy                     | Hoàn toàn bộ cọc                                                     |
| Sửa món/ghi chú              | Trước ít nhất 2 tiếng; đúng mốc 2 tiếng vẫn được sửa                 |
| Chuẩn bị món                 | Chỉ sau khi nhân viên xác nhận khách đã nhận bàn                     |
| Hoàn tiền                    | Nhân viên thực hiện bên ngoài, nhập mã giao dịch để ghi nhận kết quả |

## Giá trị mặc định cho bản demo

Các giá trị sau là lựa chọn triển khai ban đầu, **không phải yêu cầu đã chốt với nhóm**:

- Cọc **100.000đ/bàn**; giữ chờ cọc **15 phút**; dọn bàn **15 phút**; chờ khách muộn **15 phút**. Quản lý sửa được trên `/admin`.
- Quán mở 10:00–22:00; đặt trước tối đa 60 ngày, dùng bàn ít nhất 30 phút và kết thúc trong cùng ngày.
- Nhận khách từ 15 phút trước giờ hẹn, chỉ khi bàn không xung đột lượt khác.
- Múi giờ nghiệp vụ: `Asia/Ho_Chi_Minh` (UTC+7).
- Tổ hợp mặc định theo từng hàng: B01–B03, B04–B06, B07–B09, B10–B12; mỗi cặp liên tiếp hoặc cả ba bàn.

## Giới hạn hiện tại

- Đổi bàn/giờ sau cọc hỗ trợ **giữ nguyên số bàn**. Đổi số bàn và đối soát cọc tăng/giảm là phần phát triển tiếp; hệ thống chặn rõ ràng, không tự tính sai tiền.
- Sơ đồ có bố cục mẫu cố định; chưa có trình kéo thả thiết kế mặt bằng.
- Tài khoản nhân viên có thể tạo mới; chưa có khóa tài khoản, quên mật khẩu hoặc xác minh email.
- Tiền món tại quán và việc đối trừ tiền cọc do nhân viên thực hiện; chưa có module hóa đơn/POS.
- H2 phù hợp chạy đồ án một tiến trình, không dùng cho triển khai nhiều máy chủ. Chuyển MySQL/PostgreSQL cần migration SQL tương ứng.
- Hình món ăn hiện là minh họa SVG; cần thay ảnh thật khi chốt thực đơn.

## VNPAY sandbox

Chế độ demo không phải kết nối ngân hàng. Phần VNPAY đã có code tạo URL ký HMAC-SHA512, kiểm tra chữ ký/số tiền/mã đơn, xử lý callback lặp và tiền đến sau khi hết hạn giữ bàn. **Chưa kiểm thử giao dịch với VNPAY thật vì chưa có tài khoản sandbox.**

1. Đăng ký thông tin tích hợp tại https://sandbox.vnpayment.vn/devreg/.
2. Cấu hình biến môi trường trước khi chạy, không ghi secret vào Git:

```powershell
$env:VNPAY_TMN_CODE = 'ma-website-duoc-cap'
$env:VNPAY_HASH_SECRET = 'secret-duoc-cap'
$env:VNPAY_RETURN_URL = 'https://ten-mien-thu-nghiem/payment/vnpay/return'
.\run.ps1
```

3. Cấu hình IPN với VNPAY: `https://ten-mien-thu-nghiem/payment/vnpay/ipn`. VNPAY phải gọi được địa chỉ này; localhost không nhận callback từ bên ngoài. Việc đưa lên Internet/tạo tunnel chưa được thực hiện.
4. Return URL chỉ hiển thị kết quả; **chỉ IPN được ký hợp lệ mới cập nhật thanh toán**.
5. Nếu tiền đến sau hết hạn hoặc sau hủy, lượt đặt không được khôi phục và tiền được đánh dấu chờ hoàn, tránh chiếm bàn đã giao người khác.

Tài liệu nguồn: https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html.

Ứng dụng mặc định chạy profile `demo`, chỉ bind `127.0.0.1`. Không công khai profile này vì có tài khoản mẫu và nút xác nhận tiền giả lập. Profile khác mặc định tắt tạo tài khoản demo/nút thanh toán demo; **chuyển profile không xóa tài khoản demo đã lưu trong database**. Khi triển khai cần database riêng, tài khoản quản lý riêng, HTTPS và cấu hình vận hành phù hợp.

## Kiến trúc để học và viết báo cáo

```text
src/main/java/vn/edu/moc/
  MocApplication.java          Điểm khởi động
  config/                      Security, tài khoản/dữ liệu mẫu
  domain/                      Đối tượng nghiệp vụ, trạng thái và quy tắc thời gian
  data/                        Truy vấn database bằng JDBC có tham số
  service/                     Nghiệp vụ đặt bàn, transaction, VNPAY
  web/                         Controller, API, dữ liệu cho giao diện
src/main/resources/
  templates/                   HTML Thymeleaf
  static/css, js, images/       Giao diện, sơ đồ tương tác, minh họa
  schema.sql                   Cấu trúc database
src/test/                      Kiểm thử nghiệp vụ, phân quyền và rendering
docs/                          Đặc tả và sơ đồ để phát triển báo cáo
```

Stack: Java 25, Spring Boot 4.0.5, Spring MVC, Thymeleaf, Spring Security, Spring JDBC, H2, JUnit 5. Dự án có chủ đích dùng SQL rõ ràng để dễ hiểu quan hệ giữa đối tượng và bảng; chưa dùng ORM/JPA.

## Kiểm thử và đóng gói

```powershell
.\run.ps1 -Test
.\run.ps1 -Package
```

JAR sau đóng gói: `target/moc-restaurant-0.1.0.jar`. Chạy bằng `java -jar ...` với **JDK 25**, không dùng Java 8 mặc định trên máy.

Test dùng database trong bộ nhớ riêng, không xóa dữ liệu demo. Bao gồm biên 3 giờ/2 giờ, giao dịch đồng thời, ghép bàn, thời gian dọn bàn, cọc lưu theo lượt đặt, dữ liệu món, callback trễ/lặp/sai tiền, đăng nhập, CSRF, phân quyền và template.

Demo gợi ý: đăng nhập khách → ngày mai, 18:00–20:00, 9 người → B01+B02+B03 → chọn món → cọc 300.000đ → thanh toán demo → gửi yêu cầu đổi giờ → đăng nhập nhân viên xử lý → khách hủy → nhân viên ghi nhận hoàn cọc.
