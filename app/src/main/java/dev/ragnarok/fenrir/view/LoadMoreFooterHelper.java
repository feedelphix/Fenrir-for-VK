package dev.ragnarok.fenrir.view;

import android.view.View;
import android.widget.ProgressBar;

import dev.libfenrir.rlottie.RLottieImageView;
import dev.ragnarok.fenrir.R;
import dev.ragnarok.fenrir.model.LoadMoreState;
import dev.ragnarok.fenrir.settings.CurrentTheme;
import dev.ragnarok.fenrir.settings.Settings;
import dev.ragnarok.fenrir.util.Utils;

public class LoadMoreFooterHelper {

    public Callback callback;
    public Holder holder;
    public int state = LoadMoreState.INVISIBLE;
    public int animation_id;

    public static LoadMoreFooterHelper createFrom(View view, Callback callback) {
        LoadMoreFooterHelper helper = new LoadMoreFooterHelper();
        helper.animation_id = Settings.get().other().getEndListAnimation();
        helper.holder = new Holder(view);
        helper.callback = callback;
        helper.holder.bLoadMore.setOnClickListener(v -> {
            if (callback != null) {
                callback.onLoadMoreClick();
            }
        });
        return helper;
    }

    public void switchToState(@LoadMoreState int state) {
        this.state = state;
        holder.container.setVisibility(state == LoadMoreState.INVISIBLE ? View.GONE : View.VISIBLE);

        switch (state) {
            case LoadMoreState.LOADING:
                holder.tvEndOfList.setImageDrawable(null);
                holder.tvEndOfList.setVisibility(View.INVISIBLE);
                holder.bLoadMore.setVisibility(View.INVISIBLE);
                holder.progress.setVisibility(View.VISIBLE);
                break;
            case LoadMoreState.END_OF_LIST:
                holder.tvEndOfList.setVisibility(View.VISIBLE);
                holder.tvEndOfList.setAutoRepeat(false);
                if (animation_id == 0) {
                    holder.tvEndOfList.fromRes(R.raw.end_list_succes, Utils.dp(40), Utils.dp(40), new int[]{0xffffff, CurrentTheme.getColorControlNormal(holder.bLoadMore.getContext())});
                } else {
                    holder.tvEndOfList.fromRes(R.raw.end_list_balls, Utils.dp(40), Utils.dp(40), new int[]{0xffffff, CurrentTheme.getColorControlNormal(holder.bLoadMore.getContext())});
                }
                holder.tvEndOfList.playAnimation();
                holder.bLoadMore.setVisibility(View.INVISIBLE);
                holder.progress.setVisibility(View.INVISIBLE);
                break;
            case LoadMoreState.CAN_LOAD_MORE:
                holder.tvEndOfList.setImageDrawable(null);
                holder.tvEndOfList.setVisibility(View.INVISIBLE);
                holder.bLoadMore.setVisibility(View.VISIBLE);
                holder.progress.setVisibility(View.INVISIBLE);
                break;
            case LoadMoreState.INVISIBLE:
                break;
        }
    }

    public interface Callback {
        void onLoadMoreClick();
    }

    public static class Holder {
        public final View container;
        public final ProgressBar progress;
        public final View bLoadMore;
        public final RLottieImageView tvEndOfList;

        public Holder(View root) {
            container = root.findViewById(R.id.footer_load_more_root);
            progress = root.findViewById(R.id.footer_load_more_progress);
            bLoadMore = root.findViewById(R.id.footer_load_more_run);
            tvEndOfList = root.findViewById(R.id.footer_load_more_end_of_list);
        }
    }
}
