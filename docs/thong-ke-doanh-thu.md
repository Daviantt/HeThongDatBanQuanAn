# Thống kê doanh thu và món bán chạy

Quản lý đăng nhập rồi chọn **Thống kê** trên thanh điều hướng, hoặc **Xem thống kê doanh thu** tại trang Quản lý. Đường dẫn: `/admin/statistics`. Khách hàng và nhân viên không được truy cập.

## Cách sử dụng

- Chọn **Ngày bắt đầu**, **Ngày kết thúc**, cách tổng hợp **Ngày / Tháng / Năm**, rồi nhấn **Xem thống kê**.
- Khoảng lọc bao gồm cả hai ngày, theo giờ Việt Nam. Mặc định từ đầu tháng hiện tại đến hôm nay.
- Có nút chọn nhanh **Hôm nay**, **Tháng này**, **Năm nay**.
- Tổng quan hiển thị tổng doanh thu, doanh thu tại quán, doanh thu mang về và số đơn hoàn tất.
- Bảng doanh thu tổng hợp theo kỳ đã chọn; kỳ không có đơn không tạo dòng. Khoảng trống có thông báo và tổng bằng 0.
- Bảng món ăn hiển thị số phần, số đơn chứa món và tháng đạt nhãn bán chạy, xếp theo số phần giảm dần.

## Quy tắc tính

Chỉ tính đơn có trạng thái `COMPLETED` ở cả đặt bàn và mang về. Đơn chờ thanh toán, chưa hoàn tất, đã hủy, hết hạn, không đến hoặc chờ hoàn tiền không được tính.

- **Đặt bàn:** tổng giá món đã lưu trong từng dòng đơn, trừ khuyến mãi đã chốt cho lượt đặt. Làm tròn giảm giá xuống đến đồng giống cách tính tổng tiền hiện tại.
- **Mang về:** tổng tiền đã lưu trong đơn.
- **Tiền cọc:** là khoản trả trước, không cộng thêm vào doanh thu tiền món.
- **Ngày ghi nhận:** thời điểm hoàn tất phục vụ (`reservation.released_at`) hoặc giao món (`takeaway_order.completed_at`), không phải ngày tạo đơn hoặc trả cọc.
- **Dữ liệu cũ:** nếu chưa có thời điểm hoàn tất, dùng `reservation.end_at` hoặc `takeaway_order.pickup_at`. Cột `completed_at` được thêm tự động khi khởi động bằng migration có thể chạy lại trong `schema.sql`.

Đây là doanh thu tiền món ghi nhận từ đơn trong hệ thống. Chưa có hóa đơn/POS để ghi nhận món gọi thêm, khoản thu ngoài hệ thống hoặc đối soát thực thu. Thanh toán mô phỏng của bản demo vẫn xuất hiện trong báo cáo nếu đơn đã hoàn tất.

## Món bán chạy

Một món được đánh dấu **Bán chạy** khi xuất hiện trong **trên 700 đơn hoàn tất (tối thiểu 701 đơn)** trong cùng một tháng dương lịch, gộp hai kênh tại quán và mang về.

- Một đơn đặt 5 phần của món chỉ đóng góp **1 đơn**, nhưng đóng góp **5 phần**.
- Đúng 700 đơn chưa đạt; 701 đơn đạt.
- Các tháng và các năm được xét riêng. Ví dụ 400 đơn tháng 1 + 400 đơn tháng 2 không đạt.
- Chỉ xét đơn nằm trong khoảng lọc. Nếu chọn từ ngày 10 đến ngày 20, tháng đó chỉ tính các đơn trong 11 ngày đã chọn.
- Nếu nhiều tháng đạt, dòng món liệt kê từng tháng và số đơn tương ứng.

## Tự động có món mới

Báo cáo lấy toàn bộ danh mục `menu_item` rồi ghép với số liệu bán hàng. Sau khi quản lý thêm món thành công, mở hoặc tải lại trang thống kê sẽ thấy món đó ngay, với **0 phần / 0 đơn** nếu chưa phát sinh đơn hoàn tất. Không cần thao tác đồng bộ hoặc thêm dòng thống kê thủ công.

