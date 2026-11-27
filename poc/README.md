# CF-DEC-038 PoC

Script kiểm chứng 5 tiêu chí CF-DEC-038: streaming và usage, native replay, xoay khóa T2B và embedding 1.536 chiều. Không cần chạy Core, PostgreSQL hoặc Web.

## Model và phạm vi gate

Theo lựa chọn của người dùng ngày 27/11/2026, PoC dùng **`gemini-3.5-flash-lite`** thay cho `gemini-3.8-flash` trong kế hoạch ban đầu. Embedding vẫn dùng `gemini-embedding-2`, 1.536 chiều. Kết quả chỉ có giá trị trong phạm vi model/API/SDK và cặp khóa được kiểm chứng; Core Lead cần xác nhận trước khi áp dụng chính sách continuity.

`GEMINI_TEXT_MODEL`, `GEMINI_EMBEDDING_MODEL` hoặc các cờ `--text-model`, `--embedding-model` cho phép thăm dò model khác. Kết quả trên model ngoài phạm vi đã chốt trả `BLOCKER`, kể cả khi các phép thử riêng lẻ thành công.

## Chạy kiểm chứng

Tại root repo:

```powershell
# Offline: không đọc khóa hoặc gọi Provider.
uv run --script poc/poc_cf_dec_038.py --self-test

# LIVE: sao chép poc/.env.example thành poc/.env và điền hai khóa khác nhau.
uv run --script poc/poc_cf_dec_038.py --write-report
```

Runner đọc `GEMINI_KEY_A`, `GEMINI_KEY_B` từ `.env`, `poc/.env` hoặc biến môi trường. Không in hoặc commit các giá trị này. Có thể dùng `--report-path` để ghi báo cáo vào đường dẫn khác.

Để chạy test hồi quy với venv development của AI Service đã cài dependency:

```powershell
ai-service/.venv/Scripts/python.exe -m pytest -q poc/test_poc_cf_dec_038.py
```

## Diễn giải kết quả

- `PASS` của gate yêu cầu chạy **LIVE**, đủ 5 phép thử thành công và đúng model đã chốt. Self-test offline chỉ kiểm chứng runner; không đóng gate LIVE.
- T2B chỉ chạy với hai khóa khác nhau, native steps có thought signature và phép replay bằng khóa A đã thành công.
- Nếu khóa B giữ được ngữ cảnh: T2B `ACCEPTED`.
- Nếu Provider trả lỗi input 400/422 chỉ rõ thought signature không hợp lệ hoặc không tương thích: T2B `SIGNATURE_REJECTED`. Đây là kết quả thực nghiệm hợp lệ; báo cáo yêu cầu invalidate epoch khi xoay khóa trong trường hợp đã kiểm chứng.
- Lỗi mạng, auth, quota, lỗi không rõ nguyên nhân hoặc mất ngữ cảnh: T2B `INCONCLUSIVE`, gate `BLOCKER`; không suy ra chính sách epoch. Giải quyết nguyên nhân rồi chạy lại, không tự retry hoặc đổi key/model để né quota.

Exit code: `0` khi self-test thành công hoặc gate LIVE PASS; `1` khi gate BLOCKER; `2` khi thiếu/sai cấu hình hoặc không thể khởi tạo lượt chạy. Client được đóng khi kết thúc.

Chỉ commit bằng chứng đã che bí mật. Không lưu API Key, raw prompt riêng hoặc Provider-native payload chứa dữ liệu dự án vào repository. Trạng thái kiểm chứng thực tế nằm trong [báo cáo PoC](CF-DEC-038-REPORT.md).
