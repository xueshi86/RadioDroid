package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;
import java.util.Vector;

import info.debatty.java.stringsimilarity.Cosine;
import okhttp3.OkHttpClient;

public class StationSaveManager extends Observable {
    protected interface StationStatusListener {
        void onStationStatusChanged(DataRadioStation station, boolean favourite);
    }

    Context context;
    List<DataRadioStation> listStations = new ArrayList<DataRadioStation>();

    protected StationStatusListener stationStatusListener;
    private List<StationUpdateListener> updateListeners = new ArrayList<>();

    public StationSaveManager(Context ctx) {
        this.context = ctx;
        Load();
    }

    protected String getSaveId() {
        return "default";
    }
    
    // 获取默认文件名，使用数据库更新时间
    private String getDefaultFileName() {
        try {
            RadioStationRepository repository = RadioStationRepository.getInstance(context);
            long updateTime = repository.getDatabaseUpdateTime();
            
            if (updateTime > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                Date date = new Date(updateTime);
                return "RadioDroid_" + sdf.format(date) + ".m3u";
            } else {
                // 如果没有更新时间，使用当前时间
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                Date date = new Date();
                return "RadioDroid_" + sdf.format(date) + ".m3u";
            }
        } catch (Exception e) {
            Log.e("SAVE", "Error getting default filename: " + e.toString());
            // 出错时使用当前时间
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            Date date = new Date();
            return "RadioDroid_" + sdf.format(date) + ".m3u";
        }
    }

    protected void setStationStatusListener(StationStatusListener stationStatusListener) {
        this.stationStatusListener = stationStatusListener;
    }

    public void add(DataRadioStation station) {
        if (station.queue == null)
            station.queue = this;
        listStations.add(station);
        Save();

        notifyAllListeners();

        if (stationStatusListener != null) {
            stationStatusListener.onStationStatusChanged(station, true);
        }
    }

    public void addMultiple(List<DataRadioStation> stations) {
        // 防御空列表：避免误导入空 M3U 时静默清空已有数据
        if (stations == null || stations.isEmpty()) {
            Log.w("SAVE", "addMultiple called with empty list, ignoring to protect existing data");
            notifyAllListeners();
            return;
        }

        // 清空现有列表，实现覆盖式导入
        listStations.clear();

        // 添加新导入的电台，同时按 UUID 去重，避免 M3U 内部重复导致列表重复
        for (DataRadioStation station_new: stations){
            if (!has(station_new.StationUuid)) {
                // 与 add()/addFront()/addAll() 保持一致：设置 queue 字段，
                // 否则 PlayerService 调用 getNextById/getPreviousById 时会 NPE
                if (station_new.queue == null) {
                    station_new.queue = this;
                }
                listStations.add(station_new);
            }
        }
        Save();

        notifyAllListeners();
    }

    public void replaceList(List<DataRadioStation> stations_new) {
        for (DataRadioStation station_new: stations_new) {
            // 设置 queue 字段，避免 PlayerService 调用 getNextById/getPreviousById 时 NPE
            if (station_new.queue == null) {
                station_new.queue = this;
            }
            for (int i = 0; i < listStations.size(); i++) {
                if (listStations.get(i).StationUuid.equals(station_new.StationUuid)){
                    listStations.set(i, station_new);
                    break;
                }
            }
        }
        Save();

        notifyAllListeners();
    }

    public void addFront(DataRadioStation station) {
        if (station.queue == null)
            station.queue = this;
        listStations.add(0, station);
        Save();

        notifyAllListeners();

        if (stationStatusListener != null) {
            stationStatusListener.onStationStatusChanged(station, true);
        }
    }

        public void addAll(List<DataRadioStation> stations) {
        if (stations == null)
            return;
        for (DataRadioStation station : stations) {
            station.queue = this;
        }
        listStations.addAll(stations);
    }
    
    public DataRadioStation getLast() {
        if (!listStations.isEmpty()) {
            return listStations.get(listStations.size() - 1);
        }

        return null;
    }

    public DataRadioStation getFirst() {
        if (!listStations.isEmpty()) {
            return listStations.get(0);
        }

        return null;
    }

    public DataRadioStation getById(String id) {
        for (DataRadioStation station : listStations) {
            if (id.equals(station.StationUuid)) {
                return station;
            }
        }
        return null;
    }

    public DataRadioStation getNextById(String id) {
        if (listStations.isEmpty())
            return null;

        for (int i = 0; i < listStations.size() - 1; i++) {
            if (listStations.get(i).StationUuid.equals(id)) {
                return listStations.get(i + 1);
            }
        }
        return listStations.get(0);
    }

