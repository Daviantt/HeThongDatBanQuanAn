# Quản lý bàn ăn theo thao tác nhân viên

## Khách đặt bàn

Khách chọn ngày, giờ đến, số khách và bàn; không nhập giờ kết thúc. Giờ đến phải nằm trong tương lai, trong 60 ngày và trong giờ nhận khách của quán.

Mỗi bàn chỉ nhận một lượt chưa được giải quyết trong cùng ngày đặt. Không tự ước lượng thời lượng ăn để bán lại bàn trong ngày. Sau khi nhân viên kết thúc lượt trước, có thể nhận lượt mới với giờ đến từ thời điểm dọn xong trở đi. Giữ bàn chờ cọc vẫn có thời hạn như trước; quá hạn thì bàn được trả lại.

Lượt đặt của những ngày khác được giữ riêng theo ngày. Nếu khách thực tế đang ngồi, bàn vẫn bận cho đến khi nhân viên hoàn tất, kể cả qua ngày mới. Nhân viên phải xử lý lượt trước và dọn xong trước khi nhận lượt kế tiếp. Hệ thống không tự kết thúc phục vụ vào giờ đóng cửa hoặc giờ kết thúc dự kiến cũ.

## Thao tác của nhân viên

Vào **Vận hành → Quản lý bàn ăn**, hoặc chọn **Bàn ăn** trên thanh điều hướng. Quản lý cũng truy cập được; khách hàng không có quyền xem hoặc sửa.

1. Bấm biểu tượng bàn để mở chi tiết.
2. Khi khách đến, chọn **Nhận khách**. Chỉ áp dụng cho lượt đã xác nhận cọc và từ 15 phút trước giờ đến; bàn không được đang phục vụ hoặc đang dọn.
3. Khi khách rời đi, bấm biểu tượng bàn → **Đã sử dụng xong** và xác nhận.
4. Máy chủ lưu thời điểm xác nhận vào `reservation.released_at`, chuyển lượt đặt thành `COMPLETED`.
5. Bàn hiện **Đang dọn bàn**, với hạn dọn = thời điểm vừa lưu + số phút dọn cấu hình bởi quản lý. Hết hạn thì bảng tự cập nhật; bàn trống nếu không có lượt khác đang giữ.

Với bàn ghép, cả nhóm bàn thuộc cùng lượt đặt chuyển trạng thái trong một giao dịch. Không thể chỉ hoàn tất một phần của nhóm bàn. Bấm hoàn tất lặp hoặc thao tác trên dữ liệu cũ sẽ được máy chủ kiểm tra lại, không làm đặt lại giờ kết thúc.

Nhân viên cũng có thể ghi nhận khách không đến sau thời gian chờ, hoặc quán hủy lượt đặt. Việc xử lý tiền cọc theo chính sách hiện có; không sửa trực tiếp trạng thái bàn để bỏ qua cọc hoặc trạng thái phục vụ.

## Đồng bộ với lượt đặt của khách

- Trang đọc trạng thái từ các bảng lượt đặt và bàn trong database, không lưu thêm bản trạng thái riêng trong trình duyệt.
- Trang tự lấy dữ liệu mới mỗi **5 giây**. Lượt đặt mới hiện ngay trong lần cập nhật kế tiếp: **Giữ bàn · Chờ cọc**, sau thanh toán thành **Đã đặt · Chờ khách**.
- Mỗi bàn hiển thị lịch của ngày đã chọn. Các lượt đặt ngày tiếp theo cũng xuất hiện dưới bàn, có liên kết chuyển đến ngày đó.
- Khi mở hộp chi tiết để thao tác, tự làm mới tạm dừng; đóng hộp sẽ cập nhật lại. Máy chủ vẫn luôn xác thực trạng thái tại lúc gửi thao tác.
- Mất kết nối hiển thị cảnh báo dữ liệu có thể cũ và tự thử lại. Không đổi trạng thái thành trống chỉ vì lỗi kết nối.
- Các lượt cũ chưa được xác nhận kết thúc vẫn giữ nguyên lịch sử. Chọn ngày cũ để xử lý lượt xác nhận nhưng khách không đến.

## Dữ liệu và tương thích

`end_at` được cho phép `NULL` và giữ lại cho lịch sử/thống kê cũ. Lượt đặt mới không ghi giờ kết thúc dự kiến; trường `endAt` từ ứng dụng khách cũ được chấp nhận nhưng bỏ qua. Không dùng trường này để mở lại bàn.

`released_at` là mốc kết thúc thực tế, dùng cho thời gian dọn và thống kê doanh thu của đơn hoàn tất. Việc hủy trước 3 tiếng, sửa món trước 2 tiếng và tính cọc vẫn dựa trên giờ đến/chính sách hiện tại.

Trạng thái bảng được tổng hợp tại `TableBoardService`; truy vấn nằm trong `RestaurantRepository`. Thao tác dùng transaction và khóa lịch hiện có để tránh nhận trùng bàn hoặc giải phóng sai khi nhiều nhân viên/khách thao tác đồng thời.

## Kiểm thử

- `TableBoardIntegrationTest`: đặt không cần giờ kết thúc; tự phản ánh giữ/cọc/hủy/hết hạn; bàn ghép; nhân viên hoàn tất; dọn bàn đúng biên; khách ngồi quá giờ/qua ngày; quyền truy cập và CSRF.
- `table-board.test.cjs`: tự làm mới, lỗi kết nối, bấm biểu tượng mở chi tiết, bảo vệ thao tác đang mở và xác nhận hoàn tất.
- Các bộ kiểm thử đặt bàn, thanh toán, thống kê và JavaScript hiện có vẫn được chạy cùng.
