package net.programmierecke.radiodroid2.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * 「电台信息」页 v4.1 圆形频谱环：24 根径向短条沿唱片外圈均布（15° 间隔、
 * 轨道半径 78dp、条 4×18dp），播放时按相位错开的节奏律动——与设计稿 CSS 动画
 * （radial .85s ease-in-out infinite、animation-delay: calc(var(--i) * -0.07s)、
 * scaleY 0.35→1.45）等效：scale = 0.9 - 0.55·cos(2π·(t + i·0.07/0.85))。
 * <p>
 * 停止/暂停时整环 0.3s 淡出、脉冲动画停止，画面只留圆形电台图标
 * （不留基线残刺）；条色由外部按配色方案注入（setColors：条尖亮色 → 条根强调色）。
 */
public class SpectrumRingView extends View {
    private static final int BAR_COUNT = 24;
    private static final float DEGREE_STEP = 360f / BAR_COUNT;   // 15° 间隔均布
    private static final float ORBIT_DP = 78f;                   // 条中心到圆心的轨道半径
    private static final float BAR_WIDTH_DP = 4f;
    private static final float BAR_HEIGHT_DP = 18f;
    private static final float BAR_RADIUS_DP = 2f;
    private static final float SCALE_BASE = 0.9f;                // 呼吸中值（0.35 与 1.45 的中点）
    private static final float SCALE_HALF_AMPLITUDE = 0.55f;     // 呼吸振幅半径
    private static final long PERIOD_MS = 850;                   // 与设计稿 radial 动画周期一致
    private static final float PHASE_STEP = 0.07f / 0.85f;       // 相邻条相位差（周期分数）
    private static final long FADE_MS = 300;                     // 显隐淡入淡出时长

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private ValueAnimator pulseAnimator;
    private float pulseFraction;
    private boolean spectrumVisible = false;

    private int colorTip;
    private int colorBase;
    private Shader barShader;

    public SpectrumRingView(Context context) {
        this(context, null);
    }

    public SpectrumRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // 安全兜底色：外部未注入主题色时仍可见
        colorTip = 0xAAFFFFFF;
        colorBase = 0x88FFFFFF;
    }

    /** 注入主题派生色：tip = 条外尖端（更亮），base = 条内根部（强调色亮变体） */
    public void setColors(int tip, int base) {
        colorTip = tip;
        colorBase = base;
        barShader = null;
        invalidate();
    }

    /**
     * 频谱显隐：播放中淡入并启动脉冲；停止/暂停淡出并停止动画（不落回基线残刺）。
     */
    public void setSpectrumVisible(boolean visible) {
        if (visible == spectrumVisible) {
            return;
        }
        spectrumVisible = visible;
        animate().cancel();
        if (visible) {
            setAlpha(0f);
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(FADE_MS).start();
            startPulse();
        } else {
            animate().alpha(0f).setDuration(FADE_MS).start();
            stopPulse();
        }
    }

    /** 视图销毁时彻底停掉动画并复位隐藏态 */
    public void stop() {
        spectrumVisible = false;
        stopPulse();
        animate().cancel();
        setAlpha(0f);
    }

    private void startPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            return;
        }
        if (pulseAnimator == null) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
            pulseAnimator.setDuration(PERIOD_MS);
            pulseAnimator.setInterpolator(new LinearInterpolator());
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.addUpdateListener(animation -> {
                pulseFraction = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        barShader = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getAlpha() == 0f) {
            return;
        }

        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float orbit = dp(ORBIT_DP);
        final float halfWidth = dp(BAR_WIDTH_DP) / 2f;
        final float halfHeight = dp(BAR_HEIGHT_DP) / 2f;

        if (barShader == null) {
            // 条沿自身长轴渐变：外尖端更亮、内根部强调色；画布旋转后渐变随条一起旋转
            barShader = new LinearGradient(cx, cy - orbit - halfHeight,
                    cx, cy - orbit + halfHeight, colorTip, colorBase, Shader.TileMode.CLAMP);
            barPaint.setShader(barShader);
        }

        final float corner = dp(BAR_RADIUS_DP);
        for (int i = 0; i < BAR_COUNT; i++) {
            // 相位沿圆周流动：条 i 比条 0 超前 i·0.07s（与 CSS 负延迟一致），环上呈双圈波动
            final double phase = 2 * Math.PI * (pulseFraction + i * PHASE_STEP);
            final float scale = SCALE_BASE - SCALE_HALF_AMPLITUDE * (float) Math.cos(phase);

            canvas.save();
            canvas.rotate(i * DEGREE_STEP, cx, cy);
            barRect.set(cx - halfWidth,
                    cy - orbit - halfHeight * scale,
                    cx + halfWidth,
                    cy - orbit + halfHeight * scale);
            canvas.drawRoundRect(barRect, corner, corner, barPaint);
            canvas.restore();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
