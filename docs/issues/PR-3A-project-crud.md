# [PR-3A] Project CRUD cơ bản (`feature/project-crud`)

## Mục tiêu và phạm vi

Triển khai 4 API CRUD cơ bản cho `Project` (ưu tiên **P0 — cần để Demo**).

- **Trong phạm vi:** `GET /api/v1/projects`, `POST /api/v1/projects`, `GET /api/v1/projects/{projectId}`, `PATCH /api/v1/projects/{projectId}`.
- **Ngoài phạm vi PR này:** Archive/delete lifecycle, sao chép `ruleTemplateIds`/`skillTemplateIds` (nếu client gửi danh sách khác rỗng hoặc đổi `status` thì từ chối bằng `400 VALIDATION_ERROR`).

## Người thực hiện và reviewer

- **Người thực hiện:** Thành viên 3
- **Reviewer:** Chủ chốt 2

## Branch & Package cần tạo

- **Branch:** `feature/project-crud` (tách từ `main` sau khi `feature/local-demo-foundation` được merge)
- **Package:** `com.contextflow.project.*`
  - `entity/Project.java`
  - `repository/ProjectRepository.java` (`JpaRepository`)
  - `dto/` (`ProjectResponse.java`, `ProjectCreateRequest.java`, `ProjectPatchRequest.java`, `ProjectStatus.java`, `ProjectWritableStatus.java`)
  - `mapper/ProjectMapper.java` (`@Mapper(componentModel = "spring")`)
  - `service/ProjectService.java`, `service/impl/ProjectServiceImpl.java`
  - `controller/ProjectController.java`

## API, Request/Response mẫu & Mã lỗi

| Method  | Path                                      | Yêu cầu cốt lõi                                                                                                                                                                                    |
| ------- | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`   | `/api/v1/projects?page=0&size=20&status=` | Phân trang `page` (>= 0), `size` (1..100), lọc theo `owner_user_id`, ẩn `DELETING`/`DELETED`, sắp xếp `updated_at DESC, id`.                                                                       |
| `POST`  | `/api/v1/projects`                        | Tạo Project `ACTIVE` (`201 Created`) + gọi `IndexGenerationBootstrap` trong cùng `@Transactional`.                                                                                                 |
| `GET`   | `/api/v1/projects/{projectId}`            | Trả `ProjectResponse`; khác owner hoặc `DELETED` → `404 RESOURCE_NOT_FOUND`; `DELETING` → `409 PROJECT_DELETING`.                                                                                  |
| `PATCH` | `/api/v1/projects/{projectId}`            | Sửa `name`/`description` (phân biệt trường không gửi = giữ nguyên với `null` nếu xóa `description`); khác owner → `404`; `DELETING` → `409 PROJECT_DELETING`; `ARCHIVED` → `400 VALIDATION_ERROR`. |

**Request mẫu `POST /api/v1/projects`** (`contracts/fixtures/public/workspace/project-create.json`):

```json
{
  "name": "Bản demo ContextFlow",
  "description": "Dự án dùng để ghép giao diện.",
  "ruleTemplateIds": [],
  "skillTemplateIds": []
}
```

**Response mẫu `ProjectResponse`** (`contracts/fixtures/public/workspace/project.json`):

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Bản demo ContextFlow",
  "description": "Dự án dùng để ghép giao diện.",
  "projectIntelligenceEnabled": true,
  "status": "ACTIVE",
  "createdAt": "2026-11-26T00:00:00Z",
  "updatedAt": "2026-11-26T00:00:00Z"
}
```

**Response lỗi `409 PROJECT_DELETING`** (`contracts/fixtures/public/workspace/error-project-deleting.json`):

```json
{
  "error": {
    "code": "PROJECT_DELETING",
    "message": "Project is being deleted.",
    "retryable": false,
    "correlationId": "64254ba2-ba13-4a15-af4b-8e262140d080"
  }
}
```

**Mã lỗi bắt buộc:** `400 VALIDATION_ERROR`, `404 RESOURCE_NOT_FOUND`, `409 PROJECT_DELETING`.

## Phụ thuộc & Code nền bắt buộc gọi

- Lấy user hiện tại: `UUID userId = currentUserProvider.getCurrentUserId();` (`com.contextflow.identity.service.CurrentUserProvider`)
- Ngay sau khi lưu Project mới trong `@Transactional`: `indexGenerationBootstrap.initializeForProject(projectId);` (`com.contextflow.knowledge.service.IndexGenerationBootstrap`)
- Bảng & cột chuẩn xem tại `core-backend/src/main/resources/db/migration/V002__projects_and_intelligence.sql`:
  - Bảng `contextflow.projects`: `id`, `owner_user_id`, `name`, `description`, `project_information`, `project_intelligence_enabled`, `status`, `created_at`, `updated_at`.
- Đọc kỹ 5 luật cứng trong `.github/copilot-instructions.md` trước khi code (cấm `record`, cấm `@Autowired`, dùng `JpaRepository` + MapStruct, không sửa `V001`–`V005`).

## Tiêu chí nghiệm thu (DoD)

- [ ] Tạo mới Project đồng thời sinh 1 bản ghi `ACTIVE` trong `contextflow.index_generations`.
- [ ] Danh sách Project phân trang đúng, không trả Project của user khác hoặc đang `DELETING`/`DELETED`.
- [ ] Input sai (tên trắng, `size > 100`, UUID sai) trả `400 VALIDATION_ERROR`; sai owner trả `404`; `DELETING` trả `409 PROJECT_DELETING`.
- [ ] Chạy xanh `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify` trong `core-backend/`.
- [ ] Chụp **2 ảnh test trên Swagger UI** (`http://localhost:18080/swagger-ui.html` — 1 case thành công, 1 case lỗi) dán vào PR.
