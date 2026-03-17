package com.example.watchapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
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

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatboxActivity extends BaseActivity {

    private static final String TAG = "ChatboxActivity";
    private static final int PERMISSION_REQUEST_CODE = 200;

    // ✅ Không có API key — AI chạy hoàn toàn local, không cần internet

    // ── UI ────────────────────────────────────────────────────────────────────
    private LinearLayout chatContainer;
    private ScrollView   chatScrollView;
    private EditText     edtMessage;
    private FloatingActionButton btnSend, btnVoice;
    private Button       btnBack;
    private LinearLayout voiceRecordingIndicator;
    private TextView     tvRecordingTime;
    private Button       btnCancelRecording;
    private Button       btnQuickReply1, btnQuickReply2, btnQuickReply3, btnQuickReply4;

    // ── Speech ────────────────────────────────────────────────────────────────
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech     textToSpeech;
    private Handler          recordingHandler;
    private Runnable         recordingRunnable;
    private long             recordingStartTime = 0;
    private boolean          isRecording = false;

    private Handler mainHandler;

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatbox);

        mainHandler = new Handler(getMainLooper());

        initViews();
        setupFABIcons();
        checkPermissions();
        setupSpeechRecognizer();
        setupTextToSpeech();
        setupListeners();

        addBotMessage(getString(R.string.chatbot_welcome));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String saved   = LocaleHelper.getPersistedLanguage(this);
        String current = getResources().getConfiguration().locale.getLanguage();
        if (!saved.equals(current)) recreate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (textToSpeech    != null) { textToSpeech.stop(); textToSpeech.shutdown(); }
        if (recordingHandler != null) recordingHandler.removeCallbacks(recordingRunnable);
    }

    // =========================================================================
    //  GỬI TIN NHẮN
    // =========================================================================

    private void sendMessage(String message) {
        if (message.isEmpty()) return;
        addUserMessage(message);
        edtMessage.setText("");
        View typingView = addTypingIndicator();
        loadFirebaseAndAnalyze(message, typingView);
    }

    // =========================================================================
    //  BƯỚC 1 — ĐỌC FIREBASE
    //  Lấy 20 bản ghi mới nhất từ health_records
    //  (do MainActivity push lên mỗi 3 giây)
    // =========================================================================

    private void loadFirebaseAndAnalyze(String userMessage, View typingView) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("health_records");

        ref.orderByKey().limitToLast(20)
                .addListenerForSingleValueEvent(new ValueEventListener() {

                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<HealthAnalyzer.HealthRecord> records = new ArrayList<>();

                        for (DataSnapshot child : snapshot.getChildren()) {
                            try {
                                Integer hr   = child.child("heart_rate").getValue(Integer.class);
                                Integer spo2 = child.child("spo2").getValue(Integer.class);
                                String fall  = child.child("fall_detection").getValue(String.class);
                                String ts    = child.child("timestamp").getValue(String.class);

                                if (hr != null && spo2 != null) {
                                    records.add(new HealthAnalyzer.HealthRecord(
                                            hr, spo2, fall, ts));
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Parse lỗi: " + e.getMessage());
                            }
                        }

                        Log.d(TAG, "Firebase: " + records.size() + " bản ghi");
                        analyzeAndReply(userMessage, records, typingView);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Firebase error: " + error.getMessage());
                        // Vẫn chạy phân tích với dữ liệu rỗng
                        analyzeAndReply(userMessage, new ArrayList<>(), typingView);
                    }
                });
    }

    // =========================================================================
    //  BƯỚC 2 — PHÂN TÍCH BẰNG NEURAL NETWORK + SINH CÂU TRẢ LỜI
    //  Chạy trên background thread để không block UI
    // =========================================================================

    private void analyzeAndReply(String userMessage,
                                 List<HealthAnalyzer.HealthRecord> records,
                                 View typingView) {

        // 1. Build health context từ Firebase records
        StringBuilder healthContext = new StringBuilder();
        if (records.isEmpty()) {
            healthContext.append("Chưa có dữ liệu sức khỏe từ thiết bị.");
        } else {
            HealthAnalyzer.HealthRecord latest = records.get(records.size() - 1);
            int avgHr = 0, avgSpo2 = 0;
            for (HealthAnalyzer.HealthRecord r : records) {
                avgHr   += r.heartRate;
                avgSpo2 += r.spo2;
            }
            avgHr   /= records.size();
            avgSpo2 /= records.size();

            healthContext.append(String.format(
                    "Dữ liệu %d bản ghi gần nhất:\n", records.size()));
            healthContext.append(String.format(
                    "- Nhịp tim mới nhất: %d BPM (trung bình: %d)\n", latest.heartRate, avgHr));
            healthContext.append(String.format(
                    "- SpO2 mới nhất: %d%% (trung bình: %d%%)\n", latest.spo2, avgSpo2));
            if (latest.fall != null)
                healthContext.append(String.format(
                        "- Phát hiện té ngã: %s\n", latest.fall));
        }

        // 2. Build prompt
        String prompt = "Bạn là trợ lý sức khỏe thông minh tích hợp trong smartwatch. "
                + "Phân tích dữ liệu sức khỏe sau và trả lời câu hỏi bằng tiếng Việt, "
                + "ngắn gọn (tối đa 4 câu), thân thiện và dễ hiểu.\n\n"
                + healthContext + "\nCâu hỏi: " + userMessage;

        // 3. Gọi Gemini trên background thread
        new Thread(() -> {
            try {
                // 1. Khởi tạo model bằng GenerativeModel (Thay cho Client)
                com.google.ai.client.generativeai.GenerativeModel gm =
                        new com.google.ai.client.generativeai.GenerativeModel(
                                "gemini-3-flash-preview",
                                BuildConfig.GEMINI_API_KEY
                        );

                // 2. Dùng GenerativeModelFutures để hỗ trợ Java
                com.google.ai.client.generativeai.java.GenerativeModelFutures model =
                        com.google.ai.client.generativeai.java.GenerativeModelFutures.from(gm);

                // 3. Tạo nội dung gửi đi
                com.google.ai.client.generativeai.type.Content content =
                        new com.google.ai.client.generativeai.type.Content.Builder()
                                .addText(prompt)
                                .build();

                // 4. Gọi API
                com.google.common.util.concurrent.ListenableFuture<com.google.ai.client.generativeai.type.GenerateContentResponse> responseFuture =
                        model.generateContent(content);

                com.google.common.util.concurrent.Futures.addCallback(responseFuture,
                        new com.google.common.util.concurrent.FutureCallback<com.google.ai.client.generativeai.type.GenerateContentResponse>() {
                            @Override
                            public void onSuccess(com.google.ai.client.generativeai.type.GenerateContentResponse result) {
                                String reply = result.getText();
                                if (reply == null || reply.isEmpty())
                                    reply = "Xin lỗi, tôi không thể phân tích lúc này.";

                                final String finalReply = reply;
                                mainHandler.post(() -> {
                                    chatContainer.removeView(typingView);
                                    addBotMessage(finalReply);
                                    speakResponse(finalReply); // Gọi hàm TTS đã định nghĩa bên dưới
                                });
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                Log.e(TAG, "Gemini error: " + t.getMessage());
                                mainHandler.post(() -> {
                                    chatContainer.removeView(typingView);
                                    addBotMessage("Lỗi Gemini: " + t.getMessage());
                                });
                            }
                        }, androidx.core.content.ContextCompat.getMainExecutor(this));

            } catch (Exception e) {
                Log.e(TAG, "System error: " + e.getMessage());
                mainHandler.post(() -> {
                    chatContainer.removeView(typingView);
                    addBotMessage("Lỗi hệ thống: " + e.getMessage());
                });
            }
        }).start();
    }

    // =========================================================================
    //  UI HELPERS
    // =========================================================================

    private void addUserMessage(String message) {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.item_chat_user, chatContainer, false);
        ((TextView) v.findViewById(R.id.tvUserMessage)).setText(message);
        ((TextView) v.findViewById(R.id.tvUserTime)).setText(getCurrentTime());
        chatContainer.addView(v);
        scrollToBottom();
    }

    private void addBotMessage(String message) {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.item_chat_bot, chatContainer, false);
        ((TextView) v.findViewById(R.id.tvBotMessage)).setText(message);
        ((TextView) v.findViewById(R.id.tvBotTime)).setText(getCurrentTime());
        chatContainer.addView(v);
        scrollToBottom();
    }

    private View addTypingIndicator() {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.item_chat_bot, chatContainer, false);
        ((TextView) v.findViewById(R.id.tvBotMessage))
                .setText("🧠 AI đang phân tích dữ liệu...");
        ((TextView) v.findViewById(R.id.tvBotTime)).setText(getCurrentTime());
        chatContainer.addView(v);
        scrollToBottom();
        return v;
    }

    private void scrollToBottom() {
        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private String getCurrentTime() {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    }

    private void speakResponse(String text) {
        if (textToSpeech != null)
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    // =========================================================================
    //  INIT VIEWS
    // =========================================================================

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
        recordingHandler        = new Handler();
    }

    private void setupFABIcons() {
        btnSend.setImageResource(android.R.drawable.ic_menu_send);
        btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
    }

    // =========================================================================
    //  SETUP LISTENERS
    // =========================================================================

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnSend.setOnClickListener(v ->
                sendMessage(edtMessage.getText().toString().trim()));

        btnVoice.setOnClickListener(v -> {
            if (!isRecording) startRecording(); else stopRecording();
        });

        btnCancelRecording.setOnClickListener(v -> cancelRecording());

        btnQuickReply1.setOnClickListener(v -> sendMessage(getString(R.string.quick_heart)));
        btnQuickReply2.setOnClickListener(v -> sendMessage(getString(R.string.quick_oxygen)));
        btnQuickReply3.setOnClickListener(v -> sendMessage(getString(R.string.quick_tips)));
        btnQuickReply4.setOnClickListener(v -> sendMessage(getString(R.string.quick_help)));

        edtMessage.setOnEditorActionListener((v, actionId, event) -> {
            String msg = edtMessage.getText().toString().trim();
            if (!msg.isEmpty()) { sendMessage(msg); return true; }
            return false;
        });
    }

    // =========================================================================
    //  SPEECH RECOGNIZER
    // =========================================================================

    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { stopRecording(); }
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int t, Bundle p) {}

            @Override
            public void onError(int error) {
                stopRecording();
                Toast.makeText(ChatboxActivity.this,
                        getErrorMessage(error), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                stopRecording();
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    edtMessage.setText(matches.get(0));
                    sendMessage(matches.get(0));
                }
            }
        });
    }

    private void startRecording() {
        isRecording = true;
        voiceRecordingIndicator.setVisibility(View.VISIBLE);
        recordingStartTime = System.currentTimeMillis();

        recordingRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000;
                tvRecordingTime.setText(String.format(Locale.getDefault(),
                        "%02d:%02d", elapsed / 60, elapsed % 60));
                recordingHandler.postDelayed(this, 1000);
            }
        };
        recordingHandler.post(recordingRunnable);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizer.startListening(intent);
    }

    private void stopRecording() {
        isRecording = false;
        voiceRecordingIndicator.setVisibility(View.GONE);
        recordingHandler.removeCallbacks(recordingRunnable);
        speechRecognizer.stopListening();
    }

    private void cancelRecording() {
        stopRecording();
        speechRecognizer.cancel();
    }

    // =========================================================================
    //  TEXT TO SPEECH
    // =========================================================================

    private void setupTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = textToSpeech.setLanguage(new Locale("vi", "VN"));
                if (r == TextToSpeech.LANG_MISSING_DATA
                        || r == TextToSpeech.LANG_NOT_SUPPORTED)
                    textToSpeech.setLanguage(Locale.US);
            }
        });
    }

    // =========================================================================
    //  PERMISSIONS
    // =========================================================================

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERMISSION_REQUEST_CODE) {
            Toast.makeText(this,
                    results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                            ? getString(R.string.audio_permission_granted)
                            : getString(R.string.audio_permission_required),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return getString(R.string.error_audio);
            case SpeechRecognizer.ERROR_NETWORK:
                return getString(R.string.error_network);
            case SpeechRecognizer.ERROR_NO_MATCH:
                return getString(R.string.error_no_match);
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return getString(R.string.error_speech_timeout);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return getString(R.string.error_permission);
            default:
                return getString(R.string.error_unknown);
        }
    }
}