# Setup và bắt đầu code — ContextFlow

Repo hiện là nền để thành viên tự triển khai chức năng. API/DTO và CRUD viết sẵn cho Project, Conversation, hồ sơ và Personal Skills đã được gỡ, cùng Demo Mode. Dữ liệu PostgreSQL hiện có được giữ.

## 1. Công cụ

| Công cụ        | Phiên bản / mục đích                                                   |
| -------------- | ---------------------------------------------------------------------- |
| JDK            | 21; cấu hình JAVA_HOME                                                 |
| IntelliJ       | Mở Maven project; bật Annotation Processing cho Lombok/MapStruct       |
| Docker Desktop | Linux containers; PostgreSQL 16 + pgvector 0.8.1, AI và Testcontainers |
| Node/npm       | 24/11; hỗ trợ setup và công cụ repo                                    |
| Python/uv      | 3.13/0.13.1; cần khi làm AI Service                                    |

Maven Wrapper nằm trong `core-backend`. `npm ci` cần khi làm Web hoặc chạy công cụ contract/format; setup backend chỉ dùng Node built-in.

## 2. Cách chính: IntelliJ/Maven ở 18080

Bật Docker Desktop, mở terminal tại root:

```powershell
npm run setup:local
npm run infra:up:ide
```

1. Trong IntelliJ, mở `core-backend/pom.xml` và chọn **Add as Maven Project**.
2. Dùng JDK 21, Maven Wrapper; bật **Settings → Compiler → Annotation Processors → Enable annotation processing**.
3. Chọn run config **ContextFlow Core (IDE)**; working directory là `$PROJECT_DIR$/core-backend`.
4. Để Active profiles trống, không nhập lại biến môi trường hoặc chọn EnvFile; Spring tự đọc file local.
5. Run, mở http://localhost:18080/swagger-ui.html.

Nếu tạo run config mới: main class `com.contextflow.ContextFlowApplication`, module `core-backend`, cùng working directory trên.

Thay IntelliJ bằng Maven trực tiếp:

```powershell
Set-Location core-backend
.\mvnw.cmd spring-boot:run
```

macOS/Linux: `cd core-backend`, `./mvnw spring-boot:run`. Shortcut từ root: `npm run dev:core`.

Chỉ chạy một process trên 18080. Dừng bằng Ctrl+C hoặc nút Stop trong IDE trước khi chuyển cách chạy. Ở terminal root riêng:

```powershell
npm run local:check
```

Kiểm tra này xác nhận readiness, Swagger/CORS và AI Mock, không xác nhận API nghiệp vụ đã có.

## 3. Cách phụ: Docker Core ở 8080

```powershell
npm run setup:local -- --mode docker
npm run stack:up
npm run local:check
```

Swagger: http://localhost:8080/swagger-ui.html. Web scaffold: http://localhost:3000.
Dừng bằng `npm run stack:down`; volume được giữ.

Đổi về IDE: `npm run setup:local -- --mode ide`, `npm run infra:up:ide`, rồi Run Core. Khi làm Web, restart dev server sau đổi URL; Docker Web luôn build để gọi Core 8080.

## 4. Cổng và cấu hình

| Thành phần | IDE                         | Docker                        |
| ---------- | --------------------------- | ----------------------------- |
| Core       | localhost:18080             | localhost:8080                |
| PostgreSQL | localhost:15432             | postgres:5432 trong network   |
| AI         | 127.0.0.1:18000             | ai-service:8000 trong network |
| Web        | localhost:3000 khi chạy dev | localhost:3000                |

Setup tạo các file được Git bỏ qua:

- `infra/.env`: thông tin DB và service token chung.
- `core-backend/.env`: `CF_DB_*`, `CF_FLYWAY_*` tùy chọn, `CF_CORE_PORT`, `CF_AI_BASE_URL`, service token và KEK.
- `ai-service/.env`: chế độ MOCK và service token; không có DB/KEK.
- `web/.env.local`: URL Core công khai.

Setup giữ password DB/KEK và loại profile demo cũ. Nếu DB/volume đã tồn tại, password trong `infra/.env` phải khớp DB; setup không tự xoay password PostgreSQL.

