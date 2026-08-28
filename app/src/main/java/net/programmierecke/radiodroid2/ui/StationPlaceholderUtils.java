package net.programmierecke.radiodroid2.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
        canvas.drawColor(colorForUuid(context, stationUuid));

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
}
