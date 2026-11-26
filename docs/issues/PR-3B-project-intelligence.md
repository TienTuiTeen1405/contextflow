# [PR-3B] Project Intelligence, Rules & Skills (`feature/project-intelligence`)

## Mục tiêu và phạm vi

Triển khai 8 API quản lý Lớp 2 (Project Intelligence, Project Rules và Project Skills) — ưu tiên **P1**.

- **Trong phạm vi:** `GET/PUT /api/v1/projects/{projectId}/intelligence`, `POST/PATCH/DELETE /api/v1/projects/{projectId}/rules[/{ruleId}]`, `POST/PATCH/DELETE /api/v1/projects/{projectId}/skills[/{skillId}]`.
- **Ngoài phạm vi PR này:** Sao chép từ `intelligence_templates`, biên dịch prompt AI.

## Người thực hiện và reviewer

- **Người thực hiện:** Thành viên 3
- **Reviewer:** Chủ chốt 2

## Branch & Package cần tạo

- **Branch:** `feature/project-intelligence` (tách từ `main` sau khi `PR-3A` được merge)
- **Package:** `com.contextflow.project.*`
  - `entity/ProjectRule.java`, `entity/ProjectSkill.java`
  - `repository/ProjectRuleRepository.java`, `repository/ProjectSkillRepository.java`
  - `dto/` (`ProjectIntelligenceResponse.java`, `ProjectIntelligenceUpdateRequest.java`, `ProjectRuleResponse.java`, `ProjectRuleCreateRequest.java`, `ProjectRulePatchRequest.java`, `RuleEnforcementLevel.java`, `ProjectSkillResponse.java`, `ProjectSkillCreateRequest.java`, `ProjectSkillPatchRequest.java`)
  - `mapper/ProjectIntelligenceMapper.java` (`@Mapper(componentModel = "spring")`)
  - `service/ProjectIntelligenceService.java`, `controller/ProjectIntelligenceController.java`

## API, Request/Response mẫu & Mã lỗi

| Method   | Path                                            | Yêu cầu cốt lõi                                                                                                                                     |
| -------- | ----------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`    | `/api/v1/projects/{projectId}/intelligence`     | Trả `projectIntelligenceEnabled`, `projectInformation`, `rules[]` (sắp xếp `enforcement_level, priority, id`), `skills[]` (sắp xếp `priority, id`). |
| `PUT`    | `/api/v1/projects/{projectId}/intelligence`     | Cập nhật `projectIntelligenceEnabled` và `projectInformation` (giữ nguyên `isEnabled` từng Rule/Skill).                                             |
| `POST`   | `/api/v1/projects/{projectId}/rules`            | Tạo Rule (`201 Created`); mặc định `enforcementLevel = OVERRIDABLE`, `priority = 100`, `isEnabled = true`.                                          |
| `PATCH`  | `/api/v1/projects/{projectId}/rules/{ruleId}`   | Cập nhật `name`, `content`, `enforcementLevel`, `priority`, `isEnabled` đúng `projectId`.                                                           |
| `DELETE` | `/api/v1/projects/{projectId}/rules/{ruleId}`   | Xóa Rule thuộc đúng `projectId` (`204 No Content`).                                                                                                 |
| `POST`   | `/api/v1/projects/{projectId}/skills`           | Tạo Project Skill (`201 Created`, `priority >= 0`, mặc định `100`, `isEnabled = true`).                                                             |
| `PATCH`  | `/api/v1/projects/{projectId}/skills/{skillId}` | Cập nhật Skill thuộc đúng `projectId`.                                                                                                              |
| `DELETE` | `/api/v1/projects/{projectId}/skills/{skillId}` | Xóa Skill thuộc đúng `projectId` (`204 No Content`).                                                                                                |

**Mẫu Skill JSON** (`contracts/fixtures/public/workspace/skill-create.json`, `skill.json`):

```json
{
  "name": "Trả lời tiếng Việt",
  "description": "Kỹ năng mẫu cho dự án.",
  "content": "Trả lời ngắn gọn bằng tiếng Việt.",
  "priority": 100,
  "isEnabled": true
}
```

**Mã lỗi bắt buộc:** `400 VALIDATION_ERROR` (tên trắng, `priority < 0`, `enforcementLevel` sai), `404 RESOURCE_NOT_FOUND` (Project/Rule/Skill không tồn tại hoặc khác owner/khác `projectId`), `409 PROJECT_DELETING`.

## Phụ thuộc & Code nền bắt buộc gọi

- Phụ thuộc `PR-3A` (`ProjectService` kiểm tra owner & trạng thái Project).
- Lấy user hiện tại: `UUID userId = currentUserProvider.getCurrentUserId();`
- Bảng & cột chuẩn xem tại `core-backend/src/main/resources/db/migration/V002__projects_and_intelligence.sql`:
  - `contextflow.projects`: `project_information`, `project_intelligence_enabled`
  - `contextflow.project_rules`: `id`, `project_id`, `name`, `content`, `enforcement_level` (`MANDATORY` | `OVERRIDABLE`), `priority`, `is_enabled`, `created_at`, `updated_at`
  - `contextflow.project_skills`: `id`, `project_id`, `name`, `description`, `content`, `priority`, `is_enabled`, `created_at`, `updated_at`
- Tuân thủ 5 luật cứng trong `.github/copilot-instructions.md`.

## Tiêu chí nghiệm thu (DoD)

- [ ] `PUT /intelligence` chỉ đổi công tắc tổng & `projectInformation`, không ghi đè `isEnabled` của các Rule/Skill con.
- [ ] Rule/Skill không thể truy cập hoặc sửa/xóa chéo từ `projectId` khác (`404 RESOURCE_NOT_FOUND`).
- [ ] Chạy xanh `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify`.
- [ ] Chụp **2 ảnh test trên Swagger UI** (`http://localhost:18080/swagger-ui.html` — 1 case thành công, 1 case lỗi) dán vào PR.
