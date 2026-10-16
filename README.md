# FixTB NTT

Giữ thông báo của app quốc tế ổn định trên HyperOS nội địa (Xiaomi China ROM, đã cài Google Play services).
Không cần Shizuku, dịch vụ trợ năng hay tùy chọn nhà phát triển, nên không làm VNeID và app ngân hàng chặn đăng nhập.

## Build bằng GitHub Actions
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên nhánh `main`.
2. Vào tab **Actions** → **Build APK** (tự chạy khi push, hoặc bấm *Run workflow*).
3. Khi xong, mở lần chạy đó → **Artifacts** → tải `FixTB-NTT-apk`, giải nén lấy `FixTB-NTT.apk`.

### Giữ chữ ký ổn định để cập nhật đè được
Nếu không đặt secrets, mỗi lần build dùng khóa ký mới nên phải gỡ app cũ trước khi cài bản mới. Để tránh:
```
keytool -genkeypair -keystore fixtb.jks -alias fixtb -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 fixtb.jks      # copy kết quả
```
Repo → Settings → Secrets and variables → Actions, thêm: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (có thể bỏ nếu trùng mật khẩu keystore).

## Dùng
1. Cài APK, bấm **Quyền ghi cài đặt** và cấp, rồi **Miễn tối ưu pin**.
2. **Chọn nhóm đề xuất** (hoặc tự chọn app) → **Lưu & áp dụng**.
3. Nhấn giữ một app để mở nhanh các trang cài đặt riêng của Xiaomi (Tự khởi động, Tiết kiệm pin...).
4. **Kiểm tra** để xem Millet / miễn tối ưu pin / trạng thái buộc dừng của từng app.
5. (Tùy chọn) **Bật/tắt đo độ trễ** để thống kê độ trễ thông báo. Tính năng này cần quyền truy cập thông báo, mặc định tắt. Nếu app ngân hàng cảnh báo, tắt lại.

## Cách hoạt động (nhẹ pin)
Không có service thường trực. Hai job của JobScheduler: một job chạy khi khóa `MILLET_NO_RESTRICT_APP` bị đổi (theo sự kiện), một job 60 phút/lần làm lưới an toàn. Mỗi lần chạy chỉ đọc một khóa cài đặt và chỉ ghi khi thiếu mục (job theo sự kiện gom 3–15 giây để không chạy dồn). App không có quyền INTERNET, không dùng thư viện ngoài.

Khi bỏ chọn app hoặc Hoàn tác, chỉ các mục do FixTB NTT tự thêm mới bị gỡ; mục có sẵn của hệ thống được giữ nguyên.
