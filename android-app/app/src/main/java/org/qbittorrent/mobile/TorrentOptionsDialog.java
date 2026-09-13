package org.qbittorrent.mobile;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Arrays;

/** The footer is outside the scrolling content, independent of file count and name length. */
final class TorrentOptionsDialog extends Dialog {
    interface Commit { void accept(boolean[] selected, int downloadKiB, int uploadKiB); }

    private final TorrentDraft draft;
    private final String savePath;
    private final Commit commit;
    private final Runnable discard;
    private final boolean[] selected;
    private View header;
    private TextInputEditText downloadLimit, uploadLimit;
    private TextView summary;
    private View confirm;
    private boolean committed;

    TorrentOptionsDialog(Context context, TorrentDraft draft, String savePath, Commit commit, Runnable discard) {
        super(context, R.style.Theme_QBittorrent);
        this.draft = draft;
        this.savePath = savePath;
        this.commit = commit;
        this.discard = discard;
        selected = new boolean[draft.files.size()];
        Arrays.fill(selected, true);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_torrent_selection);
        setCanceledOnTouchOutside(false);
        setOnDismissListener(ignored -> { if (!committed) discard.run(); });
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        RecyclerView list = findViewById(R.id.selection_list);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        list.setItemAnimator(null);
        header = LayoutInflater.from(getContext()).inflate(R.layout.dialog_torrent_options, list, false);
        TextView metadata = header.findViewById(R.id.torrent_metadata);
        metadata.setText(getContext().getString(R.string.torrent_metadata_summary, draft.name,
                Formatters.bytes(draft.totalSize), draft.files.size(),
                getContext().getString(draft.privateTorrent ? R.string.yes : R.string.no), savePath));
        downloadLimit = header.findViewById(R.id.task_download_limit);
        uploadLimit = header.findViewById(R.id.task_upload_limit);
        FileAdapter adapter = new FileAdapter();
        list.setAdapter(adapter);
        header.findViewById(R.id.select_all_files).setOnClickListener(v -> setAll(true, adapter));
        header.findViewById(R.id.select_no_files).setOnClickListener(v -> setAll(false, adapter));
        summary = findViewById(R.id.selection_summary);
        confirm = findViewById(R.id.selection_confirm);
        findViewById(R.id.selection_cancel).setOnClickListener(v -> cancel());
        confirm.setOnClickListener(v -> {
            if (!confirm.isEnabled()) return;
            Integer down = readLimit(downloadLimit);
            Integer up = readLimit(uploadLimit);
            if (down == null || up == null) { list.scrollToPosition(0); return; }
            commit.accept(selected.clone(), down, up);
            committed = true;
            dismiss();
        });
        updateSummary();
    }

    private Integer readLimit(TextInputEditText input) {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        try {
            int value = text.isEmpty() ? 0 : Integer.parseInt(text);
            if (value < 0 || value > Integer.MAX_VALUE / 1024) throw new NumberFormatException();
            input.setError(null);
            return value;
        } catch (NumberFormatException error) {
            input.setError(getContext().getString(R.string.invalid_task_limit));
            return null;
        }
    }

    private void setAll(boolean checked, FileAdapter adapter) {
        Arrays.fill(selected, checked);
        adapter.notifyItemRangeChanged(1, selected.length);
        updateSummary();
    }

    private void updateSummary() {
        int count = 0;
        long bytes = 0;
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) { count++; bytes += draft.files.get(i).size; }
        }
        summary.setText(count == 0 ? getContext().getString(R.string.select_at_least_one_file)
                : getContext().getString(R.string.selected_files_summary, count, selected.length, Formatters.bytes(bytes)));
        confirm.setEnabled(count > 0);
    }

    private final class FileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return selected.length + 1; }
        @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View view;
            if (type == 0) view = header;
            else {
                MaterialCheckBox checkbox = new MaterialCheckBox(getContext());
                checkbox.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                int padding = Math.round(16 * getContext().getResources().getDisplayMetrics().density);
                checkbox.setPadding(padding, padding / 2, padding, padding / 2);
                checkbox.setMinHeight(padding * 3);
                view = checkbox;
            }
            return new RecyclerView.ViewHolder(view) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == 0) return;
            int index = position - 1;
            MaterialCheckBox checkbox = (MaterialCheckBox) holder.itemView;
            TorrentDraft.FileItem file = draft.files.get(index);
            checkbox.setOnCheckedChangeListener(null);
            checkbox.setText(file.path + "\n" + Formatters.bytes(file.size));
            checkbox.setChecked(selected[index]);
            checkbox.setOnCheckedChangeListener((button, checked) -> {
                selected[index] = checked;
                updateSummary();
            });
        }
    }
}
