# Feature boundaries

| Module             | Phần việc                                       |
| ------------------ | ----------------------------------------------- |
| `auth`             | Đăng nhập, Google OAuth, hồ sơ; USR-01          |
| `credentials`      | User Credential và disclosure; USR-15           |
| `workspace`        | Main/Sub-Branch, composer, fetch-SSE; USR-05/06 |
| `navigation`       | Outline, Chapter, Context Box, Dual-Pane, @Main |
| `knowledge-sot`    | KB, SoT, Condense, Promote; USR-07/08/12        |
| `lifecycle-export` | Tách Turn, xóa và Export; USR-02A–C/09/14       |
| `admin`            | ADM-01–07                                       |

Đặt API hooks, component và types trong feature tương ứng. Server state dùng TanStack Query; Zustand chỉ giữ UI state. Query key của dữ liệu riêng phải bao gồm User/Project. Xóa cache khi logout hoặc Project DELETING. Không gọi Gemini trực tiếp. Fetch-SSE và cơ chế refresh JWT thuộc gói M1, chưa được triển khai ở scaffold.