`application.yaml` import `optional:file:./.env[.properties]` qua CF_LOCAL_ENV_IMPORT và `application-database.yaml`. File database luôn được nạp, không cần profile riêng.

File Core dùng **Java properties**: không bọc giá trị bằng dấu nháy, không dùng `export`. Script setup tự escape ký tự đặc biệt; giữ comment định dạng đầu file. AI/Compose dùng dotenv riêng. Không commit hoặc sao chép secret sang `NEXT_PUBLIC_*`.

Core local bind 127.0.0.1; trong Docker bind 0.0.0.0, host port vẫn chỉ publish loopback. Container nhận cấu hình từ Compose, không đóng file local vào image.

## 5. Những gì có và chưa có

| Phần                                         | Trạng thái                                     |
| -------------------------------------------- | ---------------------------------------------- |
| Core khởi động, health, Swagger và lỗi chung | Có                                             |
| Database/Flyway                              | V001–V005, 43 bảng                             |
| JPA, MapStruct, Lombok                       | Dependency và annotation processor đã cấu hình |
| API Project/Conversation/Profile/Skills      | Chưa triển khai; thành viên code từ đầu        |
| Entity/Repository/Service/Mapper nghiệp vụ   | Chưa có                                        |
| Demo user/filter/seeder                      | Đã gỡ                                          |
| Auth/JWT                                     | Hoãn                                           |
| AI nội bộ                                    | Bốn task endpoint MOCK, không gọi Gemini       |
| Web                                          | Scaffold                                       |

Swagger runtime `/v3/api-docs` chỉ hiển thị controller đã code. `contracts/public.openapi.yaml` và fixtures là đặc tả từ BranOS; các đường dẫn trong đó không tự tạo endpoint.

Security hiện mở health và Swagger, đóng các route còn lại. Khi triển khai API, người phụ trách phải cập nhật policy truy cập và test tương ứng; không còn user demo tự động cung cấp danh tính/owner.

## 6. Nguyên tắc code của dự án

Các quy tắc dưới đây áp dụng cho chức năng mới và code sửa trong PR. Repo vẫn là bộ khung; cấu hình dependency hoặc đặc tả API không có nghĩa chức năng đã được triển khai.

### 6.1. Cấu trúc và cách đặt tên

Tạo package theo domain khi nhận việc, không tạo hàng loạt lớp rỗng:

```text
<domain>/
  controller/
  dto/
  service/
    impl/
  entity/
  repository/
  mapper/
```

- Package theo domain, không gom toàn dự án vào một thư mục controller/service/repository chung. Chỉ tạo lớp khi chức năng thực sự dùng; không tạo trước hàng loạt DTO, Entity hoặc mapper rỗng.
- Class/interface dùng PascalCase; method/biến dùng camelCase; hằng số dùng UPPER_SNAKE_CASE. Tên Java bằng tiếng Anh và thể hiện đúng nghiệp vụ.
- Tên thống nhất: `ProjectController`, `ProjectService`, `ProjectServiceImpl`, `ProjectRepository`, `ProjectMapper`, `ProjectCreateRequest`, `ProjectResponse`. Ví dụ chỉ là quy ước đặt tên, không phải yêu cầu tạo sẵn các lớp.
- Hàm xử lý một việc, tên thể hiện hành động. Comment giải thích lý do hoặc bất biến khó thấy; không lặp lại từng dòng code. Không để code bị comment, TODO không rõ đầu việc hoặc endpoint rỗng trả thành công.

### 6.2. Java, Lombok và DTO

