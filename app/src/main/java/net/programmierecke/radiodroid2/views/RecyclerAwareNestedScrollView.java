package net.programmierecke.radiodroid2.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import java.lang.reflect.Field;


/**
 * Credits to Alex Lockwood blogpost "Experimenting with Nested Scrolling"
 * Credits to https://stackoverflow.com/questions/31829976/onclick-method-not-working-properly-after-nestedscrollview-scrolled
 * <p>
 * Allows scroll view to have {@link RecyclerView} alongside with other content in it and be scrolled
 * as expected by user.
 * <p>
 * The NestedScrollView should steal the scroll/fling events away from
 * the RecyclerView if either is true:
 * - the user is dragging their finger down and the RecyclerView is scrolled to the top of its content
 * - the user is dragging their finger up and the NestedScrollView is not scrolled to the bottom of its content.
 */
public class RecyclerAwareNestedScrollView extends NestedScrollView {
    private OverScroller mScroller;
    public boolean isFling = false;
    private boolean mDisallowParentIntercept;

    public RecyclerAwareNestedScrollView(@NonNull Context context) {
        this(context, null);
    }

    public RecyclerAwareNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecyclerAwareNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mScroller = getOverScroller();
    }

    /**
     * 自身上方仍有未展示内容（未滚到顶部）时，禁止祖先容器抢占本次触摸手势。
     * <p>
     * 全屏播放器整体处于 BottomSheet 中，收起播放器的下滑手势由父容器拦截判定，
     * 而该判定只参考外层滚动容器（scrollViewContent）的位置——嵌套在其内部的滚动视图
     * （如信息页）滚离顶部后，向下拖动会被父容器误解析为"收起播放器"。
     * <p>
     * 挂载点必须用 onInterceptTouchEvent(ACTION_DOWN) 而非 onTouchEvent：DOWN 阶段每个
     * 容器都会收到 onInterceptTouchEvent 回调，无论触摸最终落在自身还是标签/备注行等
     * 可点击子控件上，防御都能生效；挂在 onTouchEvent 上时子控件消费了 DOWN 就会失效。
     * 滚到顶部后不再声明，父容器仍可正常收起播放器。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDisallowParentIntercept = canScrollVertically(-1);
            if (mDisallowParentIntercept) {
                disallowParentIntercept(true);
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDisallowParentIntercept) {
                    mDisallowParentIntercept = false;
                    disallowParentIntercept(false);
                }
                break;
        }
        return super.onTouchEvent(ev);
    }

    /**
     * 向上声明禁止/恢复拦截。若直接父容器是 ViewPager（全屏播放器信息页场景）需跳过它：
     * ViewPager 自身要保留横向翻页的拦截权，声明从其上层祖先开始即可（该声明会继续向上传播）。
     */
    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent instanceof ViewPager) {
            parent = parent.getParent();
        }
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    @Override
    public void fling(int velocityY) {
        super.fling(velocityY);

        // Here we effectively extend the super class functionality for backwards compatibility and just call invalidateOnAnimation()
        if (getChildCount() > 0) {
            ViewCompat.postInvalidateOnAnimation(this);

            // Initializing isFling to true to track fling action in onScrollChanged() method
            isFling = true;
        }
    }

    @Override
    protected void onScrollChanged(int l, final int t, final int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);

        if (isFling) {
            if (Math.abs(t - oldt) <= 3 || t == 0 || t == (getChildAt(0).getMeasuredHeight() - getMeasuredHeight())) {
                isFling = false;

                // This forces the mFinish variable in scroller to true and does the trick
                if (mScroller != null) {
                    mScroller.abortAnimation();
                }
            }
        }
    }

    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        // 嵌套滚动 target 不一定是 RecyclerView（如全屏播放器 Info 页的内嵌滚动容器）。
        // 此时必须就地终止：既不消费也不调用 super——NestedScrollView 的父角色默认实现会把
        // pre-scroll 以本视图（外层容器）的名义继续向上转发，BottomSheet 收到后会把内嵌
        // 页面的下拉误判为"外层容器在下拉"而触发收起播放器（Issue #53 真机复现）。
        // 不消费则内嵌容器会自行滚动。
        if (!(target instanceof RecyclerView)) {
            return;
        }
        final RecyclerView rv = (RecyclerView) target;
        if ((dy < 0 && isRvScrolledToTop(rv)) || (dy > 0 && !isNsvScrolledToBottom(this))) {
            // Scroll the NestedScrollView's content and record the number of pixels consumed
            // (so that the RecyclerView will know not to perform the scroll as well).
            scrollBy(0, dy);
            consumed[1] = dy;
            return;
        }
        super.onNestedPreScroll(target, dx, dy, consumed, type);
    }

    @Override
    public boolean onNestedPreFling(View target, float velX, float velY) {
        // 与 onNestedPreScroll 同理：非列表目标就地终止，不向上转发 fling，
        // 否则 BottomSheet 会把内嵌页面的甩动误判为外层容器在下拉而收起播放器
        if (!(target instanceof RecyclerView)) {
            return false;
        }
        final RecyclerView rv = (RecyclerView) target;
        if ((velY < 0 && isRvScrolledToTop(rv)) || (velY > 0 && !isNsvScrolledToBottom(this))) {
            // Fling the NestedScrollView's content and return true (so that the RecyclerView
            // will know not to perform the fling as well).
            fling((int) velY);
            return true;
        }
        return super.onNestedPreFling(target, velX, velY);
    }

    /**
     * Returns true if the NestedScrollView is scrolled to the bottom of its
     * content (i.e. if the card's inner RecyclerView is completely visible).
     */
    private static boolean isNsvScrolledToBottom(NestedScrollView nsv) {
        return !nsv.canScrollVertically(1);
    }

    /**
     * Returns true iff the RecyclerView is scrolled to the top of its
     * content (i.e. if the RecyclerView's first item is completely visible).
     */
    private static boolean isRvScrolledToTop(RecyclerView rv) {
        final LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        return lm.findFirstVisibleItemPosition() == 0
                && lm.findViewByPosition(0).getTop() == 0;
    }

    private OverScroller getOverScroller() {
        Field fs;
        try {
            fs = this.getClass().getSuperclass().getDeclaredField("mScroller");
            fs.setAccessible(true);
            return (OverScroller) fs.get(this);
        } catch (Throwable t) {
            return null;
        }
    }
}
