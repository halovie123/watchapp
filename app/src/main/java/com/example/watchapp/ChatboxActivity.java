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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatboxActivity extends BaseActivity {
    private LinearLayout chatContainer;
    private ScrollView chatScrollView;
    private EditText edtMessage;
    private FloatingActionButton btnSend, btnVoice;
    private Button btnBack;
    private LinearLayout voiceRecordingIndicator;
    private TextView tvRecordingTime;
    private Button btnCancelRecording;
    private Button btnQuickReply1, btnQuickReply2, btnQuickReply3, btnQuickReply4;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private HealthDataManager dataManager;
    private Handler recordingHandler;
    private Runnable recordingRunnable;
    private long recordingStartTime;
    private boolean isRecording = false;

    private static final int PERMISSION_REQUEST_CODE = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatbox);

        dataManager = HealthDataManager.getInstance(this);

        initViews();
        setupFABIcons();
        checkPermissions();
        setupSpeechRecognizer();
        setupTextToSpeech();
        setupListeners();

        // Hiển thị tin nhắn chào mừng
        addBotMessage(getString(R.string.chatbot_welcome));
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Kiểm tra ngôn ngữ
        String savedLanguage = LocaleHelper.getPersistedLanguage(this);
        String currentLanguage = getResources().getConfiguration().locale.getLanguage();

        if (!savedLanguage.equals(currentLanguage)) {
            recreate();
            return;
        }

    }
    private void initViews() {
        chatContainer = findViewById(R.id.chatContainer);
        chatScrollView = findViewById(R.id.chatScrollView);
        edtMessage = findViewById(R.id.edtMessage);
        btnSend = findViewById(R.id.btnSend);
        btnVoice = findViewById(R.id.btnVoice);
        btnBack = findViewById(R.id.btnBack);
        voiceRecordingIndicator = findViewById(R.id.voiceRecordingIndicator);
        tvRecordingTime = findViewById(R.id.tvRecordingTime);
        btnCancelRecording = findViewById(R.id.btnCancelRecording);
        btnQuickReply1 = findViewById(R.id.btnQuickReply1);
        btnQuickReply2 = findViewById(R.id.btnQuickReply2);
        btnQuickReply3 = findViewById(R.id.btnQuickReply3);
        btnQuickReply4 = findViewById(R.id.btnQuickReply4);

        recordingHandler = new Handler();
    }

    private void setupFABIcons() {
        // Set icons cho FAB buttons bằng Android built-in icons
        btnSend.setImageResource(android.R.drawable.ic_menu_send);
        btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                // Sẵn sàng nhận giọng nói
            }

            @Override
            public void onBeginningOfSpeech() {
                // Bắt đầu nói
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // Thay đổi âm lượng
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
                // Nhận buffer
            }

            @Override
            public void onEndOfSpeech() {
                stopRecording();
            }

            @Override
            public void onError(int error) {
                stopRecording();
                String errorMessage = getErrorMessage(error);
                Toast.makeText(ChatboxActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                stopRecording();
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String spokenText = matches.get(0);
                    edtMessage.setText(spokenText);
                    sendMessage(spokenText);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // Kết quả một phần
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
                // Sự kiện khác
            }
        });
    }

    private void setupTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(new Locale("vi", "VN"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fallback to English if Vietnamese is not supported
                    textToSpeech.setLanguage(Locale.US);
                }
            }
        });
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnSend.setOnClickListener(v -> {
            String message = edtMessage.getText().toString().trim();
            if (!message.isEmpty()) {
                sendMessage(message);
            }
        });

        btnVoice.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        btnCancelRecording.setOnClickListener(v -> {
            cancelRecording();
        });

        // Quick replies
        btnQuickReply1.setOnClickListener(v -> sendMessage(getString(R.string.quick_heart)));
        btnQuickReply2.setOnClickListener(v -> sendMessage(getString(R.string.quick_oxygen)));
        btnQuickReply3.setOnClickListener(v -> sendMessage(getString(R.string.quick_tips)));
        btnQuickReply4.setOnClickListener(v -> sendMessage(getString(R.string.quick_help)));

        // Send on Enter key
        edtMessage.setOnEditorActionListener((v, actionId, event) -> {
            String message = edtMessage.getText().toString().trim();
            if (!message.isEmpty()) {
                sendMessage(message);
                return true;
            }
            return false;
        });
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.need_audio_permission, Toast.LENGTH_SHORT).show();
            checkPermissions();
            return;
        }

        isRecording = true;
        voiceRecordingIndicator.setVisibility(View.VISIBLE);
        recordingStartTime = System.currentTimeMillis();

        // Update recording time
        recordingRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedTime = System.currentTimeMillis() - recordingStartTime;
                int seconds = (int) (elapsedTime / 1000) % 60;
                int minutes = (int) (elapsedTime / 1000) / 60;
                tvRecordingTime.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
                recordingHandler.postDelayed(this, 1000);
            }
        };
        recordingHandler.post(recordingRunnable);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
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

    private String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return getString(R.string.error_audio);
            case SpeechRecognizer.ERROR_CLIENT:
                return getString(R.string.error_client);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return getString(R.string.error_permission);
            case SpeechRecognizer.ERROR_NETWORK:
                return getString(R.string.error_network);
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return getString(R.string.error_network_timeout);
            case SpeechRecognizer.ERROR_NO_MATCH:
                return getString(R.string.error_no_match);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return getString(R.string.error_busy);
            case SpeechRecognizer.ERROR_SERVER:
                return getString(R.string.error_server);
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return getString(R.string.error_speech_timeout);
            default:
                return getString(R.string.error_unknown);
        }
    }

    private void sendMessage(String message) {
        addUserMessage(message);
        edtMessage.setText("");

        // Get bot response
        String response = getBotResponse(message);

        // Delay bot response slightly for natural feel
        new Handler().postDelayed(() -> {
            addBotMessage(response);
            speakResponse(response);
        }, 500);
    }

    private void addUserMessage(String message) {
        View messageView = LayoutInflater.from(this).inflate(R.layout.item_chat_user, chatContainer, false);
        TextView tvUserMessage = messageView.findViewById(R.id.tvUserMessage);
        TextView tvUserTime = messageView.findViewById(R.id.tvUserTime);

        tvUserMessage.setText(message);
        tvUserTime.setText(getCurrentTime());

        chatContainer.addView(messageView);
        scrollToBottom();
    }

    private void addBotMessage(String message) {
        View messageView = LayoutInflater.from(this).inflate(R.layout.item_chat_bot, chatContainer, false);
        TextView tvBotMessage = messageView.findViewById(R.id.tvBotMessage);
        TextView tvBotTime = messageView.findViewById(R.id.tvBotTime);

        tvBotMessage.setText(message);
        tvBotTime.setText(getCurrentTime());

        chatContainer.addView(messageView);
        scrollToBottom();
    }

    private void scrollToBottom() {
        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }

    private void speakResponse(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private String getBotResponse(String msg) {
        msg = msg.toLowerCase();

        // Câu hỏi về nhịp tim
        if (msg.contains("nhịp tim") || msg.contains("tim") || msg.contains("heart")) {
            int avgHeartRate = dataManager.getAverageHeartRate();
            List<HealthDataManager.HealthDataPoint> heartData = dataManager.getHeartRateData();

            if (avgHeartRate > 0) {
                String status = "";
                if (avgHeartRate < 60) {
                    status = getString(R.string.heart_status_low);
                } else if (avgHeartRate > 100) {
                    status = getString(R.string.heart_status_high);
                } else {
                    status = getString(R.string.heart_status_normal);
                }

                return getString(R.string.heart_with_data,
                        avgHeartRate, status, heartData.size());
            } else {
                return getString(R.string.heart_no_data);
            }
        }

        // Câu hỏi về nồng độ oxy
        if (msg.contains("spo2") || msg.contains("oxy") || msg.contains("oxygen") || msg.contains("o2")) {
            int avgOxygen = dataManager.getAverageOxygen();
            List<HealthDataManager.HealthDataPoint> oxygenData = dataManager.getOxygenData();

            if (avgOxygen > 0) {
                String status = "";
                if (avgOxygen < 95) {
                    status = getString(R.string.oxygen_status_low);
                } else if (avgOxygen >= 95 && avgOxygen <= 100) {
                    status = getString(R.string.oxygen_status_normal);
                } else {
                    status = getString(R.string.oxygen_status_range);
                }

                return getString(R.string.oxygen_with_data,
                        avgOxygen, status, oxygenData.size());
            } else {
                return getString(R.string.oxygen_no_data);
            }
        }

        // Câu hỏi về té ngã
        if (msg.contains("té") || msg.contains("ngã") || msg.contains("vấp") || msg.contains("fall")) {
            return getString(R.string.fall_advice);
        }

        // Câu hỏi về giấc ngủ
        if (msg.contains("ngủ") || msg.contains("sleep") || msg.contains("mệt") || msg.contains("tired")) {
            return getString(R.string.sleep_advice);
        }

        // Mẹo sức khỏe
        if (msg.contains("mẹo") || msg.contains("tip") || msg.contains("lời khuyên") || msg.contains("advice")) {
            String[] tips = getResources().getStringArray(R.array.health_tips);
            int randomIndex = (int) (Math.random() * tips.length);
            return getString(R.string.tips_intro) + tips[randomIndex];
        }

        // Câu hỏi về chức năng
        if (msg.contains("giúp") || msg.contains("help") || msg.contains("làm gì") || msg.contains("can do") || msg.contains("chức năng")) {
            return getString(R.string.bot_functions);
        }

        // Lời chào
        if (msg.contains("xin chào") || msg.contains("hi") || msg.contains("hello") || msg.contains("chào")) {
            return getString(R.string.bot_greeting);
        }

        // Cảm ơn
        if (msg.contains("cảm ơn") || msg.contains("thanks") || msg.contains("thank")) {
            return getString(R.string.bot_thanks);
        }

        // Câu hỏi về sức khỏe tổng quát
        if (msg.contains("sức khỏe") || msg.contains("health") || msg.contains("khỏe không")) {
            int avgHeartRate = dataManager.getAverageHeartRate();
            int avgOxygen = dataManager.getAverageOxygen();

            return getString(R.string.health_summary,
                    avgHeartRate, avgOxygen);
        }

        // Mặc định
        return String.format(getString(R.string.bot_default), msg);
        }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (recordingHandler != null) {
            recordingHandler.removeCallbacks(recordingRunnable);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.audio_permission_granted), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.audio_permission_required), Toast.LENGTH_LONG).show();
            }
        }
    }
}