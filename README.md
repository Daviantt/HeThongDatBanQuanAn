# GiaViên — Website đặt bàn và đặt món trước

Đồ án Phân tích thiết kế hướng đối tượng, dành cho **một quán ăn**. Đây là phiên bản đầu chạy được bằng Java; tên quán và hình minh họa là dữ liệu mẫu.

## Chạy trên máy hiện tại

Mở PowerShell trong thư mục dự án:

```powershell
.\run.ps1
```

Truy cập **http://localhost:8080**. Dừng bằng `Ctrl+C` tại terminal đang chạy.

JDK 21 và Maven đã được chuẩn bị trong `.tools`, không thay Java mặc định của Windows. Nếu máy chặn chạy file `.ps1`, có thể mở dự án bằng IntelliJ IDEA, chọn JDK 21 và chạy `GiaVienApplication`.

Trên máy Windows khác: chạy `./setup.ps1` một lần để tải JDK và Maven chính thức, có kiểm tra checksum, sau đó `./run.ps1`. Cần kết nối Internet cho lần tải dependency đầu tiên. Hoặc dùng JDK 21 và Maven cài sẵn: `mvn spring-boot:run`.

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
- Sơ đồ 2 tầng, tổng cộng 19 bàn, mỗi bàn 4 chỗ. Tầng 1 gồm B01–B08 và B10 quanh vườn (9 bàn); vị trí B09 được dành cho lối đi từ cửa vào. Tầng 2 gồm B11–B20 quanh khoảng thông tầng và ban công (10 bàn). Chuyển tầng bằng nút trên sơ đồ; đổi tầng sẽ bỏ lựa chọn bàn trước đó.
- Ghép 2–3 bàn liền nhau cùng tầng theo hàng ngang hoặc dọc, ví dụ B01 + B04 hoặc B11 + B14. Không ghép xuyên vườn, thông tầng hoặc giữa hai tầng.
- Chọn món trước, số lượng, ghi chú; giá món và tiền cọc được lưu tại thời điểm đặt.
- Giữ bàn có thời hạn, chống đặt trùng bằng transaction và khóa trong database.
- Thanh toán QR demo: quét mã mở trang mô phỏng, đợi 60 giây rồi tự bấm xác nhận thành công; đồng bộ kết quả giữa điện thoại và máy tính. Không cần tài khoản thanh toán, không chuyển tiền thật.
- Có sẵn tích hợp VNPAY Sandbox khi cần: tạo giao dịch, ký URL, nhận IPN và kiểm tra kết quả. Khi cấu hình VNPAY hợp lệ, QR demo tự tắt.
- Lịch sử đặt bàn, sửa món/ghi chú, hủy và theo dõi hoàn cọc.
- Khách gửi yêu cầu đổi bàn/giờ; nhân viên chấp nhận với lịch/bàn mới hoặc từ chối kèm lý do.
- Nhân viên nhận khách, hoàn tất phục vụ, ghi nhận không đến, quán hủy và ghi nhận đã hoàn cọc.
- Quản lý thực đơn, tạm ngừng bàn, tổ hợp ghép, cấu hình cọc/thời gian và tạo tài khoản nhân viên.
- Nhật ký thao tác của mỗi lượt đặt.
- Dữ liệu H2 mới lưu trong `data/giavien.mv.db`. Khi chạy bằng `GiaVienApplication`, `run.ps1` hoặc JAR, ứng dụng tự nhận lại `data/moc.mv.db` nếu đó là database cũ duy nhất, để giữ lịch đặt và tài khoản. Có thể chỉ định database bằng biến môi trường `DATABASE_URL`.

Tên hiển thị là **GiaViên**; package Java là `vn.edu.giavien`, file khởi động là `GiaVienApplication.java`, giao diện dùng `giavien.css` và `giavien.js`. `run.ps1` và `setup.ps1` giữ tên theo chức năng. Tài khoản và mật khẩu demo cũ vẫn giữ nguyên để người đang dùng không bị mất quyền đăng nhập.

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
- Tổ hợp mặc định gồm 2–3 bàn liên tiếp theo hàng ngang hoặc dọc trên cùng tầng, không băng qua vườn hay khoảng thông tầng. B09 đã ngừng phục vụ nên không xuất hiện trong các tổ hợp; B08 và B10 không ghép qua lối đi.

## Giới hạn hiện tại

- Đổi bàn/giờ sau cọc hỗ trợ **giữ nguyên số bàn**. Đổi số bàn và đối soát cọc tăng/giảm là phần phát triển tiếp; hệ thống chặn rõ ràng, không tự tính sai tiền.
- Sơ đồ có bố cục mẫu cố định; chưa có trình kéo thả thiết kế mặt bằng.
- Tài khoản nhân viên có thể tạo mới; chưa có khóa tài khoản, quên mật khẩu hoặc xác minh email.
- Tiền món tại quán và việc đối trừ tiền cọc do nhân viên thực hiện; chưa có module hóa đơn/POS.
- H2 phù hợp chạy đồ án một tiến trình, không dùng cho triển khai nhiều máy chủ. Chuyển MySQL/PostgreSQL cần migration SQL tương ứng.
- Hình món ăn hiện là minh họa SVG; cần thay ảnh thật khi chốt thực đơn.

