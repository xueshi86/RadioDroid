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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;

/* TODO: Actually have info about recording by storing them in the database and matching with files on disk.
 */
public class RecordingsManager {
    private final static String TAG = "Recordings";

    /** 旧录音目录（公共音乐目录）一次性迁移完成的标记 */
    private final static String PREF_LEGACY_MIGRATION_DONE = "recordings_legacy_migration_done";

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
        return new ArrayList<>(savedRecordings);
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

        savedRecordings.clear();

        File folder = new File(path);
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                DataRecording dr = new DataRecording();
                dr.Name = f.getName();
                dr.Time = new Date(f.lastModified());
                savedRecordings.add(dr);
            }

            Collections.sort(savedRecordings, (o1, o2) -> Long.compare(o2.Time.getTime(), o1.Time.getTime()));
        } else {
            Log.e(TAG, "Could not enumerate files in recordings directory");
        }

        savedRecordingsObservable.notifyObservers();
    }
}
