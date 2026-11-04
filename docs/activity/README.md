# 3.3.3-3.3.6. Biểu đồ hoạt động AD03-AD06

Bộ sơ đồ dựa trên mã nguồn hiện tại của GiaViên, đối chiếu ngày 25/10/2026. Bố cục theo ảnh mẫu: các làn trách nhiệm dọc, nền pastel, hoạt động bo góc, mũi tên vuông góc, nút quyết định hình thoi, chấm bắt đầu và vòng kép kết thúc. Các bản PNG, SVG, PDF và diagrams.net chỉ giữ phần sơ đồ, không có tiêu đề hay chú thích ở đầu và cuối hình.

[PDF 4 trang](../../output/pdf/giavien-activity-ad03-ad06.pdf) | [File diagrams.net chỉnh sửa 4 trang](giavien-ad03-ad06.drawio)

Mỗi AD nằm trên một trang PDF có kích thước phù hợp chiều dài sơ đồ. PNG dùng để chèn vào báo cáo; SVG và PDF giữ đường nét vector khi phóng to. Mở file `.drawio` bằng diagrams.net, rồi chọn tab AD tương ứng để chỉnh sửa từng làn, hoạt động, đường nối và điều kiện.

## Quy ước và phạm vi

- Khách hàng, Nhân viên và Quản lý là vai trò nghiệp vụ. Giao diện và Hệ thống xử lý nghiệp vụ là các làn trách nhiệm nội bộ, không được tính thành tác nhân ngoài trong biểu đồ use case.
- Quản lý kế thừa quyền xử lý nghiệp vụ của Nhân viên. Không bổ sung vai trò đầu bếp hay thu ngân riêng vì repo chưa có các role này.
- Mã UC05, UC08, UC09, UC06 giữ theo đề cương người dùng. Chúng không phải bảng đánh số use case hiện tại trong `docs/use-case`; không đổi mã use case gốc của repo.
- Theo phạm vi báo cáo đã lưu trong repo, thanh toán trực tuyến được thể hiện bằng QR demo. Không thêm VNPAY, IPN hay giao dịch ngân hàng thật vào sơ đồ.
- Không dùng thanh fork/join khi nghiệp vụ không thực hiện song song. Các nhánh lựa chọn dùng hình thoi; các đường quay lại thể hiện thử lại hoặc chờ tiếp.

## 3.3.3. AD03: Hủy lượt đặt bàn & Xác định điều kiện hoàn cọc

Tương ứng **UC05** theo đề cương.

[PNG](ad03.png) | [SVG](ad03.svg)

![AD03](ad03.png)

**Các tác nhân tham gia:** Khách hàng; Nhân viên/Quản lý. Các làn xử lý nội bộ: Giao diện người dùng; Hệ thống xử lý nghiệp vụ.

**Trạng thái bắt đầu:** Lượt đặt `PENDING` hoặc `CONFIRMED`; người thao tác đã đăng nhập đúng quyền.

**Luồng tuần tự:**

1. Khách mở lượt đặt của mình và chọn hủy, hoặc Nhân viên/Quản lý chọn quán hủy lượt đặt của khách.
2. Giao diện hiển thị thông báo về cọc và yêu cầu xác nhận. Bỏ xác nhận thì kết thúc, giữ nguyên lượt đặt.
3. Backend khóa lịch, xử lý giữ bàn đã hết hạn và kiểm tra quyền, trạng thái hiện tại. Khách chỉ được tự hủy trước giờ đến; quán được hủy khi lượt đặt còn `PENDING`/`CONFIRMED`.
4. Nếu không được phép, báo lỗi và kết thúc thao tác hủy.
5. Xác định cọc: chưa trả thì giữ `UNPAID`; đã trả và quán hủy hoặc khách hủy trước ít nhất 3 giờ thì `REFUND_PENDING`; khách hủy muộn hơn thì `FORFEITED`.
6. Lưu lượt đặt `CANCELLED`, giải phóng phần giữ bàn, đóng yêu cầu đổi đang chờ thành `CLOSED`, ghi nhật ký.
7. Giao diện hiển thị kết quả. Không cần hoàn cọc thì kết thúc; chưa thực hiện hoàn thì kết thúc ở trạng thái chờ hoàn.
8. Khi Nhân viên/Quản lý đã thực hiện hoàn bên ngoài, nhập mã xác nhận; hệ thống kiểm tra và lưu `REFUNDED`, ghi nhật ký, hiển thị kết quả.

