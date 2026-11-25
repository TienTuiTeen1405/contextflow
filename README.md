# ContextFlow

Bộ khung monorepo cho ContextFlow V6, theo kho tài liệu **BranOS**. Mục tiêu là tạo điểm bắt đầu chung cho các nhóm Web, Core, AI và dữ liệu.

**Trạng thái:** scaffold. Web có trang khởi đầu và trang Projects placeholder; Core có bảo mật đóng mặc định, Actuator, JPA/Flyway và correlation ID; AI Service có service authentication và adapter MOCK kiểm tra trạng thái. Chưa có đăng nhập, Chat/SSE nghiệp vụ, lưu hội thoại, Retrieval hay gọi Gemini thật.

## Cấu trúc

```text
web/           Next.js 15 / React 19 / TypeScript / Tailwind / shadcn conventions
core-backend/  Java 21 / Spring Boot 3 / Spring Security / JPA / Flyway
ai-service/    Python 3.13 / FastAPI / Pydantic / MockProviderAdapter
infra/         PostgreSQL 16 + pgvector 0.8.1, Docker Compose; Redis tùy chọn
docs/          Nguồn chuẩn, ranh giới kiến trúc, cách chia việc và các điểm OPEN
poc/           Điểm nhận bằng chứng PoC CF-DEC-038
```

## Khởi chạy bằng Docker

Cần Docker Desktop chạy Linux containers. Từ thư mục gốc:

```powershell
Copy-Item infra/.env.example infra/.env
# Sửa POSTGRES_PASSWORD và CF_INTERNAL_SERVICE_TOKEN trong infra/.env.
# Token nội bộ cần tối thiểu 24 ký tự; không dùng giá trị mẫu khi chia sẻ môi trường.
docker compose --env-file infra/.env -f infra/compose.yaml up --build -d
```

Trên macOS/Linux dùng `cp infra/.env.example infra/.env` rồi chạy cùng lệnh Docker.

Máy ít RAM có thể build lần lượt bằng các lệnh dưới đây, sau đó khởi chạy stack không build lại:

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml build ai-service
docker compose --env-file infra/.env -f infra/compose.yaml build core-backend
docker compose --env-file infra/.env -f infra/compose.yaml build web
docker compose --env-file infra/.env -f infra/compose.yaml up -d --no-build --wait
```

| Dịch vụ                | Địa chỉ                                          |
| ---------------------- | ------------------------------------------------ |
| Web                    | http://localhost:3000                            |
| Web liveness           | http://localhost:3000/api/health                 |
| Core readiness (có DB) | http://localhost:8080/actuator/health/readiness  |
| AI Service             | Nội bộ Compose, cổng 8000; không publish ra host |
| PostgreSQL development | localhost:15432 (trong Compose: postgres:5432)   |

`docker compose --env-file infra/.env -f infra/compose.yaml down` dừng stack và giữ volume dữ liệu. Script `stack:down` cũng giữ dữ liệu. Redis chỉ bật khi thêm `--profile cache`; ứng dụng hiện chưa phụ thuộc Redis.

## Phát triển từng dịch vụ

Yêu cầu Node 24 / npm 11, JDK 21 (JDK 25 cũng build được target 21), Python 3.13 và uv 0.13.1. Maven Wrapper đi kèm; không cần Maven cài toàn máy. Không đặt API Key Gemini trong Web hoặc biến `NEXT_PUBLIC_*`.

### Web

```powershell
npm ci
Copy-Item web/.env.example web/.env.local
npm run dev:web
```

### Core

Khởi động DB bằng `npm run infra:up` sau khi tạo `infra/.env`. Mở terminal riêng:

```powershell
$env:JAVA_HOME = 'C:/path/to/jdk-21'
$env:CF_DB_PASSWORD = 'cùng-mật-khẩu-trong-infra/.env'
# Nếu đổi POSTGRES_PORT, đặt cả CF_DB_URL tương ứng.
Set-Location core-backend
./mvnw.cmd spring-boot:run
```

Trên macOS/Linux dùng `export CF_DB_PASSWORD=...` và `./mvnw spring-boot:run`. Spring Boot không tự đọc `.env`; `core-backend/.env.example` là danh mục biến cần export. Business API đang bị chặn mặc định cho tới khi nhóm Identity triển khai JWT/ownership.

### AI Service

```powershell
Set-Location ai-service
Copy-Item .env.example .env
# Sửa token cho khớp Core; tối thiểu 24 ký tự.
uv sync --frozen --dev
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload --no-access-log
```

`GET /health` kiểm tra liveness. `GET /internal/v1/ai/status` cần `Authorization: Bearer <service-token>`, trả `mode=MOCK`, `external_calls_enabled=false`. Đây là endpoint bootstrap, không thay thế bốn hợp đồng task trong BranOS-04-6. Chế độ Gemini chưa được kích hoạt; SDK chỉ được chọn sau PoC Gate.

## Kiểm tra trước khi gửi PR

```powershell
npm run check:web
npm run build:web
npm run format:check
```

Trong `core-backend`: `./mvnw.cmd verify` (Linux/macOS: `./mvnw verify`). Format Java bằng `./mvnw.cmd spotless:apply`.

Trong `ai-service`: `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy app tests`, `uv run pytest`.

Compose: `docker compose --env-file infra/.env.example -f infra/compose.yaml config --quiet`.

## Bắt đầu công việc

Đọc [CONTRIBUTING.md](CONTRIBUTING.md), [bản đồ chia việc](docs/team-start.md) và [nguồn chuẩn / điểm mở](docs/baseline.md). Migration nghiệp vụ 43 bảng chưa được copy vào scaffold: nhóm dữ liệu tích hợp từ BranOS-03-5 và kiểm chứng trên PostgreSQL 16 trước khi chia sẻ. Không tự đưa hành vi TBD thành quyết định đã chốt.

Kết quả kiểm chứng scaffold được ghi tại [docs/verification.md](docs/verification.md).
