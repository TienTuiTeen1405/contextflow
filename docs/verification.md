# Kiểm chứng bộ khung — 26/11/2026

## Đã kiểm chứng

- Core: Maven Wrapper, compile target Java 21, Spotless và 2 kiểm thử bảo mật qua `verify` trên JDK 25.
- Core chạy với PostgreSQL thật: readiness `UP`; `/api/v1/projects` trả `401 UNAUTHENTICATED` cùng correlation ID.
- AI: Ruff lint/format, mypy strict, 3 pytest smoke test đều đạt. TestClient có một deprecation warning từ Starlette/httpx.
- AI container: `/health` trả `UP`; service token hợp lệ đọc được adapter `MOCK`, `external_calls_enabled=false`.
- PostgreSQL container: PostgreSQL 16.12, vector 0.8.1 và unaccent 1.1.
- Web: lint, `next typegen` + TypeScript và Prettier đều đạt local; production build/lint/typecheck đạt trong Docker sạch. Lockfile áp dụng PostCSS 8.5.33; npm audit báo 0 vulnerabilities.
- Docker: cả 3 image build thành công khi chạy lần lượt; Compose `up --no-build --wait` đưa Web, Core, AI và PostgreSQL về healthy. Core container dùng Java 21. Home và Projects trả HTTP 200; Web health/Core readiness trả UP; business API vẫn trả 401.

Các kiểm tra scaffold không thay thế nghiệm thu tính năng Chat/Auth/KB/SoT hoặc PoC Gemini. Phần database được bổ sung và kiểm chứng riêng bên dưới.

## Tích hợp database BranOS-03-5 — 26/11/2026

- V001–V005 lấy từ trang 7536718, phiên bản 2 ngày 25/11/2026; tạo đủ 43 bảng nghiệp vụ trong schema `contextflow`. V001 dùng seed model theo baseline CF-DEC-037/047 (DES-DIFF-01).
- `./mvnw.cmd -Dtest=SchemaMigrationTest test`: 10 bài DB-IT-01–10 đạt trên Testcontainers PostgreSQL 16.12 + pgvector 0.8.1; không skip. Kiểm tra cả migrate DB trống, checksum, lần migrate thứ hai không chạy lại, composite FK/route/uniqueness/retention, FTS, vector với 10.000 chunk Project khác và usage delta.
- `./mvnw.cmd spotless:apply verify` trên JDK 21.0.13: 12 bài đạt (10 schema + 2 security), build JAR và Spotless thành công.
- Build/cập nhật riêng Core Docker thành công. PostgreSQL development ghi 5 migration `success=true`, 43 bảng nghiệp vụ và 1 bảng history; registry có 8 tác vụ `gemini-3.8-flash` + 2 tác vụ `gemini-embedding-2`. Readiness Core tại `localhost:8080` trả `UP` sau migration.
- Bổ sung cấu hình `CF_FLYWAY_*` để dùng tài khoản migration riêng; local mặc định dùng lại tài khoản development. Chưa provision tài khoản production hoặc nghiệm thu quyền DB trên môi trường triển khai.

## Standards

Review độc lập diff `c89341f...3ab23c7`: không có phát hiện cần sửa về quy ước code, Docker paths/users, CI, security defaults, ranh giới dịch vụ hoặc onboarding. Review đọc source; runtime được kiểm chứng riêng.

Review bổ sung các thay đổi lockfile, Maven cache, giới hạn RAM build và type generation: không có phát hiện đáng kể.

## Spec

Review độc lập cùng diff scaffold ban đầu: không có sai lệch đáng kể trong phạm vi đã bàn giao. Schema được tích hợp trong thay đổi database tiếp theo; các luồng nghiệp vụ vẫn chưa được nghiệm thu.

## Môi trường

Docker build đồng thời từng bị ngắt trên máy development có khoảng 1,5 GB RAM trống. Dockerfile đã giới hạn heap build, Next dùng 2 worker và Core cache Maven trực tiếp. Khi máy ít RAM, chạy build từng dịch vụ trước `up --no-build`; không thay đổi baseline nghiệp vụ để vượt kiểm thử.