Món tạm ngừng bán hoặc hết tồn vẫn giữ số liệu lịch sử. Thay đổi giá thực đơn không đổi doanh thu của đơn cũ.

## Hướng lưu trữ để nâng cấp database

Chọn **database quan hệ**, dùng H2 dạng file hiện tại và định hướng chuyển sang **PostgreSQL** khi triển khai cần máy chủ database riêng. H2 hiện đã là database; việc chuyển sau này là đổi hệ quản trị, không phải chuyển dữ liệu từ giao diện hoặc file JSON sang database.

Luồng của chức năng mới:

1. `StatisticsController` nhận bộ lọc, kiểm tra đầu vào và trả trang HTML.
2. `StatisticsService` tổng hợp kỳ báo cáo, xét ngưỡng bán chạy và ghép toàn bộ danh mục món với số liệu bán hàng.
3. `StatisticsRepository` chứa SQL có tham số, đọc qua `JdbcTemplate` và DataSource do Spring cấu hình.
4. Các bảng `menu_item`, `reservation`, `order_line`, `takeaway_order`, `takeaway_order_line` là nguồn dữ liệu gốc.

Không tạo bảng doanh thu hoặc bảng thống kê món để ghi thêm mỗi khi phát sinh đơn. Tổng doanh thu, số phần, số đơn và nhãn bán chạy được tính từ dữ liệu gốc khi xem báo cáo; tránh phải đồng bộ hai bản số liệu. Món liên kết bằng ID và khóa ngoại. Giá tại thời điểm đặt, khuyến mãi đã áp dụng và thời điểm hoàn tất được lưu để giữ lịch sử.

Truy vấn tổng hợp chạy trong database; dịch vụ chỉ nhận kết quả đã nhóm theo ngày hoặc món/tháng, không nạp toàn bộ chi tiết tất cả đơn. Các lần đọc của một báo cáo dùng transaction `REPEATABLE_READ` để giữ cùng một ảnh dữ liệu trong lúc có đơn vừa hoàn tất.

Khi chuyển PostgreSQL:

1. Thêm JDBC driver và profile cấu hình kết nối PostgreSQL; đưa URL, tài khoản và mật khẩu vào biến môi trường.
2. Chuyển việc khởi tạo/thay đổi schema sang migration có phiên bản (ví dụ Flyway), gồm script riêng cho các cú pháp khởi tạo H2 hiện có. Không chỉ đổi URL rồi chạy nguyên `schema.sql`.
3. Chuyển dữ liệu gốc, giữ ID, khóa ngoại, giá lịch sử và mốc thời gian; cập nhật sequence theo ID đã chuyển. Các mốc thời gian hiện tại là giờ địa phương Việt Nam, cần giữ quy ước đó hoặc chuyển múi giờ một cách rõ ràng.
4. Chạy lại kiểm thử tích hợp trên PostgreSQL, đối chiếu tổng doanh thu, số đơn, số phần và tháng bán chạy với H2 trước khi chuyển ứng dụng.
5. Khi dữ liệu lớn, đo truy vấn để thêm chỉ mục hoặc bảng tổng hợp có cơ chế cập nhật rõ ràng. Các thay đổi SQL tập trung ở Repository, giao diện và quy tắc nghiệp vụ tiếp tục dùng được.

Hiện chức năng đã được kiểm thử trên H2; chưa thêm driver, triển khai hoặc kiểm chứng PostgreSQL trong lần thay đổi này.

## Kiểm thử

`StatisticsIntegrationTest` kiểm tra hai kênh, giá lịch sử, khuyến mãi/làm tròn, tiền cọc, trạng thái loại trừ, hai biên ngày, nhóm ngày/tháng/năm, ngưỡng 700/701, số phần so với số đơn, khoảng lọc một phần tháng, món mới qua form quản lý, phân quyền, bộ lọc sai, template và dữ liệu cũ.

Chạy toàn bộ kiểm thử Java bằng `.\run.ps1 -Test`.