**Trạng thái kết thúc:** `CANCELLED` kèm `UNPAID`, `FORFEITED`, `REFUND_PENDING` hoặc `REFUNDED`. Nhánh bỏ thao tác/không đủ điều kiện không ghi thay đổi hủy. Việc dọn giữ bàn hết hạn trước kiểm tra có thể làm trạng thái hiện tại thay đổi thành `EXPIRED`.

**Điểm cần giữ khi sửa hình:** Đúng mốc 3 giờ vẫn được hoàn toàn bộ cọc đã trả. Hệ thống chỉ ghi nhận hoàn cọc; thanh toán và hoàn cọc demo không chuyển tiền thật.

**Căn cứ:** `BookingService.cancel`, `BookingService.staffAction("refund")`; `BookingPolicy.cancellable`, `BookingPolicy.refundable`; thông báo trong `detail.html`.

## 3.3.4. AD04: Vận hành phục vụ bàn & Quản lý thời gian dọn dẹp

Tương ứng **UC08** theo đề cương.

[PNG](ad04.png) | [SVG](ad04.svg)

![AD04](ad04.png)

**Các tác nhân tham gia:** Nhân viên/Quản lý thực hiện thao tác; Khách hàng tham gia thực tế qua việc đến, sử dụng và rời bàn. Khách không có làn thao tác phần mềm riêng trong hình này. Quản lý cấu hình thời gian dọn/chờ trước khi vận hành.

**Trạng thái bắt đầu:** Lượt đặt `CONFIRMED`; Nhân viên mở bảng bàn ăn.

**Luồng tuần tự:**

1. Nhân viên chọn bàn/lượt đặt; giao diện hiển thị trạng thái hiện tại.
2. Nếu khách đến, Nhân viên chọn Nhận khách.
3. Backend kiểm tra `CONFIRMED`, thời điểm từ 15 phút trước giờ hẹn và toàn bộ bàn không đang phục vụ/dọn. Chưa đủ điều kiện thì báo lỗi và cho kiểm tra lại.
4. Hợp lệ thì lưu `SEATED`, ghi nhật ký; giao diện hiển thị Đang phục vụ.
5. Nhân viên phục vụ khách. Khi khách rời bàn, chọn Đã sử dụng xong và xác nhận.
6. Backend kiểm tra `SEATED`, lưu `COMPLETED` và thời điểm thực tế `released_at` cho cả lượt đặt/nhóm bàn.
7. Tính hạn dọn bằng `released_at + cleanupMinutes`. Giao diện hiển thị Đang dọn bàn; Nhân viên dọn bàn.
8. Trong thời gian chưa hết hạn, bảng tiếp tục hiển thị Đang dọn và tự tải lại mỗi 5 giây.
9. Hết hạn thì hệ thống tổng hợp lại trạng thái. Bàn chỉ Trống nếu không còn lượt giữ/đặt khác và bàn vẫn hoạt động; nếu không thì hiển thị Giữ bàn, Chờ khách hoặc Tạm ngừng tương ứng.
10. Nhánh khách chưa đến: sau thời gian chờ, Nhân viên có thể ghi nhận Không đến. Backend kiểm tra lại `CONFIRMED` và hạn chờ; hợp lệ thì lưu `NO_SHOW`, cọc `FORFEITED`, ghi nhật ký và cập nhật bảng.

**Trạng thái kết thúc:** Lượt đặt `COMPLETED`, hết hạn dọn và bảng hiển thị trạng thái phù hợp; hoặc `NO_SHOW`/`FORFEITED`. Nhánh đánh dấu không đến quá sớm kết thúc thao tác với thông báo lỗi, giữ lượt đặt để xử lý sau.

**Điểm cần giữ khi sửa hình:** Repo không có nút Dọn xong; hết dọn được xác định theo thời gian. Không tự kết thúc phục vụ theo giờ dự kiến hay giờ đóng cửa. Bàn ghép được nhận/hoàn tất cùng lượt đặt. Thời gian dọn có thể cấu hình 0-60 phút; nếu bằng 0 thì không có khoảng chờ dọn hữu hiệu. Máy chủ kiểm tra mọi thao tác; thao tác hoàn tất sai trạng thái sẽ bị từ chối thay vì ghi lại `released_at`.

