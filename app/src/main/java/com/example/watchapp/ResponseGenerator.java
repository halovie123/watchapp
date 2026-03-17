package com.example.watchapp;

import java.util.Random;

/**
 * Sinh câu trả lời tiếng Việt dựa trên AnalysisResult.
 * Hoàn toàn offline — không cần internet, không cần API key.
 */
public class ResponseGenerator {

    private static final Random rand = new Random();

    // =========================================================================
    //  ENTRY POINT — phân loại câu hỏi rồi gọi handler phù hợp
    // =========================================================================

    public static String generate(String userMessage, HealthAnalyzer.AnalysisResult r) {
        if (r.totalRecords == 0) {
            return "⏳ Chưa có dữ liệu sức khỏe nào. "
                    + "Hãy đeo đồng hồ và đợi vài giây để app thu thập dữ liệu nhé!";
        }

        String msg = userMessage.toLowerCase()
                .replace("ơ","o").replace("ư","u").replace("ă","a")
                .replace("â","a").replace("đ","d").replace("ê","e")
                .replace("ô","o").replace("ị","i").replace("ọ","o")
                .replace("ụ","u").replace("ả","a").replace("ẻ","e");

        // ── Phân loại câu hỏi ───────────────────────────────────────────────
        if (contains(msg, "nhip tim","nhip","bpm","tim","mach"))
            return answerHeartRate(r);
        if (contains(msg, "oxy","spo2","o2","duong khi","nong do"))
            return answerOxygen(r);
        if (contains(msg, "van dong","hoat dong","di bo","chay","the duc","luyen tap"))
            return answerActivity(r);
        if (contains(msg, "nguy co","canh bao","co van de","binh thuong","suc khoe tong"))
            return answerOverall(r);
        if (contains(msg, "xu huong","tang","giam","thay doi","dien bien"))
            return answerTrend(r);
        if (contains(msg, "loi khuyen","nen lam gi","can lam gi","tu van","goi y"))
            return answerAdvice(r);
        if (contains(msg, "khoe","on khong","the nao","hom nay","ket qua"))
            return answerOverall(r);

        // Câu hỏi không nhận ra → tổng quan
        return answerOverall(r);
    }

    // =========================================================================
    //  HANDLERS
    // =========================================================================

    private static String answerHeartRate(HealthAnalyzer.AnalysisResult r) {
        StringBuilder sb = new StringBuilder();

        // Giá trị
        sb.append("❤️ Nhịp tim của bạn:\n");
        sb.append("  • Mới nhất: ").append(r.latestBpm).append(" BPM\n");
        sb.append("  • Trung bình: ").append(r.avgBpm).append(" BPM\n");
        sb.append("  • Dao động: ").append(r.minBpm).append("–").append(r.maxBpm).append(" BPM\n\n");

        // Đánh giá
        switch (r.heartStatus) {
            case "NORMAL":
                sb.append("Nhịp tim bình thường (60–100 BPM). ");
                sb.append(pick("Tim bạn đang hoạt động rất tốt!",
                        "Sức khỏe tim mạch ổn định, tiếp tục duy trì nhé!",
                        "Nhịp tim lý tưởng cho sức khỏe tim mạch."));
                break;
            case "LOW":
                sb.append("Nhịp tim hơi thấp. ");
                sb.append("Có thể do bạn đang nghỉ ngơi hoặc tập thể dục thường xuyên. ");
                sb.append("Nếu cảm thấy chóng mặt hoặc mệt mỏi, nên gặp bác sĩ.");
                break;
            case "CRITICAL_LOW":
                sb.append("Nhịp tim thấp bất thường! ");
                sb.append("Dưới 50 BPM có thể là dấu hiệu cần chú ý. ");
                sb.append("Khuyến nghị gặp bác sĩ để kiểm tra.");
                break;
            case "HIGH":
                sb.append("Nhịp tim hơi cao. ");
                sb.append("Có thể do vận động, căng thẳng hoặc uống cà phê. ");
                sb.append("Nghỉ ngơi và theo dõi thêm.");
                break;
            case "CRITICAL_HIGH":
                sb.append("Nhịp tim cao bất thường! ");
                sb.append("Trên 110 BPM khi nghỉ ngơi cần được kiểm tra. ");
                sb.append("Hãy ngồi nghỉ và liên hệ bác sĩ nếu kéo dài.");
                break;
        }

        // Xu hướng
        if (r.heartTrend.equals("RISING"))
            sb.append("\n📈 Xu hướng: đang tăng dần.");
        else if (r.heartTrend.equals("FALLING"))
            sb.append("\n📉 Xu hướng: đang giảm dần.");

        return sb.toString();
    }