    public DataRadioStation getPreviousById(String id) {
        if (listStations.isEmpty())
            return null;

        for (int i = 1; i < listStations.size(); i++) {
            if (listStations.get(i).StationUuid.equals(id)) {
                return listStations.get(i - 1);
            }
        }
        return listStations.get(listStations.size() - 1);
    }

    public void moveWithoutNotify(int fromPos, int toPos) {
        Collections.rotate(listStations.subList(Math.min(fromPos, toPos), Math.max(fromPos, toPos) + 1), Integer.signum(fromPos - toPos));
    }

    public void move(int fromPos, int toPos) {
        moveWithoutNotify(fromPos, toPos);
        notifyAllListeners();
    }

    public @Nullable
    DataRadioStation getBestNameMatch(String query) {
        DataRadioStation bestStation = null;
        query = query.toUpperCase();
        double smallesDistance = Double.MAX_VALUE;

        Cosine distMeasure = new Cosine(); // must be in the loop for some measures (e.g. Sift4)
        for (DataRadioStation station : listStations) {
            double distance = distMeasure.distance(station.Name.toUpperCase(), query);
            if (distance < smallesDistance) {
                bestStation = station;
                smallesDistance = distance;
            }
        }

        return bestStation;
    }

    public int remove(String id) {
        for (int i = 0; i < listStations.size(); i++) {
            DataRadioStation station = listStations.get(i);
            if (station.StationUuid.equals(id)) {
                listStations.remove(i);
                Save();
                notifyAllListeners();

                if (stationStatusListener != null) {
                    stationStatusListener.onStationStatusChanged(station, false);
                }

                return i;
            }
        }

        return -1;
    }

    public void restore(DataRadioStation station, int pos) {
        station.queue = this;
        listStations.add(pos, station);
        Save();

        notifyAllListeners();

        if (stationStatusListener != null) {
            stationStatusListener.onStationStatusChanged(station, false);
        }
    }

    public void clear() {
        List<DataRadioStation> oldStation = listStations;
        listStations = new ArrayList<>();
        Save();

        notifyAllListeners();

        if (stationStatusListener != null) {
            for (DataRadioStation station : oldStation) {
                stationStatusListener.onStationStatusChanged(station, false);
            }
        }
    }

    @Override
    public boolean hasChanged() {
        return true;
    }

    /**
     * 通知所有观察者和监听器
     */
    private void notifyAllListeners() {
        // 通知传统的Observer
        notifyObservers();
        // 通知新的StationUpdateListener
        for (StationUpdateListener listener : updateListeners) {
            listener.onStationListUpdated();
        }
    }

    public int size() {
        return listStations.size();
    }

    public boolean isEmpty() {
        return listStations.size() == 0;
    }

    public boolean has(String id) {
        DataRadioStation station = getById(id);
        return station != null;
    }

    /**
     * 添加电台更新监听器
     * @param listener 监听器
     */
    public void addStationUpdateListener(StationUpdateListener listener) {
        if (!updateListeners.contains(listener)) {
            updateListeners.add(listener);
        }
    }

    /**
     * 移除电台更新监听器
     * @param listener 监听器
     */
    public void removeStationUpdateListener(StationUpdateListener listener) {
        updateListeners.remove(listener);
    }

    private boolean hasInvalidUuids() {
        for (DataRadioStation station : listStations) {
            if (!station.hasValidUuid()) {
                return true;
            }
        }

        return false;
    }

    public List<DataRadioStation> getList() {
        return Collections.unmodifiableList(listStations);
    }

    private void refreshStationsFromServer() {
        final RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        final OkHttpClient httpClient = radioDroidApp.getHttpClient();
        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(ActivityMain.ACTION_SHOW_LOADING));

