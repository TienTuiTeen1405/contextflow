# Hợp đồng API dùng chung

Nguồn chuẩn: [BranOS-04-1 v7](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/1704081), [BranOS-04-2 v2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7635034), [BranOS-04-3 v2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7471214), [BranOS-04-6 v2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536753), đối chiếu 26/11/2026. Auth/Admin chi tiết thuộc gói sau.

| Tệp                       | Vai trò                                                                                             |
| ------------------------- | --------------------------------------------------------------------------------------------------- |
| `public.openapi.yaml`     | OpenAPI 3.1 cho Chat/SSE và workspace; DTO, pagination, 25 mã lỗi, 7 public event                   |
| `internal.openapi.yaml`   | OpenAPI 3.1 xuất từ FastAPI/Pydantic; 4 task endpoint, PromptEnvelope, 7 nhóm lỗi, 4 internal event |
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

Luồng nguồn: public OpenAPI → TypeScript tại `web/src/lib/api/generated/public.ts`; internal Pydantic/route → internal OpenAPI. Không sinh DTO Java hay demo spec. Thành viên tự viết DTO của chức năng trong package domain và đối chiếu đặc tả BranOS. Swagger runtime của Core sinh từ controller thực tế; file YAML này không đăng ký route hoặc xác nhận API đã chạy. Web chỉ dùng public types, không import native/internal DTO.

Khi thay đổi: sửa nguồn, cập nhật fixture, chạy generate/check và kiểm thử phía bị ảnh hưởng; lấy review theo `../.github/ownership.json`. CI Web kiểm tra generated types; CI AI kiểm tra OpenAPI export và fixture/HTTP. Người triển khai API Core bổ sung test HTTP/nghiệp vụ cho phần mình nhận.

## Trạng thái triển khai

- **Workspace:** code CRUD/DTO/Entity/Repository/Mapper viết sẵn đã gỡ; thành viên triển khai từ đầu. Giữ đặc tả và mẫu JSON để thống nhất dữ liệu. Không còn Demo Mode hoặc user tự seed. Xem [hướng dẫn](../docs/SETUP-GUIDE.md).
- **Core Public Chat:** mới có đặc tả và fixture. Controller, DTO Java nghiệp vụ, JWT/ownership, transaction/idempotency/slot, worker, durable SSE/reconnect/TTL/cancel và persistence Turn cần được nhóm triển khai. Security mở health/Swagger, đóng các route còn lại.
- **AI Internal:** `POST /internal/v1/ai/streams`, `/generate`, `/embeddings`, `/credentials/verify` đã có bản **MOCK**. Mỗi response mang `X-Provider-Mode: MOCK`; không có SDK hoặc network tới Gemini. `GET /status` vẫn phục vụ kiểm tra bootstrap.
- **Prompt Compiler/Builder:** DTO A–E đã có. Compile quyền/ngân sách, serialization XML và pipeline thực vẫn thuộc nhóm Core/AI; chưa xác nhận checklist E2E/PoC đã đạt.

## Quy tắc wire quan trọng

- Public JSON camelCase; internal JSON snake_case; Core dùng DTO riêng cho hai biên, không tự đổi tên JSON bằng cách truyền thẳng object.
- Usage không biết giữ `null`; không tự đổi thành 0 hoặc cộng thought/cached tokens vào tổng. Các timestamp response dùng UTC.
- `ContentBlock` là union đóng TEXT/QUOTE_REF. Quote input không nhận `excludeFromAutoKb`; Core phải tự tính khi commit. Nhóm triển khai cần kiểm tra alias 1-1, LF, số code point, SHA-256, ownership, nguồn chuẩn/revision/anchors và budget theo đặc tả.
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
