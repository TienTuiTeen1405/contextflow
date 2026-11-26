# ContextFlow

Monorepo ContextFlow V6 theo tài liệu **BranOS**. Đã có Web scaffold, Core Spring Boot/43 bảng Flyway và AI Service với bốn endpoint Mock. Các luồng đăng nhập, Project/Chat nghiệp vụ và durable SSE thuộc gói M1 tiếp theo; Gemini thật chưa bật.

## Cấu trúc và nơi bắt đầu

| Thư mục         | Trách nhiệm                                                     |
| --------------- | --------------------------------------------------------------- |
| `web/`          | Next.js 15, React 19, TypeScript; gọi Core                      |
| `core-backend/` | Java 21, Spring Boot, quyền/nghiệp vụ/persistence, Flyway       |
| `ai-service/`   | Python/FastAPI stateless, DTO và 4 task endpoint Mock; không DB |
| `contracts/`    | OpenAPI 3.1, 25 mã lỗi, mẫu JSON và SSE dùng chung              |
| `infra/`        | PostgreSQL 16 + pgvector 0.8.1, Compose và preset IDE           |
| `scripts/`      | Setup local, kiểm tra kết nối, sinh DTO và kiểm tra ownership   |

Đọc [nguồn chuẩn](docs/baseline.md), [hợp đồng API](contracts/README.md), [bản đồ nhóm](docs/team-start.md), [sở hữu code/review](docs/ownership.md) và [CONTRIBUTING.md](CONTRIBUTING.md). `node_modules/` là thư viện do npm quản lý.

## Chuẩn bị máy mới

- Node **24** / npm **11**.
- Docker Desktop chạy **Linux containers**.
- Khi chạy Core ngoài Docker: **JDK 21**, `JAVA_HOME` trỏ đúng JDK; Maven Wrapper có sẵn.
- Khi phát triển AI hoặc sinh toàn bộ hợp đồng: Python **3.13**, uv **0.13.1**.

Tại thư mục gốc:

```powershell
npm ci
npm run setup:local
```

Setup mặc định chọn **docker**. Lần đầu, script tạo mật khẩu DB, service token và KEK development ngẫu nhiên trong file local được Git bỏ qua. Core/AI dùng cùng service token; Web chỉ có URL công khai. Khi chạy lại, script giữ nguyên mật khẩu DB/KEK, đồng bộ cấu hình từ `infra/.env` và giữ các khóa tùy chỉnh. Nếu `infra/.env` đã tồn tại nhưng còn placeholder, sửa cho khớp DB hiện có rồi chạy lại; script không tự đổi mật khẩu một volume DB đã tồn tại.

Các file được tạo: `infra/.env`, `core-backend/.env`, `ai-service/.env`, `web/.env.local`. Không commit chúng. Không đặt API Key/token/KEK trong `NEXT_PUBLIC_*`.

## Chế độ mặc định: Docker Core 8080

```powershell
npm run setup:local -- --mode docker
npm run stack:up
npm run local:check
```

| Dịch vụ                | Địa chỉ                                                     |
| ---------------------- | ----------------------------------------------------------- |
| Web                    | http://localhost:3000                                       |
| Web health             | http://localhost:3000/api/health                            |
| Core readiness         | http://localhost:8080/actuator/health/readiness             |
| PostgreSQL development | localhost:15432, database/user mặc định `contextflow`       |
| AI                     | Nội bộ Compose tại `ai-service:8000`, không publish ra host |

Web trong Docker được build để gọi `http://localhost:8080`. Có thể chạy Web dev riêng bằng `npm run dev:web` khi cổng 3000 trống; preset docker cũng trỏ Web dev tới Core 8080.

