# Database ContextFlow

## Nguồn và phạm vi

SQL lấy từ [BranOS-03-5 — Migration, chỉ mục và truy vấn PostgreSQL chuẩn](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718), phiên bản trang **2**, cập nhật 25/11/2026, mục 4. PostgreSQL 16 + pgvector 0.8.1 là môi trường chạy và kiểm thử của repo.

| Migration | Nhóm                                                              | Số bảng |
| --------- | ----------------------------------------------------------------- | ------: |
| V001      | Identity, session, credential, cấu hình, usage counter            |      12 |
| V002      | Project, rule, skill                                              |       3 |
| V003      | Conversation, Main/Branch, Chapter, Chat/SSE, continuity, usage   |      14 |
| V004      | Tombstone, KB/vector/FTS, outbox, SoT, Context Box                |       8 |
| V005      | AI job, native steps, deletion session, export, operation receipt |       6 |
| Tổng      | Schema `contextflow`                                              |  **43** |

Flyway có thêm bảng kỹ thuật `contextflow.flyway_schema_history`; tổng số bảng kể cả bảng này là 44. `public` chứa extension `vector`, `unaccent` và cấu hình tìm kiếm tiếng Việt `cf_vi`.

V001 hiệu chỉnh seed của 4 tác vụ Sub-Branch/Chapter sang `gemini-3.8-flash` theo baseline [BranOS-00-0, mục 10.1 / CF-DEC-037/047](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/1671254) và chỉ dẫn DES-DIFF-01 trên trang SQL. Các tác vụ embedding giữ `gemini-embedding-2`, fallback để NULL. Đây là dữ liệu cấu hình; AI vẫn MOCK, chưa gọi Gemini thật. Không seed tài khoản, mật khẩu, API Key hoặc dữ liệu người dùng.

## Tạo schema local

Sau khi tạo `infra/.env` và `core-backend/.env` như README, bật PostgreSQL và chạy Core:

```powershell
npm run infra:up
# Run ContextFlowApplication trong IntelliJ với file core-backend/.env,
# hoặc export CF_DB_* vào shell rồi chạy Maven Wrapper theo README.
```

Flyway tự tạo schema và chạy V001–V005 theo thứ tự trong transaction. Không cần chạy SQL bằng tay. Nếu đang chạy Core bằng Docker từ image cũ, cập nhật riêng service:

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml up -d --build --no-deps core-backend
```

Không chạy lại các tệp SQL trực tiếp sau khi Flyway đã ghi history. Mọi thay đổi schema tiếp theo dùng V006 trở lên; giữ nguyên checksum các migration đã chia sẻ. `clean-disabled: true` và Hibernate `ddl-auto: validate` được giữ nguyên. Bộ migration này dành cho database mới hoặc database scaffold chỉ có Flyway history; không tự baseline một schema cũ không rõ nguồn.

Kiểm tra bằng `psql` trong container:

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml exec postgres psql -U contextflow -d contextflow
```

```sql
SELECT version, description, success
FROM contextflow.flyway_schema_history
WHERE version IS NOT NULL
ORDER BY installed_rank;

SELECT count(*) AS business_tables
FROM pg_tables
WHERE schemaname = 'contextflow' AND tablename <> 'flyway_schema_history';
```

Kỳ vọng 5 migration thành công và 43 bảng nghiệp vụ.

## Xem bảng trong IntelliJ

Trong cửa sổ **Database → New → Data Source → PostgreSQL**, dùng host `localhost`, port `15432`, database/user `contextflow` theo mặc định Compose. Lấy mật khẩu local từ `infra/.env`; nếu đã đổi tên/user/cổng thì dùng giá trị tương ứng. Trong **Schemas**, chọn **contextflow**, rồi refresh. Backend chạy ở cổng HTTP `18080` hoặc `8080`; cổng kết nối PostgreSQL là `15432`.

## Tách quyền Migration và Runtime (CF-DEC-042)

Compose local dùng một tài khoản development để dễ khởi tạo. Môi trường dùng chung/production cần cấp riêng tài khoản migration và runtime:

- `CF_DB_URL`, `CF_DB_USERNAME`, `CF_DB_PASSWORD`: tài khoản runtime, chỉ DML.
- `CF_FLYWAY_URL`, `CF_FLYWAY_USERNAME`, `CF_FLYWAY_PASSWORD`: tài khoản migration, được tạo schema, extension, cấu hình FTS và DDL. Khi không đặt, local dùng lại `CF_DB_*`.
- AI Service không nhận connection string, credential DB hoặc driver PostgreSQL.

Sau khi quản trị viên tạo các role và chạy migration bằng role sở hữu schema, cấp quyền cho role runtime (ví dụ tên `contextflow_runtime`) như sau:

```sql
GRANT USAGE ON SCHEMA contextflow, public TO contextflow_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA contextflow TO contextflow_runtime;
REVOKE ALL ON contextflow.flyway_schema_history FROM contextflow_runtime;
GRANT USAGE ON TYPE public.vector TO contextflow_runtime;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA contextflow TO contextflow_runtime;
GRANT EXECUTE ON FUNCTION public.unaccent(text), public.unaccent(regdictionary, text)
    TO contextflow_runtime;
```

Tài khoản runtime không sở hữu schema/table, không có quyền CREATE hoặc quyền migration. Khi thêm V006+, cấp quyền DML cho bảng mới trong bước triển khai bằng tài khoản quản trị/migration. Không đưa mật khẩu thật vào SQL đã commit.

## Kiểm thử

Bật Docker Desktop/Linux containers, dùng JDK 21 rồi chạy trong `core-backend`:

```powershell
./mvnw.cmd -Dtest=SchemaMigrationTest test
./mvnw.cmd verify
```

Testcontainers tự tạo PostgreSQL riêng, chạy Flyway trên DB trống, validate checksum và xác nhận lần migrate thứ hai không làm gì. Fixture từng bài kiểm thử được rollback, container được dọn sau lượt chạy; không dùng hoặc xóa dữ liệu development. CI Core cũng chạy các bài này và cần Docker.

`SchemaMigrationTest` bao phủ DB-IT-01–10 của BranOS-03-5: 43 bảng/seed/trigger; cách ly Project bằng composite FK; credential ACTIVE duy nhất; epoch đúng route và SET NULL có chọn cột; slot Chat và terminal duy nhất; xác nhận skip khi xóa; SoT chỉ một successor; retention usage/counter; truy vấn vector và FTS không dấu với 10.000 chunk Project khác; usage delta idempotent. Các bài kiểm thử xác minh schema và SQL; chưa thay thế nghiệm thu luồng nghiệp vụ M1–M4 hoặc PoC Gemini.
