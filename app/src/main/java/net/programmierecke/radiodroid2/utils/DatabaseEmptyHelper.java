package net.programmierecke.radiodroid2.utils;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;

import net.programmierecke.radiodroid2.ActivityMain;
import net.programmierecke.radiodroid2.FragmentSettings;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.database.RadioStationRepository;
import net.programmierecke.radiodroid2.ui.EmptyDatabaseView;

public class DatabaseEmptyHelper {
    private static final String TAG = "DatabaseEmptyHelper";

    /**
     * 检查数据库是否为空，如果为空则显示统一的错误消息
     * @param fragment 当前Fragment
     * @param errorLayout 错误消息布局
     * @param contentLayout 内容布局
     * @param callback 检查完成后的回调
     */
    public static void checkAndShowEmptyDatabaseError(Fragment fragment, 
                                                      LinearLayout errorLayout, 
                                                      View contentLayout,
                                                      DatabaseCheckCallback callback) {
        if (fragment == null || fragment.getContext() == null) {
            return;
        }
        
        // 检查Fragment是否已附加到Activity
        if (!fragment.isAdded() || fragment.getActivity() == null) {
            return;
        }

        Context context = fragment.getContext();
        RadioStationRepository repository = RadioStationRepository.getInstance(context);

        repository.getStationCount(new RadioStationRepository.StationCountCallback() {
            @Override
            public void onStationCountReceived(int count) {
                // 再次检查Fragment状态，防止在异步操作中Fragment已分离
                if (!fragment.isAdded() || fragment.getActivity() == null) {
                    return;
                }
                
                if (count == 0) {
                    // 数据库为空，显示统一的错误消息
                    fragment.getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (!fragment.isAdded() || fragment.getActivity() == null) {
                            return;
                        }
                        
                        if (errorLayout != null && contentLayout != null) {
                            errorLayout.removeAllViews();
                            
                            EmptyDatabaseView emptyView = new EmptyDatabaseView(context);
                            emptyView.setOnUpdateClickListener(() -> {
                                // 跳转到设置页面
                                Intent intent = new Intent(context, ActivityMain.class);
                                intent.putExtra("open_settings", true);
                                context.startActivity(intent);
                            });
                            
                            errorLayout.addView(emptyView);
                            errorLayout.setVisibility(View.VISIBLE);
                            contentLayout.setVisibility(View.GONE);
                        }
                    });
                } else {
                    // 数据库不为空，继续正常流程
                    fragment.getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (!fragment.isAdded() || fragment.getActivity() == null) {
                            return;
                        }
                        
                        if (errorLayout != null && contentLayout != null) {
                            errorLayout.setVisibility(View.GONE);
                            contentLayout.setVisibility(View.VISIBLE);
                        }
                    });
                }
                
