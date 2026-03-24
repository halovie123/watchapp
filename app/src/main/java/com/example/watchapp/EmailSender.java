package com.example.watchapp;

import android.util.Log;
import java.util.List;
import java.util.Properties;
import java.util.Date;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * Gửi email cảnh báo té ngã qua Gmail SMTP.
 *
 * ⚠️  Đặt SENDER_EMAIL và SENDER_PASSWORD vào đây.
 *     Với Gmail: dùng "App Password" (bật 2FA rồi tạo tại
 *     https://myaccount.google.com/apppasswords).
 *     KHÔNG dùng mật khẩu Gmail thường — Google đã chặn.
 */
public class EmailSender {

    private static final String TAG = "EmailSender";

    // ── Cấu hình tài khoản gửi ──────────────────────────────────────────────
    private static final String SENDER_EMAIL    = "phamvy0104@gmail.com"; // ← đổi
    private static final String SENDER_PASSWORD = "ylgh quin krwi ackw";      // ← App Password

    /**
     * Gửi email cảnh báo té ngã — chạy trên thread riêng (không block UI).
     *
     * @param recipients danh sách email người nhận (lấy từ SharedPreferences)
     * @param watchName  tên đồng hồ (hiển thị trong nội dung email)
     * @param magnitude  cường độ va chạm (m/s²)
     * @param callback   trả về kết quả thành công/thất bại (nullable)
     */
    public static void sendFallAlert(List<String> recipients,
                                     String watchName,
                                     double magnitude,
                                     Callback callback) {

        if (recipients == null || recipients.isEmpty()) {
            Log.w(TAG, "Không có email người nhận — bỏ qua gửi email");
            if (callback != null) callback.onResult(false, "Không có email người nhận");
            return;
        }

        // Chạy trên background thread — KHÔNG dùng trên Main thread
        new Thread(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.auth",            "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host",            "smtp.gmail.com");
                props.put("mail.smtp.port",            "587");
                props.put("mail.smtp.connectiontimeout", "10000");
                props.put("mail.smtp.timeout",           "10000");

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(SENDER_EMAIL, SENDER_PASSWORD);
                    }
                });

                // Tạo message
                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(SENDER_EMAIL));

                // Thêm tất cả người nhận
                for (String email : recipients) {
                    String trimmed = email.trim();
                    if (!trimmed.isEmpty()) {
                        message.addRecipient(Message.RecipientType.TO,
                                new InternetAddress(trimmed));
                    }
                }

                message.setSubject("⚠️ Cảnh báo té ngã — " + watchName);
                message.setSentDate(new Date());

                // Nội dung email
                String body = buildEmailBody(watchName, magnitude);
                message.setText(body, "UTF-8");

                Transport.send(message);

                Log.d(TAG, "✅ Email đã gửi tới: " + recipients);
                if (callback != null) callback.onResult(true, null);

            } catch (Exception e) {
                Log.e(TAG, "❌ Lỗi gửi email: " + e.getMessage());
                if (callback != null) callback.onResult(false, e.getMessage());
            }
        }).start();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String buildEmailBody(String watchName, double magnitude) {
        String time = new java.text.SimpleDateFormat(
                "HH:mm:ss — dd/MM/yyyy",
                java.util.Locale.getDefault()).format(new Date());

        return  "🚨 CẢNH BÁO TÉ NGÃ\n"
                + "─────────────────────────────\n"
                + "Thiết bị  : " + watchName + "\n"
                + "Thời gian : " + time + "\n"
                + "Cường độ  : " + String.format("%.2f", magnitude) + " m/s²\n"
                + "─────────────────────────────\n\n"
                + "Người dùng KHÔNG phản hồi hoặc đã xác nhận bị ngã.\n"
                + "Vui lòng kiểm tra ngay!\n\n"
                + "— Ứng dụng SmartWatch";
    }

    // ── Callback interface ───────────────────────────────────────────────────

    public interface Callback {
        /** Được gọi trên background thread — dùng runOnUiThread nếu cần cập nhật UI */
        void onResult(boolean success, String errorMessage);
    }
}