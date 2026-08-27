package net.programmierecke.radiodroid2.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import net.programmierecke.radiodroid2.R;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 可搜索列表对话框：标题 + 输入即过滤（500ms 防抖）+ 前缀优先排序 + 选中项高亮。
 * 用于国家/语言/标签过滤下拉（替代静态全量 Spinner）。
 *
 * 用法：DialogFragment 构造参数传入 options（含 "全部" 项）、selected、title，
 * 通过 OnOptionSelectedListener 回调选中项名称。
 */
public class SearchableListDialogFragment extends DialogFragment {

    public static final String ARG_TITLE = "title";
    public static final String ARG_SELECTED = "selected";
    public static final String ARG_OPTIONS = "options";

    /** 过滤选项：名称 + 出现次数（可选，用于标签热门排序显示） */
    public static class FilterOption implements Serializable {
        public final String name;
        public final long count;

        public FilterOption(String name, long count) {
            this.name = name;
            this.count = count;
        }
    }

    public interface OnOptionSelectedListener {
        void onOptionSelected(String name);
    }

    private OnOptionSelectedListener listener;
    private final List<FilterOption> allOptions = new ArrayList<>();
    private final List<FilterOption> filteredOptions = new ArrayList<>();
    private String selectedName = "";
    private String allLabel = "";

    private final Handler handler = new Handler();
    private static final long DEBOUNCE_DELAY = 500;
    private Runnable filterRunnable;
    private ListView listView;
    private OptionAdapter adapter;

    public static SearchableListDialogFragment newInstance(String title, List<FilterOption> options, String selected) {
        SearchableListDialogFragment fragment = new SearchableListDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_SELECTED, selected);
        args.putSerializable(ARG_OPTIONS, new ArrayList<>(options));
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnOptionSelectedListener(OnOptionSelectedListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        Context context = getContext();

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_searchable_list, null);
        TextView textViewTitle = view.findViewById(R.id.dialogTitle);
        EditText editTextSearch = view.findViewById(R.id.dialogSearchInput);
        listView = view.findViewById(R.id.dialogList);

        if (args != null) {
            textViewTitle.setText(args.getString(ARG_TITLE, ""));
            selectedName = args.getString(ARG_SELECTED, "");
            List<FilterOption> opts = (List<FilterOption>) args.getSerializable(ARG_OPTIONS);
            if (opts != null) {
                allOptions.clear();
                allOptions.addAll(opts);
            }
        }
        allLabel = context.getString(R.string.multi_search_all);

        adapter = new OptionAdapter(context, filteredOptions);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        listView.setOnItemClickListener((parent, view1, position, id) -> {
            FilterOption option = filteredOptions.get(position);
            if (listener != null) {
                listener.onOptionSelected(option.name);
            }
            dismiss();
        });

        editTextSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (filterRunnable != null) {
                    handler.removeCallbacks(filterRunnable);
                }
                final String query = s.toString();
                filterRunnable = () -> applyFilter(query);
                handler.postDelayed(filterRunnable, DEBOUNCE_DELAY);
            }
        });

        applyFilter("");

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(context);
        builder.setView(view);
        // 重置按钮（neutral 渲染在 negative 左侧）：一键恢复"全部"，免去长列表下拉回顶部
        builder.setNeutralButton(R.string.multi_search_reset, (d, w) -> {
            if (listener != null) {
                listener.onOptionSelected(allLabel);
            }
            d.dismiss();
        });
        builder.setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss());
        return builder.create();
    }

    private void applyFilter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        filteredOptions.clear();
        if (q.isEmpty()) {
            filteredOptions.addAll(allOptions);
        } else {
            for (FilterOption option : allOptions) {
                if (option.name.toLowerCase(Locale.US).contains(q)) {
                    filteredOptions.add(option);
                }
            }
            // 前缀命中优先，其次按名称排序
            Collections.sort(filteredOptions, new Comparator<FilterOption>() {
                @Override
                public int compare(FilterOption a, FilterOption b) {
                    boolean aPrefix = a.name.toLowerCase(Locale.US).startsWith(q);
                    boolean bPrefix = b.name.toLowerCase(Locale.US).startsWith(q);
                    if (aPrefix != bPrefix) {
                        return aPrefix ? -1 : 1;
                    }
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
        }
        adapter.notifyDataSetChanged();
        // 恢复选中高亮并滚动到可见（初始与过滤后均生效）
        for (int i = 0; i < filteredOptions.size(); i++) {
            if (selectedName.equals(filteredOptions.get(i).name)) {
                listView.setItemChecked(i, true);
                listView.setSelection(i);
                break;
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
    }

    private class OptionAdapter extends android.widget.ArrayAdapter<FilterOption> {

        OptionAdapter(Context context, List<FilterOption> options) {
            super(context, android.R.layout.simple_list_item_single_choice, options);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView textView = (TextView) super.getView(position, convertView, parent);
            FilterOption option = getItem(position);
            if (option != null) {
                if (allLabel.equals(option.name)) {
                    textView.setText(option.name);
                } else if (option.count > 0) {
                    textView.setText(option.name + " (" + option.count + ")");
                } else {
                    textView.setText(option.name);
                }
            }
            return textView;
        }
    }
}
