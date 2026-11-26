# Sở hữu code và review

Đây là trách nhiệm phát triển/review code. Người dùng chưa phân thành viên; các vai trò bên dưới **chưa gán tên GitHub**. Một người có thể kiêm nhiều vai trò. Không có named reviewer/branch protection đang được áp dụng bởi cấu hình này.

Nguồn máy đọc: [ownership.json](../.github/ownership.json). Mỗi bảng có một domain, một Entity và một Repository dự kiến; tên lớp là điểm phân công, chưa tạo sẵn 43 lớp rỗng. Core độc quyền persistence; Web/AI không tạo Entity/Repository hay kết nối DB.

## Domain và bảng

| Domain/package dưới `com.contextflow` | Vai trò sở hữu   | Bảng                                                                                                                                                                                     |
| ------------------------------------- | ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `identity`                            | `core-identity`  | `users`, `user_oauth_accounts`, `auth_sessions`, `personal_skills`                                                                                                                       |
| `credential`                          | `core-identity`  | `ai_credentials`, `privacy_disclosure_acknowledgements`                                                                                                                                  |
| `admin`                               | `core-platform`  | `task_model_registry`, `platform_settings`, `intelligence_templates`, `prompt_templates`, `usage_quota_limits`                                                                           |
| `project`                             | `core-workspace` | `projects`, `project_rules`, `project_skills`, `deletion_sessions`, `deletion_stage_items`                                                                                               |
| `conversation`                        | `core-workspace` | `conversations`, `main_stages`, `chapters`, `chapter_summaries`, `sub_branches`, `turns`, `turn_main_references`, `turn_quote_references`                                                |
| `ai`                                  | `core-ai`        | `continuity_epochs`, `continuity_turn_entries`, `ai_requests`, `ai_request_events`, `ai_usage_records`, `ai_execution_audits`, `ai_jobs`, `ai_job_provider_steps`, `user_usage_counters` |
| `knowledge`                           | `core-knowledge` | `tombstones`, `index_generations`, `knowledge_items`, `knowledge_chunks`, `embedding_outbox`                                                                                             |
| `sot`                                 | `core-knowledge` | `sot_decisions`, `sot_decision_evidences`                                                                                                                                                |
| `context`                             | `core-workspace` | `context_box_items`                                                                                                                                                                      |
| `export`                              | `core-workspace` | `export_jobs`                                                                                                                                                                            |
| `common.idempotency`                  | `core-platform`  | `operation_receipts`                                                                                                                                                                     |

Các Entity/Repository đặt trong domain sở hữu, ví dụ `conversation/MainStage` và `conversation/MainStageRepository`. Domain khác gọi service/facade của chủ sở hữu khi ghi trạng thái; không tạo Entity thứ hai cho cùng bảng. Mapping composite FK và truy vấn phải giữ scope Project. `knowledge` sở hữu Tombstone dùng chung; `ai` sở hữu usage/counters để Admin đọc qua facade; `project` điều phối deletion session xuyên Conversation/Stage.

## Người duyệt phần dùng chung

| Phần thay đổi                                 | Vai trò review bắt buộc theo quy trình nhóm                            |
| --------------------------------------------- | ---------------------------------------------------------------------- |
| Public OpenAPI, error envelope, public SSE    | Core Platform + Web Lead; Integration Maintainer khi đổi quy ước chung |
| Internal DTO, task endpoint, internal SSE     | Core AI + AI Lead                                                      |
| Shared schema/generator ảnh hưởng cả hai biên | Integration Maintainer + Core Platform + Web Lead + AI Lead            |
| Entity/Repository hoặc migration              | Chủ domain + Data Maintainer; migration chung thêm Core Platform       |
| Core security/config/common                   | Core Platform + Integration Maintainer                                 |
| CI, Docker, preset local                      | Integration Maintainer; Data Maintainer nếu ảnh hưởng DB               |
| UI Web                                        | Web Lead; consumer Core nếu đổi API                                    |
| Provider Adapter/Prompt Builder               | AI Lead; Core AI nếu đổi hợp đồng                                      |

PR ghi domain/bảng, contract/migration ảnh hưởng và vai trò đã review. V001–V005 đã chia sẻ: thay đổi DDL tiếp theo dùng V006+, không sửa checksum cũ.

## Gán thành viên sau

1. Điền GitHub username hoặc `@organization/team` thật vào mảng `github` của từng vai trò trong `.github/ownership.json`.
2. Chạy `npm run owners:check`: kiểm tra đủ 43 bảng, không trùng chủ và không tham chiếu vai trò không tồn tại.
3. Chạy `npm run owners:generate` để tạo `.github/CODEOWNERS`. Lệnh từ chối khi còn vai trò chưa gán, tránh tạo owner giả. Có bản [CODEOWNERS.example](../.github/CODEOWNERS.example) để xem phạm vi.
4. Sau khi có remote, quản trị viên xác nhận các tài khoản có quyền repo, bật yêu cầu code-owner review và số approval phù hợp.

CODEOWNERS dùng quy tắc match cuối cùng; nhiều handle trên một dòng không đồng nghĩa GitHub buộc tất cả cùng approve. Quy trình review xuyên biên ở bảng trên vẫn cần các vai trò liên quan xác nhận trong PR; cấu hình bảo vệ nhánh phải được áp dụng trên repo chung sau khi gán người. CI hiện kiểm tra tính đầy đủ của phân công, không giả lập việc con người đã review.
