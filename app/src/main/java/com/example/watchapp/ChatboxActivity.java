package com.example.watchapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatboxActivity extends AppCompatActivity {

    private static final String TAG       = "ChatboxActivity";
    private static final int    PERM_CODE = 200;

    // ── Gemini REST API (dùng HttpURLConnection — không cần thư viện ngoài) ───
    private static final String API_KEY  = "AIzaSyChSzWfsr6c-BOUmMh6Z1QMLpgggoeRc5U";
    private static final String MODEL    = "gemini-3-flash-preview";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent?key=" + API_KEY;

    // ── Views ─────────────────────────────────────────────────────────────────
    private LinearLayout         chatContainer;
    private ScrollView           chatScrollView;
    private EditText             edtMessage;
    private FloatingActionButton btnSend, btnVoice;
    private Button               btnBack, btnCancelRecording;
    private Button               btnQuickReply1, btnQuickReply2, btnQuickReply3, btnQuickReply4;
    private LinearLayout         voiceRecordingIndicator;
    private TextView             tvRecordingTime;

    // ── STT / TTS ─────────────────────────────────────────────────────────────
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech     tts;
    private boolean          isRecording = false;
    private Handler          recHandler;
    private Runnable         recRunnable;
    private long             recStart;

    // ── Misc ──────────────────────────────────────────────────────────────────
    private HealthDataManager dataManager;
    private ExecutorService   executor = Executors.newSingleThreadExecutor();
    private Handler           uiHandler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatbox);

        dataManager = HealthDataManager.getInstance(this);

        initViews();
        setupFABIcons();
        checkAudioPermission();
        setupSpeechRecognizer();
        setupTTS();
        setupListeners();

        addBotMessage("Xin chào! Tôi là trợ lý sức khỏe AI. Hỏi tôi bất cứ điều gì nhé! 🤖");
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    private void initViews() {
        chatContainer           = findViewById(R.id.chatContainer);
        chatScrollView          = findViewById(R.id.chatScrollView);
        edtMessage              = findViewById(R.id.edtMessage);
        btnSend                 = findViewById(R.id.btnSend);
        btnVoice                = findViewById(R.id.btnVoice);
        btnBack                 = findViewById(R.id.btnBack);
        voiceRecordingIndicator = findViewById(R.id.voiceRecordingIndicator);
        tvRecordingTime         = findViewById(R.id.tvRecordingTime);
        btnCancelRecording      = findViewById(R.id.btnCancelRecording);
        btnQuickReply1          = findViewById(R.id.btnQuickReply1);
        btnQuickReply2          = findViewById(R.id.btnQuickReply2);
        btnQuickReply3          = findViewById(R.id.btnQuickReply3);
        btnQuickReply4          = findViewById(R.id.btnQuickReply4);
        recHandler              = new Handler(Looper.getMainLooper());
    }

    private void setupFABIcons() {
        btnSend.setImageResource(android.R.drawable.ic_menu_send);
        btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
    }

    // ── Permission ────────────────────────────────────────────────────────────
    private void checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERM_CODE);
        }
    }

    // ── Speech Recognizer ─────────────────────────────────────────────────────
    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int t, Bundle p) {}
            @Override public void onEndOfSpeech() { stopRecording(); }

            @Override
            public void onError(int error) {
                stopRecording();
                Toast.makeText(ChatboxActivity.this,
                        getSpeechError(error), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                stopRecording();
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    edtMessage.setText(text);
                    sendMessage(text);
                }
            }
        });
    }

    // ── TTS ───────────────────────────────────────────────────────────────────
    private void setupTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) { tts = null; return; }
            int r = tts.setLanguage(new Locale("vi", "VN"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US);
            }
        });
    }

    private void speak(String text) {
        if (tts == null) return;
        // Bỏ ký tự đặc biệt, giới hạn 200 ký tự cho TTS
        String clean = text.replaceAll("[^\\p{L}\\p{N}\\s,.!?]", " ")
                .replaceAll("\\s+", " ").trim();
        if (clean.length() > 200) clean = clean.substring(0, 200);
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "tts");
    }

    // ── Listeners ─────────────────────────────────────────────────────────────
    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnSend.setOnClickListener(v -> {
            String msg = edtMessage.getText().toString().trim();
            if (!msg.isEmpty()) sendMessage(msg);
        });

        btnVoice.setOnClickListener(v -> {
            if (!isRecording) startRecording(); else stopRecording();
        });

        btnCancelRecording.setOnClickListener(v -> cancelRecording());

        btnQuickReply1.setOnClickListener(v -> sendMessage("Nhịp tim của tôi như thế nào?"));
        btnQuickReply2.setOnClickListener(v -> sendMessage("Nồng độ oxy SpO2 của tôi?"));
        btnQuickReply3.setOnClickListener(v -> sendMessage("Cho tôi mẹo sức khỏe hôm nay"));
        btnQuickReply4.setOnClickListener(v -> sendMessage("Giúp tôi"));

        edtMessage.setOnEditorActionListener((v, actionId, event) -> {
            String msg = edtMessage.getText().toString().trim();
            if (!msg.isEmpty()) { sendMessage(msg); return true; }
            return false;
        });
    }

    // ── Recording ─────────────────────────────────────────────────────────────
    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            checkAudioPermission();
            return;
        }
        isRecording = true;
        voiceRecordingIndicator.setVisibility(View.VISIBLE);
        recStart = System.currentTimeMillis();

        recRunnable = new Runnable() {
            @Override public void run() {
                long e = System.currentTimeMillis() - recStart;
                tvRecordingTime.setText(String.format(Locale.getDefault(),
                        "%02d:%02d", (int)(e/1000)/60, (int)(e/1000)%60));
                recHandler.postDelayed(this, 1000);
            }
        };
        recHandler.post(recRunnable);

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizer.startListening(i);
    }

    private void stopRecording() {
        isRecording = false;
        voiceRecordingIndicator.setVisibility(View.GONE);
        recHandler.removeCallbacks(recRunnable);
        if (speechRecognizer != null) speechRecognizer.stopListening();
    }

    private void cancelRecording() {
        stopRecording();
        if (speechRecognizer != null) speechRecognizer.cancel();
    }

    // ── Gửi tin nhắn → Gemini ────────────────────────────────────────────────
    private void sendMessage(String userText) {
        edtMessage.setText("");
        addUserMessage(userText);

        // Hiện loading
        addBotMessage("⏳ Đang trả lời...");
        final int loadingIdx = chatContainer.getChildCount() - 1;

        // Build prompt — đưa context sức khỏe vào để Gemini trả lời thông minh hơn
        int bpm  = dataManager.getAverageHeartRate();
        int spo2 = dataManager.getAverageOxygen();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Bạn là trợ lý sức khỏe AI trong ứng dụng đồng hồ thông minh. ");
        prompt.append("Luôn trả lời bằng tiếng Việt, thân thiện, dễ hiểu, không dùng ký hiệu markdown. ");
        if (bpm  > 0) prompt.append("Nhịp tim trung bình người dùng hiện tại: ").append(bpm).append(" BPM. ");
        if (spo2 > 0) prompt.append("SpO2 trung bình: ").append(spo2).append("%. ");
        prompt.append("\nCâu hỏi của người dùng: ").append(userText);

        final String finalPrompt = prompt.toString();

        // Gọi Gemini trên background thread (dùng HttpURLConnection, không cần lib ngoài)
        executor.execute(() -> {
            String reply = callGeminiAPI(finalPrompt);
            uiHandler.post(() -> {
                // Xóa loading → hiện câu trả lời
                if (loadingIdx >= 0 && loadingIdx < chatContainer.getChildCount()) {
                    chatContainer.removeViewAt(loadingIdx);
                }
                addBotMessage(reply);
                speak(reply);
            });
        });
    }

    // ── Gọi Gemini REST API bằng HttpURLConnection (không cần thư viện ngoài) ─
    private String callGeminiAPI(String prompt) {
        try {
            // Build JSON body
            JSONObject part    = new JSONObject().put("text", prompt);
            JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(part));
            JSONObject config  = new JSONObject()
                    .put("temperature", 0.7)
                    .put("maxOutputTokens", 1024);
            JSONObject body    = new JSONObject()
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", config);

            // Tạo kết nối
            URL url = new URL(ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            // Gửi request
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }

            // Đọc response
            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            String rawResponse = sb.toString();
            Log.d(TAG, "Gemini response: " + rawResponse);

            // Parse JSON
            JSONObject json = new JSONObject(rawResponse);
            if (json.has("error")) {
                return "Lỗi API: " + json.getJSONObject("error").optString("message");
            }

            return json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim();

        } catch (Exception e) {
            Log.e(TAG, "Gemini error: " + e.getMessage());
            return "Không kết nối được. Kiểm tra mạng và thử lại nhé.";
        }
    }

    // ── Chat UI ───────────────────────────────────────────────────────────────
    private void addUserMessage(String msg) {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.item_chat_user, chatContainer, false);
        ((TextView) v.findViewById(R.id.tvUserMessage)).setText(msg);
        ((TextView) v.findViewById(R.id.tvUserTime)).setText(now());
        chatContainer.addView(v);
        scrollBottom();
    }

    private void addBotMessage(String msg) {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.item_chat_bot, chatContainer, false);
        ((TextView) v.findViewById(R.id.tvBotMessage)).setText(msg);
        ((TextView) v.findViewById(R.id.tvBotTime)).setText(now());
        chatContainer.addView(v);
        scrollBottom();
    }

    private void scrollBottom() {
        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private String now() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    // ── Speech error ──────────────────────────────────────────────────────────
    private String getSpeechError(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_AUDIO:              return "Lỗi âm thanh";
            case SpeechRecognizer.ERROR_CLIENT:             return "Lỗi client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Thiếu quyền ghi âm";
            case SpeechRecognizer.ERROR_NETWORK:            return "Lỗi mạng";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:    return "Hết thời gian mạng";
            case SpeechRecognizer.ERROR_NO_MATCH:           return "Không nhận ra giọng nói";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:    return "Đang bận";
            case SpeechRecognizer.ERROR_SERVER:             return "Lỗi server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:     return "Không nghe thấy giọng nói";
            default:                                         return "Lỗi không xác định";
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (executor != null) executor.shutdown();
        recHandler.removeCallbacks(recRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int code,
                                           String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_CODE) {
            Toast.makeText(this,
                    results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                            ? "Đã cấp quyền ghi âm"
                            : "Cần quyền ghi âm để dùng giọng nói",
                    Toast.LENGTH_SHORT).show();
        }
    }
}