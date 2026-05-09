package com.mohan.zip2share;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Zip2Share";

    private File tempZipFile;
    private TextView statusText;
    private TextView fileCountText;
    private LinearProgressIndicator progressBar;
    private View rootView;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<Intent> shareLauncher;

    // Represents a file entry to be zipped: a uri + the path it should have inside the zip
    private static class ZipTask {
        final Uri    uri;
        final String entryPath; // e.g. "folder/sub/photo.jpg"
        ZipTask(Uri uri, String entryPath) { this.uri = uri; this.entryPath = entryPath; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Edge-to-edge + system bar colour ──────────────────────────────────
        // Tell the framework we will handle insets ourselves
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        // After setContentView the decor is ready; tint bars via controller
        applySystemBarColors();

        rootView      = findViewById(R.id.rootLayout);
        statusText    = findViewById(R.id.statusText);
        fileCountText = findViewById(R.id.fileCountText);
        progressBar   = findViewById(R.id.progressBar);

        shareLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> cleanupAndFinish()
        );

        clearCache();
        handleIncomingIntent(getIntent());
    }

    /**
     * Makes status bar and nav bar icons dark-on-light or light-on-dark
     * depending on current night mode, and sets the status bar background
     * to colorSurfaceVariant (a muted, tinted tone — never blinding white).
     */
    private void applySystemBarColors() {
        Window window = getWindow();
        // colorSurfaceVariant is always a step darker/more tinted than colorSurface
        // — it reads naturally on both light and dark themes.
        int nightMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isNight = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(window, window.getDecorView());
        // light icons on dark bg (night), dark icons on light bg (day)
        controller.setAppearanceLightStatusBars(!isNight);
        controller.setAppearanceLightNavigationBars(!isNight);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) { finish(); return; }
        String action = intent.getAction();
        String type   = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Uri uri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri.class);
            if (uri != null) {
                List<Uri> list = new ArrayList<>();
                list.add(uri);
                startZipPipeline(list);
            } else {
                showErrorAndFinish(getString(R.string.error_no_files));
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            ArrayList<Uri> uris = getParcelableArrayListExtraCompat(
                intent, Intent.EXTRA_STREAM, Uri.class);
            if (uris != null && !uris.isEmpty()) {
                startZipPipeline(uris);
            } else {
                showErrorAndFinish(getString(R.string.error_no_files));
            }
        }
        // Direct launch → idle UI, nothing to do
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZIP pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point: given a raw list of URIs (may include folders), expand
     * folders recursively then hand off to createZip().
     */
    private void startZipPipeline(List<Uri> rawUris) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        setStatus(getString(R.string.status_scanning));

        executor.execute(() -> {
            List<ZipTask>  tasks   = new ArrayList<>();
            List<String>   skipped = new ArrayList<>();

            for (Uri uri : rawUris) {
                String topName = getDisplayName(uri);
                if (topName == null) topName = "file_" + System.currentTimeMillis();

                if (isDocumentDirectory(uri)) {
                    // Expand the directory tree into individual file tasks
                    expandDirectory(uri, topName + "/", tasks, skipped);
                } else {
                    tasks.add(new ZipTask(uri, sanitizeEntryName(topName)));
                }
            }

            if (tasks.isEmpty()) {
                mainHandler.post(() ->
                    showErrorAndFinish(getString(R.string.error_directories_only)));
                return;
            }

            final int total = tasks.size();
            mainHandler.post(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setMax(total);
                fileCountText.setVisibility(View.VISIBLE);
                fileCountText.setText(getString(R.string.status_file_count, total));
                setStatus(getString(R.string.status_zipping));
            });

            createZip(tasks, skipped, total);
        });
    }

    /**
     * Recursively walks a document-tree directory, appending ZipTask entries
     * for every file found. Uses DocumentsContract for proper SAF traversal.
     */
    private void expandDirectory(Uri dirUri, String pathPrefix,
                                  List<ZipTask> tasks, List<String> skipped) {
        // Build the children URI from the document URI
        Uri childrenUri;
        try {
            String docId = DocumentsContract.getDocumentId(dirUri);
            childrenUri  = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, docId);
        } catch (Exception e) {
            // Not a tree URI — some file managers send a plain document URI for a folder
            // Try to use the uri directly as a tree root
            try {
                String treeDocId = DocumentsContract.getTreeDocumentId(dirUri);
                childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, treeDocId);
            } catch (Exception e2) {
                Log.w(TAG, "Cannot expand dir URI: " + dirUri, e2);
                skipped.add(pathPrefix + " (cannot read directory)");
                return;
            }
        }

        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(
                childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                skipped.add(pathPrefix + " (unreadable)");
                return;
            }
            while (cursor.moveToNext()) {
                String childDocId  = cursor.getString(0);
                String childName   = cursor.getString(1);
                String childMime   = cursor.getString(2);
                if (childName == null) childName = childDocId;

                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, childDocId);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(childMime)) {
                    expandDirectory(childUri, pathPrefix + childName + "/", tasks, skipped);
                } else {
                    tasks.add(new ZipTask(childUri, pathPrefix + sanitizeEntryName(childName)));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "expandDirectory failed for " + dirUri, e);
            skipped.add(pathPrefix + " (read error: " + e.getMessage() + ")");
        }
    }

    /** Performs the actual ZIP creation from a flat list of ZipTasks. */
    private void createZip(List<ZipTask> tasks, List<String> alreadySkipped, int total) {
        // Must run on executor thread (called from executor.execute block already)
        List<String> unreadable = new ArrayList<>(alreadySkipped);
        Map<String, Integer> nameCount = new HashMap<>();

        try {
            String zipFileName = "zip2share_" + System.currentTimeMillis() + ".zip";
            tempZipFile = new File(getCacheDir(), zipFileName);

            try (FileOutputStream fos = new FileOutputStream(tempZipFile);
                 ZipOutputStream  zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

                for (int i = 0; i < tasks.size(); i++) {
                    final int cur = i + 1;
                    ZipTask task  = tasks.get(i);
                    final String entryName = deduplicateName(task.entryPath, nameCount);

                    mainHandler.post(() -> {
                        setStatus(getString(R.string.status_zipping_progress, cur, total));
                        progressBar.setProgress(cur);
                    });

                    InputStream is = null;
                    try {
                        is = getContentResolver().openInputStream(task.uri);
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot open stream: " + task.uri, e);
                        unreadable.add(entryName + " (unreadable)");
                        continue;
                    }

                    if (is == null) {
                        unreadable.add(entryName + " (null stream)");
                        continue;
                    }

                    try (BufferedInputStream bis = new BufferedInputStream(is)) {
                        zos.putNextEntry(new ZipEntry(entryName));
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = bis.read(buf)) != -1) zos.write(buf, 0, n);
                        zos.closeEntry();
                    } catch (IOException e) {
                        Log.w(TAG, "Failed writing entry: " + entryName, e);
                        unreadable.add(entryName + " (write error)");
                    }
                }
            }

            mainHandler.post(() -> {
                progressBar.setProgress(total);
                if (!unreadable.isEmpty()) {
                    showSkippedWarning(unreadable);
                } else {
                    setStatus(getString(R.string.status_ready));
                    launchShare();
                }
            });

        } catch (IOException e) {
            Log.e(TAG, "ZIP creation failed", e);
            mainHandler.post(() ->
                showErrorAndFinish(getString(R.string.error_zip_failed, e.getMessage())));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URI / file helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * True when the URI represents a directory/folder.
     * Checks both the MIME type from ContentResolver and DocumentsContract.
     */
    private boolean isDocumentDirectory(Uri uri) {
        if (!"content".equals(uri.getScheme())) return false;
        try {
            String mime = getContentResolver().getType(uri);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) return true;
            // Some providers return "vnd.android.document/directory"
            if (mime != null && mime.contains("directory")) return true;
            // Fallback: try querying the document's own MIME column
            String[] proj = { DocumentsContract.Document.COLUMN_MIME_TYPE };
            try (Cursor c = getContentResolver().query(uri, proj, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String docMime = c.getString(0);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(docMime)) return true;
                    if (docMime != null && docMime.contains("directory")) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isDocumentDirectory check failed: " + uri, e);
        }
        return false;
    }

    private String getDisplayName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx == -1) idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    if (idx != -1) result = c.getString(idx);
                }
            } catch (Exception e) {
                Log.w(TAG, "getDisplayName failed", e);
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        return result;
    }

    private String sanitizeEntryName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        name = name.replaceAll("^\\.+", "");
        return name.isEmpty() ? "file" : name;
    }

    private String deduplicateName(String raw, Map<String, Integer> nameCount) {
        String key = raw.toLowerCase();
        if (!nameCount.containsKey(key)) {
            nameCount.put(key, 1);
            return raw;
        }
        int n = nameCount.get(key) + 1;
        nameCount.put(key, n);
        // Preserve directory path prefix: "folder/photo.jpg" → "folder/photo (2).jpg"
        int lastSlash = raw.lastIndexOf('/');
        String dir  = lastSlash >= 0 ? raw.substring(0, lastSlash + 1) : "";
        String base = lastSlash >= 0 ? raw.substring(lastSlash + 1) : raw;
        int dot = base.lastIndexOf('.');
        if (dot > 0) return dir + base.substring(0, dot) + " (" + n + ")" + base.substring(dot);
        return dir + base + " (" + n + ")";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setStatus(String msg) { statusText.setText(msg); }

    private void launchShare() {
        Uri contentUri = FileProvider.getUriForFile(
            this, getPackageName() + ".provider", tempZipFile);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/zip");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            shareLauncher.launch(Intent.createChooser(shareIntent,
                getString(R.string.share_chooser_title)));
        } catch (ActivityNotFoundException e) {
            showErrorAndFinish(getString(R.string.error_no_share_app));
        }
    }

    private void showSkippedWarning(List<String> skipped) {
        progressBar.setVisibility(View.GONE);
        StringBuilder sb = new StringBuilder(getString(R.string.warn_skipped_intro));
        for (String s : skipped) sb.append("\n• ").append(s);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.warn_skipped_title)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.action_share_anyway, (d, w) -> {
                setStatus(getString(R.string.status_ready));
                launchShare();
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> cleanupAndFinish())
            .setCancelable(false)
            .show();
    }

    private void showErrorAndFinish(String message) {
        progressBar.setVisibility(View.GONE);
        setStatus(message);
        if (rootView != null) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_dismiss, v -> finish())
                .show();
        }
        mainHandler.postDelayed(this::cleanupAndFinish, 3500);
    }

    private void cleanupAndFinish() {
        if (tempZipFile != null && tempZipFile.exists()) {
            if (!tempZipFile.delete()) {
                mainHandler.postDelayed(() -> {
                    if (tempZipFile != null) tempZipFile.delete();
                }, 5000);
            }
        }
        finish();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compat wrappers (getParcelableExtra deprecated in API 33)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private <T extends android.os.Parcelable> T getParcelableExtraCompat(
            Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return intent.getParcelableExtra(key, clazz);
        return intent.getParcelableExtra(key);
    }

    @SuppressWarnings("deprecation")
    private <T extends android.os.Parcelable> ArrayList<T> getParcelableArrayListExtraCompat(
            Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return intent.getParcelableArrayListExtra(key, clazz);
        return intent.getParcelableArrayListExtra(key);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    private void clearCache() {
        try { deleteDir(getCacheDir()); }
        catch (Exception e) { Log.w(TAG, "clearCache", e); }
    }

    private void deleteDir(File dir) {
        if (dir == null) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) for (File c : children) deleteDir(c);
        }
        dir.delete();
    }

    @Override protected void onDestroy() { executor.shutdown(); super.onDestroy(); }
}
