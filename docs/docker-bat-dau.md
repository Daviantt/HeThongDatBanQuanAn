# Chạy GiaViên với Docker trên Windows

## Docker được dùng ở đâu?

Chỉ PostgreSQL chạy trong Docker. Ứng dụng Java/Spring Boot vẫn chạy trực tiếp trên Windows bằng JDK và Maven:

```text
Trình duyệt → localhost:8080 → Spring Boot trên Windows
                                  ↓ JDBC
                         127.0.0.1:15432
                                  ↓
                         PostgreSQL trong Docker:5432
                                  ↓
                         volume giavien_postgres_data
```

- **Image** `postgres:18`: bộ phần mềm PostgreSQL để tạo container.
- **Container** `giavien-postgres-1`: PostgreSQL đang chạy từ image đó.
- **Volume** `giavien_postgres_data`: nơi lưu database, giữ dữ liệu qua các lần dừng và khởi động container.
- **Compose**: đọc `compose.yaml` để tạo container, cổng kết nối, volume và kiểm tra PostgreSQL sẵn sàng.

Không cần cài thêm PostgreSQL trực tiếp lên Windows. Docker Desktop phải hoạt động trước khi chạy database.

## Lần đầu trên máy mới

Mở PowerShell trong thư mục dự án. Nếu Windows chặn script, lệnh sau chỉ cho phép chạy script trong cửa sổ PowerShell hiện tại:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

Nếu chưa có JDK/Maven, chạy `./setup.ps1` trước. Máy có `.tools/jdk` và `.tools/maven` dùng được thì bỏ qua bước này.

```powershell
.\database.ps1 -Init
.\database.ps1 -Start
.\run.ps1
```

`-Init` chỉ chạy một lần: tạo `.env` cho Docker và `config/database.properties` cho Java với cùng mật khẩu ngẫu nhiên. Hai file này được Git bỏ qua. Script từ chối ghi đè cấu hình hiện có.

`-Start` tải image nếu chưa có, khởi động PostgreSQL và chờ database sẵn sàng. Lần đầu cần mạng để tải image và các dependency Java.

Mở http://localhost:8080. Tài khoản khách mẫu: `khach@giavien.local`, mật khẩu `GiaVienDemo123!`; xem các vai trò khác trong README.

## Những lần tiếp theo

Bật Docker Desktop, mở PowerShell tại dự án và chạy:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\database.ps1 -Start
.\run.ps1
```

Dừng Java bằng `Ctrl+C`. Khi muốn dừng database:

```powershell
docker compose stop postgres
```

Không dùng `docker compose down -v` nếu cần giữ dữ liệu: tùy chọn `-v` xóa volume của dự án.

## Kiểm tra và chạy test

```powershell
.\database.ps1 -Status
docker compose logs --tail 50 postgres
.\run.ps1 -Test
```

Test tạo database tạm riêng rồi xóa database tạm sau khi chạy; không dùng database `giavien` để chạy test. `run.ps1 -Package` chạy test rồi đóng gói JAR.

Nếu tải image báo timeout từ Docker Hub, kiểm tra kết nối mạng rồi chạy lại `docker pull postgres:18` và `database.ps1 -Start`. Nếu không kết nối được Docker engine, kiểm tra Docker Desktop đã chạy xong.

## Dữ liệu H2 cũ có tự xuất hiện không?

Không. Commit mới bỏ driver H2 và không có công cụ chuyển dữ liệu H2 sang PostgreSQL. Khởi tạo PostgreSQL mới rồi chạy ứng dụng tạo cấu trúc bảng và dữ liệu mẫu; lịch sử đặt bàn trên H2 không tự chuyển theo.

Các thông tin “đã chuyển dữ liệu” trong `postgresql.md` mô tả lần chuyển trên máy người thực hiện trước đây. Database, volume Docker và bản sao lưu được Git bỏ qua nên không đi cùng `git pull`. Muốn giữ lịch sử cũ trên máy khác cần thực hiện chuyển dữ liệu riêng hoặc khôi phục bản sao PostgreSQL phù hợp. Giữ nguyên các file trong `data/` để có thể đối chiếu/chuyển sau này.

Chi tiết kết nối pgAdmin và sao lưu: [PostgreSQL](postgresql.md).
