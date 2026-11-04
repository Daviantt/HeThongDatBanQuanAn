# Hoàn tồn, quản trị viên và CI — issue #16, #17, #18

## Hoàn tồn đơn mang về

`WAITING_CONFIRMATION` là trạng thái hiện tại tương ứng với “chưa chuẩn bị” trong issue #18. CASH trừ tồn khi tạo; TRANSFER trừ khi xác nhận thanh toán. Mỗi dòng món ghi `stock_deducted` bằng số phần thực sự đã trừ, và `stock_returned` đánh dấu đã xử lý hoàn.

- Hủy khi còn chờ xác nhận: hoàn đúng phần đã trừ, một lần. TRANSFER đã thanh toán chuyển `REFUND_PENDING`; hoàn tiền vẫn là thao tác riêng.
- Chưa thanh toán/chưa trừ tồn: không cộng tồn. `PREPARING` và `READY`: không tự hoàn vì có thể đã dùng nguyên liệu.
- Tồn NULL không được biến thành số. Nếu lúc mua tồn NULL rồi quản lý nhập số tồn, hủy không cộng bù phần chưa từng trừ. Nếu sau mua chuyển tồn về NULL, hủy giữ NULL và đánh dấu đã xử lý, không hoàn bù khi bật quản lý tồn trở lại.
- Dòng đơn trước nâng cấp có `stock_deducted=0`, `stock_returned=false`. Không suy đoán từ trạng thái đơn; đối soát tồn của đơn cũ do quản lý thực hiện thủ công.
- Không thay cấu hình cho phép bán khi hoàn tồn. Khóa đơn trước, khóa món theo ID tăng dần; thiếu tồn rollback cả đơn/thanh toán và dấu vết khấu trừ. Hủy lặp bị từ chối mà không cộng lần hai.

Schema thêm hai cột bằng `ADD COLUMN IF NOT EXISTS`, không xóa dữ liệu. Sao lưu theo [hướng dẫn PostgreSQL](postgresql.md) trước khi nâng cấp database có dữ liệu cần giữ.

## Tạo quản trị viên ngoài demo

Khởi động với profile `production` (hoặc một profile khác `demo`). Sau khi schema và seed nền hoàn tất, ứng dụng kiểm tra ADMIN dưới khóa transaction:

1. Chưa có ADMIN: cần `BOOTSTRAP_ADMIN_EMAIL` và `BOOTSTRAP_ADMIN_PASSWORD`. Email được chuẩn hóa chữ thường; mật khẩu tối thiểu 12 ký tự và tối đa 72 byte UTF-8, lưu BCrypt.
2. Đã có ADMIN: giữ nguyên ID, email, hồ sơ và hash; không cần giữ secret bootstrap cho lần khởi động tiếp theo. Đây cũng là quy tắc cho ADMIN đã import.
3. Email cấu hình đã thuộc CUSTOMER/STAFF: báo lỗi, không nâng quyền (kể cả khi đã có ADMIN khác).
4. Thiếu/sai cấu hình trên DB chưa có ADMIN: khởi động thất bại với hướng dẫn. Không tự bật demo hoặc QR demo, không có endpoint tạo ADMIN công khai.

Ví dụ PowerShell, nhập mật khẩu qua prompt để không ghi mật khẩu vào lịch sử lệnh:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
$env:SPRING_PROFILES_ACTIVE = 'production'
$env:BOOTSTRAP_ADMIN_EMAIL = Read-Host 'Email quản trị viên'
$secureAdminPassword = Read-Host 'Mật khẩu quản trị viên' -AsSecureString
$env:BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $secureAdminPassword).Password
try {
    .\run.ps1
} finally {
    Remove-Item Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:BOOTSTRAP_ADMIN_EMAIL -ErrorAction SilentlyContinue
}
```

Giữ secret trong môi trường của tiến trình lần đầu; không đưa vào Git, log, ảnh chụp hoặc tham số dòng lệnh. Bàn giao qua kênh riêng. Sau lần đầu, dừng và chạy lại với profile production mà không có hai biến bootstrap. Nếu DB vốn có tài khoản demo, đổi profile không xóa những tài khoản đó: dùng DB riêng cho vận hành thật và quản lý tài khoản có sẵn theo nhu cầu.

## Kiểm thử tái lập và CI

Workflow `.github/workflows/ci.yml` chạy khi push, pull request hoặc chạy tay: Java 21, Maven 3.9.14 (kiểm SHA-512), PostgreSQL 18, Node 24.14.0 và dependency JS khóa bằng `package-lock.json`. Tài liệu chính thức: [setup-java](https://github.com/actions/setup-java), [setup-node](https://github.com/actions/setup-node).

Database kiểm thử có tên UUID `giavien_test_<32 hex>`, mỗi context dùng schema riêng. CI không cần `.tools`, dữ liệu máy cá nhân hoặc secret thanh toán. Các bước:

1. Chờ PostgreSQL service healthy, tạo database tạm.
2. `mvn -B clean verify`: chạy nghiệp vụ, khóa đồng thời, bootstrap, hoàn tồn và schema upgrade. Fixture H2 chỉ ở test scope để đối chiếu một tài khoản legacy/BCrypt/ID và identity sau import; toàn bộ đích kiểm thử là PostgreSQL. Đây không phải công cụ chuyển toàn bộ dữ liệu H2 của người dùng.
3. `npm ci --ignore-scripts --no-audit --no-fund` và `npm test`.
4. `node scripts/smoke-postgres.cjs`: tạo DB tạm khác, khởi động JAR production, kiểm tra HTTP, lưu dữ liệu đánh dấu, dừng và chạy lại không có secret bootstrap; kiểm tra dữ liệu/hash ADMIN còn nguyên.
5. Dọn database tạm; lưu báo cáo Surefire và JAR, không upload dump, credential hoặc database local.

Trên Windows đã có Docker/database và JDK/Maven:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\database.ps1 -Start
.\run.ps1 -Package
npm.cmd ci --ignore-scripts --no-audit --no-fund
npm.cmd test
# Dùng JDK 21 đã cài hoặc JDK được setup.ps1 tải về:
$env:JAVA_HOME = (Get-ChildItem .tools/jdk -Directory | Select-Object -First 1).FullName
$env:TEST_POSTGRES_PASSWORD = ((Get-Content .env | Where-Object { $_ -match '^POSTGRES_PASSWORD=' }) -replace '^POSTGRES_PASSWORD=', '')
try { node scripts/smoke-postgres.cjs }
finally { Remove-Item Env:TEST_POSTGRES_PASSWORD -ErrorAction SilentlyContinue }
```

Smoke mặc định dùng container `giavien-postgres-1` và cổng ứng dụng 18080. Có thể đặt `PG_CONTAINER`/`SMOKE_PORT`; script từ chối chạy nếu cổng đã bị chiếm. Test không thay đổi database chính `giavien`. Dừng phiên đang chạy JAR trong `target/` trước `-Package` vì Windows khóa file đó.
