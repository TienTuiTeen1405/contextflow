# Demo Mode một người dùng

Theo yêu cầu ngày 26/11/2026, Auth/JWT được hoãn khỏi giai đoạn demo. Đây là cấu hình local riêng; baseline xác thực của BranOS và profile mặc định vẫn giữ nguyên.

## Bật chế độ demo

Docker (Web → Core 8080):

```powershell
npm run demo:up
npm run demo:check
```

IntelliJ/Maven (Web dev → Core 18080):

```powershell
npm run setup:local -- --mode ide --demo
npm run infra:up:ide
# Run ContextFlow Core (IDE) trong IntelliJ, hoặc:
npm run dev:core
```

Ở terminal riêng: `npm run dev:web`, rồi `npm run demo:check`. Run config IntelliJ nạp cùng `core-backend/.env`, bao gồm profile demo do setup tạo. Core ngoài container bind loopback; trong Docker chỉ mở qua cổng host 127.0.0.1. AI vẫn MOCK và giữ xác thực service nội bộ.

Để trở về chế độ mặc định: `npm run setup:local -- --mode docker`, rồi `npm run stack:up`. Khi dùng IDE, restart Core sau khi đổi profile; restart Web sau khi đổi URL. Không ghép profile demo với prod/production.

## Danh tính và dữ liệu

- DemoUser có UUID `d0000000-0000-4000-8000-000000000001`, email `demo@contextflow.local`, role USER, không mật khẩu/JWT.
- Seeder chỉ chạy trong profile demo, thêm user nếu chưa có, không ghi đè tên/theme/dữ liệu đã sửa khi restart. Nếu reserved user xung đột email/role/trạng thái/mật khẩu, startup từ chối thay vì biến tài khoản có sẵn thành tài khoản demo.
- Mọi request demo được gắn cùng principal phía server. Header user ID, role hay JWT do client tự gửi không đổi được danh tính.
- Repository vẫn lọc owner/user/project; tài nguyên người khác trả 404, Project DELETING trả 409. User DISABLED bị chặn. Không mở admin, credential, auth, xóa Project hay Chat bằng permitAll.
- Không thay đổi V001–V005 hoặc thêm migration seed demo. Database vẫn 43 bảng; user/project/skill demo là dữ liệu local có thể giữ qua restart.
- CurrentUser/UserIdentity là điểm nối để thêm JWT sau; domain service/repository không hardcode DemoUser.

## API thật cho các nhóm giao diện

| Chức năng           | Endpoint demo                                                                       |
| ------------------- | ----------------------------------------------------------------------------------- |
| Hồ sơ/theme         | GET/PATCH `/api/v1/me`                                                              |
| Danh sách Skills    | GET `/api/v1/me/personal-intelligence`, lấy `personalSkills`                        |
| Skills thủ công     | POST `/api/v1/me/personal-skills`, PATCH/DELETE `.../{skillId}`                     |
| Project cơ bản      | GET/POST `/api/v1/projects`, GET/PATCH `.../{projectId}`                            |
| Conversation cơ bản | GET/POST `/api/v1/projects/{projectId}/conversations`, PATCH `.../{conversationId}` |

Tạo Project khởi tạo index generation ACTIVE (gemini-embedding-2, 1536) trong cùng transaction. Tạo Conversation khởi tạo Main 1 và epoch 1 độc lập trong cùng transaction; compatibility của epoch mang nhãn MOCK/demo, không phải bằng chứng tương thích Provider thật.

PATCH giữ nguyên trường absent; null chỉ xóa trường nullable như description, không đổi thành absent. Core dùng RequestFields cùng generated DTO để giữ thông tin presence. Validation/mã lỗi dùng hợp đồng chung; tên/tiêu đề không được trắng, priority không âm, page/size được giới hạn.

## Phạm vi cố ý để lại

Đây là API nền demo, không xác nhận M1 đầy đủ. Chưa triển khai Chat worker/public SSE/Turn persistence, archive/delete lifecycle, sao chép template, ADM-07 quota enforcement, hoặc JWT. Input template/status được định nghĩa theo 04-3 để giao diện biết hợp đồng; demo từ chối các thao tác chưa hỗ trợ bằng VALIDATION_ERROR, không báo thành công giả. Project/Conversation đã ARCHIVED chỉ đọc trong phạm vi demo; TBD-01 của baseline vẫn OPEN.

Nguồn: [BranOS-04-3 v2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7471214), schema [03-5](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718). 04-3 không liệt kê đầy đủ trường ConversationView; projection local được cố định trong OpenAPI: id/projectId/title/status/mainStageId/createdAt/updatedAt. mainStageId là Main có stageOrder lớn nhất (ổn định theo id khi hòa), nullable cho dữ liệu cũ chưa có Main; demo chỉ tạo Main 1. Chính sách chọn Main khi Macro-Fork được giao cho gói workspace tiếp theo.
