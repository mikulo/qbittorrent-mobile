package org.qbittorrent.mobile;

import android.graphics.Rect;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.checkbox.MaterialCheckBox;
import java.util.ArrayList;

/** Synthetic metadata only: these regressions never add or contact a torrent. */
@SuppressWarnings("deprecation")
public final class TorrentOptionsDialogTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public TorrentOptionsDialogTest() { super(MainActivity.class); }

    public void testLargeFileListKeepsFooterAndCommitsSelection() throws Throwable {
        MainActivity activity = getActivity();
        ArrayList<TorrentDraft.FileItem> files = new ArrayList<>();
        for (int i = 0; i < 1000; i++) files.add(new TorrentDraft.FileItem(
                "folder/" + i + "/A long file name with multiple words and nested directories.bin", 1024));
        TorrentDraft draft = new TorrentDraft("test", "Long multifile selection regression", "test", 1024000, false, files);
        TorrentOptionsDialog[] dialog = new TorrentOptionsDialog[1];
        boolean[][] result = new boolean[1][];
        int[] discarded = {0};
        runTestOnUiThread(() -> {
            dialog[0] = new TorrentOptionsDialog(activity, draft, "/Download/test",
                    (selected, down, up) -> { assertEquals(128, down); result[0] = selected; }, () -> discarded[0]++);
            dialog[0].show();
        });
        getInstrumentation().waitForIdleSync();
        android.graphics.Bitmap screen = getInstrumentation().getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                new java.io.File(activity.getExternalFilesDir(null), "selection-qa.png"))) {
            screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { screen.recycle(); }
        runTestOnUiThread(() -> {
            assertFooterVisible(dialog[0]);
            dialog[0].findViewById(R.id.select_no_files).performClick();
            assertFalse(dialog[0].findViewById(R.id.selection_confirm).isEnabled());
            dialog[0].findViewById(R.id.select_all_files).performClick();
            assertTrue(dialog[0].findViewById(R.id.selection_confirm).isEnabled());
            ((android.widget.EditText) dialog[0].findViewById(R.id.task_download_limit)).setText("128");
            RecyclerView list = dialog[0].findViewById(R.id.selection_list);
            list.scrollToPosition(1000);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            assertFooterVisible(dialog[0]);
            RecyclerView list = dialog[0].findViewById(R.id.selection_list);
            RecyclerView.ViewHolder last = list.findViewHolderForAdapterPosition(1000);
            assertNotNull(last);
            ((MaterialCheckBox) last.itemView).setChecked(false);
            list.scrollToPosition(0);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            // A short viewport represents the space left when a keyboard is visible.
            dialog[0].getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(300 * activity.getResources().getDisplayMetrics().density));
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            assertFooterVisible(dialog[0]);
            dialog[0].findViewById(R.id.selection_confirm).performClick();
            assertNotNull(result[0]);
            assertEquals(1000, result[0].length);
            assertTrue(result[0][0]);
            assertFalse(result[0][999]);
            assertEquals(0, discarded[0]);
        });
    }

    private void assertFooterVisible(TorrentOptionsDialog dialog) {
        for (int id : new int[]{R.id.selection_cancel, R.id.selection_confirm}) {
            View button = dialog.findViewById(id);
            Rect visible = new Rect();
            assertTrue(button.getGlobalVisibleRect(visible));
            assertEquals(button.getHeight(), visible.height());
            assertEquals(button.getWidth(), visible.width());
        }
    }
}
