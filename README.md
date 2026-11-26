# ContextFlow

Monorepo **ContextFlow V6** theo tài liệu BranOS. Repo hiện cung cấp bộ khung chạy dự án và database; nhóm sẽ tự triển khai API nghiệp vụ theo phân công.

## Chạy backend trong IntelliJ

Từ thư mục gốc, bật Docker Desktop và dùng JDK 21:

```powershell
npm run setup:local
npm run infra:up:ide
```

Run **ContextFlow Core (IDE)** trong IntelliJ, hoặc chạy Maven trực tiếp:

```powershell
Set-Location core-backend
.\mvnw.cmd spring-boot:run
```

Swagger UI: http://localhost:18080/swagger-ui.html. Core tự đọc `.env` ở working directory `core-backend`. Ở terminal root riêng, chạy `npm run local:check`.

## Chạy toàn bộ bằng Docker

```powershell
npm run setup:local -- --mode docker
npm run stack:up
npm run local:check
```

Swagger UI: http://localhost:8080/swagger-ui.html. Dừng bằng `npm run stack:down` để giữ dữ liệu.

## Trạng thái hiện tại

- Đã gỡ API/DTO, ServiceImpl, Entity, Repository, Mapper và user/profile demo của Project, Conversation, hồ sơ và Personal Skills.
- Giữ database/Flyway 43 bảng, cấu hình local, Swagger, xử lý lỗi chung và công cụ Maven/JPA/MapStruct/Lombok.
- Swagger runtime phản ánh controller thực tế; đặc tả trong `contracts/` là yêu cầu để triển khai, không phải API đang chạy.
- AI Service giữ bốn endpoint nội bộ **MOCK**; chưa có Chat đầu-cuối.
- Web vẫn là scaffold; Auth/JWT và API nghiệp vụ do nhóm triển khai sau.

Xem [hướng dẫn setup và bắt đầu code](docs/SETUP-GUIDE.md), [baseline BranOS](docs/baseline.md) và [hợp đồng API](contracts/README.md). Mục 6 gồm nguyên tắc code, bảng quy trình làm việc nhóm và hướng dẫn Git không cần Jira. Thành viên đọc trước khi nhận việc; dùng mẫu GitHub Issue/PR trong `.github/` để bàn giao.
