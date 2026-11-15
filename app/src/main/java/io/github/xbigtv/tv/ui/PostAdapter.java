package io.github.xbigtv.tv.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import io.github.xbigtv.tv.xapi.Post;

public class PostAdapter extends RecyclerView.Adapter<PostAdapter.VH> {

    public interface Listener {
        void onOpen(Post post);

        void onLongPress(Post post);

        void onCardFocused(Post post, PostCardView card);

        void onExpandedChanged(Post post, boolean expanded);

        void onOpenRight(Post post);

        void onNeedMore();
    }

    private final List<Post> items = new ArrayList<>();
    private final java.util.Set<String> expandedIds = new java.util.HashSet<>();
    /** 平行窗口里不折叠长文 */
    private boolean alwaysExpanded;
    private final Listener listener;

    public PostAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setAlwaysExpanded(boolean value) {
        this.alwaysExpanded = value;
    }

    public List<Post> items() {
        return items;
    }

    public void replace(List<Post> posts) {
        items.clear();
        if (posts != null) {
            items.addAll(posts);
        }
        notifyDataSetChanged();
    }

    public void append(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return;
        }
        int start = items.size();
        items.addAll(posts);
        notifyItemRangeInserted(start, posts.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        PostCardView card = new PostCardView(parent.getContext(), new PostCardView.Listener() {
            @Override
            public void onOpen(Post post) {
                listener.onOpen(post);
            }

            @Override
            public void onLongPress(Post post) {
                listener.onLongPress(post);
            }

            @Override
            public void onCardFocused(Post post, PostCardView card) {
                listener.onCardFocused(post, card);
            }

            @Override
            public void onExpandedChanged(Post post, boolean expanded) {
                if (expanded) {
                    expandedIds.add(post.id);
                } else {
                    expandedIds.remove(post.id);
                }
                listener.onExpandedChanged(post, expanded);
            }

            @Override
            public void onOpenRight(Post post) {
                listener.onOpenRight(post);
            }
        });
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(parent.getContext(), 20);
        lp.rightMargin = Ui.dp(parent.getContext(), 20);
        lp.topMargin = Ui.dp(parent.getContext(), 6);
        lp.bottomMargin = Ui.dp(parent.getContext(), 6);
        card.setLayoutParams(lp);
        return new VH(card);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Post p = items.get(position);
        holder.card.bind(p, alwaysExpanded || expandedIds.contains(p.id));
        if (position >= items.size() - 3 && listener != null) {
            listener.onNeedMore();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final PostCardView card;

        VH(PostCardView card) {
            super(card);
            this.card = card;
        }
    }
}
