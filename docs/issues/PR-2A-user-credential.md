# [PR-2A] User Credential, Envelope Encryption & Privacy Disclosure (`feature/user-credential`)

## Mục tiêu và phạm vi

Triển khai 5 API quản lý API Key cá nhân của người dùng (BYOK), mã hóa phong bì AES-256-GCM và xác nhận Unpaid Services — ưu tiên **P0 (Cần để Demo)**.

- **Trong phạm vi:**
  - `GET /api/v1/me/credential`
  - `POST /api/v1/me/credential/verify`
  - `PUT /api/v1/me/credential`
  - `DELETE /api/v1/me/credential`
  - `POST /api/v1/me/privacy-acknowledgements`
- **Ngoài phạm vi PR này:** Platform credential của Admin, luồng Chat Worker giải mã key chạy Gemini thật (nhưng cung cấp sẵn service/helper mã hóa–giải mã để nhóm Chat gọi lại).

## Người thực hiện và reviewer

- **Người thực hiện:** Chủ chốt 2 (hoặc thành viên phụ trách `core-identity`)
- **Reviewer:** Chủ chốt 1

## Branch & Package cần tạo

- **Branch:** `feature/user-credential` (tách từ `main` sau khi `feature/local-demo-foundation` được merge)
- **Package:** `com.contextflow.credential.*`
  - `entity/AiCredential.java`, `entity/PrivacyDisclosureAcknowledgement.java`
  - `repository/AiCredentialRepository.java`, `repository/PrivacyDisclosureAcknowledgementRepository.java`
  - `dto/` (`UserCredentialStatusResponse.java`, `CredentialVerifyRequest.java`, `CredentialVerifyResponse.java`, `CredentialUpsertRequest.java`, `PrivacyAcknowledgementRequest.java`, `PrivacyAcknowledgementResponse.java`, `CredentialStatus.java`, `DeclaredTier.java`, `DisclosureScope.java`)
  - `mapper/CredentialMapper.java` (`@Mapper(componentModel = "spring")`)
  - `crypto/EnvelopeCryptoService.java` (AES-256-GCM với DEK ngẫu nhiên + KEK từ `CF_CREDENTIAL_KEK_V1`)
  - `client/AiCredentialVerifyClient.java` (gọi FastAPI `POST /internal/v1/ai/credentials/verify`)
  - `service/UserCredentialService.java`, `service/impl/UserCredentialServiceImpl.java`
  - `controller/UserCredentialController.java`

## API, Request/Response mẫu & Mã lỗi

| Method   | Path                                  | Yêu cầu cốt lõi                                                                                                                                                                                                         |
| -------- | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`    | `/api/v1/me/credential`               | Chỉ trả `configured`, `status`, `maskedLabel`, `declaredTier`, `disclosureRequired` (cấm trả raw key, `encrypted_secret`, `wrapped_dek`). Khi chưa cấu hình trả `configured: false`.                                    |
| `POST`   | `/api/v1/me/credential/verify`        | Gọi FastAPI `POST /internal/v1/ai/credentials/verify` (ngoài DB transaction), trả `valid`, `checkedAt`, `errorCode`, `capabilityStatus: "NOT_TESTED"`.                                                                  |
| `PUT`    | `/api/v1/me/credential`               | Verify qua FastAPI (ngoài transaction) → vào `@Transactional`: mã hóa AES-256-GCM (DEK/KEK từ `CF_CREDENTIAL_KEK_V1`), chuyển khóa `ACTIVE` cũ sang `ROTATED`, lưu khóa mới `ACTIVE` với `credential_version` tăng dần. |
| `DELETE` | `/api/v1/me/credential`               | Chuyển khóa `ACTIVE` sang `REMOVED`, xóa sạch `encrypted_secret`/`wrapped_dek` (ghi `new byte[0]` vì cột `NOT NULL`), và hủy các `ai_requests` đang mở (`ACCEPTED`/`IN_PROGRESS`) của user.                             |
| `POST`   | `/api/v1/me/privacy-acknowledgements` | Lưu đồng ý Unpaid Services cho khóa `ACTIVE` có `declaredTier` là `FREE` hoặc `UNKNOWN` (**idempotent** theo unique key). Nếu khóa là `PAID` hoặc chưa có khóa `ACTIVE` → trả lỗi chuẩn.                                |

**Payload gọi nội bộ FastAPI `POST /internal/v1/ai/credentials/verify`** (`contracts/fixtures/internal/verify-input.json`):

- Headers bắt buộc:
  - `Authorization: Bearer <CF_INTERNAL_SERVICE_TOKEN>`
  - `X-Provider-Credential: <rawApiKey>` (ở chế độ MOCK dùng giá trị `"mock-provider-credential"`)
- Body (không gửi `userId` hay business UUID sang FastAPI):

```json
{
  "correlation_id": "64254ba2-ba13-4a15-af4b-8e262140d080",
  "provider_call_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
}
```

**Response từ FastAPI** (`contracts/fixtures/internal/verify-result.json`):

```json
{
  "correlation_id": "64254ba2-ba13-4a15-af4b-8e262140d080",
  "provider_call_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "valid": true,
  "checked_at": "2026-11-26T00:00:00Z",
  "error_code": null,
  "capability_status": "NOT_TESTED"
}
```

**Mã lỗi bắt buộc:** `400 VALIDATION_ERROR`, `401 UNAUTHENTICATED`, `403 ACCOUNT_DISABLED`, `404 RESOURCE_NOT_FOUND`, `422 USER_CREDENTIAL_UNAVAILABLE`, `422 DISCLOSURE_REQUIRED`, `503 PROVIDER_UNAVAILABLE`.

## Phụ thuộc & Code nền bắt buộc gọi

- Lấy user hiện tại: `UUID userId = currentUserProvider.getCurrentUserId();` (`com.contextflow.identity.service.CurrentUserProvider`)
- Cấu hình đã có sẵn trong `core-backend/.env`:
  - `CF_AI_BASE_URL` (trỏ tới FastAPI `http://127.0.0.1:18000` ở chế độ IDE)
  - `CF_INTERNAL_SERVICE_TOKEN` (service token dùng chung với `ai-service`)
  - `CF_CREDENTIAL_KEK_V1` (Base64 32-byte key cho AES-256-GCM envelope encryption)
