# Đặt cọc qua VNPAY Sandbox

GiaViên hiện mặc định dùng **QR demo** khi chưa có thông tin VNPAY: xem [README](../README.md#thanh-toán-qr-demo). Khi điền đủ cấu hình VNPAY hợp lệ, QR demo tự tắt và GiaViên chuyển khách sang trang VNPAY để thanh toán thử. Sandbox không thu tiền thật; giao dịch qua ngân hàng thật nằm ngoài cấu hình này.

## 1. Lấy thông tin tích hợp

Đăng ký tại [VNPAY Sandbox](https://sandbox.vnpayment.vn/devreg/). VNPAY cấp mã website (`TmnCode`) và khóa ký (`HashSecret`) qua thông tin đăng ký. Giữ khóa trên máy, không gửi vào chat hay commit lên GitHub.

## 2. Cấu hình trên máy

File `config/payment.properties` đã được chuẩn bị và được `.gitignore` loại khỏi Git. Nếu clone mới, sao chép `config/payment.example.properties` thành `config/payment.properties`. Điền **thông tin thật của tài khoản Sandbox được cấp**:

```properties
app.vnpay.tmn-code=MA_WEBSITE_DUOC_CAP
app.vnpay.hash-secret=KHOA_BI_MAT_DUOC_CAP
app.vnpay.return-url=https://TEN_MIEN_THU_NGHIEM/payment/vnpay/return
```

Các giá trị trên chỉ là chỗ điền, không phải thông tin kết nối dùng được. File mẫu cũng hỗ trợ biến môi trường `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_RETURN_URL`; không cần sửa `run.ps1`. Chạy từ thư mục gốc dự án bằng `./run.ps1`. Thay đổi cấu hình cần khởi động lại ứng dụng.

Ở trang quản lý `/admin`, thông báo cấu hình sẵn sàng chỉ cho biết đã điền đủ thông tin có định dạng phù hợp, chưa chứng minh kết nối VNPAY thành công.

## 3. Địa chỉ nhận kết quả

- Return URL: `https://TEN_MIEN_THU_NGHIEM/payment/vnpay/return` — trình duyệt quay về trang này.
- IPN URL đăng ký với VNPAY: `https://TEN_MIEN_THU_NGHIEM/payment/vnpay/ipn` — VNPAY gọi máy chủ để xác nhận giao dịch.

VNPAY không gọi được `localhost` trên máy bạn. Cần địa chỉ HTTPS công khai dẫn đến ứng dụng (triển khai thử nghiệm hoặc tunnel), sau đó cập nhật URL với VNPAY. Phiên làm việc hiện tại chưa tạo địa chỉ công khai hay đăng ký IPN thay bạn. Khi thay địa chỉ tunnel, cập nhật cả Return URL và IPN đã đăng ký. Chỉ dùng dữ liệu đồ án trên môi trường thử nghiệm công khai.

Trong luồng VNPAY, Return URL **không cập nhật tiền cọc**. Chỉ IPN có chữ ký đúng, số tiền đúng và mã giao dịch đã được GiaViên tạo mới được ghi nhận. Vì vậy trang quay về có thể tạm hiển thị “Đang chờ xác nhận thanh toán”. Trang tự kiểm tra trong khoảng một phút; vẫn có thể mở lượt đặt để xem kết quả sau đó.

## 4. Thử toàn bộ luồng

1. Đăng nhập, tạo lượt đặt trong tương lai và mở chi tiết lượt đặt.
2. Bấm **Thanh toán VNPAY sandbox**. Số tiền lấy từ cọc đã lưu trong database.
3. Chọn phương thức và sử dụng [thông tin thẻ thử chính thức của VNPAY](https://sandbox.vnpayment.vn/apis/vnpay-demo/). Không dùng thẻ cá nhân.
4. Sau khi quay lại, chờ IPN; kiểm tra lượt đặt chuyển thành **Đã xác nhận / Đã nhận cọc**.
5. Thử hủy ở VNPAY: cọc vẫn chưa thanh toán; sau khi IPN xác nhận thất bại, có thể tạo lần thanh toán mới trong hạn giữ bàn.

Nếu khách bấm nhiều lần khi giao dịch còn chờ, GiaViên dùng lại cùng mã giao dịch. Khi giao dịch thất bại đã được xác nhận, lần thử lại có mã mới. Thời hạn cọc luôn theo lượt giữ bàn, không được kéo dài khi bấm lại.

Nếu tiền đến sau khi hủy/hết hạn, GiaViên ghi **chờ hoàn cọc**, không lấy lại bàn của người khác. Nếu hai giao dịch khác nhau cùng báo đã thu tiền cho một lượt đặt, khoản thu thêm được đánh dấu riêng và hiện trên trang chi tiết để nhân viên ghi nhận hoàn. Việc hoàn tiền do nhân viên thực hiện bên ngoài rồi nhập mã đối soát; chưa có API hoàn tiền tự động.

## 5. Kiểm thử đã có và giới hạn

Kiểm thử tự động dùng khóa giả chỉ trong test và callback ký tại chỗ: URL/tiền cọc, chữ ký sai, sai merchant, sai tiền, giao dịch không tồn tại, tham số trùng, callback lặp, return không cập nhật tiền, hủy/thử lại, hết hạn, thu thêm, phân quyền và CSRF. Chúng không thay thế giao dịch qua tài khoản VNPAY Sandbox.

Chưa thể xác nhận giao dịch qua cổng VNPAY cho đến khi có tài khoản và IPN công khai. Không thêm thông tin cấu hình giả để hiện nút thanh toán.

Nguồn giao thức: [Hướng dẫn PAY và IPN của VNPAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html), [bảng mã phản hồi](https://sandbox.vnpayment.vn/apis/docs/bang-ma-loi/).