    private static String answerOxygen(HealthAnalyzer.AnalysisResult r) {
        StringBuilder sb = new StringBuilder();

        sb.append("🫁 Nồng độ oxy (SpO2):\n");
        sb.append("  • Mới nhất: ").append(r.latestSpo2).append("%\n");
        sb.append("  • Trung bình: ").append(r.avgSpo2).append("%\n\n");

        switch (r.oxygenStatus) {
            case "NORMAL":
                sb.append("SpO2 bình thường (≥97%). ");
                sb.append(pick("Phổi và hệ hô hấp hoạt động rất tốt!",
                        "Nồng độ oxy trong máu đạt mức lý tưởng.",
                        "Hệ hô hấp của bạn đang hoạt động ổn định."));
                break;
            case "BORDERLINE":
                sb.append("SpO2 ở mức hơi thấp (95–96%). ");
                sb.append("Thở sâu và đều, tránh môi trường thiếu oxy. ");
                sb.append("Theo dõi thêm trong vài giờ tới.");
                break;
            case "LOW":
                sb.append("SpO2 thấp (90–94%). ");
                sb.append("Cần chú ý! Thở sâu, ngồi ở nơi thoáng khí. ");
                sb.append("Nếu không cải thiện sau 10 phút, nên gặp bác sĩ.");
                break;
            case "CRITICAL":
                sb.append("SpO2 rất thấp (<90%)! ");
                sb.append("Đây là dấu hiệu nghiêm trọng. ");
                sb.append("Hãy tìm nơi thoáng khí ngay và liên hệ y tế khẩn cấp!");
                break;
        }

        return sb.toString();
    }

    private static String answerActivity(HealthAnalyzer.AnalysisResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mức độ vận động của bạn: ");

        switch (r.activityLevel) {
            case "REST":
                sb.append("Nghỉ ngơi / ít vận động.\n\n");
                sb.append("Ngồi lâu không tốt cho sức khỏe. ");
                sb.append("Hãy đứng dậy đi lại 5 phút mỗi giờ nhé!");
                break;
            case "LIGHT":
                sb.append("Hoạt động nhẹ.\n\n");
                sb.append("👍 Duy trì tốt! Vận động nhẹ nhàng rất có lợi cho tim mạch.");
                break;
            case "MODERATE":
            case "ACTIVE":
                sb.append("Hoạt động vừa phải.\n\n");
                sb.append("Tuyệt vời! Đây là mức vận động lý tưởng. ");
                sb.append("Nhịp tim ").append(r.avgBpm).append(" BPM phù hợp với cường độ này.");
                break;
            case "INTENSE":
                sb.append("Hoạt động cường độ cao.\n\n");
                if (r.heartStatus.equals("HIGH") || r.heartStatus.equals("CRITICAL_HIGH")) {
                    sb.append("Nhịp tim đang khá cao (").append(r.avgBpm).append(" BPM). ");
                    sb.append("Nghỉ ngơi và uống nước, tránh cố gắng quá sức.");
                } else {
                    sb.append("Đang luyện tập chăm chỉ! ");
                    sb.append("Nhớ bổ sung nước và nghỉ ngơi đầy đủ sau khi tập.");
                }
                break;
            default:
                sb.append("Chưa đủ dữ liệu để phân tích.");
                break;
        }

        return sb.toString();
    }

    private static String answerTrend(HealthAnalyzer.AnalysisResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Xu hướng sức khỏe gần đây (").append(r.totalRecords).append(" lần đo):\n\n");

        // Nhịp tim
        sb.append("❤️ Nhịp tim: ");
        switch (r.heartTrend) {
            case "RISING":  sb.append("📈 Đang tăng (").append(r.avgBpm).append(" BPM avg)\n"); break;
            case "FALLING": sb.append("📉 Đang giảm (").append(r.avgBpm).append(" BPM avg)\n"); break;
            default:        sb.append("Ổn định (").append(r.avgBpm).append(" BPM avg)\n"); break;
        }

        // SpO2
        sb.append("🫁 SpO2: ");
        switch (r.oxygenTrend) {
            case "RISING":  sb.append("📈 Đang tăng (").append(r.avgSpo2).append("% avg)\n"); break;
            case "FALLING": sb.append("📉 Đang giảm (").append(r.avgSpo2).append("% avg)\n"); break;
            default:        sb.append("Ổn định (").append(r.avgSpo2).append("% avg)\n"); break;
        }

        // Nhận xét
        sb.append("\n");
        if (r.heartTrend.equals("STABLE") && r.oxygenTrend.equals("STABLE")) {
            sb.append("Các chỉ số đều ổn định. Sức khỏe đang trong trạng thái tốt!");
        } else if (r.oxygenTrend.equals("FALLING") && r.avgSpo2 < 97) {
            sb.append("SpO2 đang có xu hướng giảm. Cần theo dõi thêm và thở sâu.");
        } else if (r.heartTrend.equals("RISING") && r.avgBpm > 90) {
            sb.append("Nhịp tim đang tăng. Nghỉ ngơi nếu không đang vận động.");
        } else {
            sb.append("Sức khỏe đang thay đổi nhẹ, tiếp tục theo dõi nhé.");
        }

        return sb.toString();
    }

