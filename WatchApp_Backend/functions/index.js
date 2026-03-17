/**
 * Firebase Cloud Function — Health Chatbot (Gemini)
 *
 * Luồng:
 *  App ghi  → ai_requests/{requestId}   { message, timestamp, status:"pending" }
 *  Function → đọc health_records (10 bản ghi mới nhất)
 *           → gọi Gemini API
 *           → ghi ai_responses/{requestId} { reply, timestamp, status:"done" }
 *  App lắng nghe ai_responses/{requestId} → hiển thị kết quả
 *
 * Deploy:
 *  1. cd functions && npm install
 *  2. firebase functions:secrets:set GEMINI_API_KEY   (nhập Gemini API key)
 *  3. firebase deploy --only functions
 */

const { onValueCreated }     = require("firebase-functions/v2/database");
const { initializeApp }      = require("firebase-admin/app");
const { getDatabase }        = require("firebase-admin/database");
const { GoogleGenerativeAI } = require("@google/generative-ai");
const { defineSecret }       = require("firebase-functions/params");

initializeApp();

// Gemini API Key lưu an toàn trong Firebase Secret Manager
// Chạy lần đầu: firebase functions:secrets:set GEMINI_API_KEY


// ─────────────────────────────────────────────────────────────────────────────
//  CLOUD FUNCTION: kích hoạt khi app ghi 1 request mới vào ai_requests/
// ─────────────────────────────────────────────────────────────────────────────
exports.healthChatbot = onValueCreated(
  {
    ref:     "/ai_requests/{requestId}",
    region:  "asia-southeast1",   // region gần Việt Nam nhất
    secrets: [GEMINI_API_KEY],
  },
  async (event) => {
    const requestId   = event.params.requestId;
    const db          = getDatabase();
    const requestData = event.data.val();

    // Chỉ xử lý request có status = "pending"
    if (!requestData || requestData.status !== "pending") return null;

    // Đánh dấu đang xử lý để tránh trigger lại
    await db.ref(`ai_requests/${requestId}/status`).set("processing");

    try {
      // ── 1. Lấy 10 bản ghi sức khỏe mới nhất từ Firebase ──────────────────
      const healthSnap = await db.ref("health_records").limitToLast(10).get();

      let healthContext = "=== CHƯA CÓ DỮ LIỆU SỨC KHỎE ===";
      if (healthSnap.exists()) {
        const records = [];
        healthSnap.forEach((child) => records.push(child.val()));
        healthContext = buildHealthContext(records);
      }

      // ── 2. Xây dựng full prompt ────────────────────────────────────────────
      const fullPrompt =
        "Bạn là trợ lý sức khỏe AI thông minh cho ứng dụng đồng hồ thông minh.\n" +
        "Nhiệm vụ: tư vấn sức khỏe dựa trên dữ liệu đo thực tế của người dùng.\n" +
        "Luôn trả lời bằng tiếng Việt, ngắn gọn, thân thiện và dễ hiểu.\n" +
        "Không được chẩn đoán bệnh — chỉ tư vấn và khuyên gặp bác sĩ khi cần.\n\n" +
        healthContext +
        "\n\nCâu hỏi của người dùng: " + requestData.message;

      // ── 3. Gọi Gemini API ─────────────────────────────────────────────────
      const genAI = new GoogleGenerativeAI(GEMINI_API_KEY.value());
      const model = genAI.getGenerativeModel({ model: "gemini-1.5-flash" });

      const result = await model.generateContent({
        contents: [{ role: "user", parts: [{ text: fullPrompt }] }],
        generationConfig: { maxOutputTokens: 500, temperature: 0.7 },
      });

      const reply = result.response.text().trim();

      // ── 4. Ghi kết quả vào ai_responses/{requestId} ───────────────────────
      await db.ref(`ai_responses/${requestId}`).set({
        reply:     reply,
        timestamp: Date.now(),
        status:    "done",
      });
      await db.ref(`ai_requests/${requestId}/status`).set("done");

      console.log(`✅ [${requestId}] Gemini replied OK`);
      return null;

    } catch (error) {
      console.error(`❌ [${requestId}] Gemini error:`, error);

      await db.ref(`ai_responses/${requestId}`).set({
        reply:     "Xin lỗi, tôi đang gặp sự cố. Vui lòng thử lại sau.",
        timestamp: Date.now(),
        status:    "error",
      });
      await db.ref(`ai_requests/${requestId}/status`).set("error");
      return null;
    }
  }
);

// ─────────────────────────────────────────────────────────────────────────────
//  HELPER: xây dựng chuỗi tóm tắt dữ liệu sức khỏe từ Firebase
// ─────────────────────────────────────────────────────────────────────────────
function buildHealthContext(records) {
  const bpmList  = records.map((r) => r.heart_rate).filter(Boolean);
  const spo2List = records.map((r) => r.spo2).filter(Boolean);

  const avgBpm  = bpmList.length
    ? Math.round(bpmList.reduce((a, b) => a + b, 0) / bpmList.length) : 0;
  const avgSpo2 = spo2List.length
    ? Math.round(spo2List.reduce((a, b) => a + b, 0) / spo2List.length) : 0;

  const latest     = records[records.length - 1] || {};
  const latestBpm  = latest.heart_rate || 0;
  const latestSpo2 = latest.spo2       || 0;
  const latestMove = latest.accelerometer?.movement || "unknown";
  const latestGyro = latest.gyroscope?.rotation     || "unknown";
  const latestTime = latest.timestamp  || "N/A";

  const heartStatus = avgBpm === 0 ? "chưa có dữ liệu"
    : avgBpm < 60  ? "thấp (nhịp chậm)"
    : avgBpm > 100 ? "cao (nhịp nhanh)"
    : "bình thường";

  const oxygenStatus = avgSpo2 === 0 ? "chưa có dữ liệu"
    : avgSpo2 < 95 ? "thấp (cần chú ý)"
    : "bình thường";

  return (
    "=== DỮ LIỆU SỨC KHỎE HIỆN TẠI CỦA NGƯỜI DÙNG ===\n" +
    "Nhịp tim:\n" +
    `  - Trung bình : ${avgBpm  > 0 ? avgBpm  + " BPM" : "chưa đo"}\n` +
    `  - Mới nhất   : ${latestBpm  > 0 ? latestBpm  + " BPM" : "chưa đo"}\n` +
    `  - Trạng thái : ${heartStatus}\n` +
    `  - Số lần đo  : ${bpmList.length} lần\n\n` +
    "Nồng độ oxy (SpO2):\n" +
    `  - Trung bình : ${avgSpo2 > 0 ? avgSpo2 + "%" : "chưa đo"}\n` +
    `  - Mới nhất   : ${latestSpo2 > 0 ? latestSpo2 + "%" : "chưa đo"}\n` +
    `  - Trạng thái : ${oxygenStatus}\n` +
    `  - Số lần đo  : ${spo2List.length} lần\n\n` +
    "Hoạt động (bản ghi mới nhất):\n" +
    `  - Cường độ vận động : ${latestMove}\n` +
    `  - Cường độ xoay     : ${latestGyro}\n` +
    `  - Thời điểm đo      : ${latestTime}\n` +
    "=================================================="
  );
}
