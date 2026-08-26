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
 * 无图标电台动态占位符：首字符 + 按 UUID 哈希稳定分配的 7 色板。
 *
 * 所有需要占位符的地方（列表、图标兜底、通知大图标）统一使用本工具，
 * 保证同一电台在任意位置显示一致的"颜色 + 字符"，避免多份实现漂移。
 *
 * 生成结果按 (uuid, size) 缓存于内存 LruCache，列表滚动零成本。
 */
public class StationPlaceholderUtils {

    private static final String DEFAULT_GLYPH = "\u266A"; // ♪（电台名无法提取字符时使用）
    private static final int CACHE_MAX = 64;

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
     * 从电台名称提取占位字符：跳过前导空白/符号，取首个字母或数字（大写）或中文首字；
     * 空名/无可提取字符时回退为 ♪。
     */
    public static String firstCharacter(@Nullable String name) {
        if (name == null) {
            return DEFAULT_GLYPH;
        }
        String s = name.trim();
        if (s.isEmpty()) {
            return DEFAULT_GLYPH;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return DEFAULT_GLYPH;
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
        String key = (stationUuid != null ? stationUuid : "anon") + "|" + sizePx;
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
        textPaint.setTextSize(sizePx * 0.5f);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        String glyph = firstCharacter(stationName);
        Rect bounds = new Rect();
        textPaint.getTextBounds(glyph, 0, glyph.length(), bounds);
        float baseline = sizePx / 2f - (bounds.top + bounds.bottom) / 2f;
        canvas.drawText(glyph, sizePx / 2f, baseline, textPaint);

        sBitmapCache.put(key, bmp);
        return bmp;
    }
}