- **Không dùng Java `record`**, kể cả DTO, configuration properties và helper model. Dùng `class` hoặc `interface` theo vai trò.
- Mỗi DTO là một file Java trong `dto/`. Tách request và response; không tái sử dụng Entity làm request/response hoặc tạo một DTO chứa mọi trường của nhiều chức năng.
- DTO có thể dùng Lombok `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor` khi cần. Entity và DTO chứa password/credential dùng annotation chọn lọc như `@Getter`, `@Setter`; không dùng `@Data`/`@ToString` làm lộ secret hoặc kéo theo quan hệ dữ liệu.
- Production code dùng constructor injection, ưu tiên `@RequiredArgsConstructor` với dependency `private final`. Không dùng field injection bằng `@Autowired`, không tự `new` service/repository do Spring quản lý.
- Dùng `UUID` cho ID theo schema và `Instant` cho thời gian UTC. Dùng enum cho tập trạng thái đóng đã có trong đặc tả; không tạo trạng thái hoặc giá trị mặc định nghiệp vụ tùy ý.
- Khai báo kiểu cụ thể. Không dùng `Object`, `Map<String, Object>` hoặc `JsonNode` thay cho DTO đã biết cấu trúc. JSON opaque của Provider được giữ ở biên nội bộ đúng hợp đồng.
- Dữ liệu hợp lệ, thiếu trường và `null` phải có nghĩa rõ ràng. PATCH cần phân biệt trường không gửi với yêu cầu xóa giá trị nullable; có kiểm thử để tránh vô tình xóa hoặc giữ sai dữ liệu.

### 6.3. Controller, Service và truy cập dữ liệu

Controller–Service–Repository là cách chia trách nhiệm, không phải tên công nghệ database. JPA quản lý Entity có annotation và cung cấp CRUD qua JpaRepository; JdbcTemplate thực thi SQL do Repository tự viết và ánh xạ kết quả vào model.

- Controller chỉ nhận HTTP, validate bằng `@Valid`/Jakarta Validation, gọi service và đặt HTTP status/header. Không truy vấn DB, xử lý quyền, dựng prompt hoặc đặt transaction ở Controller.
- Service nghiệp vụ là **interface trong `service/`**, implementation là **`@Service` trong `service/impl/`**. Controller và domain khác inject interface. ServiceImpl giữ nghiệp vụ, kiểm tra quyền, lifecycle và transaction.
- Repository chỉ đọc/ghi dữ liệu, trả Entity hoặc read model có kiểu rõ ràng; không quyết định nghiệp vụ và không dựng HTTP response. Không gọi repository trực tiếp từ Controller.
- **Lựa chọn chính đã chốt: JPA cho CRUD.** Model lưu trữ là class có `@Entity`; Repository là interface kế thừa `JpaRepository<Entity, UUID>`, dùng các hàm có sẵn như `save`, `findById`, `delete` hoặc query có scope theo nghiệp vụ. SQL đặc thù, worker và thao tác khóa có thể dùng `JdbcTemplate` trong Repository khi có lý do cụ thể.
- Với `JdbcTemplate`, truyền giá trị bằng tham số `?`; không nối dữ liệu người dùng vào SQL. Nếu cần tên cột hoặc hướng sort động, chọn từ whitelist do server định nghĩa. Liệt kê cột cần đọc, dùng `RowMapper` có kiểu; tránh `SELECT *` và map key dạng chuỗi cho dữ liệu đã biết.
- Với JPA, giữ `ddl-auto=validate`, tắt Open Session in View. Không dùng cascade/eager fetch tràn lan; kiểm tra truy vấn phát sinh khi đọc danh sách để tránh N+1. Không tạo mapping Entity cho các bảng chưa dùng.
- Không tự mở `Connection`, tự commit/rollback hoặc tự đóng connection do Spring quản lý. Khi phối hợp JPA và JDBC, dùng cùng datasource/transaction; flush bản ghi cha trước SQL tạo con.
- Entity/model có nhiều trường cần chuyển đổi dùng MapStruct: `componentModel = "spring"`, `unmappedTargetPolicy = ReportingPolicy.ERROR`. Mapper chỉ ánh xạ dữ liệu; không gọi DB, kiểm tra quyền hoặc chứa nghiệp vụ. Không viết tay/sửa/commit MapperImpl trong `target/`.

### 6.4. Quyền sở hữu và transaction

