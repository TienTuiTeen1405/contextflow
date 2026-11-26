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

## Hợp đồng API, ownership và local presets — 26/11/2026

- Nguồn: BranOS-04-1 v7, 04-2 v2 và 04-6 v2. Đã có public/internal OpenAPI 3.1, 25 mã lỗi, 25 fixture JSON, 7 public event và 4 internal event. Type Web và Java DTO sinh từ cùng nguồn; internal OpenAPI được xuất từ Pydantic/FastAPI thực tế.
- Core `verify` trên JDK 21: 43 kiểm thử đạt (31 DTO/fixture/Unicode/null/registry + 10 database + 2 security), Spotless và JAR đạt. Lượt đầu thiếu Docker daemon đã được chạy lại sau khi khởi động Docker Desktop; không bỏ qua schema tests.
- AI: Ruff lint/format, mypy và 50 pytest đạt; bao gồm bốn endpoint Mock qua HTTP, service auth trước body, provider header bắt buộc, structured output, vector hữu hạn/chiều/count, usage null, opaque native JSON, lỗi/terminal stream và schema ref không gọi mạng. Có deprecation warning TestClient Starlette/httpx như trước.
- Web: lint/typecheck, production build và Prettier toàn repo đạt. `contracts:check` xác nhận generated code và OpenAPI không lệch; dependency production audit báo 0 vulnerabilities.
- Tooling: 3 kiểm thử setup local đạt; preset mới tạo secret đồng bộ, đổi Docker/IDE giữ DB password/KEK và từ chối tự xoay placeholder của DB đã tồn tại.
- Ownership: đủ 43 bảng có domain/Entity/Repository và review role. Thành viên chưa được gán theo thông tin người dùng; CODEOWNERS thật chưa được tạo và không tuyên bố GitHub review đã được cưỡng chế.
- Docker: các image Core, AI và Web build thành công; stack lên healthy. `local:check` xác nhận Web config → Core 8080, readiness UP, auth envelope và CORS. Các kết quả này xác nhận nền hợp đồng; không nghiệm thu business Chat/JWT/worker/public SSE hoặc PoC Gemini.
- Kiểm tra HTTP trực tiếp trong AI container với fixture tổng hợp: cả `/streams`, `/generate`, `/embeddings`, `/credentials/verify` đạt, response đánh dấu MOCK; vector đúng số lượng/1536 chiều/hữu hạn, usage chưa biết giữ null và Verify vẫn NOT_TESTED. Preset cuối giữ AI không publish ra host.
- Preset IDE: `setup:local -- --mode ide`, Compose override DB/AI và `dev:core` trên JDK 21 đã chạy thành công Core 18080, Flyway validate schema 005; `local:check` đạt Core readiness/Auth/CORS và AI service identity/MOCK tại 18000. Dùng cùng file env với run config IntelliJ đã chia sẻ. Process Core kiểm thử đã dừng; workspace được đưa về preset Docker mặc định sau lượt thử.
- Repo chưa có remote: CI đã được khai báo/cập nhật, chưa có kết quả pipeline trên repository chung.

## Standards

Review độc lập diff `c89341f...3ab23c7`: không có phát hiện cần sửa về quy ước code, Docker paths/users, CI, security defaults, ranh giới dịch vụ hoặc onboarding. Review đọc source; runtime được kiểm chứng riêng.

Review bổ sung các thay đổi lockfile, Maven cache, giới hạn RAM build và type generation: không có phát hiện đáng kể.

Review database `bf567e8...c3e6578`: không có phát hiện Standards cần sửa về quyền sở hữu DB, kỷ luật Flyway, secret, ranh giới dịch vụ hoặc quy ước repo. Đối chiếu cả 5 thân SQL với nguồn Confluence khớp sau khi loại phần dẫn nguồn và hiệu chỉnh seed đã công bố.

Review hợp đồng/ownership/local `6b369bc...8d51553`: không có phát hiện Standards cần sửa. Core giữ quyền persistence, AI stateless/Mock, Web chỉ import public contracts; nguồn sinh DTO và việc chưa gán named reviewer đều được mô tả rõ. Chỉnh sửa `.gitignore` và format POM của người dùng nằm ngoài review/commit.

## Spec

Review độc lập cùng diff scaffold ban đầu: không có sai lệch đáng kể trong phạm vi đã bàn giao. Schema được tích hợp trong thay đổi database tiếp theo; các luồng nghiệp vụ vẫn chưa được nghiệm thu.

Review Spec của phần database phát hiện 1 điểm P3 trong kiểm thử DB-IT-09: mới đọc hàng vector đầu tiên nên chưa chứng minh toàn bộ tập kết quả hợp lệ. Đã sửa để đọc mọi ID, kiểm tra tập đầy đủ trên Project nhỏ và exact fallback, kiểm tra ANN không lẫn ID/không trùng trên dữ liệu lớn, thêm fixture generation SUPERSEDED. Review lại xác nhận đã xử lý; không còn phát hiện Spec cần sửa.

Review Spec của phần hợp đồng phát hiện 1 điểm P2: union ContentBlock nội bộ bị sinh thành JsonNode ở Java. Đã chuẩn hóa thành named component và sealed union, giữ native JSON opaque; thêm regression kiểm tra kiểu current/history và từ chối IMAGE/trường thừa. Generator từ chối closed oneOf chưa được đặt tên thay vì âm thầm hạ kiểu. Header X-Provider-Credential cũng được đối chiếu lại và đặt required/minLength ở runtime/OpenAPI với kiểm thử riêng. Reviewer xác nhận các sửa đổi; không còn phát hiện Spec cần sửa.

Review hợp đồng kết thúc: Standards 0 phát hiện; Spec 0 còn mở (1 P2 đã xử lý).

## Môi trường

Docker build đồng thời từng bị ngắt trên máy development có khoảng 1,5 GB RAM trống. Dockerfile đã giới hạn heap build, Next dùng 2 worker và Core cache Maven trực tiếp. Khi máy ít RAM, chạy build từng dịch vụ trước `up --no-build`; không thay đổi baseline nghiệp vụ để vượt kiểm thử.
