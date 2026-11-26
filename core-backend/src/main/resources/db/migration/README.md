# Flyway ownership

Đã tích hợp 5 migration nghiệp vụ, tổng 43 bảng trong schema `contextflow`, từ [BranOS-03-5](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718), phiên bản trang 2 ngày 25/11/2026, mục 4. Flyway tự chạy tuần tự khi Core khởi động.

Lịch migration duy nhất:

1. `V001__identity_credentials_admin.sql`
2. `V002__projects_and_intelligence.sql`
3. `V003__conversations_chat_sse_continuity.sql`
4. `V004__knowledge_base_sot_tombstones.sql`
5. `V005__ai_jobs_deletion_and_exports.sql`

Không trộn DDL 41 bảng trong Database Design tham chiếu hoặc lịch 2 file A-11 cũ. Không chỉnh sửa migration đã được nhóm chạy chung. Extensions hạ tầng được bootstrap bởi `infra/postgres/init.sql`; schema nghiệp vụ thuộc Flyway/Core.

Hiệu chỉnh có chủ đích trước lần chạy đầu: V001 dùng `gemini-3.8-flash` cho cả 8 tác vụ sinh văn bản theo CF-DEC-037/047 và ghi chú DES-DIFF-01 trên trang nguồn. Hai tác vụ embedding giữ `gemini-embedding-2`; không seed Credential, tài khoản Admin hay dữ liệu người dùng. Registry không kích hoạt gọi Gemini thật trong scaffold MOCK.

Xem [hướng dẫn setup & database](../../../../../../docs/SETUP-GUIDE.md#5-database-postgresql-16--pgvector--flyway) về kết nối, xem bảng trong IntelliJ và kiểm thử PostgreSQL/Testcontainers.