- Core là nơi duy nhất truy cập PostgreSQL. Truy vấn tài nguyên người dùng phải có owner scope; truy vấn tài nguyên dự án phải có project scope, kiểm tra cả chuỗi cha–con. Không chỉ tìm theo ID rồi mặc định cho phép thao tác.
- Không lấy role/owner/user hiện hành từ trường do client tự khai báo. Không hardcode một user giả hoặc tự seed dữ liệu nghiệp vụ để làm endpoint chạy được. Cách cung cấp danh tính khi Auth/JWT còn hoãn phải được chốt ở đầu việc tích hợp.
- `@Transactional` đặt ở ServiceImpl cho một use case. Chỉ đọc dùng `readOnly = true` khi phù hợp. Các bước dữ liệu phải nguyên tử thì cùng commit hoặc cùng rollback.
- **Không giữ DB transaction trong lúc gọi FastAPI, Gemini hoặc dịch vụ mạng.** Đọc/snapshot, gọi mạng và commit là các pha riêng theo đặc tả; kiểm tra lại điều kiện cần thiết trước commit.
- Trường hợp đồng thời phải dùng constraint/lock/lease và transaction đúng thiết kế. Không thay bằng biến static hoặc khóa trong bộ nhớ của một instance; không dựa riêng vào kiểm tra tồn tại trước insert.
- Không sửa hoặc xóa dữ liệu của domain khác bằng repository tự tạo; gọi service của domain sở hữu. Không sửa migration đã chia sẻ hoặc bật Hibernate auto-create/update để vượt lỗi DB.

### 6.5. Hợp đồng API, validation và lỗi

- Đường dẫn, method, status, trường JSON và mã lỗi bám BranOS; không tự thêm wrapper thành công hoặc đổi tên payload theo sở thích. Public JSON dùng camelCase, nội bộ Core–AI dùng snake_case.
- Request validate ở biên HTTP; service kiểm tra quyền, quan hệ, trạng thái và điều kiện nghiệp vụ. Giá trị sai phải bị từ chối rõ ràng; không âm thầm đổi kiểu, bỏ trường lỗi hoặc trả thành công giả.
- Giới hạn page/size và dùng thứ tự sort ổn định; có ID làm tiêu chí phụ khi cần. Không trả toàn bộ bảng chỉ vì dữ liệu local ít.
- Lỗi nghiệp vụ dùng `ApiProblem` và `ErrorCode` chung; `ApiProblemHandler` trả `ErrorEnvelope`. Không bắt mọi exception rồi trả HTTP 200/null/danh sách rỗng; không trả stack trace hoặc nội dung exception chứa secret cho client.
- Tài nguyên không thuộc quyền và Project DELETING xử lý theo hợp đồng, tránh để lộ dữ liệu. Response không chứa Entity, password hash, raw credential hoặc dữ liệu Provider nội bộ.
- Swagger sinh từ controller/DTO thực tế. Đặc tả YAML hoặc response mẫu chưa chứng minh endpoint đã chạy; chỉ ghi hoàn thành sau khi có kiểm chứng hành vi.

### 6.6. AI Service và luồng Chat/SSE

- AI Service dùng Pydantic v2, type hints đầy đủ, snake_case, Ruff và mypy strict. Settings đọc từ cấu hình; không hardcode token/model/URL vào hàm nghiệp vụ.
- FastAPI stateless theo request: không kết nối DB, không lưu/cache credential, không tự retry nghiệp vụ. Chỉ Core điều phối persistence, retry và quyền sử dụng dữ liệu.
- Dùng service token cho kênh Core–AI. Chỉ truyền các trace ID và dữ liệu cần thiết đúng hợp đồng; không gửi business UUID tùy ý hoặc secret qua query string/log.
- Giữ Mock và ghi rõ kết quả Mock. Provider/SDK thật chỉ bật theo quyết định và PoC Gate; không coi phản hồi giả hoặc Verify metadata là bằng chứng Chat thật hoạt động.
- Khi triển khai SSE, worker độc lập với subscriber; reconnect chỉ đọc lại sự kiện, không dispatch lại Chat. Chỉ một terminal event; delta tạm không phải Turn đã commit.
- Usage không biết giữ `null`, không đổi thành 0. Không phát thought/signature/raw Provider steps/secret qua public SSE.

### 6.7. Web, cấu hình và bảo mật

