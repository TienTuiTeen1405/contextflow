# Kiểm chứng bộ khung — 26/11/2026

## Đã kiểm chứng

- Core: Maven Wrapper, compile target Java 21, Spotless và 2 kiểm thử bảo mật qua `verify` trên JDK 25.
- Core chạy với PostgreSQL thật: readiness `UP`; `/api/v1/projects` trả `401 UNAUTHENTICATED` cùng correlation ID.
- AI: Ruff lint/format, mypy strict, 3 pytest smoke test đều đạt. TestClient có một deprecation warning từ Starlette/httpx.
- AI container: `/health` trả `UP`; service token hợp lệ đọc được adapter `MOCK`, `external_calls_enabled=false`.
- PostgreSQL container: PostgreSQL 16.12, vector 0.8.1 và unaccent 1.1.
- Web: lint, `next typegen` + TypeScript và Prettier đều đạt local; production build/lint/typecheck đạt trong Docker sạch. Lockfile áp dụng PostCSS 8.5.33; npm audit báo 0 vulnerabilities.
- Docker: cả 3 image build thành công khi chạy lần lượt; Compose `up --no-build --wait` đưa Web, Core, AI và PostgreSQL về healthy. Core container dùng Java 21. Home và Projects trả HTTP 200; Web health/Core readiness trả UP; business API vẫn trả 401.

Các kiểm tra này chỉ xác nhận nền tảng scaffold. Chưa nghiệm thu tính năng Chat/Auth/KB/SoT, chưa chạy 43 migration nghiệp vụ và chưa chạy PoC Gemini.

## Standards

Review độc lập diff `c89341f...3ab23c7`: không có phát hiện cần sửa về quy ước code, Docker paths/users, CI, security defaults, ranh giới dịch vụ hoặc onboarding. Review đọc source; runtime được kiểm chứng riêng.

Review bổ sung các thay đổi lockfile, Maven cache, giới hạn RAM build và type generation: không có phát hiện đáng kể.

## Spec

Review độc lập cùng diff: không có sai lệch đáng kể trong phạm vi scaffold so với yêu cầu khởi tạo và tài liệu baseline. Chưa triển khai nghiệp vụ/43 bảng là phạm vi được ghi rõ trong bàn giao, không phải xác nhận các chức năng đã hoàn thành.

## Môi trường

Docker build đồng thời từng bị ngắt trên máy development có khoảng 1,5 GB RAM trống. Dockerfile đã giới hạn heap build, Next dùng 2 worker và Core cache Maven trực tiếp. Khi máy ít RAM, chạy build từng dịch vụ trước `up --no-build`; không thay đổi baseline nghiệp vụ để vượt kiểm thử.
