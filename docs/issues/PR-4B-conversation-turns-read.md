# [PR-4B] Đọc lịch sử Turns & Outline của Conversation (`feature/conversation-turns-read`)

## Mục tiêu và phạm vi

Triển khai 2 API đọc dữ liệu hội thoại để Web F5 tải lại đúng lịch sử (ưu tiên **P0** cho `/turns`, **P1** cho `/outline`).

- **Trong phạm vi:**
  - `GET /api/v1/projects/{projectId}/stages/{stageId}/turns` (P0 — cần để Demo)
  - `GET /api/v1/projects/{projectId}/conversations/{conversationId}/outline` (P1)
- **Ngoài phạm vi PR này:** Ghi/commit Turn (thuộc gói Chat Worker của nhóm chủ chốt), Chapters & Sub-Branches đầy đủ (trả mảng rỗng `[]` ở M1).

## Người thực hiện và reviewer

- **Người thực hiện:** Thành viên 4
- **Reviewer:** Chủ chốt 2

## Branch & Package cần tạo

- **Branch:** `feature/conversation-turns-read` (tách từ `main` sau khi `PR-4A` được merge)
- **Package:** `com.contextflow.conversation.*`
  - `entity/Turn.java` (read-only mapping bảng `contextflow.turns`)
  - `repository/TurnRepository.java`
  - `dto/` (`TurnResponse.java`, `TurnsPageResponse.java`, `ConversationOutlineResponse.java`, `StageOutlineItem.java`)
  - Bổ sung method vào `ConversationService` / `ConversationController` (hoặc `StageTurnController`)

## API, Request/Response mẫu & Mã lỗi

| Method | Path                                                                                          | Yêu cầu cốt lõi                                                                                                                                                                                                                                                             |
| ------ | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`  | `/api/v1/projects/{projectId}/stages/{stageId}/turns?afterSequence=&beforeSequence=&limit=20` | Đọc lịch sử `turns` đã commit trên Main Stage (`sub_branch_id IS NULL`), sắp xếp tăng dần theo `sequence_no`, hỗ trợ cursor `afterSequence` / `beforeSequence` và `limit` (1..100). Không truyền đồng thời cả `afterSequence` và `beforeSequence` (`400 VALIDATION_ERROR`). |
| `GET`  | `/api/v1/projects/{projectId}/conversations/{conversationId}/outline`                         | Trả danh sách `stages[]` cơ bản của Conversation (sắp xếp theo `stage_order ASC`); các trường `chapters` và `subBranches` trả mảng rỗng `[]` ở M1.                                                                                                                          |

**Mẫu Stage JSON** (`contracts/fixtures/public/workspace/stage.json`):

```json
{
  "id": "30000000-0000-4000-8000-000000000001",
  "conversationId": "20000000-0000-4000-8000-000000000001",
  "stageOrder": 1,
  "title": "Main 1",
  "status": "ACTIVE",
  "createdAt": "2026-11-26T00:00:00Z"
}
```

**Mã lỗi bắt buộc:** `400 VALIDATION_ERROR` (limit ngoài 1..100, truyền cả 2 cursor), `404 RESOURCE_NOT_FOUND` (Project/Stage/Conversation không tồn tại hoặc sai owner/scope), `409 PROJECT_DELETING`.

## Phụ thuộc & Code nền bắt buộc gọi

- Phụ thuộc `PR-4A` (`Conversation`, `MainStage`).
- Lấy user hiện tại: `UUID userId = currentUserProvider.getCurrentUserId();`
- Bảng & cột chuẩn xem tại `core-backend/src/main/resources/db/migration/V003__conversations_chat_sse_continuity.sql`:
  - `contextflow.turns`: `id`, `project_id`, `conversation_id`, `main_stage_id`, `sub_branch_id`, `chapter_id`, `request_id`, `sequence_no`, `source_revision`, `user_content_blocks` (`jsonb`), `assistant_text`, `created_at`
  - `contextflow.main_stages`: `id`, `project_id`, `conversation_id`, `stage_order`, `title`, `status`, `created_at`
- Tuân thủ 5 luật cứng trong `.github/copilot-instructions.md`.

## Tiêu chí nghiệm thu (DoD)

- [ ] Đọc đúng danh sách `turns` theo `sequence_no` tăng dần, lọc đúng `project_id` + `main_stage_id` + `sub_branch_id IS NULL`.
- [ ] Cursor `afterSequence`, `beforeSequence`, `limit` hoạt động đúng; truyền cả 2 cursor trả `400 VALIDATION_ERROR`.
- [ ] `/outline` trả danh sách `stages[]` theo đúng `stage_order`, `chapters: []`, `subBranches: []`.
- [ ] Chạy xanh `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify`.
- [ ] Chụp **2 ảnh test trên Swagger UI** (`http://localhost:18080/swagger-ui.html` — 1 case thành công, 1 case lỗi) dán vào PR.
