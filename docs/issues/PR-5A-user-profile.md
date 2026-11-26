# [PR-5A] Hồ sơ người dùng & Theme (`feature/user-profile`)

## Mục tiêu và phạm vi

Triển khai 2 API đọc/cập nhật hồ sơ cá nhân phi Auth (ưu tiên **P1**).

- **Trong phạm vi:** `GET /api/v1/me`, `PATCH /api/v1/me`.
- **Ngoài phạm vi PR này:** Đăng nhập/đăng ký/JWT/OAuth, đổi mật khẩu, quản trị user.

## Người thực hiện và reviewer

- **Người thực hiện:** Thành viên 5
- **Reviewer:** Chủ chốt 2

## Branch & Package cần tạo

- **Branch:** `feature/user-profile` (tách từ `main` sau khi `feature/local-demo-foundation` được merge)
- **Package:** `com.contextflow.identity.*`
  - `entity/User.java` (mapping bảng `contextflow.users`)
  - `repository/UserRepository.java` (`JpaRepository`)
  - `dto/` (`UserProfileResponse.java`, `ProfilePatchRequest.java`, `UserRole.java`, `UserStatus.java`, `ThemePreference.java`)
  - `mapper/UserProfileMapper.java` (`@Mapper(componentModel = "spring")`)
  - `service/ProfileService.java`, `service/impl/ProfileServiceImpl.java`
  - `controller/ProfileController.java`

## API, Request/Response mẫu & Mã lỗi

| Method  | Path         | Yêu cầu cốt lõi                                                                                                                             |
| ------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`   | `/api/v1/me` | Trả hồ sơ user hiện tại từ `CurrentUserProvider`. **Không bao giờ trả `password_hash`** (chỉ trả boolean `hasLocalPassword`).               |
| `PATCH` | `/api/v1/me` | Cập nhật `displayName` (không trắng) và/hoặc `themePreference` (`LIGHT` / `DARK` / `SYSTEM`). Không cho phép sửa `role`, `status`, `email`. |

**Response mẫu `GET /api/v1/me`** (`contracts/fixtures/public/workspace/profile.json`):

```json
{
  "id": "d0000000-0000-4000-8000-000000000001",
  "email": "demo@contextflow.local",
  "displayName": "Demo User",
  "role": "USER",
  "status": "ACTIVE",
  "themePreference": "SYSTEM",
  "hasLocalPassword": false,
  "personalIntelligenceEnabled": true
}
```

**Request mẫu `PATCH /api/v1/me`** (`contracts/fixtures/public/workspace/profile-patch.json`):

```json
{
  "displayName": "Người dùng demo",
  "themePreference": "DARK"
}
```

**Mã lỗi bắt buộc:** `400 VALIDATION_ERROR` (body rỗng, `displayName` trắng, field lạ như `role`), `401 UNAUTHENTICATED`, `403 ACCOUNT_DISABLED`.

## Phụ thuộc & Code nền bắt buộc gọi

- Lấy user hiện tại: `UUID userId = currentUserProvider.getCurrentUserId();` (`com.contextflow.identity.service.CurrentUserProvider`)
- Bảng & cột chuẩn xem tại `core-backend/src/main/resources/db/migration/V001__identity_credentials_admin.sql`:
  - `contextflow.users`: `id`, `email`, `password_hash`, `display_name`, `role`, `status`, `theme_preference`, `personal_intelligence_enabled`, `personal_profile_text`, `personal_instructions`, `created_at`, `updated_at`
- Tuân thủ 5 luật cứng trong `.github/copilot-instructions.md`.

## Tiêu chí nghiệm thu (DoD)

- [ ] `GET /api/v1/me` trả đúng thông tin user demo, không bao giờ lộ `password_hash`.
- [ ] `PATCH /api/v1/me` chỉ sửa `displayName`/`themePreference`, gửi `role` hoặc body rỗng trả `400 VALIDATION_ERROR`.
- [ ] Chạy xanh `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify`.
- [ ] Chụp **2 ảnh test trên Swagger UI** (`http://localhost:18080/swagger-ui.html` — 1 case thành công, 1 case lỗi) dán vào PR.