    private static String answerAdvice(HealthAnalyzer.AnalysisResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lời khuyên cho bạn hôm nay:\n\n");

        // Dựa trên điểm rủi ro
        if (r.riskScore >= 50) {
            sb.append("Mức rủi ro: Cần chú ý (").append(r.riskScore).append("/100)\n");
            sb.append("Khuyến nghị gặp bác sĩ để được kiểm tra.\n\n");
        } else if (r.riskScore >= 25) {
            sb.append("Mức rủi ro: Trung bình (").append(r.riskScore).append("/100)\n\n");
        } else {
            sb.append("Mức rủi ro: Thấp (").append(r.riskScore).append("/100)\n\n");
        }

        // Lời khuyên cụ thể theo tình trạng
        if (r.activityLevel.equals("REST") || r.activityLevel.equals("LIGHT")) {
            sb.append("Vận động: Hãy đi bộ 30 phút/ngày để cải thiện sức khỏe tim mạch.\n");
        }
        if (r.heartStatus.equals("HIGH") || r.heartStatus.equals("CRITICAL_HIGH")) {
            sb.append("Thư giãn: Hít thở sâu, giảm căng thẳng, tránh caffeine.\n");
        }
        if (r.oxygenStatus.equals("BORDERLINE") || r.oxygenStatus.equals("LOW")) {
            sb.append("Hô hấp: Tập thở sâu 5 phút, ở nơi thoáng khí.\n");
        }
        if (r.heartStatus.equals("NORMAL") && r.oxygenStatus.equals("NORMAL")) {
            sb.append(pick(
                    "Sức khỏe tốt! Duy trì lối sống lành mạnh, ngủ đủ giấc và uống đủ nước.",
                    "Chỉ số tốt! Tiếp tục ăn uống cân bằng và vận động đều đặn.",
                    "Tuyệt vời! Đừng quên uống đủ 2 lít nước mỗi ngày."
            ));
        }

        return sb.toString();
    }

    private static String answerOverall(HealthAnalyzer.AnalysisResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tổng quan sức khỏe (").append(r.totalRecords).append(" lần đo):\n\n");

        // Nhịp tim
        sb.append("Nhịp tim: ").append(r.avgBpm).append(" BPM ");
        sb.append(heartEmoji(r.heartStatus)).append("\n");

        // SpO2
        sb.append("SpO2: ").append(r.avgSpo2).append("% ");
        sb.append(oxygenEmoji(r.oxygenStatus)).append("\n");

        // Vận động
        sb.append("Vận động: ").append(activityLabel(r.activityLevel)).append("\n");

        // Điểm rủi ro
        sb.append("Rủi ro: ").append(riskLabel(r.riskScore)).append("\n\n");

        // Nhận xét tổng hợp
        if (r.riskScore < 10) {
            sb.append(pick(
                    "Sức khỏe của bạn đang rất tốt! Tiếp tục duy trì nhé!",
                    "Tất cả chỉ số bình thường. Bạn đang trong trạng thái tốt!",
                    "Tuyệt vời! Hãy tiếp tục lối sống lành mạnh này."
            ));
        } else if (r.riskScore < 30) {
            sb.append("Sức khỏe ổn, có một vài chỉ số cần theo dõi thêm.");
        } else if (r.riskScore < 60) {
            sb.append("Có một số chỉ số cần chú ý. Xem chi tiết từng mục nhé.");
        } else {
            sb.append("Một số chỉ số bất thường. Nên tham khảo ý kiến bác sĩ.");
        }

        return sb.toString();
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private static boolean contains(String msg, String... keywords) {
        for (String kw : keywords)
            if (msg.contains(kw)) return true;
        return false;
    }

    private static String pick(String... options) {
        return options[rand.nextInt(options.length)];
    }

    private static String heartEmoji(String status) {
        switch (status) {
            case "NORMAL":        return "bình thường";
            case "LOW":           return "hơi thấp";
            case "CRITICAL_LOW":  return "thấp bất thường";
            case "HIGH":          return "hơi cao";
            case "CRITICAL_HIGH": return "cao bất thường";
            default:              return "❓";
        }
    }

    private static String oxygenEmoji(String status) {
        switch (status) {
            case "NORMAL":     return "bình thường";
            case "BORDERLINE": return "hơi thấp";
            case "LOW":        return "thấp";
            case "CRITICAL":   return "nguy hiểm";
            default:           return "❓";
        }
    }

    private static String activityLabel(String level) {
        switch (level) {
            case "REST":     return "nghỉ ngơi";
            case "LIGHT":    return "nhẹ nhàng";
            case "MODERATE": return "vừa phải";
            case "ACTIVE":   return "tích cực";
            case "INTENSE":  return "cường độ cao";
            default:         return "chưa xác định";
        }
    }

    private static String riskLabel(int score) {
        if (score < 10)  return "Thấp (" + score + "/100) ";
        if (score < 30)  return "Trung bình (" + score + "/100) ";
        if (score < 60)  return "Cần chú ý (" + score + "/100) ";
        return               "Cao (" + score + "/100) ";
    }
}
