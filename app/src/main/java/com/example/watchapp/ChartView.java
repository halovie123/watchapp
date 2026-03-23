package com.example.watchapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChartView extends View {
    private Paint linePaint;
    private Paint pointPaint;
    private Paint gridPaint;
    private List<HealthDataManager.HealthDataPoint> dataPoints;
    private int lineColor;
    private int minValue;
    private int maxValue;

    // Tooltip
    private HealthDataManager.HealthDataPoint selectedPoint = null;
    private float selectedX = -1, selectedY = -1;
    private final Paint tooltipBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimLinePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat sdf   =
            new SimpleDateFormat("HH:mm:ss  dd/MM/yyyy", Locale.getDefault());
    private final Runnable hideTooltip   = () -> { selectedPoint = null; invalidate(); };

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

        // Tooltip background
        tooltipBgPaint.setColor(0xFFFFFFFF);
        tooltipBgPaint.setStyle(Paint.Style.FILL);
        tooltipBgPaint.setShadowLayer(8f, 2f, 4f, 0x55000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // cần cho shadow

        // Tooltip text
        tooltipTextPaint.setColor(0xFF212121);
        tooltipTextPaint.setTextSize(26f);

        // Vòng tròn highlight điểm được chọn
        highlightPaint.setStyle(Paint.Style.FILL);
        highlightPaint.setColor(0xFFFFFFFF);

        // Đường dọc tại điểm chọn
        dimLinePaint.setStyle(Paint.Style.STROKE);
        dimLinePaint.setStrokeWidth(1.5f);
        dimLinePaint.setColor(0x88888888);
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

    // ── Touch để hiện tooltip ────────────────────────────────
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (dataPoints == null || dataPoints.size() < 2) return false;

        int paddingLeft = 60, padding = 20;
        float w = getWidth(), h = getHeight();
        float stepX = (w - paddingLeft - padding) / (float)(dataPoints.size() - 1);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                removeCallbacks(hideTooltip);
                float touchX = event.getX();

                // Tìm điểm gần nhất
                int nearest = 0;
                float minDist = Float.MAX_VALUE;
                for (int i = 0; i < dataPoints.size(); i++) {
                    float px = paddingLeft + i * stepX;
                    float d = Math.abs(px - touchX);
                    if (d < minDist) { minDist = d; nearest = i; }
                }

                selectedPoint = dataPoints.get(nearest);
                selectedX = paddingLeft + nearest * stepX;
                float norm = (selectedPoint.value - minValue) / (float)(maxValue - minValue);
                selectedY = h - padding - (h - 2 * padding) * norm;

                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                postDelayed(hideTooltip, 3000); // tự ẩn sau 3s
                return true;
        }
        return false;
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

        // ── Vẽ tooltip nếu có điểm được chọn ────────────────
        if (selectedPoint != null) {
            // Đường dọc tại điểm chọn
            canvas.drawLine(selectedX, padding, selectedX, height - padding, dimLinePaint);

            // Vòng tròn ngoài (viền màu line)
            Paint outerDot = new Paint(pointPaint);
            outerDot.setStyle(Paint.Style.FILL);
            canvas.drawCircle(selectedX, selectedY, 14f, outerDot);
            canvas.drawCircle(selectedX, selectedY, 8f,  highlightPaint);

            // Chuẩn bị nội dung tooltip
            String valStr  = "Giá trị: " + selectedPoint.value;
            String timeStr = sdf.format(new Date(selectedPoint.timestamp));

            float padding2  = 28f;
            float lineH     = tooltipTextPaint.descent() - tooltipTextPaint.ascent();
            float boxW      = Math.max(
                    tooltipTextPaint.measureText(valStr),
                    tooltipTextPaint.measureText(timeStr)
            ) + padding2 * 2;
            float boxH = lineH * 2 + padding2 * 2;

            // Canh không bị ra ngoài màn hình
            float boxX = selectedX + 24;
            if (boxX + boxW > width - padding) boxX = selectedX - boxW - 24;
            float boxY = selectedY - boxH / 2;
            if (boxY < padding) boxY = (float) padding;
            if (boxY + boxH > height - padding) boxY = height - padding - boxH;

            // Header màu (dải trên tooltip)
            RectF tooltipRect = new RectF(boxX, boxY, boxX + boxW, boxY + boxH);
            tooltipBgPaint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(tooltipRect, 14f, 14f, tooltipBgPaint);

            // Dải màu header
            RectF headerRect = new RectF(boxX, boxY, boxX + boxW, boxY + lineH + padding2);
            Paint headerPaint = new Paint(tooltipBgPaint);
            headerPaint.setColor(lineColor);
            canvas.drawRoundRect(headerRect, 14f, 14f, headerPaint);
            // che góc dưới header (để chỉ tròn trên)
            canvas.drawRect(boxX, boxY + lineH / 2, boxX + boxW, boxY + lineH + padding2, headerPaint);

            // Text giá trị (trên header trắng)
            Paint valTextPaint = new Paint(tooltipTextPaint);
            valTextPaint.setColor(0xFFFFFFFF);
            valTextPaint.setTextSize(38f);
            canvas.drawText(valStr, boxX + padding2,
                    boxY + padding2 + lineH * 0.75f, valTextPaint);

            // Text thời gian (dưới, nền trắng)
            tooltipTextPaint.setColor(0xFF424242);
            tooltipTextPaint.setTextSize(34f);
            canvas.drawText(timeStr, boxX + padding2,
                    boxY + lineH + padding2 * 1.5f + lineH * 0.75f, tooltipTextPaint);
        }

    }
}