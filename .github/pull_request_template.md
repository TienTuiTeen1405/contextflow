## Đầu việc và kết quả

<!-- Link GitHub Issue (#...) hoặc dòng trong bảng việc; không cần Jira. -->

- Đầu việc:
- Vấn đề/hành vi trước → sau:
- Người review:
- Phạm vi chưa làm hoặc chưa tích hợp:

## API và phạm vi code

<!-- Liệt kê method/path/status của API thay đổi hoặc ghi không có. -->

- API/DTO:
- Domain, bảng, file chính:
- Nguồn BranOS/tiêu chí nghiệm thu:

## Kiểm chứng

<!-- Ghi đúng các lệnh đã chạy và kết quả. Tài liệu thuần túy không cần test nghiệp vụ. -->

| Lệnh hoặc kịch bản         | Kết quả  | Môi trường / Mock / giới hạn |
| -------------------------- | -------- | ---------------------------- |
| Điền lệnh/kịch bản thực tế | Chưa ghi | Bổ sung                      |

Cách người khác chạy lại kịch bản nghiệm thu:

## Ảnh hưởng và phụ thuộc

- PR/helper/API của người khác cần có trước:
- Thay đổi contracts, cấu hình, dependency hoặc biến môi trường:
- Migration mới và cách áp dụng; nếu không có ghi không có:
- Người phụ trách phần dùng chung/consumer đã trao đổi:

## Checklist trước review

- [ ] Một đầu việc, branch đúng quy tắc, đích PR là main.
- [ ] Đã đọc diff và chỉ gồm file đúng phạm vi; không có secret, .env, log hoặc generated output trong target.
- [ ] Theo mục 6 của SETUP-GUIDE; API/đặc tả và kiểm chứng khớp nhau, phần chưa chạy được ghi rõ.
- [ ] Đã chạy check phù hợp; kết quả thật được ghi ở trên.
- [ ] Đã đồng bộ main khi có thay đổi ảnh hưởng, xử lý conflict và kiểm tra lại.
- [ ] Domain/bảng đúng ownership; thay đổi shared contract/config đã trao đổi với người liên quan.
- [ ] Không sửa V001–V005 đã chia sẻ; migration mới đã thống nhất version nếu có.
- [ ] Đã yêu cầu người khác review; tác giả không tự approve PR.

## Trước merge — reviewer/đầu mối xác nhận

- [ ] Tiêu chí nghiệm thu đạt, nhận xét bắt buộc đã xử lý, check bắt buộc xanh.
- [ ] Đã duyệt bản mới nhất và phối hợp với consumer nếu đổi hợp đồng.
- [ ] Squash and merge; sau merge cập nhật đầu việc và dọn remote feature branch.
