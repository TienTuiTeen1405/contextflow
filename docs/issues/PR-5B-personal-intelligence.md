# [PR-5B] Personal Intelligence, Personal Skills & Bộ kịch bản test Swagger (`feature/personal-intelligence`)

## Mục tiêu và phạm vi

Triển khai 5 API quản lý Lớp 1 (Personal Intelligence + Personal Skills) — ưu tiên **P1**, đồng thời lập **bộ kịch bản test tổng hợp trên Swagger UI** (`http://localhost:18080/swagger-ui.html`) cho cả nhóm trước ngày demo.

- **Trong phạm vi:** `GET/PUT /api/v1/me/personal-intelligence`, `POST/PATCH/DELETE /api/v1/me/personal-skills[/{skillId}]`, và file kịch bản smoke test Swagger UI.
- **Ngoài phạm vi PR này:** Sao chép `sourceTemplateId` từ admin templates (nếu truyền khác null thì trả `400 VALIDATION_ERROR`).

## Người thực hiện và reviewer

- **Người thực hiện:** Thành viên 5
- **Reviewer:** Chủ chốt 2

## Branch & Package cần tạo

- **Branch:** `feature/personal-intelligence` (tách từ `main` sau khi `PR-5A` được merge)
- **Package:** `com.contextflow.identity.*`
  - `entity/PersonalSkill.java`
  - `repository/PersonalSkillRepository.java` (`JpaRepository`)
  - `dto/` (`PersonalIntelligenceResponse.java`, `PersonalIntelligenceUpdateRequest.java`, `SkillResponse.java`, `SkillCreateRequest.java`, `SkillPatchRequest.java`)
  - `mapper/PersonalSkillMapper.java` (`@Mapper(componentModel = "spring")`)
  - `service/PersonalSkillService.java`, `service/impl/PersonalSkillServiceImpl.java`
  - Bổ sung endpoint vào `ProfileController` (hoặc `PersonalIntelligenceController`)

## API, Request/Response mẫu & Mã lỗi

| Method   | Path                                   | Yêu cầu cốt lõi                                                                                                                |
| -------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `GET`    | `/api/v1/me/personal-intelligence`     | Trả `personalIntelligenceEnabled`, `personalProfileText`, `personalInstructions`, `personalSkills[]` (sắp xếp `priority, id`). |
| `PUT`    | `/api/v1/me/personal-intelligence`     | Cập nhật công tắc Lớp 1 (`personalIntelligenceEnabled`) và thông tin nền (`personalProfileText`, `personalInstructions`).      |
| `POST`   | `/api/v1/me/personal-skills`           | Tạo kỹ năng cá nhân (`201 Created`, `priority >= 0`, mặc định `priority = 100`, `isEnabled = true`).                           |
| `PATCH`  | `/api/v1/me/personal-skills/{skillId}` | Sửa kỹ năng cá nhân — **bắt buộc lọc theo `user_id` hiện tại** (khác user trả `404`).                                          |
| `DELETE` | `/api/v1/me/personal-skills/{skillId}` | Xóa kỹ năng cá nhân của chính user (`204 No Content`, khác user hoặc không tồn tại trả `404`).                                 |

**Response mẫu `GET /api/v1/me/personal-intelligence`** (`contracts/fixtures/public/workspace/personal-intelligence.json`):

```json
{
  "personalIntelligenceEnabled": true,
  "personalProfileText": null,
  "personalInstructions": null,
  "personalSkills": [
    {
      "id": "40000000-0000-4000-8000-000000000001",
      "name": "Trả lời tiếng Việt",
      "description": "Kỹ năng cá nhân mẫu.",
      "content": "Trả lời ngắn gọn bằng tiếng Việt.",
      "priority": 100,
      "isEnabled": true
    }
  ]
}
```

**Request mẫu `POST /api/v1/me/personal-skills`** (`contracts/fixtures/public/workspace/skill-create.json`):

```json
{
  "name": "Trả lời tiếng Việt",
  "description": "Kỹ năng cá nhân mẫu.",
  "content": "Trả lời ngắn gọn bằng tiếng Việt.",
  "priority": 100,
  "isEnabled": true
}
```

**Mã lỗi bắt buộc:** `400 VALIDATION_ERROR` (`priority < 0`, tên trắng, body rỗng), `404 RESOURCE_NOT_FOUND` (Skill không tồn tại hoặc thuộc user khác).

## Phụ thuộc & Code nền bắt buộc gọi

- Phụ thuộc `PR-5A` (`User`, `UserRepository`).
- Lấy user hiện tại: `UUID userId = currentUserProvider.getCurrentUserId();` (`com.contextflow.identity.service.CurrentUserProvider`)
- Bảng & cột chuẩn xem tại `core-backend/src/main/resources/db/migration/V001__identity_credentials_admin.sql`:
  - `contextflow.users`: `personal_intelligence_enabled`, `personal_profile_text`, `personal_instructions`
  - `contextflow.personal_skills`: `id`, `user_id`, `name`, `description`, `content`, `priority`, `is_enabled`, `created_at`, `updated_at`
- Tuân thủ 5 luật cứng trong `.github/copilot-instructions.md`.

## Tiêu chí nghiệm thu (DoD)

- [ ] Tạo/sửa/xóa Personal Skill hoạt động đúng và cách ly theo `user_id` hiện tại (skill của user khác trả `404`).
- [ ] `PUT /personal-intelligence` cập nhật đúng công tắc Lớp 1 và text nền mà không xóa danh sách skill.
- [ ] Có kèm checklist kịch bản test tổng hợp trên Swagger UI (`http://localhost:18080/swagger-ui.html`) cho cả 3 nhóm (Project, Conversation, Profile/Skills).
- [ ] Chạy xanh `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify`.
- [ ] Chụp **2 ảnh test trên Swagger UI** (`http://localhost:18080/swagger-ui.html` — 1 case thành công, 1 case lỗi) dán vào PR.
