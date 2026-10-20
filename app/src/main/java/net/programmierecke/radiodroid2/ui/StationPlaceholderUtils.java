package net.programmierecke.radiodroid2.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.Nullable;

import net.programmierecke.radiodroid2.R;

/**
 * 无图标电台动态占位符：名称关键词/首词 + 按 UUID 哈希稳定分配的 7 色板。
 *
 * 相比早期的"首字符"方案，显示完整关键词（如 "BBC"、"济南新闻"、"Antenne"）
 * 显著提高同首字母电台之间的辨识度；提取规则见 {@link StationWordExtractor}，
 * 提取失败（空名/纯符号）回退 ♪。字号按词长自适应收缩，超长词在可读下限内截断。
 *
 * 图标本体采用「渐变底 + 同心圆环」（D1）：原先的纯色平涂被 Hero 放大模糊后是
 * 均匀色块，整体缩放时边缘位移量为零，呼吸完全看不见；改为同色系对角双色渐变
 * （有明暗过渡、缩放即有内容在位移）叠加极淡同心圆环（高频边界遍布全图，缩放时
 * 每道环都在动），既让呼吸的缩放轴重新可见，也让图标本身不再是「一块色块」。
 * 圆环语义上贴合电波，小尺寸下主要落在四角，不与居中关键词争抢。
 *
 * 所有需要占位符的地方（列表、图标兜底、通知大图标）统一使用本工具，
 * 保证同一电台在任意位置显示一致的"颜色 + 词"，避免多份实现漂移。
 *
 * 生成结果按 (uuid, size, word) 缓存于内存 LruCache，列表滚动零成本。
 */
public class StationPlaceholderUtils {

    private static final String DEFAULT_GLYPH = "\u266A"; // ♪（电台名无法提取关键词时使用）
    private static final int CACHE_MAX = 64;

    /** 文字最大宽度占图标边长比例 */
    private static final float TEXT_MAX_WIDTH_RATIO = 0.84f;
    /** 多字词起始字号占边长比例 */
    private static final float TEXT_START_SIZE_RATIO = 0.42f;
    /** 单字符（含 ♪）起始字号占边长比例 */
    private static final float TEXT_SINGLE_SIZE_RATIO = 0.5f;
    /** 可读字号下限占边长比例 */
    private static final float TEXT_MIN_SIZE_RATIO = 0.16f;

    /** 渐变底：本色向白色混合的比例（左上受光的高光调） */
    private static final float GRADIENT_LIGHT_MIX = 0.38f;
    /** 渐变底：本色向黑色混合的比例（右下入深的暗调） */
    private static final float GRADIENT_DARK_MIX = 0.34f;

    /** 同心圆环圆心（相对边长）：偏左上，语义上像从该点向外扩散的电波 */
    private static final float RING_CENTER_X = 0.28f;
    private static final float RING_CENTER_Y = 0.24f;
    /** 圆环线宽占边长比例 */
    private static final float RING_STROKE_RATIO = 0.030f;
    /** 相邻圆环的半径间距占边长比例 */
    private static final float RING_SPACING_RATIO = 0.115f;
    /** 圆环白色不透明度（约 13%，只在渐变底上留下极淡的纹理） */
    private static final int RING_ALPHA = 0x22;

    private static final int[] COLOR_RES_IDS = {
            R.color.placeholderColor1,
            R.color.placeholderColor2,
            R.color.placeholderColor3,
            R.color.placeholderColor4,
            R.color.placeholderColor5,
            R.color.placeholderColor6,
            R.color.placeholderColor7
    };

    private static final LruCache<String, Bitmap> sBitmapCache = new LruCache<>(CACHE_MAX);

    private StationPlaceholderUtils() {
    }

    /**
     * 按 UUID 哈希稳定分配 7 色板之一（同一 uuid 永远同色）。
     */
    public static int colorForUuid(Context context, @Nullable String uuid) {
        int index = 0;
        if (uuid != null && !uuid.isEmpty()) {
            // floorMod 防止 hashCode()==Integer.MIN_VALUE 时 abs 溢出为负导致数组越界
            index = Math.floorMod(uuid.hashCode(), COLOR_RES_IDS.length);
        }
        return context.getResources().getColor(COLOR_RES_IDS[index]);
    }

    /**
     * 生成占位 Drawable（尺寸 56dp 方形），供 ImageView 直接使用。
     */
    public static Drawable createPlaceholderDrawable(Context context, @Nullable String stationName, @Nullable String stationUuid) {
        float density = context.getResources().getDisplayMetrics().density;
        int sizePx = Math.round(56 * density);
        Bitmap bmp = getPlaceholderBitmap(context, stationName, stationUuid, sizePx);
        return new BitmapDrawable(context.getResources(), bmp);
    }