**Căn cứ:** `BookingService.staffAction("checkin"/"complete"/"noshow")`, `BookingService.updateSettings`, `TableBoardService.board`, `table-board.js`, `docs/quan-ly-ban-an.md`.

## 3.3.5. AD05: Điều phối đổi bàn / giờ hẹn

Tương ứng **UC05 & UC09** theo đề cương.

[PNG](ad05.png) | [SVG](ad05.svg)

![AD05](ad05.png)

**Các tác nhân tham gia:** Khách hàng; Nhân viên/Quản lý. Các làn xử lý nội bộ: Giao diện người dùng; Hệ thống xử lý nghiệp vụ.

**Trạng thái bắt đầu:** Chủ lượt đặt có lượt `CONFIRMED`, chưa tới giờ, chưa có yêu cầu `PENDING` khác.

**Luồng tuần tự:**

1. Khách nhập nội dung mong muốn đổi bàn/giờ và gửi yêu cầu.
2. Backend kiểm tra quyền chủ lượt đặt, trạng thái, giờ đến và yêu cầu chờ đang tồn tại. Không hợp lệ thì không tạo yêu cầu và hiển thị lỗi.
3. Hợp lệ thì tạo yêu cầu `PENDING`, ghi nhật ký; lịch/bàn cũ vẫn được giữ.
4. Nhân viên/Quản lý xem yêu cầu và phương án thay thế.
5. Nếu từ chối, nhập lý do; hệ thống lưu `REJECTED`, phản hồi và nhật ký, hiển thị kết quả; lượt đặt không thay đổi.
6. Nếu chấp nhận, nhập giờ đến, số khách, bàn mới và phản hồi.
7. Hệ thống kiểm tra lại lượt đặt còn được đổi; giờ mới thuộc tương lai, trong 60 ngày và giờ phục vụ; 1-12 khách; bàn hoạt động, đủ chỗ, khả dụng theo lịch; tổ hợp ghép hợp lệ; giữ nguyên số bàn. Nếu không hợp lệ, báo lỗi để chọn lại.
8. Lưu giờ đến, số khách và danh sách bàn mới, giữ `CONFIRMED` và cọc đã thu; không ghi giờ kết thúc dự kiến.
9. Đổi yêu cầu đang chờ thành `APPROVED`, lưu phản hồi và nhật ký; hiển thị kết quả cho khách.

**Trạng thái kết thúc:** Yêu cầu `APPROVED` với lịch/bàn mới hoặc `REJECTED` với lịch/bàn cũ; lượt đặt vẫn `CONFIRMED`. Nếu chưa xử lý hoặc phương án chưa hợp lệ, yêu cầu vẫn `PENDING`.

**Điểm cần giữ khi sửa hình:** Yêu cầu của khách là văn bản, không tự áp dụng vào lịch. Repo cho phép Nhân viên điều phối trực tiếp mà không bắt buộc tồn tại yêu cầu đang chờ; hình này trình bày luồng bắt đầu từ yêu cầu khách. Nếu cần đổi số bàn, tạo lượt đặt mới để đối soát cọc.

**Căn cứ:** `BookingService.requestChange`, `reschedule`, `rejectChange`, `validateTime`, `validateTables`; biểu mẫu `detail.html`.

## 3.3.6. AD06: Đặt món mang về & Chốt đơn

Tương ứng **UC06** theo đề cương.

[PNG](ad06.png) | [SVG](ad06.svg)

![AD06](ad06.png)

**Các tác nhân tham gia:** Khách hàng; Người thanh toán bằng token QR (có thể là khách); Nhân viên/Quản lý. Các làn xử lý nội bộ: Giao diện người dùng; Hệ thống xử lý nghiệp vụ.

**Trạng thái bắt đầu:** Khách chọn món mang về. Phải đăng nhập bằng role `CUSTOMER` khi tạo đơn.

**Luồng tuần tự:**

