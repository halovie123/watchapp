package com.example.watchapp;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Neural Network Inference — đọc và chạy model health_model.bin
 * Không cần TensorFlow Lite dependency, tự implement forward pass.
 *
 * Model: 4 → 32 → 16 → 4
 * Input:  [heart_rate, spo2, accel_magnitude, gyro_magnitude]
 * Output: [hr_risk, spo2_risk, activity, overall_risk]  (mỗi cái 0-3)
 *
 * Cách dùng:
 *   NNInference nn = NNInference.load(context);
 *   int[] result = nn.predict(78f, 97f, 10.5f, 0.3f);
 *   // result = [hrRisk, spo2Risk, activity, overallRisk]
 */
public class NNInference {

    private static final String TAG   = "NNInference";
    private static final String MODEL = "health_model.bin";
    private static final byte[] MAGIC = {'W','A','P','P'};

    // Normalization params (đọc từ file)
    private float[] mean;   // shape [4]
    private float[] std;    // shape [4]

    // Weights (W1,b1,W2,b2,W3,b3)
    private float[][] W1, W2, W3;
    private float[]   b1, b2, b3;

    // Architecture
    private static final int IN=4, H1=32, H2=16, OUT=4;

    // =========================================================================
    //  LOAD MODEL
    // =========================================================================

    public static NNInference load(Context ctx) {
        try {
            InputStream is   = ctx.getAssets().open(MODEL);
            byte[]      data = new byte[is.available()];
            is.read(data);
            is.close();

            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());

            // Kiểm tra magic header
            byte[] magic = new byte[4];
            buf.get(magic);
            for (int i = 0; i < 4; i++) {
                if (magic[i] != MAGIC[i])
                    throw new Exception("Invalid model file");
            }
            buf.getInt(); // version

            NNInference nn = new NNInference();

            // Normalization
            nn.mean = readFloatArray(buf, 4);
            nn.std  = readFloatArray(buf, 4);

            // Weights
            nn.W1 = readMatrix(buf, IN, H1);
            nn.b1 = readFloatArray(buf, H1);
            nn.W2 = readMatrix(buf, H1, H2);
            nn.b2 = readFloatArray(buf, H2);
            nn.W3 = readMatrix(buf, H2, OUT);
            nn.b3 = readFloatArray(buf, OUT);

            Log.d(TAG, "✅ Model loaded successfully");
            return nn;

        } catch (Exception e) {
            Log.e(TAG, "❌ Load model failed: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    //  PREDICT
    //  input: [heart_rate, spo2, accel_magnitude, gyro_magnitude]
    //  output: [hr_risk, spo2_risk, activity, overall_risk]  (0=OK, 1=chú ý, 2=cao, 3=nguy hiểm)
    // =========================================================================

    public int[] predict(float heartRate, float spo2, float accelMag, float gyroMag) {
        // Normalize input
        float[] x = {heartRate, spo2, accelMag, gyroMag};
        float[] xNorm = new float[IN];
        for (int i = 0; i < IN; i++)
            xNorm[i] = (x[i] - mean[i]) / std[i];

        // Forward pass: 4 → ReLU(32) → ReLU(16) → Sigmoid(4)
        float[] h1  = relu(matMul(xNorm, W1, b1));   // [32]
        float[] h2  = relu(matMul(h1,   W2, b2));    // [16]
        float[] out = sigmoid(matMul(h2, W3, b3));   // [4]  values in [0,1]

        // Scale về [0,3] và làm tròn
        int[] result = new int[OUT];
        for (int i = 0; i < OUT; i++)
            result[i] = Math.min(3, Math.max(0, Math.round(out[i] * 3)));

        Log.d(TAG, String.format(
                "Predict: HR=%d SpO2=%d Accel=%.1f Gyro=%.2f → hrRisk=%d spo2Risk=%d act=%d overall=%d",
                (int)heartRate, (int)spo2, accelMag, gyroMag,
                result[0], result[1], result[2], result[3]));

        return result;
    }

    // =========================================================================
    //  MATH HELPERS
    // =========================================================================

    private static float[] matMul(float[] x, float[][] W, float[] b) {
        int rows = W[0].length;
        float[] out = new float[rows];
        for (int j = 0; j < rows; j++) {
            out[j] = b[j];
            for (int i = 0; i < x.length; i++)
                out[j] += x[i] * W[i][j];
        }
        return out;
    }

    private static float[] relu(float[] x) {
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++)
            out[i] = Math.max(0, x[i]);
        return out;
    }

    private static float[] sigmoid(float[] x) {
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++)
            out[i] = (float)(1.0 / (1.0 + Math.exp(-Math.max(-10, Math.min(10, x[i])))));
        return out;
    }

    // =========================================================================
    //  FILE READING HELPERS
    // =========================================================================

    private static float[] readFloatArray(ByteBuffer buf, int size) {
        int ndim = buf.getInt();
        // skip shape ints
        for (int i = 0; i < ndim; i++) buf.getInt();
        float[] arr = new float[size];
        for (int i = 0; i < size; i++) arr[i] = buf.getFloat();
        return arr;
    }

    private static float[][] readMatrix(ByteBuffer buf, int rows, int cols) {
        int ndim = buf.getInt();
        for (int i = 0; i < ndim; i++) buf.getInt();
        float[][] mat = new float[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                mat[r][c] = buf.getFloat();
        return mat;
    }
}