package net.programmierecke.radiodroid2;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import net.programmierecke.radiodroid2.interfaces.IApplicationSelected;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ApplicationSelectorDialog extends BottomSheetDialogFragment {

    private IApplicationSelected callback;
    private AppListAdapter adapter;
    private List<AppInfo> appList = new ArrayList<>();
    private boolean appSelected = false;

    public void setCallback(IApplicationSelected callback) {
        this.callback = callback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_app_selector, container, false);

        RecyclerView recyclerView = view.findViewById(R.id.recyclerViewApps);
        TextView textNoApps = view.findViewById(R.id.textNoApps);

        loadApps();

        if (appList.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            textNoApps.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            textNoApps.setVisibility(View.GONE);
            adapter = new AppListAdapter(appList, appInfo -> {
                appSelected = true;
                if (callback != null) {
                    callback.onAppSelected(appInfo.packageName, appInfo.activityName);
                }
                dismiss();
            });
            recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            recyclerView.setAdapter(adapter);
        }

        return view;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!appSelected && callback != null) {
            callback.onAppSelectionCancelled();
        }
    }

    private void loadApps() {
        appList.clear();
        PackageManager pm = requireContext().getPackageManager();

        Intent mainIntent = new Intent(Intent.ACTION_VIEW);
        mainIntent.setDataAndType(Uri.parse("http://example.com/test.mp3"), "audio/*");
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);

        for (ResolveInfo info : resolveInfos) {
            ApplicationInfo applicationInfo = info.activityInfo.applicationInfo;
            String appName = String.valueOf(pm.getApplicationLabel(applicationInfo));
            Drawable icon = pm.getApplicationIcon(applicationInfo);
            String packageName = info.activityInfo.packageName;
            String activityName = info.activityInfo.name;

            appList.add(new AppInfo(appName, packageName, activityName, icon));
        }

        Collections.sort(appList, Comparator.comparing(a -> a.appName.toLowerCase()));
    }

    private static class AppInfo {
        final String appName;
        final String packageName;
        final String activityName;
        final Drawable icon;

        AppInfo(String appName, String packageName, String activityName, Drawable icon) {
            this.appName = appName;
            this.packageName = packageName;
            this.activityName = activityName;
            this.icon = icon;
        }
    }

    private static class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        private final List<AppInfo> apps;
        private final OnAppClickListener listener;

        interface OnAppClickListener {
            void onAppClick(AppInfo appInfo);
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView appIcon;
            final TextView appName;

            ViewHolder(View view) {
                super(view);
                appIcon = view.findViewById(R.id.appIcon);
                appName = view.findViewById(R.id.appName);
            }
        }

        AppListAdapter(List<AppInfo> apps, OnAppClickListener listener) {
            this.apps = apps;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_app_selector, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo app = apps.get(position);
            holder.appIcon.setImageDrawable(app.icon);
            holder.appName.setText(app.appName);
            holder.itemView.setOnClickListener(v -> listener.onAppClick(app));
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
