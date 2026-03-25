package com.example.watchapp;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Fall Detection Model using TensorFlow Lite
 *
 * Model input: [1, 512, 1] - 512 timesteps of magnitude data
 * Model output: [1, 1] - probability of fall (0-1)
 *
 * Usage:
 *   FallDetectionModel model = new FallDetectionModel(context);
 *   model.addDataPoint(magnitude);  // Add each new data point
 *   if (model.isReadyForInference()) {
 *       float probability = model.predict();
 *       if (probability > 0.5f) {
 *           // Fall detected!
 *       }
 *   }
 */
public class FallDetectionModel {

    private static final String TAG = "FallDetectionModel";
    private static final String MODEL_PATH = "fall_detection.tflite";

    // Model parameters
    private static final int WINDOW_SIZE = 512;
    private static final float FALL_THRESHOLD = 0.8f; // Probability threshold

    private Interpreter tflite;
    private List<Float> dataBuffer;
    private boolean isModelLoaded = false;

    // Input/Output buffers for TFLite
    private float[][][] inputBuffer;  // [1, 512, 1]
    private float[][] outputBuffer;   // [1, 1]

    public FallDetectionModel(Context context) {
        dataBuffer = new ArrayList<>();
        inputBuffer = new float[1][WINDOW_SIZE][1];
        outputBuffer = new float[1][1];

        try {
            tflite = new Interpreter(loadModelFile(context));
            isModelLoaded = true;
            Log.d(TAG, "✓ Model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to load model: " + e.getMessage());
            isModelLoaded = false;
        }
    }

    /**
     * Load TFLite model from assets
     */
    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Add a new magnitude data point to the sliding window
     *
     * @param magnitude Acceleration magnitude in g (e.g., 1.0 = 1g)
     */
    public void addDataPoint(float magnitude) {
        if (!isModelLoaded) return;

        dataBuffer.add(magnitude);

        // Keep only the last WINDOW_SIZE points (sliding window)
        if (dataBuffer.size() > WINDOW_SIZE) {
            dataBuffer.remove(0);
        }

        Log.d(TAG, "Buffer size: " + dataBuffer.size() + "/" + WINDOW_SIZE +
                " | Latest: " + String.format("%.3f", magnitude) + "g");
    }

    /**
     * Check if we have enough data for inference
     */
    public boolean isReadyForInference() {
        return isModelLoaded && dataBuffer.size() >= WINDOW_SIZE;
    }

    /**
     * Run inference on the current window
     *
     * @return Probability of fall (0.0 to 1.0), or -1 if not ready
     */
    public float predict() {
        if (!isReadyForInference()) {
            Log.w(TAG, "Not ready for inference. Buffer: " + dataBuffer.size() + "/" + WINDOW_SIZE);
            return -1f;
        }

        try {
            // Prepare input buffer
            for (int i = 0; i < WINDOW_SIZE; i++) {
                inputBuffer[0][i][0] = dataBuffer.get(i);
            }

            // Run inference
            tflite.run(inputBuffer, outputBuffer);

            float probability = outputBuffer[0][0];

            Log.d(TAG, "Inference → Probability: " + String.format("%.4f", probability) +
                    " (" + (probability > FALL_THRESHOLD ? "FALL" : "NORMAL") + ")");

            return probability;

        } catch (Exception e) {
            Log.e(TAG, "Inference error: " + e.getMessage());
            return -1f;
        }
    }

    /**
     * Predict and return if fall is detected
     */
    public boolean detectFall() {
        float prob = predict();
        return prob >= FALL_THRESHOLD;
    }

    /**
     * Get current buffer statistics
     */
    public String getBufferStats() {
        if (dataBuffer.isEmpty()) return "Empty buffer";

        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        float sum = 0;

        for (float val : dataBuffer) {
            if (val < min) min = val;
            if (val > max) max = val;
            sum += val;
        }

        float avg = sum / dataBuffer.size();

        return String.format("Buffer: %d/%d | Min: %.2f | Max: %.2f | Avg: %.2f",
                dataBuffer.size(), WINDOW_SIZE, min, max, avg);
    }

    public float calculateVariance() {
        if (dataBuffer.size() < WINDOW_SIZE) return 0;

        float mean = 0;
        for (float v : dataBuffer) mean += v;
        mean /= dataBuffer.size();

        float var = 0;
        for (float v : dataBuffer) {
            float diff = v - mean;
            var += diff * diff;
        }
        return var / dataBuffer.size();
    }

    /**
     * Clear the buffer (use after detecting a fall to avoid re-triggering)
     */
    public void clearBuffer() {
        dataBuffer.clear();
        Log.d(TAG, "Buffer cleared");
    }

    /**
     * Get the fall threshold
     */
    public float getFallThreshold() {
        return FALL_THRESHOLD;
    }

    /**
     * Check if model is loaded and ready
     */
    public boolean isModelReady() {
        return isModelLoaded;
    }

    /**
     * Release resources
     */
    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (dataBuffer != null) {
            dataBuffer.clear();
        }
        Log.d(TAG, "Model closed");
    }
}