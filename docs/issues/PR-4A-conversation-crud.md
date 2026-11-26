# [PR-4A] Conversation CRUD cơ bản (`feature/conversation-crud`)

## Mục tiêu và phạm vi

Triển khai 3 API CRUD cơ bản cho `Conversation` (ưu tiên **P0 — cần để Demo**).

- **Trong phạm vi:** `GET /api/v1/projects/{projectId}/conversations`, `POST /api/v1/projects/{projectId}/conversations`, `PATCH /api/v1/projects/{projectId}/conversations/{conversationId}`.
- **Ngoài phạm vi PR này:** Archive/delete conversation, Macro-Fork tạo Main 2+, Sub-Branch.

## Người thực hiện và reviewer

- **Người thực hiện:** Thành viên 4
- **Reviewer:** Chủ chốt 2

## Branch & Package cần tạo

- **Branch:** `feature/conversation-crud` (tách từ `main` sau khi `feature/local-demo-foundation` được merge)
- **Package:** `com.contextflow.conversation.*`
  - `entity/Conversation.java`, `entity/MainStage.java`
  - `repository/ConversationRepository.java`, `repository/MainStageRepository.java`
  - `dto/` (`ConversationResponse.java`, `ConversationCreateRequest.java`, `ConversationPatchRequest.java`, `ConversationStatus.java`)
  - `mapper/ConversationMapper.java` (`@Mapper(componentModel = "spring")`)
  - `service/ConversationService.java`, `service/impl/ConversationServiceImpl.java`
  - `controller/ConversationController.java`

## API, Request/Response mẫu & Mã lỗi

| Method  | Path                                                                | Yêu cầu cốt lõi                                                                                                                                           |
| ------- | ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`   | `/api/v1/projects/{projectId}/conversations?page=0&size=20&status=` | Phân trang Conversation thuộc đúng `projectId` (ẩn `DELETING`), kèm `mainStageId` hiện hành (Main có `stage_order` lớn nhất).                             |
| `POST`  | `/api/v1/projects/{projectId}/conversations`                        | Tạo Conversation (`201 Created`) + tự tạo `Main 1` (`stage_order = 1`, `title = 'Main 1'`) + gọi `ContinuityEpochBootstrap` trong **1 `@Transactional`**. |
| `PATCH` | `/api/v1/projects/{projectId}/conversations/{conversationId}`       | Đổi `title` (chặn nếu Conversation đang `DELETING` hoặc `ARCHIVED`/`LOCKED_READONLY`).                                                                    |

**Request mẫu `POST`** (`contracts/fixtures/public/workspace/conversation-create.json`):

```json
{
  "title": "Hội thoại đầu tiên"
}
```

**Response mẫu `ConversationResponse`** (`contracts/fixtures/public/workspace/conversation.json`):

```json
{
  "id": "20000000-0000-4000-8000-000000000001",
  "projectId": "10000000-0000-4000-8000-000000000001",
  "title": "Hội thoại đầu tiên",
  "status": "ACTIVE",
  "mainStageId": "30000000-0000-4000-8000-000000000001",
  "createdAt": "2026-11-26T00:00:00Z",
  "updatedAt": "2026-11-26T00:00:00Z"
}
```

**Mã lỗi bắt buộc:** `400 VALIDATION_ERROR`, `404 RESOURCE_NOT_FOUND` (Project/Conversation không tồn tại, khác owner hoặc lệch `projectId`), `409 PROJECT_DELETING`.

## Phụ thuộc & Code nền bắt buộc gọi

- Lấy user hiện tại: `UUID userId = currentUserProvider.getCurrentUserId();` (`com.contextflow.identity.service.CurrentUserProvider`)
- Kiểm tra quyền sở hữu & trạng thái `projectId` (có thể phối hợp dùng facade/service của `project` sau khi `PR-3A` merge).
- Ngay sau khi tạo Conversation + `Main 1` trong cùng `@Transactional`:
  ```java
  continuityEpochBootstrap.initializeForMainStage(projectId, conversationId, stageId);
  ```
  (`com.contextflow.ai.service.ContinuityEpochBootstrap`)
- Bảng & cột chuẩn xem tại `core-backend/src/main/resources/db/migration/V003__conversations_chat_sse_continuity.sql`:
  - `contextflow.conversations`: `id`, `project_id`, `title`, `status`, `created_at`, `updated_at`
  - `contextflow.main_stages`: `id`, `project_id`, `conversation_id`, `parent_stage_id`, `ancestor_stage_ids`, `stage_order`, `title`, `status`, `previous_status`, `created_at`
- Tuân thủ 5 luật cứng trong `.github/copilot-instructions.md`.

## Tiêu chí nghiệm thu (DoD)

- [ ] Tạo Conversation tự sinh đúng 1 `main_stages` (`stage_order = 1`, `title = 'Main 1'`) và 1 `continuity_epochs` (`ACTIVE`) trong cùng transaction.
- [ ] `PATCH` từ `projectId` khác trả `404 RESOURCE_NOT_FOUND`; Project đang `DELETING` trả `409 PROJECT_DELETING`.
- [ ] Chạy xanh `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify`.
- [ ] Chụp **2 ảnh test trên Swagger UI** (`http://localhost:18080/swagger-ui.html` — 1 case thành công, 1 case lỗi) dán vào PR.
