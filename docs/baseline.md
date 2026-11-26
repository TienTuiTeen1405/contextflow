# Baseline BranOS V6

Ngày đối chiếu: 25/11/2026. Confluence là nguồn chuẩn; trang này là bản dẫn đường cho scaffold.

## Framework patches

Scaffold giữ Next.js 15 theo [bản vá chính thức tháng 11/2026](https://nextjs.org/blog/november-2026-security-release), Spring Boot 3 theo [system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html). Root override PostCSS 8.5.33 để loại dependency cũ của Next 15; kiểm tra lại audit và build khi nâng cấp. Google Java Format 2.0.0 dùng cho Spotless, tương thích JDK hiện hành; xem [release notes](https://github.com/google/google-java-format/releases/tag/v2.0.0).

## Thứ tự nguồn

1. [BranOS-00-0 / CF-SRS-V6 Rev 1.2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/1671254): phạm vi và nghiệp vụ.
2. [Master Decision V4.1.1](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7897114): CF-DEC-000–051 CONFIRMED, các ghi chú hiệu chỉnh và cổng còn mở.
3. [SRS 01-1](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/1703953) và nhóm 01: 121 FR, 17 UC, 11 state machines, 18 E2E.
4. Nhóm 02–04: thiết kế và hợp đồng để triển khai, không tự đóng quyết định OPEN.

## Tài liệu theo nhóm

| Nhóm                | Nguồn chính                                                                                                                                                                  |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Repository          | [00-3](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/196810)                                                                                                   |
| Core                | [02-2](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/3014658)                                                                                                  |
| Compiler / Adapter  | [02-3](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/3080193)                                                                                                  |
| SSE / Orchestration | [02-4](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/3047426)                                                                                                  |
| Web                 | [02-5](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/3047452)                                                                                                  |
| Data / SQL          | [03-5, 43 bảng, 5 migration](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536718)                                                                            |
| Public API          | [04-1, 25 mã lỗi](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/1704081), [04-2 Chat/SSE](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7635034) |
| Core ↔ AI          | [04-6](https://tientuiteen.atlassian.net/wiki/spaces/BranOS1/pages/7536753)                                                                                                  |

## Cổng cần giữ nguyên

Theo yêu cầu người dùng ngày 26/11/2026, Auth/JWT được hoãn và code CRUD/demo viết sẵn đã được gỡ để nhóm tự triển khai. Repo giữ nền chạy, database và đặc tả; không còn user demo hoặc public business controller. Baseline JWT, TBD-01 và PoC Gate không được thay đổi. Trạng thái hiện tại xem [SETUP-GUIDE.md](SETUP-GUIDE.md).

- CF-DEC-038 PoC Gate: PENDING. M1 được phát triển với Mock. Chưa pin SDK Gemini hoặc bật Provider thật.
- CF-OPEN-030A/B/D/E/F/G và 031A–E: OPEN trong Master Notes. Các con số A-11 là cấu hình khởi điểm, cần kiểm chứng.
- CF-OPEN-033: OPEN trước public release; không chặn scaffold/MVP nội bộ.
- Context Condensation giữa phiên: DEFERRED Post-V6. Không triển khai upload/Platform RAG/GitHub/IDE trong V6.

## Quyết định nghiệp vụ còn chờ

| Mã     | Nội dung                                                    |
| ------ | ----------------------------------------------------------- |
| TBD-01 | Quyền thao tác khi Project/Conversation ARCHIVED            |
| TBD-02 | Xóa Main cuối cùng hoặc Main tổ tiên còn Stage con          |
| TBD-03 | Trạng thái Main nguồn sau Rollover                          |
| TBD-04 | Thu hồi SoT độc lập bằng revoke có thuộc V6 không           |
| TBD-05 | Rule cùng nhóm/cùng priority bị xung đột                    |
| TBD-06 | Retention/purge KB xóa mềm, tombstone và vòng đời tài khoản |

DES-DIFF-01: không seed Flash Lite như mặc định mới khi chưa xác nhận cấu hình. DES-DIFF-02: dùng bộ migration tại 03-5, không trộn DDL cũ. DES-DIFF-03: scaffold phân thư mục Compiler tại Core và Builder tại AI theo đề xuất thiết kế; chưa hiện thực pipeline hoặc tự đóng điểm xác nhận.

Các trang cũ có số 33/41 bảng, 21 mã lỗi và lịch migration khác. Đối chiếu 03-5 / 04-1 trước khi code. Framework patches của scaffold lấy nhánh Next 15, React 19 và Spring Boot 3 theo phiên bản khả dụng; dependency lockfile không thay đổi baseline sản phẩm.
