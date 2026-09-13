package org.qbittorrent.mobile;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TorrentAdapter extends RecyclerView.Adapter<TorrentAdapter.Holder> {
    interface Actions {
        void onOpen(TorrentSnapshot item);
        void onToggle(TorrentSnapshot item);
        void onDelete(TorrentSnapshot item);
    }

    private final ArrayList<TorrentSnapshot> items = new ArrayList<>();
    private final Actions actions;

    TorrentAdapter(Actions actions) { this.actions = actions; }

    void submit(List<TorrentSnapshot> next) {
        ArrayList<TorrentSnapshot> old = new ArrayList<>(items);
        ArrayList<TorrentSnapshot> updated = new ArrayList<>(next);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return updated.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return old.get(oldPosition).hash.equals(updated.get(newPosition).hash);
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                TorrentSnapshot a = old.get(oldPosition);
                TorrentSnapshot b = updated.get(newPosition);
                return a.name.equals(b.name) && a.progress == b.progress && a.downloadRate == b.downloadRate
                        && a.completed == b.completed && a.total == b.total && a.uploaded == b.uploaded
                        && a.uploadRate == b.uploadRate && a.peers == b.peers && a.seeds == b.seeds
                        && a.etaSeconds == b.etaSeconds && a.state.equals(b.state) && a.group == b.group;
            }
        });
        items.clear();
        items.addAll(updated);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_torrent, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        TorrentSnapshot item = items.get(position);
        holder.name.setText(item.name);
        holder.progress.setProgress(Math.round(item.progress * 100000));
        holder.percent.setText(String.format(Locale.getDefault(), "%.1f%%  ·  %s / %s", item.progress * 100,
                Formatters.downloadedBytes(item.completed), Formatters.bytes(item.total)));
        holder.state.setText(stateLabel(holder.itemView.getContext(), item));
        holder.speeds.setText(holder.itemView.getContext().getString(R.string.torrent_speeds,
                Formatters.speed(item.downloadRate), Formatters.speed(item.uploadRate), Formatters.duration(item.etaSeconds)));
        holder.peers.setText(holder.itemView.getContext().getString(R.string.torrent_peers,
                item.seeds, item.peers, Formatters.bytes(item.uploaded)));
        boolean paused = item.group == TorrentSnapshot.Group.PAUSED;
        holder.pause.setText(paused ? R.string.resume : R.string.pause);
        holder.itemView.setOnClickListener(v -> actions.onOpen(item));
        holder.pause.setOnClickListener(v -> actions.onToggle(item));
        holder.delete.setOnClickListener(v -> actions.onDelete(item));
    }

    @Override public int getItemCount() { return items.size(); }

    private String stateLabel(Context context, TorrentSnapshot item) {
        switch (item.group) {
            case SEEDING: return context.getString(R.string.state_seeding);
            case PAUSED: return context.getString(R.string.state_paused);
            case CHECKING: return context.getString(R.string.state_checking);
            case ERROR: return context.getString(R.string.state_error);
            default: return context.getString(R.string.state_downloading);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, state, percent, speeds, peers;
        final ProgressBar progress;
        final MaterialButton pause, delete;
        Holder(@NonNull View view) {
            super(view);
            name = view.findViewById(R.id.name);
            state = view.findViewById(R.id.state);
            percent = view.findViewById(R.id.percent);
            speeds = view.findViewById(R.id.speeds);
            peers = view.findViewById(R.id.peers);
            progress = view.findViewById(R.id.progress);
            pause = view.findViewById(R.id.pause_button);
            delete = view.findViewById(R.id.delete_button);
        }
    }
}
