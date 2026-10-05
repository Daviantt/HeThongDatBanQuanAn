# Sơ đồ use-case GiaViên

Tài liệu mô tả **hệ thống đặt bàn và đặt món trước cho một quán ăn**, đối chiếu trực tiếp với mã nguồn hiện tại. Phạm vi gồm truy cập công khai, tài khoản, đặt/giữ bàn, cọc, phục vụ, quản trị và hai chế độ thanh toán. Đây là mô hình chức năng đã triển khai; không bổ sung chức năng dự kiến vào sơ đồ.

## Bộ sơ đồ

[Tải PDF gồm 6 trang](../../output/pdf/giavien-use-case.pdf). Mỗi sơ đồ có ảnh PNG để chèn báo cáo, SVG để phóng lớn và nguồn PlantUML để chỉnh sửa.

| Trang | Nội dung | Tệp |
| --- | --- | --- |
| 1 | Tổng quan tác nhân và các nhóm chức năng | [PNG](01-overview.png) · [SVG](01-overview.svg) · [PlantUML](01-overview.puml) |
| 2 | Đặt bàn và quản lý lượt đặt của người dùng | [PNG](02-customer.png) · [SVG](02-customer.svg) · [PlantUML](02-customer.puml) |
| 3 | Lịch đặt và thao tác phục vụ của nhân viên | [PNG](03-staff.png) · [SVG](03-staff.svg) · [PlantUML](03-staff.puml) |
| 4 | Quản trị thực đơn, bàn, cấu hình và nhân viên | [PNG](04-admin.png) · [SVG](04-admin.svg) · [PlantUML](04-admin.puml) |
| 5 | Thanh toán và xác nhận cọc qua VNPAY Sandbox | [PNG](05-vnpay.png) · [SVG](05-vnpay.svg) · [PlantUML](05-vnpay.puml) |
| 6 | Mô phỏng thanh toán bằng QR demo | [PNG](06-demo.png) · [SVG](06-demo.svg) · [PlantUML](06-demo.puml) |

Sơ đồ tổng quan ưu tiên các mục tiêu chính; các trang chi tiết phân rã hành vi bắt buộc, tùy chọn và các điều kiện nghiệp vụ. ID dưới đây được dùng thống nhất trong cả bộ.

## Tác nhân và ranh giới hệ thống

| Tác nhân | Trách nhiệm/quyền trong hệ thống |
| --- | --- |
| **Khách truy cập** | Xem giới thiệu, thực đơn; đăng ký và đăng nhập. Các trang công khai cũng dùng được khi đã đăng nhập. |
| **Người dùng đã đăng nhập** | Tác nhân tổng quát cho chức năng đặt bàn. Khi thao tác trên một lượt đặt, các chức năng của chủ lượt đặt chỉ áp dụng nếu tài khoản sở hữu lượt đó. |
| **Khách hàng — CUSTOMER** | Chuyên biệt của Người dùng đã đăng nhập; vai trò được tạo bởi đăng ký công khai. |
| **Nhân viên — STAFF** | Chuyên biệt của Người dùng đã đăng nhập; thêm quyền xem lịch toàn quán và thao tác phục vụ. Nhân viên vẫn có thể đặt bàn cho chính tài khoản của mình. |
| **Quản lý — ADMIN** | Chuyên biệt của Nhân viên; kế thừa quyền nhân viên và thêm quản trị. Quản lý cũng có thể là chủ lượt đặt. |
| **VNPAY Sandbox** | Hệ thống bên ngoài nhận yêu cầu thanh toán và gửi IPN xác nhận. Chỉ xuất hiện trong luồng VNPAY. |
| **Người giữ mã QR** | Người có đường dẫn/token của phiên demo, có thể mở và xác nhận mô phỏng mà không đăng nhập trên thiết bị đó. Chỉ xuất hiện trong sơ đồ QR demo. |

Ranh giới là **website GiaViên**, bao gồm kiểm tra quy tắc, lưu lịch, nhật ký và trạng thái cọc. Cơ sở dữ liệu, bộ lập lịch hết hạn giữ bàn và mã tạo QR là thành phần nội bộ, không phải tác nhân. Hiện tại không có tài khoản bếp, module POS, báo cáo doanh thu hay chức năng quên mật khẩu.