- Web dùng TypeScript strict, chỉ gọi Core; không gọi Provider hoặc database trực tiếp. TanStack Query giữ server state; Zustand giữ UI state. Query key phải có phạm vi user/project phù hợp.
- Không lưu API Key vào localStorage, sessionStorage hoặc state dùng chung. Không đưa token nội bộ, password hoặc KEK vào `NEXT_PUBLIC_*`.
- Cấu hình local đi qua file `.env` được Git bỏ qua. Không commit secret, raw prompt, native steps thực tế hoặc log chưa che bí mật; không copy secret từ dự án khác.
- Chỉ mở endpoint cần thiết khi triển khai và có kiểm thử truy cập. Không mở toàn bộ API bằng `permitAll` để bỏ qua lỗi hoặc tự thêm lại cơ chế demo.

### 6.8. Bàn giao và kiểm thử

- Mỗi PR theo một chức năng, ghi API được thêm/sửa, trạng thái trước/sau, cách kiểm tra và ảnh hưởng DB/consumer. Không sửa hàng loạt module ngoài phạm vi hoặc thêm thư viện chưa có nhu cầu cụ thể.
- Chạy check của phần thay đổi theo mục 8. API mới cần kiểm thử thành công, input lỗi, sai quyền/project và lifecycle liên quan; thêm kiểm thử rollback/đồng thời khi use case có yêu cầu nguyên tử hoặc idempotency.
- DB integration test dùng PostgreSQL/pgvector riêng qua Testcontainers, không dùng H2/SQLite thay thế hoặc chạy trên DB development. Mock biên gọi mạng khi kiểm thử, không gọi Provider thật trong CI thông thường.
- Kiểm thử phải xác nhận hành vi; không chỉ kiểm tra mock được gọi hoặc sao chép từng dòng implementation. Tài liệu thuần túy chỉ cần kiểm tra định dạng và tính nhất quán, không chạy lại toàn bộ test chức năng.
- Chạy Spotless cho Java, Ruff cho Python, ESLint/TypeScript cho Web và Prettier cho tài liệu/config. Không commit generated output trong `target/`, dependency hoặc file local.

Ownership domain và bảng nằm tại `.github/ownership.json`. Tên Entity/Repository trong file là quy hoạch để nhóm triển khai, không xác nhận class đã tồn tại. Domain khác ghi dữ liệu qua service của chủ sở hữu; không tạo mapping trùng cùng bảng. Reviewer theo tên chưa được gán.

### 6.9. Bảng nguyên tắc phối hợp nhóm — không dùng Jira

Dùng GitHub Issues để nhận việc và PR để bàn giao code; không cần Jira hoặc mã ticket trong tên branch. Trước khi có repo GitHub chung, ghi cùng thông tin vào bảng việc của nhóm rồi chuyển sang Issues khi repo sẵn sàng. Mẫu nhận việc nằm tại `.github/ISSUE_TEMPLATE/task.yml`.

