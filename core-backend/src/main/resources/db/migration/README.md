# Flyway ownership

Scaffold chưa cung cấp migration nghiệp vụ. Không tạo migration rỗng V001–V005: tránh ghi checksum cho schema chưa được tích hợp.

Gói dữ liệu M1 lấy SQL từ **BranOS-03-5** (43 bảng), kiểm tra bằng PostgreSQL 16 + pgvector >= 0.8 rồi tích hợp đúng một lịch:

1. `V001__identity_credentials_admin.sql`
2. `V002__projects_and_intelligence.sql`
3. `V003__conversations_chat_sse_continuity.sql`
4. `V004__knowledge_base_sot_tombstones.sql`
5. `V005__ai_jobs_deletion_and_exports.sql`

Không trộn DDL 41 bảng trong Database Design tham chiếu hoặc lịch 2 file A-11 cũ. Không chỉnh sửa migration đã được nhóm chạy chung. Extensions hạ tầng được bootstrap bởi `infra/postgres/init.sql`; schema nghiệp vụ thuộc Flyway/Core.
