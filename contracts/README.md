# Hợp đồng API dùng chung

Nguồn chuẩn: [BranOS-04-1 v7](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/1704081), [BranOS-04-2 v2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7635034), [BranOS-04-3 v2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7471214), [BranOS-04-6 v2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536753), đối chiếu 26/11/2026. Auth/Admin chi tiết thuộc gói sau.

| Tệp                       | Vai trò                                                                                             |
| ------------------------- | --------------------------------------------------------------------------------------------------- |
| `public.openapi.json`     | OpenAPI 3.1 cho Chat/SSE và workspace; DTO, pagination, 25 mã lỗi, 7 public event                   |
| `demo.openapi.json`       | View sinh tự động cho 13 thao tác workspace demo không JWT; tham chiếu schema public                |
| `internal.openapi.json`   | OpenAPI 3.1 xuất từ FastAPI/Pydantic; 4 task endpoint, PromptEnvelope, 7 nhóm lỗi, 4 internal event |
| `error-codes.json`        | HTTP/retryable/SSE metadata cho đúng 25 mã chuẩn 04-1                                               |
| `fixtures/manifest.json`  | 44 mẫu JSON có tên document/schema để Java và Python dùng cùng bộ mẫu                               |
| `fixtures/public/*.sse`   | Happy path, failure, cancellation; event ID tăng dần, đúng một terminal                             |
| `fixtures/internal/*.sse` | Dispatch acknowledgement, delta và terminal; không có cursor/replay                                 |

## Đồng bộ code

Tại thư mục gốc, sau `npm ci` và `uv sync --frozen --dev` trong AI:

```powershell
npm run contracts:generate
npm run contracts:check
```

Luồng nguồn: public OpenAPI → TypeScript và Java record; internal Pydantic/route → internal OpenAPI → Java record. DTO sinh ở `web/src/lib/api/generated/public.ts` và `core-backend/src/main/java/com/contextflow/contracts/generated`; không sửa trực tiếp. Generator hiện hỗ trợ tập schema được dùng trong repo, không phải công cụ OpenAPI tổng quát; mở rộng generator cùng contract khi thêm kiểu schema mới. Java generated được bỏ qua Spotless và được kiểm tra nguyên bản bằng generator trong CI. Web chỉ sinh public types, không import native/internal DTO.

Khi thay đổi: sửa nguồn, cập nhật fixture, chạy generate/check và kiểm thử hai phía; lấy review theo `../.github/ownership.json`. CI Web kiểm tra generated code; CI AI kiểm tra OpenAPI export và fixture/HTTP; Core kiểm tra DTO round-trip, Unicode, null, mã lỗi và registry SSE.

## Trạng thái triển khai

- **Workspace demo:** hồ sơ, Project, Conversation và Personal Skills có DTO/type/19 fixture mới cùng API CRUD cơ bản. Chỉ bật trong profile demo; xem [giới hạn](../docs/demo.md). Public spec giữ JWT baseline, demo spec thể hiện chính xác ngoại lệ local. PATCH dùng RequestFields để phân biệt absent/null; NonNullIfPresent là metadata cho reader, không phải Bean Validation constraint.
- **Core Public Chat:** hợp đồng/DTO/quy tắc transport và fixture đã có. Business controller, JWT/ownership, transaction/idempotency/slot, worker, durable SSE/reconnect/TTL/cancel và persistence Turn vẫn là công việc M1. Security tiếp tục đóng mặc định.
- **AI Internal:** `POST /internal/v1/ai/streams`, `/generate`, `/embeddings`, `/credentials/verify` đã có bản **MOCK**. Mỗi response mang `X-Provider-Mode: MOCK`; không có SDK hoặc network tới Gemini. `GET /status` vẫn phục vụ kiểm tra bootstrap.
- **Prompt Compiler/Builder:** DTO A–E đã có. Compile quyền/ngân sách, serialization XML và pipeline thực vẫn thuộc nhóm Core/AI; chưa xác nhận checklist E2E/PoC đã đạt.

## Quy tắc wire quan trọng

- Public JSON camelCase; internal JSON snake_case; Core dùng DTO riêng cho hai biên, không tự đổi tên JSON bằng cách truyền thẳng object.
- Usage không biết giữ `null`; không tự đổi thành 0 hoặc cộng thought/cached tokens vào tổng. Các timestamp response dùng UTC.
- `ContentBlock` là union đóng TEXT/QUOTE_REF. Quote input không nhận `excludeFromAutoKb`; Core tính khi commit. `PublicChatRules` kiểm tra alias 1-1, LF, số code point và SHA-256 của snapshot; ownership, nguồn chuẩn/revision/anchors và budget vẫn cần service kiểm tra.
- Header SSE cursor là chuỗi số thập phân, ưu tiên `Last-Event-ID` hơn query. Public `GET /events` chỉ đọc, không dispatch; internal `/streams` không reconnect/replay.
- Public SSE không mang native steps, signature, API Key hoặc Prompt Audit. Native JSON ở internal DTO giữ opaque, gồm thứ tự trường; fixture native trong repo hoàn toàn tổng hợp.
- Internal top-level chỉ nhận hai UUID trace. Metadata nguồn dùng local alias; không gửi business UUID của Project/User/Turn/Job. Các trường A–E lồng nhau dùng cấu trúc typed; background/personal instructions có thể null khi lớp Intelligence tắt.
- Lỗi auth trước khi đọc body dùng UUID nil cho trace chưa xác định và `dispatched=false`; không đọc payload để lấy ID khi service token sai. Lỗi validation được làm sạch, không trả body/secret trong diagnostics.

## Hành vi Mock để ghép nhóm

Dùng service token local và header `X-Provider-Credential: mock-provider-credential`; đây là giá trị tổng hợp, không phải API Key thật. Không đưa header này vào Web.

- Streams phát dispatched → 2 delta → completed; native steps tổng hợp; usage toàn null/`UNAVAILABLE`. Dispatch ở đây là attempt mô phỏng, không có external call.
- Generate trả text cố định; khi có response_schema, parsed_json mẫu có dạng `{"summary":"[MOCK] ..."}`. Schema không khớp trả lỗi có chuẩn hóa; Mock chưa sinh nội dung tùy ý theo mọi JSON Schema.
- Embeddings tạo vector hữu hạn 1536 chiều, giữ thứ tự input. Vector này dùng kiểm tra hợp đồng, không có ý nghĩa ngữ nghĩa.
- Verify chỉ báo valid cho sentinel `mock-provider-credential`, luôn `capability_status=NOT_TESTED`. Không kiểm tra API Key thật hay quota.

Mẫu lỗi `fixtures/public/error.json` lấy từ 04-1; ChatInput/ChatAccepted lấy từ 04-2. Các ví dụ khác là dữ liệu tổng hợp theo schema. Không commit log/secret/raw prompt hoặc native steps thực tế. PoC Gate CF-DEC-038 vẫn PENDING.