| Nội dung            | Quy tắc thống nhất                                                                                                                                                                                                                              |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Nhánh chung         | `main` là nhánh tích hợp. Thành viên làm trên branch riêng; code chức năng vào main qua PR. Người phụ trách repo đưa bộ khung ban đầu lên GitHub trước khi cả nhóm tạo branch.                                                                  |
| Tên branch          | `<loại>/<phần-việc>`, tiếng Anh, chữ thường, dùng dấu `-`. Loại gồm `feature`, `fix`, `refactor`, `docs`, `chore`. Không cần tên cá nhân, ngày tháng hoặc mã Jira.                                                                              |
| Ví dụ branch        | `feature/ai-prompt-builder`, `feature/core-chat-request`, `feature/project-create`, `feature/conversation-create`, `feature/personal-skill-crud`, `fix/project-pagination`, `docs/setup-guide`, `chore/update-ci`.                              |
| Phạm vi             | Một branch phục vụ một đầu việc và một PR. Không gom toàn bộ việc của một thành viên vào branch tồn tại nhiều tuần hoặc sửa thêm module ngoài phạm vi.                                                                                          |
| Nhận việc           | Mỗi đầu việc có một người chịu trách nhiệm, một người review, mục tiêu, API/bảng/file liên quan, phụ thuộc và tiêu chí nghiệm thu. Nhận việc trước khi code để tránh hai người làm trùng.                                                       |
| Tiến độ             | Theo dõi Chưa làm → Đang làm → Chờ review → Hoàn thành. Bị chặn phải ghi nguyên nhân, cần ai hỗ trợ và phần độc lập vẫn làm được; giữ rõ trạng thái chưa hoàn thành.                                                                            |
| Số việc đang làm    | Mỗi người tập trung một đầu việc chính. Việc đang chờ review có thể chuyển sang đầu việc độc lập đã nhận, không mở nhiều thay đổi phụ thuộc chưa tích hợp.                                                                                      |
| Phân công           | Bạn phụ trách AI Service; bạn chủ chốt còn lại phụ trách Core Chat/tích hợp; ba thành viên phụ trách Project, Conversation và hồ sơ/Personal Skills. Việc ngoài đợt đầu được tách riêng, không tự mở rộng phạm vi.                              |
| Reviewer            | PR của ba thành viên Core do đầu mối Core duyệt. PR của đầu mối Core do đầu mối AI duyệt; PR của đầu mối AI do đầu mối Core duyệt về hợp đồng, ranh giới và kiểm chứng. Tác giả giải thích phần chuyên môn khi cần; không tự duyệt PR của mình. |
| Phần dùng chung     | Sửa `common/`, cấu hình security, contracts, migration, `pom.xml`, lockfile, Compose, script hoặc CI phải nêu phạm vi trước khi sửa và để hai đầu mối xem ảnh hưởng. Người dùng chung phải được biết trước thay đổi phá tương thích.            |
| API giữa hai người  | Chốt method/path, DTO, trường bắt buộc/null, status và mã lỗi trước khi code hai phía. Chốt rõ bên nào cung cấp interface/helper dùng chung; không tự đặt payload khác nhau rồi chờ lúc merge mới sửa.                                          |
| Phụ thuộc chưa có   | Ghi rõ interface và dữ liệu cần nhận. Dùng fixture/mock trong test để làm phần độc lập; chưa có phụ thuộc thật thì ghi chưa tích hợp, không tự tạo endpoint giả trả thành công.                                                                 |
| Migration           | Thống nhất số version tiếp theo với đầu mối Core trước khi tạo để tránh hai PR cùng V006. Schema do Flyway quản lý; không sửa migration đã chia sẻ, xóa volume hoặc chạy SQL tay trên DB chung để vượt lỗi.                                     |
| Commit              | `<loại>(<phạm-vi>): <hành-động>`, ví dụ `feat(project): add project creation`, `fix(ai): handle missing usage`, `docs(team): document review workflow`. Dùng commit nhỏ có mục đích; tránh tên `update`, `fix bug`, `done`.                     |
| File được commit    | Đọc `git status` và diff trước commit. Chỉ stage file của đầu việc; không đưa `.env`, secret, `.idea`, `target`, `.venv`, `node_modules`, log hoặc generated MapperImpl vào PR.                                                                 |
| Draft PR            | Mở Draft khi cần thống nhất hướng làm hoặc phát hiện xung đột sớm. Chuyển sang Ready for review khi checklist đạt; ghi rõ phần chưa có và kết quả Mock/local.                                                                                   |
| Đồng bộ main        | Fetch main trước khi bắt đầu và cập nhật branch khi main thay đổi có ảnh hưởng, trước khi bàn giao. Ưu tiên merge `origin/main` vào branch riêng để các bạn mới không cần viết lại lịch sử.                                                     |
| Conflict            | Người làm PR xử lý cùng người sở hữu phần bị xung đột, đọc cả hai thay đổi và kiểm tra lại hành vi. Không chọn hàng loạt ours/theirs hoặc nhờ AI giải quyết mà không đọc diff.                                                                  |
| Kiểm tra            | Chạy check phù hợp mục 8 và ghi lệnh/kết quả thật trong PR. Thay đổi shared config/API phải kiểm tra consumer liên quan. CI đỏ phải xử lý hoặc ghi rõ nguyên nhân được reviewer xác minh; không coi PR đỏ là sẵn sàng merge.                    |
| Review và merge     | Cần ít nhất một approval từ người khác theo phân công; nhận xét bắt buộc đã xử lý, check bắt buộc xanh và branch không conflict. Người duyệt hoặc đầu mối tích hợp dùng Squash and merge vào main.                                              |
| Sau review          | Nếu có commit sửa đáng kể sau approval, yêu cầu reviewer xem lại trước merge. Không thay toàn bộ phạm vi trong PR đã duyệt.                                                                                                                     |
| Sau merge           | Đóng đầu việc, ghi API/cách chạy đã bàn giao, xóa feature branch sau khi xác nhận đã merge. Cập nhật main ở máy; đầu việc tiếp theo tạo branch mới từ main mới nhất.                                                                            |
| Main bị lỗi         | Báo ngay, tạm ngừng merge các phần phụ thuộc; hai đầu mối phối hợp sửa bằng PR nhỏ hoặc revert PR gây lỗi. Không viết lại lịch sử main hoặc sửa trực tiếp DB để che lỗi.                                                                        |
| Hỗ trợ bằng AI      | Tác giả phải hiểu, giải thích và kiểm tra code AI tạo ra; đọc toàn bộ diff trước bàn giao. AI không thay người chịu trách nhiệm hoặc người review. Không gửi secret/dữ liệu thật vào prompt.                                                    |
| Trao đổi trong nhóm | Khi bắt đầu/kết thúc buổi làm hoặc gặp vướng, cập nhật đầu việc: đã làm gì, làm tiếp gì, đang chờ ai. Reviewer phản hồi trong buổi làm kế tiếp hoặc báo khi chưa thể review; không để người giao việc phải đoán tiến độ.                        |

