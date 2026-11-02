# PostgreSQL cho hệ thống GiaViên

Ứng dụng và toàn bộ kiểm thử tích hợp chỉ sử dụng PostgreSQL. Dependency, profile dự phòng, mã đọc/chuyển database cũ và kiểm thử migration đã được gỡ. Các bản sao lưu cũ được giữ nguyên để đối chiếu lịch sử; dự án không đọc các file này khi chạy.

## Kết nối trên máy này

| Mục | Giá trị |
| --- | --- |
| Host | `127.0.0.1` |
| Port bên ngoài Docker | `15432` |
| Port bên trong container | `5432` |
| Database / username | `giavien` / `giavien` |
| JDBC URL | `jdbc:postgresql://127.0.0.1:15432/giavien` |
| Driver | `org.postgresql.Driver` |
| Mật khẩu | `.env` hoặc `config/database.properties` trên máy; không đưa vào Git |
| Container | `giavien-postgres-1` |
| Volume | `giavien_postgres_data` |

Cổng 15432 chỉ được mở trên loopback. Container dùng PostgreSQL 18, lưu dữ liệu tại `/var/lib/postgresql` qua Docker volume. Dừng container không xóa dữ liệu; không dùng `docker compose down -v` nếu cần giữ database.

## Chạy ứng dụng

Bật Docker Desktop, mở PowerShell tại thư mục dự án:

```powershell
.\database.ps1 -Start
.\run.ps1
```

Website: http://localhost:8080. Dừng ứng dụng bằng `Ctrl+C` trong terminal đang chạy.

Máy mới chưa có `.env` và `config/database.properties` chạy `.\database.ps1 -Init` một lần trước `-Start`. Lệnh này sinh mật khẩu ngẫu nhiên cho Docker và Spring, từ chối ghi đè cấu hình hiện có. Máy đã chuyển dữ liệu không cần khởi tạo lại.

Spring Boot đọc `config/database.properties`; các biến `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` có thể ghi đè kết nối PostgreSQL. Chỉ có driver PostgreSQL trong dependency. JVM dùng múi giờ `Asia/Ho_Chi_Minh` (UTC+7) để tương thích với PostgreSQL trên máy này. Cấu trúc bảng tiếp tục nằm trong `src/main/resources/schema.sql`.

```powershell
.\database.ps1 -Status
docker compose stop postgres
```

Đổi mật khẩu trong `.env` không tự đổi mật khẩu của PostgreSQL đã được khởi tạo.

## Kiểm thử và đóng gói

```powershell
.\run.ps1 -Test
# Hoặc cùng bộ kiểm thử:
.\test-postgres.ps1

# Clean build, chạy kiểm thử PostgreSQL rồi tạo JAR:
.\run.ps1 -Package
```

Cả hai lệnh chạy kiểm thử đều tạo database riêng `giavien_test_<UUID>`, tạo schema riêng cho mỗi context, và xóa đúng database tạm đó khi hoàn tất. Lệnh đóng gói cũng dùng cơ chế này. Không có fallback sang database trong bộ nhớ.

Bộ kiểm thử kiểm tra URL trước khi mở kết nối: chỉ chấp nhận database tạm trên `127.0.0.1:15432`, từ chối database chính `giavien`, URL thiếu hoặc URL có tham số đổi schema. Khi thiếu `TEST_POSTGRES_URL`/`TEST_POSTGRES_PASSWORD`, kiểm thử báo lỗi cùng hướng dẫn dùng script; không lấy kết nối ứng dụng làm mặc định. Nếu chạy Maven hoặc JUnit trực tiếp từ IDE, cần cung cấp database tạm đúng quy tắc trên; cách đơn giản là dùng script.

Script khôi phục các biến môi trường kiểm thử trước đó khi kết thúc. `clean` loại bỏ class/resource cũ trước build. JAR kết quả: `target/giavien-restaurant-0.1.0.jar`; database được chọn qua cùng cấu hình PostgreSQL khi chạy JAR.

