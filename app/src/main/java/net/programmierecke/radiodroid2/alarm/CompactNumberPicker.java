package net.programmierecke.radiodroid2.alarm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.animation.DecelerateInterpolator;
import android.widget.NumberPicker;

import net.programmierecke.radiodroid2.R;

import java.util.Locale;

/**
 * 时间滚轮（完全自绘版本）：
 * <ul>
 *   <li>选中行高大、字号大且加粗；上下相邻行高小、字号小且半透明，
 *       形成中间突出、两侧收窄的视觉效果；</li>
 *   <li>继承 {@link NumberPicker} 仅为保持对外 API 兼容
 *       （setMinValue/setMaxValue/setValue/getValue/setWrapSelectorWheel/
 *       setDisplayedValues/setOnLongPressUpdateInterval 等），
 *       绘制与触摸全部由本类接管，不依赖系统内部实现；</li>
 *   <li>颜色由主题属性 timePickerValueColor / timePickerDividerColor 控制，
 *       亮色/暗色主题均正常显示。</li>
 * </ul>
 */
public class CompactNumberPicker extends NumberPicker {

    /** 选中行（红框）高度 */
    private static final float SELECTED_ROW_DP = 88f;
    /** 相邻行（绿框）高度 */
    private static final float NEIGHBOR_ROW_DP = 46f;
    /** 选中行字号 */
    private static final float SELECTED_TEXT_SP = 44f;
    /** 相邻行字号 */
    private static final float NEIGHBOR_TEXT_SP = 26f;
    /** 相邻行文字透明度 */
    private static final float NEIGHBOR_ALPHA = 0.40f;
    /** 分割线高度 */
    private static final float DIVIDER_HEIGHT_DP = 1f;
    /** 水平内边距 */
    private static final float H_PADDING_DP = 12f;

    private final float density;
    private float selectedRowPx;
    private float neighborRowPx;
    /** 行中心到行中心的距离（一步） */
    private float stepPx;
    private float totalHeightPx;
    private float dividerPx;
    private float hPaddingPx;
    private float selectedTextPx;
    private float neighborTextPx;

    private int valueColor;
    private int dividerColor;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();

    private int minValue = 0;
    private int maxValue = 0;
    private int value = 0;
    private String[] displayedValues;
    private boolean wrap;
    private OnValueChangeListener listener;

    /** 连续滚动位置：0 对应当前 value，单位为“步” */
    private float position = 0f;
    private int lastReportedValue;

    private ValueAnimator snapAnimator;
    private VelocityTracker velocityTracker;
    private float lastTouchY;