- Bảng & cột chuẩn xem tại `core-backend/src/main/resources/db/migration/V001__identity_credentials_admin.sql`:
  - `contextflow.ai_credentials`: `id`, `owner_scope` (`'USER'`), `owner_user_id`, `credential_version`, `status` (`ACTIVE`/`ROTATED`/`REMOVED`/`REVOKED`/`BLOCKED`), `masked_label`, `encrypted_secret` (`bytea NOT NULL`), `wrapped_dek` (`bytea NOT NULL`), `kek_version`, `declared_tier` (`FREE`/`PAID`/`UNKNOWN`), `google_cloud_project_ref`, `verified_at`, `created_at`, `updated_at`.
  - Unique partial index: `one_active_user_credential` (`owner_user_id` WHERE `owner_scope = 'USER' AND status = 'ACTIVE'`).
  - `contextflow.privacy_disclosure_acknowledgements`: `id`, `actor_user_id`, `credential_id`, `credential_version`, `disclosure_version`, `scope` (`CREDENTIAL_SETUP`/`WORKSPACE_OPERATION`), `effective_tier` (`FREE`/`UNKNOWN`), `google_cloud_project_ref`, `acknowledged_at`.
  - Unique constraint: `(actor_user_id, credential_id, credential_version, disclosure_version, scope)`.
- **Bảo mật bắt buộc:**
  - Tuyệt đối không log `X-Provider-Credential`, raw API key, DEK hoặc KEK ở bất kỳ mức log nào.
  - Mở route `/api/v1/me/credential`, `/api/v1/me/credential/verify`, `/api/v1/me/privacy-acknowledgements` trong `@Profile("local-demo")` tại `SecurityConfiguration` (giữ profile mặc định đóng 401).
- Tuân thủ 5 luật cứng trong `.github/copilot-instructions.md` (cấm `record`, cấm `@Autowired`, không sửa `V001`–`V005`).

## Tiêu chí nghiệm thu (DoD)

- [ ] `GET /api/v1/me/credential` không bao giờ trả raw key, `encrypted_secret`, hay `wrapped_dek`; trả đúng `disclosureRequired = true` khi `declaredTier` là `FREE`/`UNKNOWN` và chưa có acknowledgement.
- [ ] `POST /verify` gọi thật tới FastAPI Mock (`X-Provider-Credential: mock-provider-credential`) ngoài DB transaction và trả `capabilityStatus: "NOT_TESTED"`.
- [ ] `PUT /api/v1/me/credential` mã hóa AES-256-GCM giải mã lại đúng nguyên bản, rotate khóa `ACTIVE` cũ sang `ROTATED` không dính lỗi unique index `one_active_user_credential`.
- [ ] `DELETE /api/v1/me/credential` chuyển `REMOVED`, ghi rỗng `encrypted_secret`/`wrapped_dek` (`new byte[0]`), và hủy các Chat `ai_requests` đang mở của user.
- [ ] `POST /api/v1/me/privacy-acknowledgements` gọi lặp lại nhiều lần vẫn thành công idempotent, sau đó `GET /api/v1/me/credential` trả `disclosureRequired = false`.
- [ ] Chạy xanh `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify`.
- [ ] Chụp **2 ảnh test trên Swagger UI** (`http://localhost:18080/swagger-ui.html` — 1 case thành công, 1 case lỗi) dán vào PR.