Máy ít RAM có thể build từng dịch vụ rồi khởi động:

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml build ai-service
docker compose --env-file infra/.env -f infra/compose.yaml build core-backend
docker compose --env-file infra/.env -f infra/compose.yaml build web
docker compose --env-file infra/.env -f infra/compose.yaml up -d --no-build --wait
```

`npm run stack:down` dừng stack và giữ volume dữ liệu. Redis chỉ bật với `--profile cache`.

## Chế độ phát triển: IntelliJ Core 18080

Preset này dùng PostgreSQL/AI trong Docker, Core trong IntelliJ hoặc Maven Wrapper và Web chạy dev. AI chỉ được publish trên loopback `127.0.0.1:18000` bằng file override riêng.

```powershell
npm run setup:local -- --mode ide
npm run infra:up:ide
```

1. Mở monorepo trong IntelliJ; mở `core-backend/pom.xml` và chọn **Add as Maven Project**. Maven phải nhận **ContextFlow Core Backend**.
2. Chọn Project/module SDK **JDK 21**, Maven home **Use Maven wrapper**.
3. Chọn cấu hình chia sẻ **ContextFlow Core (IDE)** rồi Run/Debug. Cấu hình trong `.run/` đã trỏ module, main class, working directory và file `core-backend/.env`; mật khẩu không nằm trong run config.
4. Nếu IDE không hiển thị run config, tạo Spring Boot config với main class `com.contextflow.ContextFlowApplication`, module `core-backend`; bật **Modify options → Environment variables** và chọn file `core-backend/.env`.
5. Mở terminal khác ở thư mục gốc:

```powershell
npm run dev:web
npm run local:check
```

Chạy `local:check` trong terminal riêng khi Core/Web đang chạy. Script kiểm tra URL Web, readiness Core, error envelope/Auth/CORS và service identity/Mock AI. Nó chưa nghiệm thu API nghiệp vụ.

Không dùng IDE cũng có thể chạy Core bằng lệnh dưới đây; script nạp file local vào process, không in secret:

```powershell
# Đặt JAVA_HOME tới JDK 21 của máy nếu chưa cấu hình.
npm run dev:core
```

| Thành phần   | URL ở preset IDE       |
| ------------ | ---------------------- |
| Web dev      | http://localhost:3000  |
| Web gọi Core | http://localhost:18080 |
| Core gọi AI  | http://127.0.0.1:18000 |
| PostgreSQL   | localhost:15432        |

Nếu Docker Web đang chiếm 3000, dừng riêng service Web trước khi chạy Web dev: `docker compose --env-file infra/.env -f infra/compose.yaml stop web`. Core Docker 8080 có thể chạy đồng thời; Web preset IDE vẫn gọi đúng 18080.

**Khi đổi preset**, chạy lại `setup:local -- --mode docker` hoặc `--mode ide` và restart Web dev. Biến `NEXT_PUBLIC_CORE_API_URL` được Next.js lấy lúc build/dev startup; đổi env không tự đổi một image Web đã build.

Spring Boot không tự đọc `.env`; chỉ run config IntelliJ và `dev:core` nạp file này. Nếu dùng `./mvnw spring-boot:run` trực tiếp, tự export biến trước. Không log hoặc dán secret vào lệnh được chia sẻ.

## Phát triển AI ngoài Docker

```powershell
# Ở thư mục gốc, sau setup:local
Set-Location ai-service
uv sync --frozen --dev
uv run uvicorn app.main:app --host 127.0.0.1 --port 18000 --reload --no-access-log
```

Chỉ chạy một AI local trên cổng 18000: dùng container preset IDE hoặc uvicorn ở trên. `GET /health` là liveness; `GET /internal/v1/ai/status` cần service token và trả `MOCK / external_calls_enabled=false`. Bốn task endpoint và credential giả dùng ghép nhóm được mô tả trong [hợp đồng API](contracts/README.md). Không gửi API Key thật để thử Mock Verify.

## Hợp đồng, database và review

```powershell
npm run contracts:generate
npm run contracts:check
npm run owners:check
```

Public OpenAPI sinh DTO Java và type Web; Pydantic/route FastAPI sinh internal OpenAPI rồi sinh DTO Java. Dùng fixture trong `contracts/fixtures`; không sửa generated code trực tiếp.

Flyway tự chạy V001–V005, tạo **43 bảng nghiệp vụ** trong schema `contextflow` và bảng kỹ thuật history. Xem [database và cách xem bảng trong IntelliJ](docs/database.md). Thay đổi tiếp theo dùng V006+, không sửa migration đã chia sẻ. Local dùng tài khoản DB development; môi trường chung cần tách runtime/migration bằng `CF_DB_*` và `CF_FLYWAY_*`.

Vai trò sở hữu domain/Entity/Repository và bảng được khai báo trong `.github/ownership.json`. Hiện **chưa gán thành viên**. Điền GitHub identities rồi chạy `npm run owners:generate` để tạo CODEOWNERS; xem [quy trình review](docs/ownership.md). Không tạo sẵn 43 Entity rỗng hoặc cho nhiều nhóm tự tạo mapping trùng một bảng.

## Kiểm tra trước PR

Tại root:

```powershell
npm run contracts:check
npm run owners:check
npm run test:tooling
npm run check:web
npm run build:web
npm run format:check
```

Trong `core-backend`, bật Docker rồi chạy `./mvnw.cmd verify` (macOS/Linux: `./mvnw verify`); format Java bằng `./mvnw.cmd spotless:apply`. Testcontainers dùng PostgreSQL riêng, không sửa DB development.

Trong `ai-service`: `uv sync --frozen --dev`, `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy app tests`, `uv run pytest`.

Compose: `docker compose --env-file infra/.env.example -f infra/compose.yaml config --quiet`; preset IDE thêm `-f infra/compose.ide.yaml`.

Kết quả kiểm chứng: [docs/verification.md](docs/verification.md). CI được khai báo trong repo; kết quả trên remote cần được xác nhận khi có repository chung.
