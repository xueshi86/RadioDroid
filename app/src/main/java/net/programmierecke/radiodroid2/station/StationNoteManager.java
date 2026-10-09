package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * 电台自定义备注的独立存储（Issue #54）。
 * 以 stationUuid -> 备注文本 的键值对保存在默认 SharedPreferences 的 station_notes 键下，
 * 不混入电台数据库（数据库恢复会整体替换）也不混入收藏 JSON，
 * 因此导入/导出与 WebDAV 备份都以独立文件 station_notes.json 进行。
 */
public class StationNoteManager {
    private static final String TAG = "StationNoteManager";
    private static final String PREF_KEY = "station_notes";

    // 备注文件的格式版本：{"version":1,"notes":{"<uuid>":"<note>"}}
    private static final int FORMAT_VERSION = 1;

    private final Context context;
    private final JSONObject notes = new JSONObject();

    public StationNoteManager(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    /** 取指定电台的备注；无备注返回 null。 */
    public String getNote(String stationUuid) {
        if (stationUuid == null) return null;
        return notes.optString(stationUuid, null);
    }

    /** 保存备注；文本为空时视为删除该电台的备注。 */
    public void setNote(String stationUuid, String note) {
        if (stationUuid == null) return;
        try {
            if (note == null || note.trim().isEmpty()) {
                notes.remove(stationUuid);
            } else {
                notes.put(stationUuid, note.trim());
            }
        } catch (JSONException e) {
            Log.e(TAG, "setNote failed", e);
            return;
        }
        persist();
    }

    /** 是否没有任何备注。 */
    public boolean isEmpty() {
        return notes.length() == 0;
    }

    /**
     * 返回备注文本包含 query（不区分大小写）的电台 uuid 列表（Issue #54 搜索联动）。
     * 仅遍历内存中的备注映射，供搜索流程在后台线程进一步查库补全结果。
     */
    public java.util.List<String> findUuidsByNoteQuery(String query) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (query == null || query.trim().isEmpty()) return result;
        String q = query.toLowerCase();
        Iterator<String> keys = notes.keys();
        while (keys.hasNext()) {
            String uuid = keys.next();
            String note = notes.optString(uuid, null);
            if (note != null && note.toLowerCase().contains(q)) {
                result.add(uuid);
            }
        }
        return result;
    }

    /** 备注条数。 */
    public int count() {
        return notes.length();
    }

    /**
     * 导出为独立文件的 JSON 内容：{"version":1,"notes":{uuid:note}}。
     * 无备注时导出空对象（格式仍完整，导入端可识别）。
     */
    public String exportToJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            root.put("notes", new JSONObject(notes.toString()));
            return root.toString();
        } catch (JSONException e) {
            Log.e(TAG, "exportToJson failed", e);
            return "{\"version\":" + FORMAT_VERSION + ",\"notes\":{}}";
        }
    }

    /**
     * 从导出格式的 JSON 导入备注，覆盖合并：文件中已有的 uuid 覆盖本地，本地其余备注保留。
     *
     * @return 导入的条数
     * @throws JSONException 文件格式无效
     */
    public int importFromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject imported = root.optJSONObject("notes");
        if (imported == null) {
            // 兼容直接传 {uuid:note} 的裸对象
            imported = root;
        }
        int count = 0;
        Iterator<String> keys = imported.keys();
        while (keys.hasNext()) {
            String uuid = keys.next();
            String note = imported.optString(uuid, null);
            if (uuid == null || uuid.isEmpty() || note == null || note.trim().isEmpty()) continue;
            try {
                notes.put(uuid, note.trim());
                count++;
            } catch (JSONException e) {
                Log.w(TAG, "importFromJson skip invalid entry", e);
            }
        }
        if (count > 0) persist();
        return count;
    }

    private void load() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String str = prefs.getString(PREF_KEY, null);
        if (str == null) return;
        try {
            JSONObject stored = new JSONObject(str);
            // 兼容自身导出格式与裸对象两种存储形态
            JSONObject loaded = stored.optJSONObject("notes");
            if (loaded == null) loaded = stored;
            Iterator<String> keys = loaded.keys();
            while (keys.hasNext()) {
                String uuid = keys.next();
                String note = loaded.optString(uuid, null);
                if (uuid == null || uuid.isEmpty() || note == null || note.trim().isEmpty()) continue;
                notes.put(uuid, note.trim());
            }
        } catch (JSONException e) {
            Log.e(TAG, "load failed, notes reset", e);
        }
    }

    private void persist() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREF_KEY, notes.toString()).apply();
    }
}
