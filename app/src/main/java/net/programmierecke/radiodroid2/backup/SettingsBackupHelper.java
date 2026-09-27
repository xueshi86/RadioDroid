package net.programmierecke.radiodroid2.backup;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 应用设置（默认 SharedPreferences）与 JSON 备份文件之间的序列化工具。
 *
 * <p>本机「导出/导入设置」与 WebDAV「设置」备份/恢复共用本类，保证两条路径生成与读取的文件
 * 格式完全一致。导出内容为除运行时/临时状态外的全部用户设置；每个键以
 * {@code {"type":..., "value":...}} 形式显式标注类型，避免 JSON 数字在 int/long/float 之间
 * 以及 String 与 StringSet 之间的歧义。</p>
 */
public final class SettingsBackupHelper {

    public static final int FORMAT_VERSION = 1;

    /** JSON 备份文件的扩展名。 */
    public static final String FILE_EXTENSION = "json";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * 运行时/临时状态键：不参与导出，导入时也跳过，避免把过期状态带到新设备。
     *
     * <p>分为三组：本地数据库状态缓存（由数据库重新计算）、增量更新水位（与本地库状态绑定）、
     * 其它纯运行时状态（导航选中项、更新检查节流时间、录音序号计数器）。</p>
     */
    private static final Set<String> EXCLUDED_KEYS = new HashSet<>(Arrays.asList(
            // 本地数据库状态缓存
            "local_database_last_status",
            "local_database_last_error",
            "local_database_last_update",
            "local_database_station_count",
            "database_status_summary",
            // 增量更新水位（与本地数据库状态绑定，恢复设置时不应带入）
            "incremental_lastchange_time",
            // 纯运行时状态
            "last_selectedMenuItem",
            "auto_check_update_last_time",
            "record_num"
    ));

    private SettingsBackupHelper() {
    }

    /** 统计可导出的设置项数量（用于生成默认文件名）。 */
    public static int countExportableKeys(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        int count = 0;
        for (String key : preferences.getAll().keySet()) {
            if (!EXCLUDED_KEYS.contains(key)) count++;
        }
        return count;
    }

    /**
     * 把全部应用设置导出为 JSON 写入 {@code out}。
     *
     * @return 实际导出的键数量
     */
    public static int export(Context context, OutputStream out) throws IOException {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        Map<String, ?> all = preferences.getAll();
        JSONObject settings = new JSONObject();
        int count = 0;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (EXCLUDED_KEYS.contains(key)) continue;
            Object value = entry.getValue();
            JSONObject item = new JSONObject();
            try {
                if (value instanceof Boolean) {
                    item.put("type", "boolean");
                    item.put("value", value);
                } else if (value instanceof Integer) {
                    item.put("type", "int");
                    item.put("value", value);
                } else if (value instanceof Long) {
                    item.put("type", "long");
                    item.put("value", value);
                } else if (value instanceof Float) {
                    item.put("type", "float");
                    item.put("value", value);
                } else if (value instanceof String) {
                    item.put("type", "string");
                    item.put("value", value);
                } else if (value instanceof Set) {
                    item.put("type", "stringset");
                    JSONArray array = new JSONArray();
                    for (Object element : (Set<?>) value) {
                        array.put(String.valueOf(element));
                    }
                    item.put("value", array);
                } else {
                    continue;
                }
                settings.put(key, item);
                count++;
            } catch (JSONException e) {
                // 单个键序列化失败不应中断整体导出
            }
        }

        JSONObject root = new JSONObject();
        try {
            root.put("version", FORMAT_VERSION);
            root.put("exported_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
            root.put("settings", settings);
        } catch (JSONException e) {
            throw new IOException("Unable to build settings JSON", e);
        }

        try {
            out.write(root.toString(2).getBytes(UTF8));
        } catch (JSONException e) {
            throw new IOException("Unable to serialize settings JSON", e);
        }
        out.flush();
        return count;
    }

    /**
     * 从 JSON 输入流导入设置并同步写回默认 SharedPreferences。
     *
     * @return 实际写回的键数量
     */
    public static int importFrom(Context context, InputStream in) throws IOException {
        JSONObject root;
        try {
            root = new JSONObject(readAll(in));
        } catch (JSONException e) {
            throw new IOException("Invalid settings file", e);
        }
        int version = root.optInt("version", -1);
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported settings file version: " + version);
        }
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) {
            throw new IOException("Missing settings section");
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        SharedPreferences.Editor editor = preferences.edit();
        int count = 0;
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (EXCLUDED_KEYS.contains(key)) continue;
            JSONObject item = settings.optJSONObject(key);
            if (item == null) continue;
            String type = item.optString("type", "");
            try {
                switch (type) {
                    case "boolean":
                        editor.putBoolean(key, item.getBoolean("value"));
                        break;
                    case "int":
                        editor.putInt(key, item.getInt("value"));
                        break;
                    case "long":
                        editor.putLong(key, item.getLong("value"));
                        break;
                    case "float":
                        editor.putFloat(key, (float) item.getDouble("value"));
                        break;
                    case "string":
                        editor.putString(key, item.getString("value"));
                        break;
                    case "stringset": {
                        JSONArray array = item.optJSONArray("value");
                        Set<String> set = new HashSet<>();
                        if (array != null) {
                            for (int i = 0; i < array.length(); i++) {
                                set.add(array.getString(i));
                            }
                        }
                        editor.putStringSet(key, set);
                        break;
                    }
                    default:
                        continue;
                }
                count++;
            } catch (JSONException e) {
                // 跳过单个格式损坏的条目，尽量完成整体导入
            }
        }
        editor.commit();
        return count;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }
}