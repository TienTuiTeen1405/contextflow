# Bản đồ bắt đầu cho các nhóm

## Các gói có thể bắt đầu song song

| Gói                   | Vị trí sở hữu                                            | Đầu ra đầu tiên                                                                    | Phụ thuộc                                                       |
| --------------------- | -------------------------------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| Data foundation       | `core-backend/src/main/resources/db/migration`           | V001–V005 đã tích hợp; tiếp tục repository/query và migration V006+ khi cần        | Dùng schema 43 bảng từ 03-5; không sửa checksum V001–V005       |
| Identity & Credential | Core `identity`, `credential`; Web `auth`, `credentials` | Local/OAuth/JWT, ownership, envelope encryption, disclosure                        | Data V001–V002; không mở toàn API tạm thời                      |
| Workspace Web         | `web/src/features/workspace`, `navigation`               | Composer, cây Main/Branch, parser fetch-SSE có fixture                             | DTO 04-2; chưa cần Provider thật                                |
| Chat orchestration    | Core `ai`, `conversation`                                | POST 202, key/slot, events/cursor, status/cancel, commit Turn                      | Data, Identity/Credential; mock fixtures                        |
| AI Adapter            | `ai-service/app/providers`, `schemas`, `prompt_builder`  | DTO 04-6, Mock thực thi 4 task endpoints, Builder A–E, cancellation/error fixtures | Hợp đồng thống nhất với Core; không cần SDK thật                |
| Provider PoC          | `poc`                                                    | Bằng chứng CF-DEC-038 đã redaction, kết luận SDK/API                               | Khóa thử nghiệm do chủ sở hữu cung cấp; chưa nằm trong scaffold |

`ProviderAdapter` hiện chỉ có `status()`. Nhóm AI bổ sung execution methods theo DTO 04-6; không dùng endpoint bootstrap `/status` làm API Chat. Core chưa gọi AI hoặc giải mã Credential. File `.env.example` liệt kê biến dự kiến, không xác nhận tính năng đã có.

## Thứ tự tích hợp

1. **M1:** Data/Identity/Credential + Main Chat E2E, SSE reconnect/cancel và usage. Chạy bằng Mock trước.
2. **M2:** Sub-Branch, Quote nhiều khối, @Main, Retroactive Branching, Chapter, Rollover/Macro-Fork.
3. **M3:** Project KB/Outbox, Hybrid Retrieval, SoT, Promote và Condense.
4. **M4:** Deletion Sessions, Export, Admin Portal đầy đủ.

Mốc là cách chia việc, không phải cam kết lịch. Chốt DTO/fixture trong PR chung trước khi mỗi nhóm nối hệ thống; public JSON camelCase, internal snake_case. Dùng 25 mã lỗi từ 04-1. Một change contract cần nêu rõ consumer và migration liên quan.

## Điều kiện bàn giao M1

- Precheck lỗi không giữ key/slot; replay key đúng trả cùng request; khác payload trả conflict.
- Rớt POST sau commit không tạo Call thứ hai; reconnect SSE chỉ đọc cùng request.
- Cancel/Complete chỉ có một terminal; kết quả muộn không tạo Turn.
- Kiểm tra lại Project/credential/epoch/lease trước commit; DB transaction không giữ qua network.
- Không log secret/native steps; không gửi business ID sang FastAPI.
- Flyway chạy trên PostgreSQL 16 + pgvector >= 0.8; UI tải lại đúng Turn và Quote order.