Phân quyền không chặn `/book`, `/api/availability` và `/api/bookings` theo riêng vai trò CUSTOMER: những đường dẫn này yêu cầu đăng nhập. Vì vậy gắn toàn bộ chức năng đặt bàn chỉ với CUSTOMER sẽ thiếu quyền thực tế của STAFF/ADMIN. Quyền xem chi tiết của nhân viên rộng hơn quyền sửa/hủy/thanh toán với tư cách chủ lượt đặt. Xem [SecurityConfig.java, dòng 30–58](../../src/main/java/vn/edu/giavien/config/SecurityConfig.java#L30), [BookingService.java, dòng 36–60](../../src/main/java/vn/edu/giavien/service/BookingService.java#L36) và [BookingApi.java, dòng 21–34](../../src/main/java/vn/edu/giavien/web/BookingApi.java#L21).

## Cách đọc quan hệ UML

- **Association**: đường liền giữa tác nhân và use-case cho biết tác nhân tham gia, không biểu diễn thứ tự xử lý.
- **`<<include>>`**: mũi tên nét đứt từ use-case sử dụng đến hành vi bắt buộc được dùng lại. Ví dụ KH02 Đặt bàn bao gồm KH04 Kiểm tra thời gian/số khách và KH05 Kiểm tra bàn/tổ hợp/xung đột.
- **`<<extend>>`**: mũi tên nét đứt từ hành vi tùy chọn hoặc có điều kiện đến use-case cơ sở. Ví dụ KH07 Đặt món trước mở rộng KH02 khi người dùng chọn món; lượt đặt không bắt buộc có món.
- **Generalization**: mũi tên tam giác rỗng hướng về tác nhân hoặc use-case tổng quát. ADMIN kế thừa STAFF; QL03 Thêm món và QL04 Sửa món/trạng thái bán là hai trường hợp của QL02 Lưu món.

**Đăng nhập là tiền điều kiện**, không phải một bước `include` được thực hiện lại mỗi lần đặt bàn hoặc hủy. Tạo lượt giữ bàn và bắt đầu thanh toán là hai mục tiêu độc lập: người dùng có thể tạo một lượt `PENDING` nhưng chưa thanh toán. Yêu cầu đổi lịch và thao tác nhân viên đổi lịch cũng không có quan hệ `include`: mã nguồn cho phép nhân viên đổi một lượt hợp lệ dù chưa có yêu cầu chờ xử lý.

Những use-case kiểm tra/ghi nhận nội bộ trong các trang chi tiết làm rõ trách nhiệm của hệ thống; người dùng không trực tiếp kích hoạt chúng như một chức năng độc lập. Hết hạn giữ bàn là hành vi nội bộ chạy định kỳ và trước nhiều thao tác, không có tác nhân “đồng hồ”.

Các quan hệ phân rã trong bộ sơ đồ:

| Loại | Từ → đến | Điều kiện/ý nghĩa |
| --- | --- | --- |
| Include | KH01 → KH04 | Kiểm tra dữ liệu thời gian/số khách trước khi tra. |
| Include | KH02 → KH03, KH04, KH05, KH06 | Chọn và kiểm tra bàn, tính cọc, tạo hạn giữ cho lượt đặt. |
| Extend | KH07 → KH02 | Khách chọn món trước lúc gửi đặt; có thể đặt bàn không kèm món. |
| Include | KH12 → KH13; NV07 → KH13 | Xác định trạng thái cọc khi khách/quán hủy. |
| Include | NV02 → NV10 | Kiểm tra bàn sẵn sàng trước khi nhận khách. |
| Include | NV05 → KH04, KH05 | Kiểm tra thời gian, số khách và bàn/tổ hợp mới trước khi đổi. |
| Generalization | QL03, QL04 → QL02 | Thêm và sửa là các trường hợp chuyên biệt của lưu món. |
| Include | TT03 → TT04, TT05 | Xác minh IPN rồi xử lý kết quả; IPN sai bị chặn trước khi ghi tiền. |
| Extend | TT06 → TT05 | Thêm một giao dịch thành công khác khoản cọc chính cần hoàn riêng. |
| Include | DM03 → DM04, DM05 | Kiểm tra phiên đủ điều kiện rồi ghi nhận cọc mô phỏng. |

Ghi nhận hoàn cọc không `include` hủy: khoản chờ hoàn cũng có thể phát sinh từ tiền đến muộn. Xử lý callback không `include` tạo lượt giữ bàn: callback xử lý một lượt/attempt đã tồn tại.

## Danh mục use-case

Các tiền điều kiện dưới đây bổ sung cho quyền của tác nhân. “Chủ” nghĩa là tài khoản đăng nhập có `id` trùng `userId` của lượt đặt. STAFF và ADMIN được kế thừa mọi use-case của Người dùng đã đăng nhập khi thỏa điều kiện chủ.

### Truy cập và tài khoản

| ID | Use-case | Tác nhân | Tiền điều kiện/kết quả chính |
| --- | --- | --- | --- |
| UC01 | Xem giới thiệu quán | Khách truy cập | Công khai; trang chủ hiển thị thông tin và một số món đang bán. |
| UC02 | Xem/lọc thực đơn | Khách truy cập | Công khai; xem món và lọc theo danh mục. |
| UC03 | Đăng ký tài khoản | Khách truy cập | Email chưa tồn tại; họ tên, điện thoại và mật khẩu hợp lệ; tạo CUSTOMER. |
| UC04 | Đăng nhập | Khách truy cập | Tài khoản tồn tại và mật khẩu đúng; tạo phiên đăng nhập. |
| UC05 | Đăng xuất | Người dùng đã đăng nhập | Có phiên; kết thúc phiên và trở về trang chủ. |

Nguồn: [PageController.java, dòng 38–77](../../src/main/java/vn/edu/giavien/web/PageController.java#L38), [giavien.js, dòng 11–22](../../src/main/resources/static/js/giavien.js#L11), [BookingService.java, dòng 467–486](../../src/main/java/vn/edu/giavien/service/BookingService.java#L467), [SecurityConfig.java, dòng 51–58](../../src/main/java/vn/edu/giavien/config/SecurityConfig.java#L51).

### Đặt bàn và quản lý lượt đặt

| ID | Use-case | Tác nhân | Tiền điều kiện/kết quả chính |
| --- | --- | --- | --- |
| KH01 | Tra bàn khả dụng | Người dùng đã đăng nhập | Nhập thời gian và 1–12 khách; trả các bàn/tổ hợp phù hợp cùng cọc dự kiến. |
| KH02 | Đặt bàn | Người dùng đã đăng nhập | Lựa chọn hợp lệ tại lúc tạo; lưu chủ lượt, lịch, bàn và trạng thái `PENDING/UNPAID`. |
| KH03 | Chọn bàn/tổ hợp | Người dùng đã đăng nhập | Chọn đúng số bàn theo số khách; tổ hợp ghép được khai báo và phù hợp mặt bằng. |
| KH04 | Kiểm tra thời gian/số khách | Nội bộ | Dùng khi tra/tạo/đổi: tương lai, cùng ngày, trong giờ mở cửa, tối thiểu 30 phút, tối đa 60 ngày, chính xác đến phút. |
| KH05 | Kiểm tra bàn/tổ hợp/xung đột | Nội bộ | Bàn phục vụ, đủ chỗ, tổ hợp hợp lệ, không xung đột kể cả thời gian dọn; kiểm tra lại lúc lưu. |
| KH06 | Tính cọc và giữ bàn | Nội bộ | Cọc = số bàn × cọc mỗi bàn; hạn giữ không vượt quá giờ đến. |
| KH07 | Đặt món trước | Người dùng đã đăng nhập | Tùy chọn trong lúc đặt; món hợp lệ, số lượng 0–20/món; lưu tên và đơn giá tại thời điểm đặt. |
| KH08 | Xem lịch sử của mình | Người dùng đã đăng nhập | Có phiên; chỉ liệt kê lượt thuộc tài khoản đó. |
| KH09 | Xem chi tiết/trạng thái/phản hồi/nhật ký | Người dùng đã đăng nhập; Nhân viên | Chủ xem lượt của mình; STAFF/ADMIN xem mọi lượt; hiển thị món, cọc, yêu cầu, giao dịch và nhật ký. |
| KH10 | Sửa món/ghi chú | Người dùng đã đăng nhập | Chủ; `PENDING` hoặc `CONFIRMED`; trước giờ đến ít nhất 2 giờ, kể cả đúng mốc. |
| KH11 | Gửi yêu cầu đổi bàn/giờ | Người dùng đã đăng nhập | Chủ; `CONFIRMED`, chưa đến giờ, chưa có yêu cầu `PENDING`; lịch/bàn cũ vẫn được giữ. |
| KH12 | Hủy lượt đặt của mình | Người dùng đã đăng nhập | Chủ; `PENDING` hoặc `CONFIRMED`, chưa đến giờ; đóng yêu cầu đang chờ. |
| KH13 | Xác định xử lý cọc khi hủy | Nội bộ | Nếu đã cọc: khách hủy trước ít nhất 3 giờ → `REFUND_PENDING`; dưới 3 giờ → `FORFEITED`; quán hủy → `REFUND_PENDING`. Chưa cọc giữ `UNPAID`. |

Nguồn: [BookingService.java, dòng 79–242](../../src/main/java/vn/edu/giavien/service/BookingService.java#L79) (tra/tạo), [dòng 244–310](../../src/main/java/vn/edu/giavien/service/BookingService.java#L244) (sửa/hủy/yêu cầu), [BookingPolicy.java, dòng 10–37](../../src/main/java/vn/edu/giavien/domain/BookingPolicy.java#L10) (biên thời gian/xung đột), [PageController.java, dòng 85–112](../../src/main/java/vn/edu/giavien/web/PageController.java#L85) (lịch sử/chi tiết).

### Phục vụ và hoàn cọc

| ID | Use-case | Tác nhân | Tiền điều kiện/kết quả chính |
| --- | --- | --- | --- |
| NV01 | Xem/tìm lịch toàn quán và thống kê nhanh | Nhân viên | STAFF/ADMIN; tìm trong danh sách, xem số lượt hôm nay, xác nhận, đang phục vụ và chờ hoàn. |
| NV02 | Nhận khách | Nhân viên | `CONFIRMED`; từ 15 phút trước hẹn đến trước giờ kết thúc; bàn không xung đột; chuyển `SEATED`. |
| NV03 | Hoàn tất phục vụ | Nhân viên | `SEATED`; chuyển `COMPLETED`, lưu giờ trả bàn để tính thời gian dọn. |
| NV04 | Ghi nhận khách không đến | Nhân viên | `CONFIRMED`; hết thời gian chờ theo cấu hình; chuyển `NO_SHOW/FORFEITED`. |
| NV05 | Đổi bàn/giờ | Nhân viên | `CONFIRMED`, chưa đến giờ; lịch/bàn mới hợp lệ, giữ nguyên số bàn; cập nhật mọi yêu cầu đang chờ thành `APPROVED`. **Không bắt buộc có yêu cầu PENDING.** |
| NV06 | Từ chối yêu cầu đổi | Nhân viên | Có yêu cầu `PENDING`; phản hồi 1–500 ký tự; chuyển yêu cầu thành `REJECTED`. |
| NV07 | Quán hủy lượt đặt | Nhân viên | `PENDING` hoặc `CONFIRMED`; nếu đã cọc thì chờ hoàn toàn bộ, không phụ thuộc mốc 3 giờ. |
| NV08 | Ghi nhận đã hoàn cọc | Nhân viên | Cọc `REFUND_PENDING`; đã hoàn bên ngoài và nhập mã giao dịch 3–150 ký tự; chuyển `REFUNDED`. |
| NV09 | Ghi nhận hoàn khoản thu thêm | Nhân viên | Giao dịch thuộc lượt đặt và có trạng thái `EXTRA_REFUND_PENDING`; nhập mã hoàn để chuyển giao dịch thành `REFUNDED`. |
| NV10 | Kiểm tra bàn sẵn sàng | Nội bộ | Dùng khi nhận khách: không xung đột trong khoảng từ hiện tại đến giờ kết thúc, loại trừ chính lượt đang nhận. Khác KH05 kiểm tra lựa chọn bàn/tổ hợp khi đặt/đổi. |

Nguồn: [PageController.java, dòng 163–233](../../src/main/java/vn/edu/giavien/web/PageController.java#L163), [giavien.js, dòng 23–30](../../src/main/resources/static/js/giavien.js#L23), [BookingService.java, dòng 312–418](../../src/main/java/vn/edu/giavien/service/BookingService.java#L312), [VnpayPaymentService.java, dòng 241–257](../../src/main/java/vn/edu/giavien/service/VnpayPaymentService.java#L241).

### Quản trị

| ID | Use-case | Tác nhân | Tiền điều kiện/kết quả chính |
| --- | --- | --- | --- |
| QL01 | Xem dữ liệu quản trị | Quản lý | ADMIN; xem danh sách món, bàn, tổ hợp và tài khoản. |
| QL02 | Lưu món ăn | Quản lý | Use-case tổng quát của thêm/sửa; tên, giá, mô tả và danh mục hợp lệ. |
| QL03 | Thêm món | Quản lý | ADMIN; gửi `id=0`; tạo món với ảnh chung và trạng thái bán đã chọn. |
| QL04 | Sửa món/trạng thái bán | Quản lý | ADMIN; món tồn tại; sửa tên, mô tả, danh mục, giá hoặc trạng thái bán. |
| QL05 | Bật/tạm ngừng bàn | Quản lý | ADMIN; bàn tồn tại, không bị retired, không còn lượt hiệu lực có giờ kết thúc trong tương lai. |
| QL06 | Thêm tổ hợp ghép bàn | Quản lý | ADMIN; 2–3 bàn tồn tại, liền nhau cùng tầng theo hàng/cột, phù hợp mặt bằng. |
| QL07 | Xóa tổ hợp ghép bàn | Quản lý | ADMIN; 2–3 mã bàn tồn tại; bỏ tổ hợp khỏi danh sách được phép ghép. |
| QL08 | Cấu hình cọc/thời gian | Quản lý | ADMIN; cọc 10.000–10.000.000đ/bàn, giữ 5–30 phút, dọn/chờ khách 0–60 phút; cọc đã lưu của lượt cũ giữ nguyên. |
| QL09 | Tạo tài khoản nhân viên | Quản lý | ADMIN; thông tin đăng ký hợp lệ, email chưa tồn tại; tạo vai trò STAFF. |

Nguồn: [PageController.java, dòng 236–300](../../src/main/java/vn/edu/giavien/web/PageController.java#L236), [BookingService.java, dòng 489–594](../../src/main/java/vn/edu/giavien/service/BookingService.java#L489). QL03 và QL04 chuyên biệt QL02; hai nhánh thực tế dùng cùng phương thức `saveDish`, không phải chuỗi thao tác thêm rồi sửa.

### VNPAY Sandbox

| ID | Use-case | Tác nhân | Tiền điều kiện/kết quả chính |
| --- | --- | --- | --- |
| TT01 | Khởi tạo thanh toán VNPAY | Người dùng đã đăng nhập; VNPAY Sandbox | Chủ; `PENDING/UNPAID`; cấu hình VNPAY hợp lệ; dùng lại attempt đang chờ hoặc tạo attempt mới sau thất bại. |
| TT02 | Xem kết quả/trạng thái thanh toán | Chủ lượt đặt; Nhân viên/Quản lý (xem trạng thái) | Return URL công khai hiển thị kết quả đã kiểm tra; API trạng thái cần chủ hoặc nhân viên. Trở về trình duyệt không xác nhận cọc. |
| TT03 | Tiếp nhận và xử lý IPN | VNPAY Sandbox | Callback gửi vào endpoint công khai; chỉ thông điệp hợp lệ mới dẫn đến ghi nhận giao dịch. |
| TT04 | Kiểm tra chữ ký/mã/số tiền | Nội bộ | Kiểm tra merchant, chữ ký, định dạng, mã giao dịch và số tiền đúng attempt; từ chối tham số trùng/sai dữ liệu. |
| TT05 | Ghi nhận kết quả thanh toán | Nội bộ | IPN đã xác minh; thành công trong hạn → `CONFIRMED/PAID`; tiền đến muộn → cọc `REFUND_PENDING`, không khôi phục lượt đặt. Callback lặp không ghi tiền lần hai. |
| TT06 | Ghi nhận khoản thu thêm | Nội bộ | IPN thành công của attempt khác khi cọc lượt đặt đã được ghi nhận; lưu `EXTRA_REFUND_PENDING` để nhân viên hoàn riêng. |

Nguồn: [VnpayPaymentService.java, dòng 67–159](../../src/main/java/vn/edu/giavien/service/VnpayPaymentService.java#L67) (khởi tạo/IPN), [dòng 162–229](../../src/main/java/vn/edu/giavien/service/VnpayPaymentService.java#L162) (return/trạng thái), [VnpayGateway.java, dòng 44–107](../../src/main/java/vn/edu/giavien/service/VnpayGateway.java#L44) (ký/xác minh), [BookingApi.java, dòng 45–66](../../src/main/java/vn/edu/giavien/web/BookingApi.java#L45) (endpoint IPN), [BookingService.java, dòng 422–458](../../src/main/java/vn/edu/giavien/service/BookingService.java#L422) (xác nhận cọc).

### Thanh toán QR demo

| ID | Use-case | Tác nhân | Tiền điều kiện/kết quả chính |
| --- | --- | --- | --- |
| DM01 | Mở phiên QR demo | Người dùng đã đăng nhập | Chủ; `PENDING/UNPAID`; demo bật và VNPAY chưa cấu hình; tạo hoặc dùng lại token của lượt đặt. |
| DM02 | Mở/xem phiên demo | Người giữ mã QR | Có token hợp lệ; xem mã lượt, số tiền, trạng thái/đếm ngược và QR; không cần đăng nhập. |
| DM03 | Mô phỏng thanh toán thành công | Người giữ mã QR | Gửi xác nhận chủ động sau đủ 60 giây từ lúc mở phiên; lượt còn hiệu lực. |
| DM04 | Kiểm tra token/thời gian/trạng thái | Nội bộ | Kiểm tra token tồn tại, demo đang bật, thời gian chờ và trạng thái lượt; chặn xác nhận trước hạn/hết hiệu lực. |
| DM05 | Xác nhận cọc mô phỏng | Nội bộ | Phiên `READY`; lưu `CONFIRMED/PAID`, nhà cung cấp `DEMO` và nhật ký; không có tiền thật. Xác nhận lặp không ghi nhận thêm. |

Nguồn: [DemoPaymentController.java, dòng 42–91](../../src/main/java/vn/edu/giavien/web/DemoPaymentController.java#L42), [DemoPaymentService.java, dòng 34–94](../../src/main/java/vn/edu/giavien/service/DemoPaymentService.java#L34) (chế độ/phiên/token), [dòng 106–148](../../src/main/java/vn/edu/giavien/service/DemoPaymentService.java#L106) (trạng thái/xác nhận).

## Đặc tả ngắn các luồng chính

### 1. Đặt bàn và xác nhận cọc

**Mục tiêu:** giữ bàn phù hợp rồi xác nhận đặt bàn bằng cọc. **Tác nhân khởi tạo:** Người dùng đã đăng nhập. **Tiền điều kiện:** có phiên; thời gian/số khách hợp lệ.

1. Người dùng nhập giờ đến, kết thúc và số khách; hệ thống tra bàn và tính cọc dự kiến (KH01, KH04).
2. Người dùng chọn bàn hoặc tổ hợp; có thể chọn món và ghi chú (KH03, KH07).
3. Khi gửi đặt bàn, hệ thống khóa lịch, xử lý giữ bàn đã hết hạn, kiểm tra lại thời gian và bàn; lưu lượt `PENDING/UNPAID`, cọc và hạn giữ (KH02, KH04–KH06).
4. Chủ lượt chọn thanh toán. Với VNPAY, hệ thống tạo/dùng lại attempt và chuyển đến URL có chữ ký (TT01).
5. VNPAY gửi IPN; hệ thống xác minh và ghi nhận cọc. Còn hạn thì lượt chuyển `CONFIRMED/PAID`; người dùng xem kết quả (TT03–TT05, TT02).

**Nhánh khác:** bàn vừa được người khác giữ → từ chối tạo; không thanh toán trước hạn → `EXPIRED`; IPN sai → không xác nhận; giao dịch thất bại → có thể thử lại khi lượt còn hợp lệ. IPN thành công đến sau hủy/hết hạn ghi `REFUND_PENDING` và giữ trạng thái lượt. Return URL chỉ phản ánh kết quả, không thay IPN. Chế độ demo thay bước 4–5 bằng luồng 6.

### 2. Yêu cầu đổi và nhân viên đổi bàn/giờ

**Mục tiêu:** điều chỉnh lượt đã xác nhận. **Tiền điều kiện:** `CONFIRMED`, chưa đến giờ; người gửi yêu cầu là chủ lượt.

1. Chủ gửi nội dung yêu cầu 5–500 ký tự; hệ thống tạo yêu cầu `PENDING` nếu chưa có yêu cầu chờ (KH11). Bàn/giờ hiện tại vẫn giữ nguyên.
2. Nhân viên xem lượt, chọn lịch, số khách và bàn mới; nhập phản hồi (NV05).
3. Hệ thống kiểm tra lịch/bàn mới và số bàn bằng số bàn cũ; cập nhật lịch, bàn và mọi yêu cầu đang chờ thành `APPROVED`.
4. Chủ xem lịch mới và phản hồi ở chi tiết lượt (KH09).

**Nhánh khác:** lịch/bàn xung đột hoặc đổi số bàn → từ chối cập nhật; nhân viên có thể từ chối yêu cầu kèm phản hồi (NV06). NV05 cũng thực hiện được trực tiếp khi không có yêu cầu chờ; không mô hình hóa KH11 là tiền điều kiện bắt buộc của NV05. Tiền cọc giữ nguyên.

### 3. Hủy và ghi nhận hoàn cọc

**Mục tiêu:** kết thúc lượt chưa phục vụ và xử lý trạng thái cọc. **Tiền điều kiện:** khách tự hủy phải là chủ, chưa đến giờ và lượt `PENDING/CONFIRMED`; quán hủy cần STAFF/ADMIN và lượt `PENDING/CONFIRMED`.

1. Chủ hoặc nhân viên thực hiện hủy (KH12 hoặc NV07).
2. Hệ thống xác định xử lý cọc (KH13), chuyển lượt `CANCELLED`, đóng yêu cầu đang chờ và ghi nhật ký.
3. Nếu cọc chờ hoàn, nhân viên hoàn tiền bên ngoài hệ thống rồi nhập mã giao dịch (NV08); trạng thái cọc chuyển `REFUNDED`.

**Quy tắc:** khách hủy trước ít nhất 3 giờ được hoàn toàn bộ, kể cả đúng mốc 3 giờ; hủy dưới 3 giờ bị mất cọc. Quán hủy hoàn toàn bộ cọc. Nếu chưa thanh toán, không tạo khoản hoàn. Khoản thu thêm được hoàn/ghi nhận theo giao dịch riêng qua NV09; hệ thống không tự gửi lệnh hoàn tiền đến VNPAY.

### 4. Nhận khách và hoàn tất phục vụ

**Mục tiêu:** ghi nhận quá trình phục vụ và lúc trả bàn. **Tác nhân:** Nhân viên, gồm ADMIN qua kế thừa.

1. Nhân viên tìm lượt đã xác nhận trong lịch toàn quán (NV01).
2. Từ 15 phút trước giờ hẹn đến trước giờ kết thúc, nhân viên nhận khách; hệ thống kiểm tra bàn sẵn sàng, không xung đột và chuyển `SEATED` (NV02, NV10). Nhật ký ghi bắt đầu chuẩn bị món.
3. Khi phục vụ xong, nhân viên hoàn tất; hệ thống chuyển `COMPLETED`, ghi giờ trả bàn; thời gian dọn vẫn được tính khi tra lượt tiếp theo (NV03).

**Nhánh khác:** khách chưa đến sau thời gian chờ, nhân viên đánh dấu `NO_SHOW/FORFEITED` (NV04). Việc không đến không tự xảy ra do bộ đếm giờ. Chưa nhận bàn không thể hoàn tất. Không có use-case bếp hoặc thanh toán hóa đơn tại quán trong phiên bản này.

### 5. Quản trị dữ liệu quán

**Mục tiêu:** duy trì thực đơn, khả năng nhận đặt và tài khoản nhân viên. **Tiền điều kiện:** ADMIN.

1. Quản lý mở trang quản trị và xem dữ liệu hiện có (QL01).
2. Chọn thêm/sửa món, bật/tạm ngừng bàn, thêm/xóa tổ hợp, sửa cọc/thời gian hoặc tạo STAFF (QL03–QL09).
3. Hệ thống kiểm tra quyền và dữ liệu tương ứng, lưu thay đổi rồi hiển thị thông báo.

**Nhánh khác:** thông tin món/tài khoản/cấu hình sai → không lưu; bàn còn lượt hiệu lực → không đổi trạng thái bàn; tổ hợp sai mặt bằng → không thêm. Thay đổi cấu hình cọc không tính lại cọc của lượt đã tạo. Món đã đặt lưu đơn giá riêng; sửa giá thực đơn không đổi đơn giá đã lưu của dòng món cũ.

### 6. Thanh toán QR demo

**Mục tiêu:** trình diễn nhận cọc trên máy tính/điện thoại. **Tiền điều kiện:** demo bật, VNPAY chưa cấu hình; lượt `PENDING/UNPAID` còn hiệu lực.

1. Chủ lượt đã đăng nhập mở phiên demo; hệ thống tạo hoặc dùng lại token và thời điểm bắt đầu (DM01).
2. Người giữ mã quét QR hoặc mở đường dẫn; hệ thống kiểm tra token, hiển thị số tiền, trạng thái và thời gian còn chờ (DM02, DM04).
3. Sau đủ 60 giây từ lúc mở phiên, người giữ mã tự bấm mô phỏng thành công (DM03). Quét mã, mở trang và chờ không tự xác nhận.
4. Hệ thống khóa lịch, xử lý hết hạn, kiểm tra phiên `READY`, lưu `CONFIRMED/PAID` với nhà cung cấp `DEMO` (DM04–DM05). Các trang khác đọc trạng thái để cập nhật kết quả.

**Nhánh khác:** token sai hoặc demo tắt → không truy cập được; chưa đủ 60 giây → chưa được xác nhận; lượt hủy/hết hạn → đóng phiên; gửi xác nhận lặp sau thành công → không ghi cọc lần hai. Tải lại hoặc mở trên thiết bị khác không đặt lại mốc 60 giây. Đây là mô phỏng, không tạo attempt VNPAY và không chuyển tiền thật.

## Các điểm cần giữ khi đưa vào báo cáo

- Số bàn bằng `ceil(số khách/4)`, tối đa 3 bàn. Ghép bàn cần tổ hợp đã khai báo và đúng mặt bằng; không ghép giữa hai tầng hoặc xuyên vùng trống. Sơ đồ mặt bằng hiện tại có 19 bàn phục vụ.
- Khoảng dùng bàn và thời gian dọn được kiểm tra ở máy chủ; kết quả tra bàn chưa phải cam kết giữ bàn. Tạo/đổi/nhận/hủy đều bảo vệ lịch bằng transaction và khóa chung.
- Trạng thái lượt đặt tách khỏi trạng thái thanh toán: `PENDING → CONFIRMED → SEATED → COMPLETED`; các nhánh kết thúc gồm `EXPIRED`, `CANCELLED`, `NO_SHOW`. Cọc có `UNPAID`, `PAID`, `REFUND_PENDING`, `REFUNDED`, `FORFEITED`.
- QR demo và VNPAY là hai chế độ thay thế. Khi VNPAY được cấu hình hợp lệ, QR demo tự tắt. Actor Người giữ mã QR không đại diện cho khách thanh toán VNPAY.
- Thống kê NV01 là các con số theo trạng thái lượt đặt, không phải báo cáo doanh thu. “Ghi nhận hoàn” là nhập kết quả đã hoàn bên ngoài, không phải hoàn tự động.
- Đổi lịch hiện giữ nguyên số bàn; chưa đối soát cọc tăng/giảm, chưa có POS/hóa đơn, khóa tài khoản hoặc khôi phục mật khẩu.

Nguồn bổ sung: [FloorPlan.java](../../src/main/java/vn/edu/giavien/domain/FloorPlan.java), [RestaurantRepository.java, dòng 183–230](../../src/main/java/vn/edu/giavien/data/RestaurantRepository.java#L183), [BookingService.java, dòng 438–464](../../src/main/java/vn/edu/giavien/service/BookingService.java#L438), [README dự án, dòng 52–82](../../README.md#L52). Use-case là mô hình mục tiêu và quyền tương tác; khi cần trình bày thứ tự chi tiết hoặc chuyển trạng thái, dùng thêm sơ đồ sequence/activity/state.