## Thanh toán QR demo

Chạy `./run.ps1`, đặt bàn rồi chọn **Thanh toán bằng QR demo**. Quét bằng camera điện thoại để mở trang mô phỏng, hoặc thao tác trực tiếp trên máy tính. Sau **60 giây kể từ khi mở phiên**, nút **Mô phỏng thanh toán thành công** được bật. Phải tự bấm nút; chờ hoặc quét mã không tự xác nhận cọc. Tải lại trang không làm đếm lại từ đầu.

Sau xác nhận, lượt đặt chuyển thành **Đã xác nhận / Đã nhận cọc**, được lưu với nhà cung cấp `DEMO` và hiện thông báo không thu tiền thật. Trang đang mở trên thiết bị khác tự cập nhật. Bàn đã hủy/hết hạn không thể được xác nhận lại.

Profile mặc định `demo` cho phép kết nối cùng mạng (`0.0.0.0:8080`); QR tự dùng địa chỉ LAN nếu mở bằng localhost. Điện thoại và máy tính cần cùng Wi-Fi và cổng 8080 phải truy cập được. Nếu QR chọn nhầm card mạng, xem IPv4 của Wi-Fi bằng `ipconfig`, rồi đặt biến trước khi khởi động, ví dụ `$env:DEMO_PAYMENT_BASE_URL='http://192.168.1.20:8080'`. Thay địa chỉ ví dụ bằng IP máy bạn. Có thể mở đường dẫn bên dưới QR trên điện thoại để kiểm tra kết nối. Nếu Windows Firewall chặn Java, cho phép ứng dụng trên mạng riêng mà bạn dùng để demo.

QR là đường dẫn riêng cho một phiên demo, cho phép người cầm mã xác nhận mô phỏng mà không cần đăng nhập trên điện thoại. Không có thông tin ngân hàng trong mã. Có thể tắt bằng `$env:DEMO_PAYMENT_ENABLED='false'`; dùng `$env:SERVER_ADDRESS='127.0.0.1'` nếu chỉ chạy trên máy tính. Profile khác mặc định tắt thanh toán demo.

## VNPAY Sandbox (tùy chọn)

Khi cấu hình đủ VNPAY Sandbox, luồng QR demo tự tắt và nút thanh toán chuyển sang VNPAY. Mỗi lần thanh toán VNPAY được lưu vào `payment_attempt`; chỉ IPN hợp lệ mới xác nhận tiền cọc của luồng này. Giao diện có trang kết quả tự cập nhật, xử lý thất bại/hủy, callback lặp và tiền đến sau khi lượt đặt hết hiệu lực.

Xem [hướng dẫn cấu hình và thử thanh toán](docs/vnpay-sandbox.md). Điền mã website và khóa do VNPAY cấp trong `config/payment.properties` (đã được Git bỏ qua), cấu hình Return URL/IPN công khai rồi khởi động lại. **Chưa thực hiện giao dịch qua VNPAY vì chưa có tài khoản Sandbox và địa chỉ IPN công khai.**

Profile `demo` tạo tài khoản mẫu và bật QR mô phỏng khi chưa có cấu hình VNPAY. Chuyển profile không xóa các tài khoản mẫu đã lưu. Thanh toán Sandbox không thu tiền thật; hoàn cọc vẫn do nhân viên thực hiện và ghi nhận mã đối soát.

## Kiến trúc để học và viết báo cáo

```text
src/main/java/vn/edu/giavien/
  GiaVienApplication.java          Điểm khởi động
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

Stack: Java 21, Spring Boot 4.0.5, Spring MVC, Thymeleaf, Spring Security, Spring JDBC, H2, JUnit 5. Dự án có chủ đích dùng SQL rõ ràng để dễ hiểu quan hệ giữa đối tượng và bảng; chưa dùng ORM/JPA.

## Kiểm thử và đóng gói

```powershell
.\run.ps1 -Test
.\run.ps1 -Package
```

JAR sau đóng gói: `target/giavien-restaurant-0.1.0.jar`. Chạy bằng `java -jar ...` với **JDK 21**, không dùng Java 8 mặc định trên máy.

Test dùng database trong bộ nhớ riêng, không xóa dữ liệu demo. Bao gồm biên 3 giờ/2 giờ, giao dịch đồng thời, ghép bàn, thời gian dọn bàn, cọc lưu theo lượt đặt, dữ liệu món, callback trễ/lặp/sai tiền, đăng nhập, CSRF, phân quyền và template.

Kiểm thử JavaScript cho chuyển tầng, QR demo và kết quả VNPAY (cần Node.js):

```powershell
npm.cmd install --prefix .tools/ui-test --no-audit --no-fund jsdom@29.1.1
node --test src/test/js/booking.test.cjs src/test/js/payment.test.cjs src/test/js/demo-payment.test.cjs
```

Demo gợi ý: đăng nhập khách → ngày mai, 18:00–20:00, 9 người → B01+B02+B03 → chọn món → cọc 300.000đ → thanh toán demo → gửi yêu cầu đổi giờ → đăng nhập nhân viên xử lý → khách hủy → nhân viên ghi nhận hoàn cọc.
