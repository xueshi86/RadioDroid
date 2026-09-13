package net.programmierecke.radiodroid2.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewParent;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

public class RecyclerItemMoveAndSwipeHelper<ViewHolderType extends SwipeableViewHolder> extends RecyclerItemSwipeHelper {

    public interface MoveAndSwipeCallback<ViewHolderType> extends SwipeCallback<ViewHolderType>  {
        void onDragged(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, double dX, double dY);
        void onMoved(ViewHolderType viewHolder, int from, int to);
        void onMoveEnded(ViewHolderType viewHolder);

        default void onDragStarted(ViewHolderType viewHolder) {
        }
    }

    private MoveAndSwipeCallback<ViewHolderType> moveAndSwipeListener;

    @SuppressWarnings("unchecked")
    public RecyclerItemMoveAndSwipeHelper(Context context, int dragDirs, int swipeDirs, MoveAndSwipeCallback<ViewHolderType> moveAndSwipeListener) {
        super(context, dragDirs, swipeDirs, moveAndSwipeListener);
        this.moveAndSwipeListener = moveAndSwipeListener;
    }

    @Override
    public boolean isLongPressDragEnabled() {
        return true;
    }

    @Override
    public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
        super.onSelectedChanged(viewHolder, actionState);
        if (viewHolder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            RecyclerView recyclerView = findRecyclerView(viewHolder.itemView);
            if (recyclerView != null) {
                // 阻止父级 SwipeRefreshLayout 拦截向下拖拽手势（否则触发下拉刷新而非拖拽排序）
                recyclerView.requestDisallowInterceptTouchEvent(true);
            }
            @SuppressWarnings("unchecked")
            ViewHolderType viewHolderType = (ViewHolderType) viewHolder;
            moveAndSwipeListener.onDragStarted(viewHolderType);
        }
    }

    @Override
    public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
        int fromPos = viewHolder.getAdapterPosition();
        int toPos = target.getAdapterPosition();

        if (fromPos < 0 || toPos < 0 || fromPos == toPos) {
            return false;
        }

        @SuppressWarnings("unchecked")
        ViewHolderType viewHolderType = (ViewHolderType) viewHolder;
        moveAndSwipeListener.onMoved(viewHolderType, fromPos, toPos);

        return true;
    }

    @Override
    public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
        @SuppressWarnings("unchecked")
        ViewHolderType viewHolderType = (ViewHolderType) viewHolder;
        super.clearView(recyclerView, viewHolder);
        // 拖拽/滑动结束，恢复父级容器（SwipeRefreshLayout）对触摸事件的处理
        recyclerView.requestDisallowInterceptTouchEvent(false);
        moveAndSwipeListener.onMoveEnded(viewHolderType);
    }

    private static RecyclerView findRecyclerView(View view) {
        ViewParent parent = view.getParent();
        while (parent != null && !(parent instanceof RecyclerView)) {
            parent = parent.getParent();
        }
        return (RecyclerView) parent;
    }

    @Override
    public void onChildDraw(Canvas c, RecyclerView recyclerView,
                            RecyclerView.ViewHolder viewHolder, float dX, float dY,
                            int actionState, boolean isCurrentlyActive) {

        moveAndSwipeListener.onDragged(recyclerView, viewHolder, dX, dY);

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
    }

}