    public CompactNumberPicker(Context context) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        initColors();
        initSizes();
    }

    public CompactNumberPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.density = context.getResources().getDisplayMetrics().density;
        initColors();
        initSizes();
    }

    private void initColors() {
        TypedValue value = new TypedValue();
        TypedValue divider = new TypedValue();
        getContext().getTheme().resolveAttribute(R.attr.timePickerValueColor, value, true);
        getContext().getTheme().resolveAttribute(R.attr.timePickerDividerColor, divider, true);
        valueColor = value.data;
        dividerColor = divider.data;
        dividerPaint.setColor(dividerColor);
    }

    private void initSizes() {
        selectedRowPx = SELECTED_ROW_DP * density;
        neighborRowPx = NEIGHBOR_ROW_DP * density;
        stepPx = (selectedRowPx + neighborRowPx) / 2f;
        totalHeightPx = selectedRowPx + neighborRowPx * 2f;
        dividerPx = Math.max(1f, DIVIDER_HEIGHT_DP * density);
        hPaddingPx = H_PADDING_DP * density;
        selectedTextPx = sp2px(SELECTED_TEXT_SP);
        neighborTextPx = sp2px(NEIGHBOR_TEXT_SP);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setPadding(0, 0, 0, 0);
    }

    // ---------- 对外 API（覆写父类，全部自管） ----------

    @Override
    public void setMinValue(int minValue) {
        this.minValue = minValue;
    }

    @Override
    public void setMaxValue(int maxValue) {
        this.maxValue = maxValue;
    }

    @Override
    public void setValue(int value) {
        cancelSnap();
        this.value = clampValue(value);
        this.position = this.value - minValue;
        lastReportedValue = this.value;
        requestLayout();
        invalidate();
    }

    @Override
    public int getValue() {
        return valueFromPosition(position);
    }

    @Override
    public void setWrapSelectorWheel(boolean wrap) {
        this.wrap = wrap;
    }

    @Override
    public void setDisplayedValues(String[] displayedValues) {
        this.displayedValues = displayedValues;
        requestLayout();
        invalidate();
    }

    @Override
    public void setOnValueChangedListener(OnValueChangeListener listener) {
        this.listener = listener;
    }

    // setOnLongPressUpdateInterval / setDescendantFocusability 沿用父类实现即可。

    // ---------- 值换算 ----------

    private int count() {
        return Math.max(1, maxValue - minValue + 1);
    }

    private int clampValue(int v) {
        if (wrap) {
            int c = count();
            return ((v - minValue) % c + c) % c + minValue;
        }
        return Math.max(minValue, Math.min(maxValue, v));
    }

    private int valueFromPosition(float pos) {
        int rounded = Math.round(pos);
        return clampValue(minValue + rounded);
    }

    private float clampPosition(float pos) {
        if (!wrap) {
            return Math.max(0f, Math.min(count() - 1f, pos));
        }
        return pos;
    }

    private String textForStep(int step) {
        int idx;
        if (wrap) {
            int c = count();
            idx = ((step % c) + c) % c;
        } else {
            idx = step;
            if (idx < 0 || idx >= count()) {
                return null;
            }
        }
        if (displayedValues != null && idx < displayedValues.length) {
            return displayedValues[idx];
        }
        return String.format(Locale.US, "%02d", minValue + idx);
    }

    // ---------- 测量 ----------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float maxTextWidth = 0f;
        textPaint.setTextSize(selectedTextPx);
        int n = count();
        for (int i = 0; i < n; i++) {
            String t = displayedValues != null && i < displayedValues.length
                    ? displayedValues[i]
                    : String.format(Locale.US, "%02d", minValue + i);
            maxTextWidth = Math.max(maxTextWidth, textPaint.measureText(t));
        }
        int width = Math.round(maxTextWidth + hPaddingPx * 2);
        setMeasuredDimension(
                resolveSize(width, widthMeasureSpec),
                resolveSize(Math.round(totalHeightPx), heightMeasureSpec));
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        // 不调用 super.onDraw：完全自绘，避免父类滚轮（等高白色数字）叠加造成重影
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        // 上下分割线框出选中的“大行”
        canvas.drawRect(0, cy - selectedRowPx / 2f, getWidth(), cy - selectedRowPx / 2f + dividerPx, dividerPaint);
        canvas.drawRect(0, cy + selectedRowPx / 2f - dividerPx, getWidth(), cy + selectedRowPx / 2f, dividerPaint);

        // 绘制可视范围内的数字行：离中心越远越小越淡
        int centerStep = Math.round(position);
        for (int j = centerStep - 2; j <= centerStep + 2; j++) {
            String text = textForStep(j);
            if (text == null) {
                continue;
            }
            float offsetSteps = position - j; // >0 表示该行在中心上方（数值更大）
            float rowCy = cy - offsetSteps * stepPx;
            if (rowCy < -selectedTextPx || rowCy > getHeight() + selectedTextPx) {
                continue;
            }
            float t = Math.min(1f, Math.abs(offsetSteps) / 0.9f);
            float size = selectedTextPx + (neighborTextPx - selectedTextPx) * t;
            float alpha = 1f - (1f - NEIGHBOR_ALPHA) * t;
            boolean bold = t < 0.5f;

            textPaint.setTextSize(size);
            textPaint.setFakeBoldText(bold);
            textPaint.setAlpha(Math.round(255 * alpha));
            textPaint.setColor(valueColor);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = rowCy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, cx, baseline, textPaint);
        }
        textPaint.setAlpha(255);
        textPaint.setFakeBoldText(false);
        reportValueIfChanged();
    }

    // ---------- 触摸与滚动 ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSnap();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - lastTouchY;
                lastTouchY = event.getY();
                position = clampPosition(position - dy / stepPx);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                velocityTracker.computeCurrentVelocity(1000);
                float velocityY = velocityTracker.getYVelocity();
                velocityTracker.recycle();
                velocityTracker = null;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    snapWithVelocity(velocityY);
                } else {
                    snapTo(Math.round(position));
                }
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** 依据甩动速度决定目标步数并吸附 */
    private void snapWithVelocity(float velocityY) {
        float flingSteps = -velocityY / stepPx * 0.12f;
        int target = Math.round(position + flingSteps);
        snapTo(target);
    }

    /** 平滑吸附到指定步 */
    private void snapTo(int target) {
        float startPos = position;
        float endPos = wrap ? target : Math.max(0f, Math.min(count() - 1f, target));
        if (Math.abs(endPos - startPos) < 0.001f) {
            position = endPos;
            value = valueFromPosition(endPos);
            reportValueIfChanged();
            invalidate();
            return;
        }
        snapAnimator = ValueAnimator.ofFloat(startPos, endPos);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.setDuration((long) Math.min(500, 180 + Math.abs(endPos - startPos) * 60));
        snapAnimator.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        snapAnimator.start();
    }

    private void cancelSnap() {
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
    }

    /** 值发生变化时通知监听器（拖动经过或吸附完成后） */
    private void reportValueIfChanged() {
        int current = getValue();
        if (current != lastReportedValue) {
            int old = lastReportedValue;
            lastReportedValue = current;
            value = current;
            if (listener != null) {
                listener.onValueChange(this, old, current);
            }
        }
    }

    private float sp2px(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }
}
