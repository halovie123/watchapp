package com.example.watchapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.List;

public class ChartView extends View {
    private Paint linePaint;
    private Paint pointPaint;
    private Paint gridPaint;
    private List<HealthDataManager.HealthDataPoint> dataPoints;
    private int lineColor;
    private int minValue;
    private int maxValue;

    public ChartView(Context context) {
        super(context);
        init();
    }

    public ChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Vẽ đường biểu đồ
        linePaint = new Paint();
        linePaint.setAntiAlias(true);
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        // Vẽ điểm
        pointPaint = new Paint();
        pointPaint.setAntiAlias(true);
        pointPaint.setStyle(Paint.Style.FILL);

        // Vẽ lưới
        gridPaint = new Paint();
        gridPaint.setAntiAlias(true);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(0xFFE0E0E0);
    }

    public void setData(List<HealthDataManager.HealthDataPoint> data, int color, int min, int max) {
        this.dataPoints = data;
        this.lineColor = color;
        this.minValue = min;
        this.maxValue = max;
        linePaint.setColor(color);
        pointPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        if (dataPoints == null || dataPoints.isEmpty()) {
            // Vẽ text "Chưa có dữ liệu"
            Paint textPaint = new Paint();
            textPaint.setColor(0xFF999999);
            textPaint.setTextSize(36f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            String noData = getContext().getString(R.string.no_data);
            canvas.drawText(noData, width / 2f, height / 2f, textPaint);
            return;
        }


        int paddingLeft = 60;
        int padding = 20;

        // Vẽ lưới ngang
        for (int i = 0; i <= 4; i++) {
            float y = padding + (height - 2 * padding) * i / 4f;
            canvas.drawLine(paddingLeft, y, width - padding, y, gridPaint);

        }

        // Vẽ biểu đồ
        if (dataPoints.size() >= 2) {
            Path path = new Path();

            for (int i = 0; i < dataPoints.size(); i++) {
                HealthDataManager.HealthDataPoint point = dataPoints.get(i);

                // Tính toán vị trí x, y
                float x = paddingLeft + (width - paddingLeft - padding)
                        * i / (float)(dataPoints.size() - 1);

                float normalizedValue = (point.value - minValue) / (float)(maxValue - minValue);
                float y = height - padding - (height - 2 * padding) * normalizedValue;

                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }

                // Vẽ điểm
                canvas.drawCircle(x, y, 6f, pointPaint);
            }

            // Vẽ đường
            canvas.drawPath(path, linePaint);
        }

        // Vẽ giá trị min/max
        Paint labelPaint = new Paint();
        labelPaint.setColor(0xFF666666);
        labelPaint.setTextSize(22f);
        labelPaint.setTextAlign(Paint.Align.RIGHT);

        canvas.drawText(
                String.valueOf(maxValue),
                paddingLeft - 8,
                padding + 18,
                labelPaint
        );

        canvas.drawText(
                String.valueOf(minValue),
                paddingLeft - 8,
                height - padding + 18,
                labelPaint
        );

    }
}