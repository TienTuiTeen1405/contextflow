# ContextFlow Core Backend — Quy ước bắt buộc cho AI & Thành viên

Khi sinh hoặc sửa code trong `core-backend/`, **BẮT BUỘC tuân thủ đúng 5 luật cứng sau**:

1. **Java 21 — CẤM dùng `record`, CẤM dùng field `@Autowired`:**
   - Toàn bộ DTO, Entity, Config và Helper đều dùng `class` thường + **Lombok**.
   - Dùng `@RequiredArgsConstructor` (constructor injection với `private final`) cho Controller, Service, Repository.
   - Dùng `@Getter` / `@Setter` (và `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder` khi cần) cho JPA Entity — **không dùng `@Data` trên `@Entity`**.
   - Mỗi DTO là 1 file `.java` độc lập trong `com.contextflow.<domain>.dto`.

2. **Persistence & Mapping chuẩn:**
   - CRUD dùng **Spring Data `JpaRepository`** + **MapStruct** (`@Mapper(componentModel = "spring")`).
   - Luôn lọc truy vấn theo `ownerUserId` / `projectId` / `userId` để cách ly dữ liệu giữa các user và project.
   - Không mở DB transaction xuyên qua network I/O.

3. **Lấy danh tính user duy nhất qua `CurrentUserProvider`:**
   - Luôn inject `com.contextflow.identity.service.CurrentUserProvider` và gọi `currentUserProvider.getCurrentUserId()`.
   - **Cấm** hardcode UUID user demo, **cấm** đọc `userId` từ request body/header/query của client.
   - Khi tạo mới **Project** (trong cùng `@Transactional`): gọi `IndexGenerationBootstrap.initializeForProject(projectId)`.
   - Khi tạo mới **Conversation** + Main 1 (trong cùng `@Transactional`): gọi `ContinuityEpochBootstrap.initializeForMainStage(projectId, conversationId, mainStageId)`.

4. **Lỗi chuẩn & Bảo vệ Database Migration:**
   - Mọi lỗi nghiệp vụ ném `ApiProblem` với `ErrorCode` có sẵn trong `com.contextflow.common.api` (ví dụ `ApiProblem.invalid(...)`, `ApiProblem.missing()`, `new ApiProblem(ErrorCode.PROJECT_DELETING, ...)`).
   - **Tuyệt đối không sửa** các file Flyway `V001`–`V005`. Hibernate giữ `ddl-auto: validate`.
   - Không bao giờ trả `password_hash`, secret, token hoặc raw prompt ra API response hay log.

5. **Đúng phạm vi domain & format trước khi xong:**
   - Chỉ tạo/sửa file trong package domain và bảng được giao (xem `.github/ownership.json`). Không tạo nhánh hay sửa ngoài phạm vi PR.
   - Chạy `.\mvnw.cmd spotless:apply` và `.\mvnw.cmd -B verify` trước khi mở PR.