1. Khách chọn món/số lượng. Giao diện lấy báo giá và tình trạng bán hiện tại, tính tổng tiền.
2. Khách nhập người nhận, điện thoại, giờ lấy, chọn tiền mặt hoặc QR demo và xác nhận.
3. Backend kiểm tra quyền, thông tin, giờ lấy trong ngày/giờ phục vụ và cách thời điểm đặt ít nhất 15 phút; 1-30 loại món, mỗi loại 1-20 phần; món khả dụng và giá khớp với giá khách vừa xem.
4. Sai dữ liệu/món thì báo lỗi; giá thay đổi thì trả báo giá mới để khách xác nhận lại.
5. Tiền mặt: tạo `WAITING_CONFIRMATION` và trừ tồn đã khai báo trong cùng giao dịch. Nếu không đủ tồn, toàn bộ tạo đơn bị rollback; khách sửa giỏ và thử lại.
6. QR demo: tạo `WAITING_CONFIRMATION` nhưng chưa trừ tồn, mở phiên QR và hiển thị đếm lùi 60 giây.
7. Người thanh toán mở/quét QR và xác nhận mô phỏng. Nếu chưa thanh toán mà đã tới giờ lấy, hệ thống đổi `EXPIRED` và kết thúc nhánh này.
8. Nếu chưa tới giờ lấy, kiểm tra phiên, đủ 60 giây, đơn còn chờ thanh toán và đủ tồn. Không hợp lệ thì báo lỗi để thử lại; hợp lệ thì ghi nhận thanh toán và trừ tồn trong cùng giao dịch. Thiếu tồn không được ghi nhận thanh toán thành công.
9. Nhân viên chọn tiếp tục xử lý hoặc hủy. Đơn tiền mặt và QR đã trả mới được xác nhận bắt đầu làm; chuyển `PREPARING`.
10. Nhân viên chuẩn bị xong, chọn Chờ khách đến lấy; hệ thống chuyển `READY`.
11. Khách tới nhận. Nếu tiền mặt chưa thu, Nhân viên thu tiền; QR đã ghi nhận thanh toán thì bỏ qua bước thu.
12. Nhân viên giao món và chọn Hoàn tất. Backend kiểm tra `READY`, ghi nhận tiền mặt nếu có, lưu `COMPLETED` và `completed_at`; giao diện hiển thị hoàn thành.
13. Nhánh hủy được gom một chỗ ở cuối hình: nếu chưa trả thì `CANCELLED`; đã trả thì `REFUND_PENDING`; sau khi nhân viên ghi nhận mã hoàn tiền thì `CANCELLED`. Nhánh này cũng áp dụng trong `PREPARING` và `READY`, không chỉ trước khi làm món. Đây là hành vi xử lý chung được tóm tắt trong một hoạt động để giữ hình dễ đọc.

**Trạng thái kết thúc:** `COMPLETED` và đã ghi nhận thanh toán; `EXPIRED` khi QR quá hạn; `CANCELLED` hoặc tạm chờ tại `REFUND_PENDING` khi quán hủy.

**Điểm cần giữ khi sửa hình:** Thanh toán mang về là QR demo, không phải giao dịch ngân hàng. Tiền mặt trừ tồn lúc tạo đơn; QR trừ lúc thanh toán thành công. Tồn NULL giữ ý nghĩa chưa kiểm kê. Nhánh hủy không tự hoàn tồn trong phiên bản hiện tại. Không áp dụng khuyến mãi nhóm theo số khách cho đơn mang về. Sơ đồ tóm tắt các kiểm tra trước lưu, không tách mọi lỗi quyền/cạnh tranh trạng thái thành ô riêng.

**Căn cứ:** `TakeawayService.quote/create/startDemoPayment/paymentStatus/confirmDemoPayment/deductStock/expireUnpaid/staffAction`, `Models.TakeawayStatus`, `TakeawayApi`, `TakeawayController`, `takeaway.js`.

## Tái tạo và kiểm tra

Chạy `build_activity.py` với Python có `reportlab`, `Pillow`, `pypdf` và Poppler `pdftoppm`. Trong môi trường Codex hiện tại:

```powershell
& 'C:/Users/phuct/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' docs/activity/build_activity.py
```

Nguồn hình học trong script được dùng chung cho SVG, PDF và diagrams.net. `model.json` lưu tọa độ, hoạt động, đường nối, điều kiện để kiểm tra/tái sử dụng. PNG được render từ PDF bằng Poppler, không phải ảnh dựng độc lập.

Bộ kiểm tra dựng hình xác nhận chữ vừa ô, các ô không chồng nhau, mũi tên vuông góc, các nút quyết định có điều kiện, mỗi nút được nối từ nút bắt đầu, PDF có đủ bốn trang và nội dung tiếng Việt. Tất cả trang được xem lại bằng ảnh render trước khi giao.
