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

public class ChatboxActivity extends AppCompatActivity {
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
        addBotMessage("Xin chào! Tôi là trợ lý sức khỏe của bạn. Tôi có thể giúp gì cho bạn? 🤖");
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
        btnQuickReply1.setOnClickListener(v -> sendMessage("Nhịp tim của tôi như thế nào?"));
        btnQuickReply2.setOnClickListener(v -> sendMessage("Nồng độ oxy của tôi"));
        btnQuickReply3.setOnClickListener(v -> sendMessage("Cho tôi mẹo sức khỏe"));
        btnQuickReply4.setOnClickListener(v -> sendMessage("Giúp tôi"));

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
            Toast.makeText(this, "Cần quyền ghi âm", Toast.LENGTH_SHORT).show();
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
                return "Lỗi âm thanh";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Lỗi client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Không đủ quyền";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Lỗi mạng";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Hết thời gian chờ mạng";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "Không nhận diện được giọng nói";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Đang bận";
            case SpeechRecognizer.ERROR_SERVER:
                return "Lỗi server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Không có giọng nói được phát hiện";
            default:
                return "Lỗi không xác định";
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
                    status = "thấp hơn bình thường";
                } else if (avgHeartRate > 100) {
                    status = "cao hơn bình thường";
                } else {
                    status = "trong khoảng bình thường";
                }

                return String.format("Nhịp tim trung bình của bạn là %d BPM (%s). " +
                                "Tôi đã theo dõi %d lần đo trong 24 giờ qua. " +
                                "Nhịp tim bình thường khi nghỉ là 60-100 BPM.",
                        avgHeartRate, status, heartData.size());
            } else {
                return "Hiện tại chưa có dữ liệu nhịp tim. Hãy đo nhịp tim để tôi có thể phân tích cho bạn. " +
                        "Nhịp tim bình thường khi nghỉ là 60-100 BPM.";
            }
        }

        // Câu hỏi về nồng độ oxy
        if (msg.contains("spo2") || msg.contains("oxy") || msg.contains("oxygen") || msg.contains("o2")) {
            int avgOxygen = dataManager.getAverageOxygen();
            List<HealthDataManager.HealthDataPoint> oxygenData = dataManager.getOxygenData();

            if (avgOxygen > 0) {
                String status = "";
                if (avgOxygen < 95) {
                    status = "thấp, cần theo dõi";
                } else if (avgOxygen >= 95 && avgOxygen <= 100) {
                    status = "bình thường";
                } else {
                    status = "trong khoảng đo";
                }

                return String.format("Nồng độ oxy trung bình của bạn là %d%% (%s). " +
                                "Tôi đã theo dõi %d lần đo trong 24 giờ qua. " +
                                "SpO2 bình thường từ 95-100%%. Nếu dưới 92%% hãy theo dõi kỹ.",
                        avgOxygen, status, oxygenData.size());
            } else {
                return "Hiện tại chưa có dữ liệu nồng độ oxy. Hãy đo SpO2 để tôi có thể phân tích cho bạn. " +
                        "SpO2 bình thường từ 95-100%.";
            }
        }

        // Câu hỏi về té ngã
        if (msg.contains("té") || msg.contains("ngã") || msg.contains("vấp") || msg.contains("fall")) {
            return "Nếu bạn vừa bị té ngã:\n" +
                    "1. Hãy ngồi yên và đánh giá tình trạng\n" +
                    "2. Kiểm tra xem có bị thương không\n" +
                    "3. Nếu có đau hoặc chóng mặt, hãy gọi trợ giúp\n" +
                    "4. Đồng hồ có tính năng phát hiện té ngã tự động và sẽ thông báo cho người thân nếu cần.";
        }

        // Câu hỏi về giấc ngủ
        if (msg.contains("ngủ") || msg.contains("sleep") || msg.contains("mệt") || msg.contains("tired")) {
            return "Về giấc ngủ:\n" +
                    "✓ Người trưởng thành nên ngủ 7-8 tiếng mỗi ngày\n" +
                    "✓ Đi ngủ và thức dậy đúng giờ\n" +
                    "✓ Tránh màn hình 1 giờ trước khi ngủ\n" +
                    "✓ Phòng ngủ tối, mát và yên tĩnh\n" +
                    "✓ Tránh caffeine sau 2 giờ chiều";
        }

        // Mẹo sức khỏe
        if (msg.contains("mẹo") || msg.contains("tip") || msg.contains("lời khuyên") || msg.contains("advice")) {
            String[] tips = {
                    "💧 Uống đủ 2 lít nước mỗi ngày để duy trì sức khỏe tốt.",
                    "🚶 Đi bộ ít nhất 30 phút mỗi ngày giúp cải thiện tuần hoàn.",
                    "🥗 Ăn nhiều rau xanh và trái cây tươi.",
                    "😴 Ngủ đủ 7-8 tiếng mỗi đêm để cơ thể phục hồi.",
                    "🧘 Thực hành thiền định hoặc yoga giúp giảm stress.",
                    "📱 Giảm thời gian nhìn màn hình, nghỉ ngơi mắt 20 giây sau mỗi 20 phút.",
                    "💪 Vận động nhẹ nhàng mỗi ngày giúp tăng cường sức khỏe tim mạch."
            };
            int randomIndex = (int) (Math.random() * tips.length);
            return "Đây là mẹo sức khỏe cho bạn:\n\n" + tips[randomIndex];
        }

        // Câu hỏi về chức năng
        if (msg.contains("giúp") || msg.contains("help") || msg.contains("làm gì") || msg.contains("can do") || msg.contains("chức năng")) {
            return "Tôi có thể giúp bạn:\n\n" +
                    "💓 Theo dõi và phân tích nhịp tim\n" +
                    "🫁 Theo dõi nồng độ oxy (SpO2)\n" +
                    "🚨 Cảnh báo té ngã\n" +
                    "💡 Cung cấp mẹo sức khỏe\n" +
                    "📊 Phân tích dữ liệu sức khỏe của bạn\n" +
                    "😴 Tư vấn về giấc ngủ\n\n" +
                    "Bạn có thể hỏi tôi bất cứ điều gì về sức khỏe!";
        }

        // Lời chào
        if (msg.contains("xin chào") || msg.contains("hi") || msg.contains("hello") || msg.contains("chào")) {
            return "Xin chào! Rất vui được gặp bạn. Tôi là trợ lý sức khỏe thông minh. " +
                    "Bạn cần tôi giúp gì về sức khỏe của bạn không? 😊";
        }

        // Cảm ơn
        if (msg.contains("cảm ơn") || msg.contains("thanks") || msg.contains("thank")) {
            return "Không có gì! Tôi luôn sẵn sàng giúp đỡ bạn. Hãy chăm sóc sức khỏe nhé! 😊";
        }

        // Câu hỏi về sức khỏe tổng quát
        if (msg.contains("sức khỏe") || msg.contains("health") || msg.contains("khỏe không")) {
            int avgHeartRate = dataManager.getAverageHeartRate();
            int avgOxygen = dataManager.getAverageOxygen();

            if (avgHeartRate > 0 && avgOxygen > 0) {
                return String.format("Dựa trên dữ liệu của bạn:\n\n" +
                                "💓 Nhịp tim trung bình: %d BPM\n" +
                                "🫁 Nồng độ oxy: %d%%\n\n" +
                                "Các chỉ số của bạn nhìn chung ổn. Hãy tiếp tục duy trì lối sống lành mạnh!",
                        avgHeartRate, avgOxygen);
            } else {
                return "Tôi cần thêm dữ liệu để đánh giá sức khỏe của bạn. " +
                        "Hãy đo nhịp tim và nồng độ oxy để tôi có thể phân tích tốt hơn.";
            }
        }

        // Mặc định
        return "Tôi hiểu bạn đang hỏi về \"" + msg + "\". " +
                "Bạn có thể hỏi tôi về:\n" +
                "• Nhịp tim\n" +
                "• Nồng độ oxy (SpO2)\n" +
                "• Té ngã\n" +
                "• Giấc ngủ\n" +
                "• Mẹo sức khỏe\n\n" +
                "Hoặc nói \"giúp tôi\" để xem tất cả chức năng! 😊";
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
                Toast.makeText(this, "Đã cấp quyền ghi âm", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Cần quyền ghi âm để sử dụng tính năng giọng nói", Toast.LENGTH_LONG).show();
            }
        }
    }
}