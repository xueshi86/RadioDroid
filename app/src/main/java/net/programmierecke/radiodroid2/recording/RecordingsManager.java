package net.programmierecke.radiodroid2.recording;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.database.RadioDroidDatabase;
import net.programmierecke.radiodroid2.database.RadioStation;
import net.programmierecke.radiodroid2.history.TrackHistoryEntry;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* TODO: Actually have info about recording by storing them in the database and matching with files on disk.
 */
public class RecordingsManager {
    private final static String TAG = "Recordings";

    /** 旧录音目录（公共音乐目录）一次性迁移完成的标记 */
    private final static String PREF_LEGACY_MIGRATION_DONE = "recordings_legacy_migration_done";

    /** 录音文件名 -> 所属电台（UUID/名称）的持久化映射，用于录音列表行首图标 */
    private final static String PREF_RECORDING_STATION_META = "recording_station_meta";

    /** 录音文件名中的录制起始时间（默认命名格式含 yyyy-MM-dd_HH-mm） */
    private static final Pattern RECORDING_DATE_TIME_PATTERN =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})");

    /** 已解析的录音所属电台信息（含图标地址） */
    private static class StationInfo {
        final String uuid;
        final String name;
        final String iconUrl;
        final String homePageUrl;

        StationInfo(String uuid, String name, String iconUrl, String homePageUrl) {
            this.uuid = uuid;
            this.name = name;
            this.iconUrl = iconUrl;
            this.homePageUrl = homePageUrl;
        }
    }

    private final Object stationLock = new Object();
    /** 文件名 -> 电台信息；避免列表每次刷新都查库 */
    private final Map<String, StationInfo> stationInfoCache = new HashMap<>();
    /** 已确认无法反查所属电台的文件名，避免重复查库 */
    private final Set<String> failedStationLookup = new HashSet<>();
    private boolean stationResolutionRunning = false;

    private final Context appContext;

    private DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private DateFormat timeFormatter = new SimpleDateFormat("HH-mm", Locale.US);

    public RecordingsManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    private class RecordingsObservable extends Observable {
        @Override
        public synchronized boolean hasChanged() {
            return true;
        }
    }

    private Observable savedRecordingsObservable = new RecordingsObservable();

    private class RunningRecordableListener implements RecordableListener {
        private RunningRecordingInfo runningRecordingInfo;
        private Context context;
        private boolean ended;
        private long lastFlushBytes;

        private RunningRecordableListener(@NonNull RunningRecordingInfo runningRecordingInfo, @NonNull Context context) {
            this.runningRecordingInfo = runningRecordingInfo;
            this.context = context.getApplicationContext();
        }

        @Override
        public void onBytesAvailable(byte[] buffer, int offset, int length) {
            try {
                runningRecordingInfo.getOutputStream().write(buffer, offset, length);
                runningRecordingInfo.setBytesWritten(runningRecordingInfo.getBytesWritten() + length);

                if (runningRecordingInfo.getBytesWritten() - lastFlushBytes >= 65536) {
                    runningRecordingInfo.getOutputStream().flush();
                    lastFlushBytes = runningRecordingInfo.getBytesWritten();
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Recording flushed, total bytes: " + runningRecordingInfo.getBytesWritten());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error writing recording bytes: " + e.getMessage(), e);
                showRecordingMessage(context, R.string.recording_write_failed, Toast.LENGTH_LONG);
                runningRecordingInfo.getRecordable().stopRecording();
            }
        }

        @Override
        public void onRecordingEnded() {
            if (ended) {
                return;
            }

            ended = true;

            try {
                runningRecordingInfo.getOutputStream().flush();
                runningRecordingInfo.getOutputStream().getFD().sync();
            } catch (IOException e) {
                Log.e(TAG, "Error flushing recording: " + e.getMessage());
            }

            try {
                runningRecordingInfo.getOutputStream().close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing recording: " + e.getMessage());
            }

            RecordingsManager.this.stopRecording(runningRecordingInfo.getRecordable());
        }
    }

    private Map<Recordable, RunningRecordingInfo> runningRecordings = new HashMap<>();
    private ArrayList<DataRecording> savedRecordings = new ArrayList<>();

    private void showRecordingMessage(@NonNull Context context, int messageId, int duration) {
        Context applicationContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(applicationContext, messageId, duration).show());
    }

    public void record(@NonNull Context context, @NonNull Recordable recordable) {
        if (!recordable.canRecord()) {
            Log.w(TAG, "Cannot record: canRecord() returned false");
            showRecordingMessage(context, R.string.recording_not_available, Toast.LENGTH_SHORT);
            return;
        }

        if (!runningRecordings.containsKey(recordable)) {
            Log.d(TAG, "Starting new recording...");
            RunningRecordingInfo info = new RunningRecordingInfo();

            info.setRecordable(recordable);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

            final String fileNameFormat = prefs.getString("record_name_formatting", context.getString(R.string.settings_record_name_formatting_default));

            final Map<String, String> formattingArgs = new HashMap<>(recordable.getRecordNameFormattingArgs());

            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());

            final Date currentTime = calendar.getTime();

            String dateStr = dateFormatter.format(currentTime);
            String timeStr = timeFormatter.format(currentTime);

            formattingArgs.put("date", dateStr);
            formattingArgs.put("time", timeStr);

            final int recordNum = prefs.getInt("record_num", 1);
            formattingArgs.put("index", Integer.toString(recordNum));

            String recordTitle = Utils.formatStringWithNamedArgs(fileNameFormat, formattingArgs);
            // 兜底限长（UTF-8 字节）：存储层限制单个文件名组件 ≤255 字节，默认格式
            // 已在 RadioPlayer 分组件限长（≈189 字节），但用户自定义格式（如多个
            // 长参数连拼）仍可能超限——超限时文件 open 失败（ENOENT），表现为
            // 「无法开始录音」。此处统一钳到 200 字节，为重名后缀 _N 与扩展名留空间
            recordTitle = Utils.truncateUtf8Bytes(recordTitle, 200);

            info.setTitle(recordTitle);
            info.setFileName(String.format("%s.%s", recordTitle, recordable.getExtension()));

            //TODO: check available disk space here

            // 文件名中的 {time} 仅精确到分钟：同一分钟内重复录制同曲目会产生同名文件，
            // FileOutputStream 会静默截断覆盖旧录音，导致录音列表条目数不增反丢。
            // 检测到同名文件时在扩展名前追加序号（_1、_2…）保证每次录音独立成文件。
            File recordFile = new File(getRecordDir(), info.getFileName());
            if (recordFile.exists()) {
                final String rawName = info.getFileName();
                final int dotIndex = rawName.lastIndexOf('.');
                final String stem = dotIndex >= 0 ? rawName.substring(0, dotIndex) : rawName;
                final String extension = dotIndex >= 0 ? rawName.substring(dotIndex) : "";
                int suffix = 1;
                do {
                    recordFile = new File(getRecordDir(), String.format("%s_%d%s", stem, suffix, extension));
                    suffix++;
                } while (recordFile.exists());
                info.setFileName(recordFile.getName());
            }

            String filePath = recordFile.getPath();
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Starting recording to: " + filePath);
            }
            try {
                info.setOutputStream(new FileOutputStream(filePath));
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Could not open recording file for writing: " + filePath, e);
                showRecordingMessage(context, R.string.recording_start_failed, Toast.LENGTH_LONG);
                return;
            }

            // 文件已成功创建后再记录所属电台，供录音列表行首显示对应电台图标。
            // 录音文件本身不含电台身份，且文件名中的中文台名会被清理为空，
            // 因此必须在录制时单独持久化，不能靠文件名反解析。
            saveStationMeta(info.getFileName(), recordable.getStationUuid(), recordable.getStationName());

            recordable.startRecording(new RunningRecordableListener(info, context));

            runningRecordings.put(recordable, info);
            Log.d(TAG, "Recording started successfully: " + info.getFileName());

            prefs.edit().putInt("record_num", recordNum + 1).apply();
        }
    }

    public void stopRecording(@NonNull Recordable recordable) {
        recordable.stopRecording();

        runningRecordings.remove(recordable);

        updateRecordingsList();
    }

    public RunningRecordingInfo getRecordingInfo(Recordable recordable) {
        return runningRecordings.get(recordable);
    }

    public Map<Recordable, RunningRecordingInfo> getRunningRecordings() {
        return Collections.unmodifiableMap(runningRecordings);
    }

    public List<DataRecording> getSavedRecordings() {
        synchronized (savedRecordings) {
            return new ArrayList<>(savedRecordings);
        }
    }

    public Observable getSavedRecordingsObservable() {
        return savedRecordingsObservable;
    }

    public String getRecordDir() {
        File dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) {
            // 外部存储不可用时的兜底，保证录音仍可写入
            dir = appContext.getFilesDir();
        }
        File folder = new File(dir, "Recordings");
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                Log.e(TAG, "could not create dir:" + folder.getAbsolutePath());
            }
        }
        return folder.getAbsolutePath();
    }

    /**
     * 将旧版公共音乐目录（Music/Recordings）中的录音一次性迁移到应用专属目录。
     * 受分区存储限制时旧文件可能无法读取或移动，此时保持原样、不阻断流程。
     * 通过 SharedPreferences 标记保证只执行一次。
     */
    public void migrateLegacyRecordings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        if (prefs.getBoolean(PREF_LEGACY_MIGRATION_DONE, false)) {
            return;
        }

        try {
            File legacyDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings");
            File newDir = new File(getRecordDir());

            if (legacyDir.exists() && !legacyDir.getAbsolutePath().equals(newDir.getAbsolutePath())) {
                File[] files = legacyDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile()) {
                            continue;
                        }
                        File target = new File(newDir, f.getName());
                        if (target.exists()) {
                            continue;
                        }
                        if (f.renameTo(target)) {
                            Log.i(TAG, "Migrated recording: " + f.getName());
                        } else {
                            Log.w(TAG, "Could not migrate recording, left in place: " + f.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Legacy recordings migration failed: " + e.getMessage(), e);
        }

        prefs.edit().putBoolean(PREF_LEGACY_MIGRATION_DONE, true).apply();
    }

    /**
     * 将指定录音拷贝到用户通过系统文件选择器（SAF）指定的位置。
     * 拷贝在后台线程执行，避免大文件阻塞界面。
     */
    public void exportRecordingToUri(@NonNull Context context, @NonNull DataRecording recording, @NonNull Uri targetUri) {
        File source = new File(getRecordDir(), recording.Name);
        if (!source.exists()) {
            showRecordingMessage(context, R.string.recording_file_not_found, Toast.LENGTH_SHORT);
            return;
        }

        new Thread(() -> {
            boolean success = false;
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = context.getContentResolver().openOutputStream(targetUri)) {
                if (out != null) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    success = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to export recording " + recording.Name + ": " + e.getMessage(), e);
            }

            showRecordingMessage(context,
                    success ? R.string.recording_export_success : R.string.recording_export_failed,
                    success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG);
        }).start();
    }

    /**
     * API 19 以下没有系统文件选择器（SAF）时的导出兜底：
     * 将录音拷贝到公共音乐目录，使文件管理器可见。
     */
    public void exportRecordingToPublicDir(@NonNull Context context, @NonNull DataRecording recording) {
        File source = new File(getRecordDir(), recording.Name);
        if (!source.exists()) {
            showRecordingMessage(context, R.string.recording_file_not_found, Toast.LENGTH_SHORT);
            return;
        }

        File targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings");
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Log.e(TAG, "Could not create export dir: " + targetDir.getAbsolutePath());
        }
        File target = new File(targetDir, recording.Name);

        new Thread(() -> {
            boolean success = false;
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                success = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to export recording " + recording.Name + ": " + e.getMessage(), e);
            }

            if (success) {
                showRecordingMessage(context, R.string.recording_export_success, Toast.LENGTH_SHORT);
            } else {
                showRecordingMessage(context, R.string.recording_export_failed, Toast.LENGTH_LONG);
            }
        }).start();
    }

    public void updateRecordingsList() {
        String path = getRecordDir();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Updating recordings from " + path);
        }

        File folder = new File(path);
        File[] files = folder.listFiles();
        if (files != null) {
            JSONObject persistedMeta = readStationMeta();
            List<DataRecording> newRecordings = new ArrayList<>();

            for (File f : files) {
                DataRecording dr = new DataRecording();
                dr.Name = f.getName();
                dr.Time = new Date(f.lastModified());

                // 先取录制时持久化的电台身份（仅 UUID/名称，图标地址下面再查库补全）
                JSONObject meta = persistedMeta.optJSONObject(dr.Name);
                if (meta != null) {
                    dr.StationUuid = meta.optString("uuid", "");
                    dr.StationName = meta.optString("name", "");
                }

                // 已解析过的电台信息直接复用，避免滚动/刷新反复查库
                synchronized (stationLock) {
                    StationInfo info = stationInfoCache.get(dr.Name);
                    if (info != null) {
                        applyStationInfo(dr, info);
                    }
                }

                newRecordings.add(dr);
            }

            Collections.sort(newRecordings, (o1, o2) -> Long.compare(o2.Time.getTime(), o1.Time.getTime()));
            synchronized (savedRecordings) {
                savedRecordings.clear();
                savedRecordings.addAll(newRecordings);
            }
            pruneStationMeta();
        } else {
            synchronized (savedRecordings) {
                savedRecordings.clear();
            }
            Log.e(TAG, "Could not enumerate files in recordings directory");
        }

        savedRecordingsObservable.notifyObservers();
        scheduleStationInfoResolution();
    }

    private void applyStationInfo(DataRecording dr, StationInfo info) {
        dr.StationUuid = info.uuid;
        dr.StationName = info.name;
        dr.StationIconUrl = info.iconUrl;
        dr.StationHomePageUrl = info.homePageUrl;
    }

    /**
     * 为缺少所属电台信息的录音补全电台（UUID、名称、图标地址）。
     * 旧录音文件名不含电台身份（中文台名在文件名中会被清理为空），
     * 因此按录制起始时间在曲目历史中反查当时正在播放的电台。
     * 整个过程在后台线程执行，结果回主线程刷新列表。
     */
    private void scheduleStationInfoResolution() {
        final List<DataRecording> snapshot;
        synchronized (savedRecordings) {
            snapshot = new ArrayList<>(savedRecordings);
        }

        synchronized (stationLock) {
            if (stationResolutionRunning) {
                return;
            }
            boolean needResolve = false;
            for (DataRecording dr : snapshot) {
                if (needsStationInfo(dr.Name)) {
                    needResolve = true;
                    break;
                }
            }
            if (!needResolve) {
                return;
            }
            stationResolutionRunning = true;
        }

        new Thread(() -> {
            boolean changed = false;
            boolean failedWithError = false;
            try {
                RadioDroidDatabase db = RadioDroidDatabase.getDatabase(appContext);
                for (DataRecording dr : snapshot) {
                    synchronized (stationLock) {
                        if (!needsStationInfo(dr.Name)) {
                            continue;
                        }
                    }

                    StationInfo info = resolveStationInfo(db, dr);
                    synchronized (stationLock) {
                        if (info != null) {
                            stationInfoCache.put(dr.Name, info);
                        } else {
                            failedStationLookup.add(dr.Name);
                        }
                    }
                    if (info != null) {
                        changed = true;
                    }
                }
            } catch (Exception e) {
                failedWithError = true;
                Log.e(TAG, "Failed to resolve recording station: " + e.getMessage(), e);
            } finally {
                synchronized (stationLock) {
                    stationResolutionRunning = false;
                }
            }

            final boolean anyResolved = changed;
            final boolean canReschedule = !failedWithError;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (anyResolved) {
                    synchronized (savedRecordings) {
                        synchronized (stationLock) {
                            for (DataRecording dr : savedRecordings) {
                                StationInfo info = stationInfoCache.get(dr.Name);
                                if (info != null) {
                                    applyStationInfo(dr, info);
                                }
                            }
                        }
                    }
                    savedRecordingsObservable.notifyObservers();
                }
                // 解析期间列表可能新增了录音，补一次调度保证全部补全；
                // 查询异常时不再重试，避免数据库不可用时反复起线程
                if (canReschedule) {
                    scheduleStationInfoResolution();
                }
            });
        }, "RecordingStationResolver").start();
    }

    private boolean needsStationInfo(String fileName) {
        return fileName != null
                && !stationInfoCache.containsKey(fileName)
                && !failedStationLookup.contains(fileName);
    }

    private StationInfo resolveStationInfo(RadioDroidDatabase db, DataRecording dr) {
        String uuid = dr.StationUuid;
        String name = dr.StationName;
        String iconUrl = dr.StationIconUrl;
        String homePageUrl = dr.StationHomePageUrl;

        boolean uuidFromHistory = false;
        if (isEmpty(uuid)) {
            TrackHistoryEntry entry = db.songHistoryDao().getHistoryCoveringTime(recordingStartTime(dr));
            if (entry != null && !isEmpty(entry.stationUuid)) {
                uuid = entry.stationUuid;
                uuidFromHistory = true;
            }
        }

        if (isEmpty(uuid)) {
            // 完全没有归属线索时尝试按文件名里的电台名反查
            RadioStation byName = findStationByFileName(db, dr);
            if (byName == null) {
                return null;
            }
            uuid = byName.stationUuid;
            name = byName.name;
            iconUrl = byName.favicon;
            homePageUrl = byName.homepage;
            return new StationInfo(uuid, name, iconUrl, homePageUrl);
        }

        RadioStation station = db.radioStationDao().getStationById(uuid);

        // 时间匹配可能因曲目历史未及时记录换台而得到错误电台：
        // 用文件名里的电台名做一致性校验，不符时按文件名重新定位，仍找不到就宁可不显示图标。
        if (uuidFromHistory && (station == null || !isStationNameConsistent(station, dr))) {
            RadioStation byName = findStationByFileName(db, dr);
            if (byName == null) {
                return null;
            }
            station = byName;
            uuid = byName.stationUuid;
            name = byName.name;
            iconUrl = byName.favicon;
            homePageUrl = byName.homepage;
        }

        if (station != null) {
            if (isEmpty(name)) {
                name = station.name;
            }
            if (isEmpty(iconUrl)) {
                iconUrl = station.favicon;
            }
            if (isEmpty(homePageUrl)) {
                homePageUrl = station.homepage;
            }
        }

        return new StationInfo(uuid, name, iconUrl, homePageUrl);
    }

    /**
     * 电台名称经文件名清理后是否与录音文件名开头一致。
     * 中文台名清理后为空，此时无法校验，保留时间匹配结果。
     */
    private boolean isStationNameConsistent(RadioStation station, DataRecording dr) {
        if (dr.Name == null) {
            return false;
        }
        String sanitized = Utils.sanitizeName(station.name, 70);
        return sanitized.isEmpty() || dr.Name.startsWith(sanitized);
    }

    /**
     * 按文件名开头的电台名片段在电台库中反查，取清理后名称与文件名前缀匹配最长的一个。
     * 文件名格式不含电台名（如以序号/日期开头或中文台名被清理为空）时返回 null。
     */
    private RadioStation findStationByFileName(RadioDroidDatabase db, DataRecording dr) {
        if (dr.Name == null) {
            return null;
        }

        int separator = dr.Name.indexOf('_');
        if (separator < 2) {
            return null;
        }

        String prefixToken = dr.Name.substring(0, separator);
        if (prefixToken.matches("\\d+")) {
            return null;
        }

        RadioStation best = null;
        int bestLength = 0;
        for (RadioStation station : db.radioStationDao().getStationsByRawNamePrefix(prefixToken)) {
            String sanitized = Utils.sanitizeName(station.name, 70);
            if (!sanitized.isEmpty() && dr.Name.startsWith(sanitized) && sanitized.length() > bestLength) {
                best = station;
                bestLength = sanitized.length();
            }
        }
        return best;
    }

    /**
     * 录音起始时间：优先从文件名中的 yyyy-MM-dd_HH-mm 解析（录制时生成），
     * 命名格式不含时间时退化为文件修改时间（记录结束时刻，通常仍在同一曲目历史区间内）。
     */
    private Date recordingStartTime(DataRecording dr) {
        if (dr.Name != null) {
            Matcher matcher = RECORDING_DATE_TIME_PATTERN.matcher(dr.Name);
            if (matcher.find()) {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US);
                formatter.setLenient(false);
                try {
                    return formatter.parse(matcher.group());
                } catch (Exception ignored) {
                    // 解析失败时使用文件时间兜底
                }
            }
        }
        return dr.Time != null ? dr.Time : new Date();
    }

    private boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private void saveStationMeta(String fileName, String stationUuid, String stationName) {
        if (isEmpty(fileName)) {
            return;
        }

        synchronized (stationLock) {
            // 同名文件可能被复用（删除后重新录制），先清掉旧解析结果
            stationInfoCache.remove(fileName);
            failedStationLookup.remove(fileName);

            JSONObject all = readStationMetaLocked();
            JSONObject item = new JSONObject();
            try {
                item.put("uuid", stationUuid == null ? "" : stationUuid);
                item.put("name", stationName == null ? "" : stationName);
                all.put(fileName, item);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to store recording station meta: " + e.getMessage());
                return;
            }
            PreferenceManager.getDefaultSharedPreferences(appContext)
                    .edit().putString(PREF_RECORDING_STATION_META, all.toString()).apply();
        }
    }

    /**
     * 删除录音时一并清理其电台映射，避免录音目录无限增长后元数据残留。
     */
    public void removeRecordingStationInfo(String fileName) {
        if (isEmpty(fileName)) {
            return;
        }

        synchronized (stationLock) {
            stationInfoCache.remove(fileName);
            failedStationLookup.remove(fileName);

            JSONObject all = readStationMetaLocked();
            if (all.has(fileName)) {
                all.remove(fileName);
                PreferenceManager.getDefaultSharedPreferences(appContext)
                        .edit().putString(PREF_RECORDING_STATION_META, all.toString()).apply();
            }
        }
    }

    private JSONObject readStationMeta() {
        synchronized (stationLock) {
            return readStationMetaLocked();
        }
    }

    private JSONObject readStationMetaLocked() {
        String raw = PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(PREF_RECORDING_STATION_META, "{}");
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * 清理已不存在的录音文件对应的电台映射，防止偏好设置无限累积。
     * 以文件是否真实存在为准，避免与「刚创建文件并保存映射」并发时误删。
     */
    private void pruneStationMeta() {
        synchronized (stationLock) {
            JSONObject all = readStationMetaLocked();
            List<String> stale = new ArrayList<>();
            File recordDir = new File(getRecordDir());
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!new File(recordDir, key).exists()) {
                    stale.add(key);
                }
            }
            if (!stale.isEmpty()) {
                for (String key : stale) {
                    all.remove(key);
                }
                PreferenceManager.getDefaultSharedPreferences(appContext)
                        .edit().putString(PREF_RECORDING_STATION_META, all.toString()).apply();
            }
        }
    }
}