                if (callback != null) {
                    fragment.getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (!fragment.isAdded() || fragment.getActivity() == null) {
                            return;
                        }
                        callback.onCheckCompleted(count == 0, count);
                    });
                }
            }

            @Override
            public void onError(String error) {
                // 再次检查Fragment状态，防止在异步操作中Fragment已分离
                if (!fragment.isAdded() || fragment.getActivity() == null) {
                    return;
                }
                
                // 发生错误，继续正常流程
                fragment.getActivity().runOnUiThread(() -> {
                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                    if (!fragment.isAdded() || fragment.getActivity() == null) {
                        return;
                    }
                    
                    if (errorLayout != null && contentLayout != null) {
                        errorLayout.setVisibility(View.GONE);
                        contentLayout.setVisibility(View.VISIBLE);
                    }
                });
                
                if (callback != null) {
                    fragment.getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (!fragment.isAdded() || fragment.getActivity() == null) {
                            return;
                        }
                        callback.onCheckError(error);
                    });
                }
            }
        });
    }

    /**
     * 检查数据库是否为空，如果为空则显示统一的错误消息（简化版，不处理回调）
     * @param fragment 当前Fragment
     * @param errorLayout 错误消息布局
     * @param contentLayout 内容布局
     */
    public static void checkAndShowEmptyDatabaseError(Fragment fragment, 
                                                      LinearLayout errorLayout, 
                                                      View contentLayout) {
        checkAndShowEmptyDatabaseError(fragment, errorLayout, contentLayout, null);
    }
    
    /**
     * 检查数据库是否为空，如果为空则显示统一的错误消息（ConstraintLayout版本）
     * @param fragment 当前Fragment
     * @param errorLayout 错误消息布局（ConstraintLayout）
     * @param contentLayout 内容布局
     * @param callback 检查完成后的回调
     */
    public static void checkAndShowEmptyDatabaseError(Fragment fragment, 
                                                      ConstraintLayout errorLayout, 
                                                      View contentLayout,
                                                      DatabaseCheckCallback callback) {
        if (fragment == null || fragment.getContext() == null) {
            return;
        }
        
        // 检查Fragment是否已附加到Activity
        if (!fragment.isAdded() || fragment.getActivity() == null) {
            return;
        }

        Context context = fragment.getContext();
        RadioStationRepository repository = RadioStationRepository.getInstance(context);

        repository.getStationCount(new RadioStationRepository.StationCountCallback() {
            @Override
            public void onStationCountReceived(int count) {
                // 再次检查Fragment状态，防止在异步操作中Fragment已分离
                if (!fragment.isAdded() || fragment.getActivity() == null) {
                    return;
                }
                
                if (count == 0) {
                    // 数据库为空，显示统一的错误消息
                    fragment.getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (!fragment.isAdded() || fragment.getActivity() == null) {
                            return;
                        }
                        
                        if (errorLayout != null && contentLayout != null) {
                            errorLayout.removeAllViews();
                            
                            EmptyDatabaseView emptyView = new EmptyDatabaseView(context);
                            emptyView.setOnUpdateClickListener(() -> {
                                // 跳转到设置页面
                                Intent intent = new Intent(context, ActivityMain.class);
                                intent.putExtra("open_settings", true);
                                context.startActivity(intent);
                            });
                            
                            // 将EmptyDatabaseView添加到ConstraintLayout
                            ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
                                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                                    ConstraintLayout.LayoutParams.MATCH_PARENT);
                            emptyView.setLayoutParams(params);
                            errorLayout.addView(emptyView);
                            errorLayout.setVisibility(View.VISIBLE);
                            contentLayout.setVisibility(View.GONE);
                        }
                    });
                } else {
                    // 数据库不为空，继续正常流程
                    fragment.getActivity().runOnUiThread(() -> {
                        // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                        if (!fragment.isAdded() || fragment.getActivity() == null) {
                            return;
                        }
                        
                        if (errorLayout != null && contentLayout != null) {
                            errorLayout.setVisibility(View.GONE);
                            contentLayout.setVisibility(View.VISIBLE);
                        }
                    });
                }
                
                if (callback != null) {
                    callback.onCheckCompleted(count == 0, count);
                }
            }

            @Override
            public void onError(String error) {
                // 再次检查Fragment状态，防止在异步操作中Fragment已分离
                if (!fragment.isAdded() || fragment.getActivity() == null) {
                    return;
                }
                
                // 发生错误，继续正常流程
                fragment.getActivity().runOnUiThread(() -> {
                    // 再次检查Fragment状态，防止在UI操作中Fragment已分离
                    if (!fragment.isAdded() || fragment.getActivity() == null) {
                        return;
                    }
                    
                    if (errorLayout != null && contentLayout != null) {
                        errorLayout.setVisibility(View.GONE);
                        contentLayout.setVisibility(View.VISIBLE);
                    }
                });
                
                if (callback != null) {
                    callback.onCheckError(error);
                }
            }
        });
    }
    
    /**
     * 检查数据库是否为空，如果为空则显示统一的错误消息（ConstraintLayout简化版）
     * @param fragment 当前Fragment
     * @param errorLayout 错误消息布局（ConstraintLayout）
     * @param contentLayout 内容布局
     */
    public static void checkAndShowEmptyDatabaseError(Fragment fragment, 
                                                      ConstraintLayout errorLayout, 
                                                      View contentLayout) {
        checkAndShowEmptyDatabaseError(fragment, errorLayout, contentLayout, null);
    }

    /**
     * 数据库检查回调接口
     */
    public interface DatabaseCheckCallback {
        void onCheckCompleted(boolean isEmpty, int count);
        void onCheckError(String error);
    }
}