## Xem dữ liệu

Trong pgAdmin: **Register → Server**, đặt tên `GiaVien Docker`. Tab **Connection** điền host, port, database, username ở bảng trên và mật khẩu trong `.env`. Mở **Databases → giavien → Schemas → public → Tables** để xem 18 bảng.

Hoặc dùng psql:

```powershell
docker compose exec postgres psql -U giavien -d giavien
```

```sql
\dt
SELECT COUNT(*) FROM app_user;
SELECT COUNT(*) FROM reservation;
SELECT COUNT(*) FROM takeaway_order;
SELECT id, name, price FROM menu_item ORDER BY id;
\q
```

## Sao lưu

Ghi file bên trong container rồi sao chép ra để tránh PowerShell làm hỏng file nhị phân:

```powershell
docker compose exec -T postgres pg_dump -U giavien -d giavien -Fc -f /tmp/giavien.dump
docker compose cp postgres:/tmp/giavien.dump ./data/backups/giavien-postgresql.dump
```

Trước khi gỡ dependency database cũ ngày 02/11/2026, đã lưu bản sao PostgreSQL tại `data/backups/postgres-only-20261102-150721/giavien.dump` cùng bảng số dòng/checksum `before.csv`. Việc kiểm thử chỉ thao tác trên database tạm.

## Kết quả xác minh bản chỉ dùng PostgreSQL — 02/11/2026

- `run.ps1 -Package` clean build thành công, chạy **100 kiểm thử, 0 lỗi, 0 bỏ qua**: 94 bài nghiệp vụ hiện có và 6 trường hợp kiểm tra URL database kiểm thử an toàn.
- JAR chứa driver PostgreSQL; không còn driver database cũ, class migration hoặc profile dự phòng.
- Đã chạy thử JAR trên cổng tạm 18080 với database PostgreSQL hiện có: `/`, `/menu`, `/login` đều trả HTTP 200; PostgreSQL ghi nhận 10 kết nối JDBC. Tiến trình chạy thử đã được dừng sau kiểm tra.
- Cả **18 bảng dữ liệu chính có số dòng và checksum nội dung giống hệt trước thay đổi**; xem `before.csv` và `after.csv` trong thư mục sao lưu nêu trên.
- Database kiểm thử tạm đã được xóa sau build. Các bản sao lưu cũ và file cấu hình kết nối hiện có được giữ nguyên.
- Log build: `tmp/postgres-only/build.log`; kết quả kiểm tra JAR và HTTP: `tmp/postgres-only/artifact-check.txt`, `tmp/postgres-only/smoke-check.txt`.

## Lịch sử chuyển dữ liệu đã hoàn tất

Ngày 29/10/2026 đã chuyển từ H2 sang PostgreSQL 18.6, đối chiếu đủ 18 bảng, 84 bản ghi trước commit. Tài khoản, mật khẩu BCrypt, ID, khóa ngoại và lịch sử được giữ nguyên. Tại thời điểm đó, 96 kiểm thử PostgreSQL đạt, gồm 2 kiểm thử công cụ chuyển dữ liệu; hai kiểm thử này được gỡ cùng công cụ vì dự án nay chỉ sử dụng PostgreSQL. Các kiểm thử nghiệp vụ tiếp tục được giữ.

- File nguồn cũ: `data/giavien.mv.db`.
- Bản sao nguồn và checksum: `data/backups/h2-20261029-112852-433/`.
- [Báo cáo đối chiếu 18 bảng](../data/backups/h2-20261029-112852-433/migration-report.csv).
- Bản sao PostgreSQL sau chuyển: `data/backups/giavien-after-migration.dump`.

Các file trên là bản lưu lịch sử, được Git bỏ qua. Profile dự phòng và lệnh `-Migrate` đã được gỡ; không chạy lại migration trên database đang sử dụng. Dữ liệu PostgreSQL phát sinh sau ngày chuyển không nằm trong file nguồn cũ.