        new AsyncTask<Void, Void, ArrayList<DataRadioStation>>() {
            private ArrayList<DataRadioStation> savedStations;

            @Override
            protected void onPreExecute() {
                savedStations = new ArrayList<>(listStations);
            }

            @Override
            protected ArrayList<DataRadioStation> doInBackground(Void... params) {
                ArrayList<DataRadioStation> stationsToRemove = new ArrayList<>();
                for (DataRadioStation station : savedStations) {
                    if (!station.refresh(httpClient, context) && !station.hasValidUuid() && station.RefreshRetryCount > DataRadioStation.MAX_REFRESH_RETRIES) {
                        stationsToRemove.add(station);
                    }
                }

                return stationsToRemove;
            }

            @Override
            protected void onPostExecute(ArrayList<DataRadioStation> stationsToRemove) {
                listStations.removeAll(stationsToRemove);

                Save();

                notifyAllListeners();

                LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent(ActivityMain.ACTION_HIDE_LOADING));
                super.onPostExecute(stationsToRemove);
            }
        }.execute();
    }

    void Load() {
        listStations.clear();

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String str = sharedPref.getString(getSaveId(), null);
        if (str != null) {
            List<DataRadioStation> arr = DataRadioStation.DecodeJson(str);
            for (DataRadioStation station : arr) {
                station.queue = this;
            }
            listStations.addAll(arr);
            if (hasInvalidUuids() && Utils.hasAnyConnection(context)) {
                refreshStationsFromServer();
            }
        } else {
            Log.w("SAVE", "Load() no stations to load");
        }
    }

    void Save() {
        JSONArray arr = new JSONArray();
        for (DataRadioStation station : listStations) {
            arr.put(station.toJson());
        }

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPref.edit();
        String str = arr.toString();
        if (BuildConfig.DEBUG) {
            Log.d("SAVE", "wrote: " + str);
        }
        editor.putString(getSaveId(), str);
        editor.commit();
    }

    public static String getSaveDir() {
        String path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) + "";
        File folder = new File(path);
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                Log.e("SAVE", "could not create dir:" + path);
            }
        }
        return path;
    }

    public void SaveM3U(final String filePath, final String fileName) {
        // 如果文件名为空，使用数据库更新时间作为默认文件名
        final String finalFileName;
        if (fileName == null || fileName.isEmpty()) {
            finalFileName = getDefaultFileName();
        } else {
            finalFileName = fileName;
        }
        
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_now, filePath, finalFileName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                return SaveM3UInternal(filePath, finalFileName);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (result.booleanValue()) {
                    Log.i("SAVE", "OK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_ok, filePath, finalFileName), Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    Log.i("SAVE", "NOK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_nok, filePath, finalFileName), Toast.LENGTH_LONG);
                    toast.show();
                }
                super.onPostExecute(result);
            }
        }.execute();
    }

    public void SaveM3USimple(final String filePath, final String fileName) {
        // 如果文件名为空，使用数据库更新时间作为默认文件名
        final String finalFileName;
        if (fileName == null || fileName.isEmpty()) {
            finalFileName = getDefaultFileName();
        } else {
            finalFileName = fileName;
        }
        
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_now, filePath, finalFileName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                return SaveM3UInternal(filePath, finalFileName);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (result.booleanValue()) {
                    Log.i("SAVE", "OK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_ok, filePath, finalFileName), Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    Log.i("SAVE", "NOK");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_save_playlist_nok, filePath, finalFileName), Toast.LENGTH_LONG);
                    toast.show();
                }
                super.onPostExecute(result);
            }
        }.execute();
    }

    public void LoadM3U(final String filePath, final String fileName) {
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_now, filePath, fileName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, List<DataRadioStation>>() {
            @Override
            protected List<DataRadioStation> doInBackground(Void... params) {
                return LoadM3UInternal(filePath, fileName);
            }

            @Override
            protected void onPostExecute(List<DataRadioStation> result) {
                if (result != null) {
                    Log.i("LOAD", "Loaded " + result.size() + "stations");
                    addMultiple(result);
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_ok, result.size(), filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    Log.e("LOAD", "Load failed");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_nok, filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                }

                notifyAllListeners();

                super.onPostExecute(result);
            }
        }.execute();
    }

    public void LoadM3USimple(final Reader reader) {
        LoadM3USimpleWithFileName(reader, "", "");
    }
    
    public void LoadM3USimpleWithFileName(final Reader reader, final String filePath, final String fileName) {
        Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_now, filePath, fileName), Toast.LENGTH_LONG);
        toast.show();

        new AsyncTask<Void, Void, List<DataRadioStation>>() {
            @Override
            protected List<DataRadioStation> doInBackground(Void... params) {
                return LoadM3UReader(reader);
            }

            @Override
            protected void onPostExecute(List<DataRadioStation> result) {
                if (result != null) {
                    Log.i("LOAD", "Loaded " + result.size() + "stations");
                    addMultiple(result);
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_ok, result.size(), filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                } else {
                    Log.e("LOAD", "Load failed");
                    Toast toast = Toast.makeText(context, context.getResources().getString(R.string.notify_load_playlist_nok, filePath, fileName), Toast.LENGTH_LONG);
                    toast.show();
                }

                notifyAllListeners();

                super.onPostExecute(result);
            }
        }.execute();
    }

    protected final String M3U_PREFIX = "#RADIOBROWSERUUID:";

    boolean SaveM3UInternal(String filePath, String fileName) {
        final RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        final OkHttpClient httpClient = radioDroidApp.getHttpClient();

        File f = new File(filePath, fileName);
        // 显式指定 UTF-8 字符集，与 SaveM3UToStream 保持一致，避免默认字符集导致乱码
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            SaveM3UWriter(bw);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))));
            } else {
                MediaScannerConnection
                        .scanFile(context, new String[]{f.getAbsolutePath()}, null, null);
            }
            return true;
        } catch (Exception e) {
            Log.e("Exception", "File write failed: " + e.toString());
            return false;
        }
    }

    public boolean SaveM3UWriter(Writer bw) {
        try {
            // 对 listStations 做快照，避免导出过程中主线程修改列表触发 ConcurrentModificationException
            List<DataRadioStation> snapshot;
            synchronized (this) {
                snapshot = new ArrayList<>(listStations);
            }
            bw.write("#EXTM3U\n");
            for (DataRadioStation station : snapshot) {
                // 转义 Name/StreamUrl 中的换行符，避免破坏 M3U 格式
                String safeName = station.Name == null ? "" : station.Name.replaceAll("[\\r\\n]", " ");
                String safeUrl = station.StreamUrl == null ? "" : station.StreamUrl.replaceAll("[\\r\\n]", "");
                bw.write(M3U_PREFIX + station.StationUuid + "\n");
                bw.write("#EXTINF:-1," + safeName + "\n");
                bw.write(safeUrl + "\n\n");
            }
            bw.flush();

            return true;
        } catch (Exception e) {
            Log.e("Exception", "File write failed: " + e.toString());
            return false;
        }
    }

    public boolean SaveM3UToStream(OutputStream outputStream) {
        // 注意：不关闭底层 outputStream（调用方负责），仅确保 BufferedWriter 的缓冲被刷新
        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            return SaveM3UWriter(bw);
        } catch (Exception e) {
            Log.e("Exception", "Stream write failed: " + e.toString());
            return false;
        } finally {
            if (bw != null) {
                try {
                    bw.flush();
                } catch (IOException ignored) {
                }
            }
        }
    }

    List<DataRadioStation> LoadM3UInternal(String filePath, String fileName) {
        // 显式指定 UTF-8 字符集，与 SaveM3U 保持一致
        try (InputStream is = new FileInputStream(new File(filePath, fileName))) {
            return LoadM3UReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e("LOAD", "File read failed: " + e.toString());
            return null;
        }
    }

    protected List<DataRadioStation> LoadM3UReader(Reader reader) {
        final RadioDroidApp radioDroidApp = (RadioDroidApp) context.getApplicationContext();
        final OkHttpClient httpClient = radioDroidApp.getHttpClient();
        ArrayList<String> listUuids = new ArrayList<String>();

        // 用 try-with-resources 关闭 BufferedReader，避免异常路径下文件句柄泄漏
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                // 跳过 UTF-8 BOM（如有），避免首行 #EXTM3U 或 #RADIOBROWSERUUID 解析失败
                if (firstLine) {
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                    firstLine = false;
                }
                if (line.startsWith(M3U_PREFIX) && line.length() > M3U_PREFIX.length()) {
                    String uuid = line.substring(M3U_PREFIX.length()).trim();
                    listUuids.add(uuid);
                }
            }
        } catch (Exception e) {
            Log.e("LOAD", "File read failed: " + e.toString());
            return null;
        }

        // M3U 中无 UUID 时直接返回空列表，避免发起空 uuids 的网络请求
        if (listUuids.isEmpty()) {
            return new ArrayList<>();
        }

        List<DataRadioStation> listStationsNew = Utils.getStationsByUuid(httpClient, context, listUuids);
        // 网络失败时 getStationsByUuid 返回 null，避免后续 for-each 解引用 null 抛 NPE
        if (listStationsNew == null) {
            Log.e("LOAD", "getStationsByUuid returned null (network failure?)");
            return null;
        }

        // 用 Map 替代 O(n*m) 嵌套循环，按 M3U 文件中的顺序排序
        Map<String, DataRadioStation> byUuid = new HashMap<>();
        for (DataRadioStation s : listStationsNew) {
            byUuid.put(s.StationUuid, s);
        }
        List<DataRadioStation> listStationsSorted = new ArrayList<>();
        for (String uuid : listUuids) {
            DataRadioStation s = byUuid.get(uuid);
            if (s != null) {
                listStationsSorted.add(s);
            }
        }
        return listStationsSorted;
    }
}