Các branch naming và commit convention hiện được kiểm tra khi review. Repo chưa có remote chung và chưa gán GitHub username trong ownership; bảng này không có nghĩa GitHub đã tự chặn push/merge. Khi tạo repo chung, người quản lý bật bảo vệ main, yêu cầu PR, ít nhất một approval, hủy approval cũ khi có thay đổi mới, CI checks phù hợp và chặn force-push/xóa main. Mẫu CODEOWNERS chỉ trở thành cấu hình thật sau khi điền người phụ trách.

### 6.10. Các bước Git cho mỗi đầu việc

Các lệnh dưới đây dùng sau khi repo đã có remote tên `origin` và nhánh main chung. Working tree phải sạch; commit có chọn lọc phần đang làm trước khi đổi branch. Không dùng reset/clean để bỏ thay đổi chỉ vì chưa biết xử lý.

**Bắt đầu từ main mới nhất:**

```powershell
git status
git switch main
git pull --ff-only origin main
git switch -c feature/project-create
```

**Làm và gửi branch:**

```powershell
git status
git diff
# Chọn từng file của đầu việc trong IDE hoặc git add <file>.
git diff --cached
git commit -m "feat(project): add project creation"
git push -u origin feature/project-create
```

Tạo PR từ branch vừa push vào main, dùng template của repo và chọn reviewer. Không push code chức năng trực tiếp vào main.

**Đưa main mới vào branch đang làm khi cần:**

```powershell
# Đang đứng trên feature branch, working tree sạch.
git fetch origin
git merge origin/main
```

Nếu conflict, giải quyết với người phụ trách file, stage file đã xử lý, hoàn tất merge và chạy lại check bị ảnh hưởng trước khi push. Không force-push lên main hoặc branch của người khác.

**Sau khi PR được squash-merge:**

```powershell
git switch main
git pull --ff-only origin main
git switch -c feature/project-detail
```

GitHub có thể xóa remote feature branch sau merge. Local branch có thể giữ để đối chiếu rồi xóa qua IDE khi đã xác minh; không tiếp tục commit công việc mới vào branch đã merge.

### 6.11. Checklist hoàn thành một chức năng

