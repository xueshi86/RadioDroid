package net.programmierecke.radiodroid2.recording;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;

import net.programmierecke.radiodroid2.R;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.RecordingItemViewHolder> {
    final static String TAG = "RecordingsAdapter";

    private Context context;
    private List<DataRecording> recordings;
    private RecordingsManager recordingsManager;
    private ExoPlayer activePlayer;
    private AlertDialog activePlayerDialog;
    private Handler seekHandler = new Handler(Looper.getMainLooper());
    private Runnable seekUpdateRunnable;

    class RecordingItemViewHolder extends RecyclerView.ViewHolder {
        final ViewGroup viewRoot;
        final TextView textViewTitle;
        final TextView textViewTime;

        private RecordingItemViewHolder(View itemView) {
            super(itemView);

            viewRoot = (ViewGroup) itemView;
            textViewTitle = itemView.findViewById(R.id.textViewTitle);
            textViewTime = itemView.findViewById(R.id.textViewTime);
        }
    }

    public RecordingsAdapter(@NonNull Context context, @NonNull RecordingsManager recordingsManager) {
        this.context = context;
        this.recordingsManager = recordingsManager;
    }

    public void releasePlayer() {
        if (activePlayer != null) {
            stopSeekUpdate();
            activePlayer.release();
            activePlayer = null;
        }
        if (activePlayerDialog != null && activePlayerDialog.isShowing()) {
            activePlayerDialog.dismiss();
            activePlayerDialog = null;
        }
    }

    @NonNull
    @Override
    public RecordingsAdapter.RecordingItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View itemView = inflater.inflate(R.layout.list_item_recording, parent, false);
        return new RecordingItemViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull RecordingItemViewHolder holder, int position) {
        final DataRecording recording = recordings.get(position);

        holder.textViewTitle.setText(recording.Name);
        if (recording.Time != null) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            holder.textViewTime.setText(sdf.format(recording.Time));
        }

        holder.viewRoot.setOnClickListener(view -> playRecordingInApp(recording));

        holder.viewRoot.setOnLongClickListener(view -> {
            showContextMenu(recording, holder.getAdapterPosition());
            return true;
        });
    }

    public void setRecordings(List<DataRecording> recordings) {
        if (this.recordings != null && recordings.size() == this.recordings.size()) {
            boolean same = true;
            for (int i = 0; i < recordings.size(); i++) {
                if (!recordings.get(i).equals(this.recordings.get(i))) {
                    same = false;
                    break;
                }
            }

            if (same) {
                return;
            }
        }

        this.recordings = recordings;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return recordings != null ? recordings.size() : 0;
    }

    private void playRecordingInApp(DataRecording recording) {
        String path = RecordingsManager.getRecordDir() + "/" + recording.Name;
        File file = new File(path);
        if (!file.exists()) {
            Toast.makeText(context, R.string.recording_file_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        if (file.length() == 0) {
            Toast.makeText(context, R.string.recording_empty_file, Toast.LENGTH_SHORT).show();
            return;
        }

        releasePlayer();

        ExoPlayer player = new ExoPlayer.Builder(context).build();

        activePlayer = player;

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_recording_player, null);
        TextView tvTitle = dialogView.findViewById(R.id.textViewRecordingTitle);
        SeekBar seekBar = dialogView.findViewById(R.id.seekBarRecording);
        TextView tvCurrent = dialogView.findViewById(R.id.textViewCurrentPosition);
        TextView tvDuration = dialogView.findViewById(R.id.textViewDuration);
        Button btnPlayPause = dialogView.findViewById(R.id.btnPlayPause);

        tvTitle.setText(recording.Name);
        tvCurrent.setText(formatDuration(0));
        seekBar.setMax(0);
        seekBar.setProgress(0);
        btnPlayPause.setEnabled(false);
        btnPlayPause.setText(R.string.recording_play);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        tvDuration.setText(formatDuration((int) (duration / 1000)));
                        seekBar.setMax((int) (duration / 1000));
                    }
                    btnPlayPause.setEnabled(true);
                    btnPlayPause.setText(R.string.recording_pause);
                    startSeekUpdate(seekBar, tvCurrent);
                } else if (playbackState == Player.STATE_ENDED) {
                    btnPlayPause.setText(R.string.recording_play);
                    stopSeekUpdate();
                }
            }

            @Override
            public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                Log.e(TAG, "ExoPlayer error: " + error.getMessage());
                Toast.makeText(context, R.string.recording_play_error, Toast.LENGTH_SHORT).show();
                releasePlayer();
            }
        });

        btnPlayPause.setOnClickListener(v -> {
            if (activePlayer != null) {
                if (activePlayer.isPlaying()) {
                    activePlayer.pause();
                    btnPlayPause.setText(R.string.recording_play);
                    stopSeekUpdate();
                } else {
                    activePlayer.play();
                    btnPlayPause.setText(R.string.recording_pause);
                    startSeekUpdate(seekBar, tvCurrent);
                }
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar1, int progress, boolean fromUser) {
                if (fromUser && activePlayer != null) {
                    activePlayer.seekTo(progress * 1000L);
                    tvCurrent.setText(formatDuration(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar1) {
                stopSeekUpdate();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar1) {
                if (activePlayer != null && activePlayer.isPlaying()) {
                    startSeekUpdate(seekBar1, tvCurrent);
                }
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.recording_player_title)
                .setView(dialogView)
                .setOnDismissListener(d -> {
                    stopSeekUpdate();
                    if (activePlayer != null) {
                        activePlayer.release();
                        activePlayer = null;
                    }
                })
                .setPositiveButton(R.string.action_ok, null)
                .create();

        activePlayerDialog = dialog;
        dialog.show();

        try {
            Uri fileUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            DefaultDataSourceFactory dataSourceFactory = new DefaultDataSourceFactory(context, "RadioDroid");
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(
                    dataSourceFactory,
                    new DefaultExtractorsFactory()
            ).createMediaSource(MediaItem.fromUri(fileUri));

            player.setMediaSource(mediaSource);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up ExoPlayer: " + e.getMessage());
            Toast.makeText(context, R.string.recording_play_error, Toast.LENGTH_SHORT).show();
            releasePlayer();
        }
    }

    private void startSeekUpdate(SeekBar seekBar, TextView tvCurrent) {
        stopSeekUpdate();
        seekUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (activePlayer != null) {
                    long pos = activePlayer.getCurrentPosition() / 1000;
                    seekBar.setProgress((int) pos);
                    tvCurrent.setText(formatDuration((int) pos));
                    seekHandler.postDelayed(this, 200);
                }
            }
        };
        seekHandler.post(seekUpdateRunnable);
    }

    private void stopSeekUpdate() {
        if (seekUpdateRunnable != null) {
            seekHandler.removeCallbacks(seekUpdateRunnable);
            seekUpdateRunnable = null;
        }
    }

    private String formatDuration(int seconds) {
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private void playRecordingExternal(DataRecording recording) {
        String path = RecordingsManager.getRecordDir() + "/" + recording.Name;

        File file = new File(path);
        if (!file.exists()) {
            Toast.makeText(context, R.string.recording_file_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        Uri fileUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);

        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(fileUri, "audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Error opening recording externally: " + e.getMessage());
            Toast.makeText(context, R.string.recording_play_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void showContextMenu(DataRecording recording, int position) {
        String[] items = {
                context.getString(R.string.recording_play_external),
                context.getString(R.string.recording_details),
                context.getString(R.string.action_delete)
        };
        new AlertDialog.Builder(context)
                .setTitle(recording.Name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            playRecordingExternal(recording);
                            break;
                        case 1:
                            showDetailsDialog(recording);
                            break;
                        case 2:
                            confirmDelete(recording);
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDetailsDialog(DataRecording recording) {
        String path = RecordingsManager.getRecordDir() + "/" + recording.Name;
        File file = new File(path);

        StringBuilder info = new StringBuilder();

        info.append(context.getString(R.string.recording_detail_name)).append(" ").append(recording.Name).append("\n\n");

        info.append(context.getString(R.string.recording_detail_path)).append(" ").append(path).append("\n\n");

        boolean isRecordingInProgress = false;
        long runningBytesWritten = 0;
        Map<Recordable, RunningRecordingInfo> runningRecordings = recordingsManager.getRunningRecordings();
        for (RunningRecordingInfo ri : runningRecordings.values()) {
            if (ri.getFileName() != null && ri.getFileName().equals(recording.Name)) {
                isRecordingInProgress = true;
                runningBytesWritten = ri.getBytesWritten();
                break;
            }
        }

        if (file.exists() || isRecordingInProgress) {
            long sizeBytes;
            if (isRecordingInProgress) {
                sizeBytes = runningBytesWritten;
            } else {
                sizeBytes = file.length();
            }

            String sizeStr;
            if (sizeBytes < 1024) {
                sizeStr = sizeBytes + " B";
            } else if (sizeBytes < 1024 * 1024) {
                sizeStr = String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024.0);
            } else {
                sizeStr = String.format(Locale.getDefault(), "%.2f MB", sizeBytes / (1024.0 * 1024.0));
            }
            info.append(context.getString(R.string.recording_detail_size)).append(" ").append(sizeStr).append("\n\n");

            long duration = 0;
            int dotIndex = recording.Name.lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = recording.Name.substring(dotIndex + 1).toLowerCase();
                if (ext.equals("mp3")) {
                    duration = sizeBytes * 8 / 128000;
                } else if (ext.equals("aac")) {
                    duration = sizeBytes * 8 / 128000;
                } else if (ext.equals("ogg")) {
                    duration = sizeBytes * 8 / 112000;
                }
            }
            if (duration > 0) {
                int minutes = (int) (duration / 60);
                int seconds = (int) (duration % 60);
                info.append(context.getString(R.string.recording_detail_duration)).append(" ")
                        .append(String.format(Locale.getDefault(), "%d:%02d", minutes, seconds))
                        .append("\n\n");
            }

            if (file.exists()) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                info.append(context.getString(R.string.recording_detail_modified)).append(" ").append(sdf.format(new java.util.Date(file.lastModified())));
            }
        } else {
            info.append(context.getString(R.string.recording_file_not_found));
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.recording_details)
                .setMessage(info.toString())
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    private void confirmDelete(DataRecording recording) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.recording_delete_title)
                .setMessage(context.getString(R.string.recording_delete_confirm, recording.Name))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    deleteRecording(recording);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteRecording(DataRecording recording) {
        String path = RecordingsManager.getRecordDir() + "/" + recording.Name;
        File file = new File(path);
        boolean deleted = false;
        if (file.exists()) {
            deleted = file.delete();
            if (deleted) {
                Toast.makeText(context, R.string.recording_deleted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, R.string.recording_delete_failed, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(context, R.string.recording_file_not_found, Toast.LENGTH_SHORT).show();
        }
        if (deleted || !file.exists()) {
            recordingsManager.updateRecordingsList();
        }
    }
}
