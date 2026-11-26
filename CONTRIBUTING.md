# Cách làm việc trong nhóm

## Quy ước chung

- Bám nguồn chuẩn trong `docs/baseline.md`. Khi thay đổi hợp đồng liên dịch vụ, báo rõ nhóm bị ảnh hưởng trong PR.
- Nhánh: `feature/<mốc>-<phần-việc>` hoặc `fix/<mô-tả>`. Commit theo Conventional Commits: `feat(core): ...`, `fix(web): ...`, `docs: ...`.
- Thay đổi nhỏ theo một chức năng; mỗi PR ghi hành vi, kiểm chứng và ảnh hưởng migration/API.
- Không commit secret, `.env`, raw prompt, provider-native steps hoặc bằng chứng PoC chưa che bí mật.
- Không mở lại CF-DEC CONFIRMED hoặc tự chọn hành vi TBD. Ghi phát hiện vào tài liệu và xin quyết định cho phần phụ thuộc; vẫn tiếp tục phần việc độc lập.

## Web

- Component/type PascalCase, biến/hàm camelCase, thư mục feature kebab-case. TypeScript strict.
- TanStack Query giữ server state; Zustand giữ UI state. Không coi stream tạm là Turn đã commit.
- Chỉ gọi Core; không gọi Gemini trực tiếp. Không persist API Key hoặc JWT vào localStorage trong scaffold.
- Khi có SSE ở M1, dùng fetch streaming với Authorization và Last-Event-ID; không POST lại để reconnect.

## Core

- Java 21, record cho DTO bất biến. Controller validate input; Service giữ quyền/nghiệp vụ/transaction; Repository luôn có scope Project khi đọc dữ liệu dự án.
- Core độc quyền DB và quyết định persistence. Network I/O ngoài transaction; kiểm tra lại quyền, lifecycle, epoch, lease và revision trước commit.
- DDL qua Flyway; không sửa migration đã chạy chung. Không dùng Hibernate auto-create/update.
- Không log Authorization, X-Provider-Credential, secret, DEK/KEK, raw prompt hoặc provider payload.
- Security hiện deny-by-default. Nhóm Identity thay bằng JWT/ownership có kiểm thử, không dùng permitAll cho toàn bộ API.
- Theo yêu cầu demo ngày 26/11/2026, profile `demo` dùng principal USER cố định và chỉ mở workspace cơ bản. Auth/JWT được hoãn; không mở rộng ngoại lệ này sang profile mặc định hoặc admin. Xem `docs/demo.md`.

## AI Service

- Type hints đầy đủ, snake_case, Pydantic v2, Ruff và mypy strict.
- Stateless theo request; không DB driver, không lưu/caching Credential, không retry nghiệp vụ.
- Chỉ nhận correlation_id/provider_call_id và dữ liệu cần thiết từ Core. Không đưa business UUID vào tracing hoặc log.
- Prompt B/D không vào system_instruction. Provider-native steps giữ ở kênh nội bộ có kiểm soát, không public SSE/Export.
- Khi tích hợp SDK, một attempt ở Adapter; Core giữ retry. Không thêm SDK thật vào dependency trước khi chốt PoC.

## Kiểm chứng

Chạy check của dịch vụ thay đổi; chạy contract/integration test cho thay đổi ảnh hưởng dịch vụ khác. Test PostgreSQL dùng PostgreSQL 16 + pgvector >= 0.8, không thay bằng H2/SQLite. CI scaffold chưa thay thế nghiệm thu M1–M4.