- [ ] Đúng phạm vi nhận việc và hợp đồng BranOS; không để thay đổi chung chưa thống nhất.
- [ ] Người khác chạy được theo README và thực hiện lại kịch bản nghiệm thu trong PR.
- [ ] API/DTO, quyền sở hữu, validation, transaction và mã lỗi đã kiểm tra theo phạm vi chức năng.
- [ ] Check liên quan đạt; có lệnh và kết quả, ghi rõ Mock/local/chưa tích hợp.
- [ ] Migration/config mới đã ghi cách áp dụng; không cần sửa SQL tay hoặc xóa dữ liệu để chạy.
- [ ] Đã đọc diff, loại file ngoài phạm vi/secret/generated output và cập nhật tài liệu cần thiết.
- [ ] Reviewer khác tác giả đã duyệt phiên bản cuối, PR merge vào main và đầu việc được cập nhật.

### 6.12. Bảng nhận việc khi chưa có GitHub Issues

Mỗi dòng là một đầu việc nhỏ, không phải toàn bộ phần việc của một người. Khi có Issues, chuyển thông tin vào issue và để lại link tại bảng. Chỉ đánh dấu Đang làm sau khi người thực hiện nhận việc và các phụ thuộc được xác nhận.

| Đầu việc                             | Người thực hiện | Reviewer | Branch   | Phụ thuộc            | Trạng thái | Issue/PR hoặc bằng chứng |
| ------------------------------------ | --------------- | -------- | -------- | -------------------- | ---------- | ------------------------ |
| Điền tên đầu việc khi nhóm nhận việc | Chưa gán        | Chưa gán | Theo 6.9 | Ghi rõ hoặc không có | Chưa làm   | Bổ sung sau              |

## 7. Database và Flyway

Core tự migrate V001–V005. Giữ nguyên migration đã chia sẻ; thêm V006+ khi cần thay DDL. `ddl-auto=validate`, Open Session in View tắt, Flyway clean bị khóa. `CF_FLYWAY_*` cho phép tài khoản migration riêng; local mặc định dùng `CF_DB_*`.

Xem DB trong IntelliJ: Database → PostgreSQL, host localhost, port 15432, database/user theo `infra/.env`, chọn schema `contextflow`.

Xác minh migration từ root, với database/user mặc định:

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml exec -T postgres psql -U contextflow -d contextflow -c "SELECT version, success FROM contextflow.flyway_schema_history ORDER BY installed_rank;"
```

Mong đợi version 001–005 success=true. Không xóa volume hay chạy SQL seed nghiệp vụ để sửa lỗi startup.

## 8. Kiểm tra trước PR

Core (Docker phải chạy cho Testcontainers):

```powershell
Set-Location core-backend
.\mvnw.cmd spotless:apply
.\mvnw.cmd -B verify
```

Spring test dùng CF_LOCAL_ENV_IMPORT tới resource rỗng để không nạp .env cá nhân; schema test dùng PostgreSQL riêng.

AI:

```powershell
Set-Location ai-service
uv sync --frozen --dev
uv run ruff check .
uv run ruff format --check .
uv run mypy app tests
uv run pytest
```

Chung tại root sau `npm ci`: `npm run owners:check`, `npm run contracts:check`, `npm run format:check`.

Tên branch và commit theo bảng 6.9; không cần mã Jira. Dùng checklist 6.11 và mẫu PR trong `.github/pull_request_template.md` trước khi bàn giao.

## 9. Lỗi thường gặp

| Hiện tượng                            | Cách kiểm tra                                                    |
| ------------------------------------- | ---------------------------------------------------------------- |
| Maven không nhận JDK                  | java -version, JAVA_HOME trỏ JDK 21                              |
| Getter/Mapper báo đỏ                  | Annotation Processing, Reload Maven; compile để sinh MapperImpl  |
| Thiếu password khi Maven chạy         | Working directory core-backend, có .env; chạy lại setup          |
| Profile/port khác file                | Xóa biến nhập tay trong run config, restart Core                 |
| 18080 đang dùng                       | Stop Core cũ trước Run trong IDE                                 |
| Flyway/DB auth lỗi                    | Password và `CF_DB_*`/`CF_FLYWAY_*`; giữ nguyên migration/volume |
| Không thấy business API trong Swagger | API chưa được thành viên triển khai                              |