    /**
     * 生成指定尺寸的占位 Bitmap（如通知大图标 256px）。
     */
    public static Bitmap createPlaceholderBitmap(Context context, @Nullable String stationName, @Nullable String stationUuid, int sizePx) {
        return getPlaceholderBitmap(context, stationName, stationUuid, sizePx);
    }

    private static Bitmap getPlaceholderBitmap(Context context, @Nullable String stationName, @Nullable String stationUuid, int sizePx) {
        if (sizePx <= 0) {
            sizePx = 56;
        }
        String word = StationWordExtractor.extractDisplayWord(stationName);
        String glyph = (word != null && !word.isEmpty()) ? word : DEFAULT_GLYPH;
        String key = (stationUuid != null ? stationUuid : "anon") + "|" + sizePx + "|" + glyph;
        Bitmap cached = sBitmapCache.get(key);
        if (cached != null) {
            return cached;
        }

        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        drawBase(canvas, sizePx, colorForUuid(context, stationUuid));
        drawRings(canvas, sizePx);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        float startSize = sizePx * (glyph.length() == 1 ? TEXT_SINGLE_SIZE_RATIO : TEXT_START_SIZE_RATIO);
        float minSize = sizePx * TEXT_MIN_SIZE_RATIO;
        float maxWidth = sizePx * TEXT_MAX_WIDTH_RATIO;

        // 字号自适应：先按起始字号绘制，超宽则按比例收缩至下限；
        // 仍超宽（极端长词）时在最小字号下从尾部截字直至放下
        float textSize = startSize;
        Rect bounds = new Rect();
        while (true) {
            textPaint.setTextSize(textSize);
            textPaint.getTextBounds(glyph, 0, glyph.length(), bounds);
            if (bounds.width() <= maxWidth) {
                break;
            }
            if (textSize > minSize) {
                textSize = Math.max(minSize, textSize * 0.9f);
            } else if (glyph.length() > 1) {
                glyph = glyph.substring(0, glyph.length() - 1);
            } else {
                break;
            }
        }

        float baseline = sizePx / 2f - (bounds.top + bounds.bottom) / 2f;
        canvas.drawText(glyph, sizePx / 2f, baseline, textPaint);

        sBitmapCache.put(key, bmp);
        return bmp;
    }

    /**
     * ① 渐变底：由本色派生的亮调 → 本色 → 暗调，沿左上到右下铺开。
     * 纯色平涂被 hero 放大模糊后是均匀色块，缩放时像素几乎不变；渐变带来明暗过渡，
     * 缩放时始终有内容在位移，同时也让图标有体积感、不再是一块平色。
     */
    private static void drawBase(Canvas canvas, int sizePx, int baseColor) {
        int light = blendToward(baseColor, Color.WHITE, GRADIENT_LIGHT_MIX);
        int dark = blendToward(baseColor, Color.BLACK, GRADIENT_DARK_MIX);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new LinearGradient(0f, 0f, sizePx, sizePx,
                new int[]{light, baseColor, dark},
                new float[]{0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, sizePx, sizePx, bgPaint);
    }

    /**
     * ② 同心圆环：白色 13% 不透明度，圆心偏左上。这些环提供遍布全图的高频边界，
     * 是缩放位移的「显示器」——呼吸时每一道环都在动，缩放任一轴的可见度都不再依赖
     * 图像内容；半径按边长等比取值，故同一电台在 56dp 列表图与 256px 通知大图上
     * 呈现一致的纹理密度。
     */
    private static void drawRings(Canvas canvas, int sizePx) {
        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(sizePx * RING_STROKE_RATIO);
        ringPaint.setColor((RING_ALPHA << 24) | 0x00FFFFFF);

        final float cx = sizePx * RING_CENTER_X;
        final float cy = sizePx * RING_CENTER_Y;
        final float spacing = sizePx * RING_SPACING_RATIO;
        // 画到对角线远端即可覆盖整张图（相对圆心的最远距离为 sizePx）
        for (float r = spacing; r <= sizePx; r += spacing) {
            canvas.drawCircle(cx, cy, r, ringPaint);
        }
    }

    /** 把颜色按比例朝目标色混合（mix = 0 返回本色，mix = 1 返回目标色）。 */
    private static int blendToward(int color, int target, float mix) {
        final float inv = 1f - mix;
        return Color.rgb(
                Math.round(Color.red(color) * inv + Color.red(target) * mix),
                Math.round(Color.green(color) * inv + Color.green(target) * mix),
                Math.round(Color.blue(color) * inv + Color.blue(target) * mix));
    }
}
