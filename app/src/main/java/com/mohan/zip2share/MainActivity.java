package com.mohan.zip2share;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.mohan.zip2share.databinding.ActivityMainBinding;

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
    private ActivityMainBinding binding;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<Intent> shareLauncher;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Follow the system light/dark setting — must be called before super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // Modern activity-result API (replaces deprecated startActivityForResult)
        shareLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> cleanupAndFinish()
        );

        clearCache();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Intent dispatching
    // -------------------------------------------------------------------------

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) { finish(); return; }

        String action = intent.getAction();
        String type   = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Uri fileUri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri.class);
            if (fileUri != null) {
                List<Uri> list = new ArrayList<>();
                list.add(fileUri);
                createZipFromUris(list);
            } else {
                showErrorAndFinish(getString(R.string.error_no_files));
            }

        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            ArrayList<Uri> fileUris = getParcelableArrayListExtraCompat(
                intent, Intent.EXTRA_STREAM, Uri.class);
            if (fileUris != null && !fileUris.isEmpty()) {
                createZipFromUris(fileUris);
            } else {
                showErrorAndFinish(getString(R.string.error_no_files));
            }
        }
        // Launched directly (MAIN launcher): show idle UI — no-op
    }

    // -------------------------------------------------------------------------
    // ZIP creation — supports both files and directories
    // -------------------------------------------------------------------------

    /**
     * Entry point: resolves each URI to either a plain file or a directory tree,
     * then hands off to the background worker.
     */
    private void createZipFromUris(List<Uri> uris) {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.progressBar.setIndeterminate(true);
        setStatus(getString(R.string.status_zipping));

        executor.execute(() -> {
            List<ZipTask>  tasks    = new ArrayList<>();
            List<String>   failures = new ArrayList<>();
            int            dirCount = 0;

            for (Uri uri : uris) {
                if (isDirectory(uri)) {
                    dirCount++;
                    String dirName = getDisplayName(uri);
                    if (dirName == null || dirName.isEmpty()) dirName = "folder_" + dirCount;
                    dirName = sanitizeEntryName(dirName);
                    collectDirectory(uri, dirName + "/", tasks, failures);
                } else {
                    String name = getDisplayName(uri);
                    if (name == null || name.isEmpty()) name = "file_" + System.currentTimeMillis();
                    tasks.add(new ZipTask(uri, sanitizeEntryName(name), getLastModified(uri)));
                }
            }

            if (tasks.isEmpty()) {
                mainHandler.post(() -> showErrorAndFinish(getString(R.string.error_no_files)));
                return;
            }

            // Deduplicate entry names across all tasks
            Map<String, Integer> nameCount = new HashMap<>();
            for (ZipTask t : tasks) {
                t.entryName = deduplicateName(t.entryName, nameCount);
            }

            final int total = tasks.size();
            mainHandler.post(() -> {
                binding.fileCountText.setVisibility(View.VISIBLE);
                binding.fileCountText.setText(getString(R.string.status_file_count, total));
                binding.progressBar.setIndeterminate(false);
                binding.progressBar.setMax(total);
                binding.progressBar.setProgress(0);
            });

            try {
                String zipFileName = "zip2share_" + System.currentTimeMillis() + ".zip";
                tempZipFile = new File(getCacheDir(), zipFileName);

                try (FileOutputStream fos = new FileOutputStream(tempZipFile);
                     ZipOutputStream  zos = new ZipOutputStream(new BufferedOutputStream(fos, 65536))) {

                    for (int i = 0; i < total; i++) {
                        final int cur = i + 1;
                        mainHandler.post(() -> {
                            setStatus(getString(R.string.status_zipping_progress, cur, total));
                            binding.progressBar.setProgress(cur);
                        });

                        ZipTask task = tasks.get(i);

                        // Directory marker entry (no content)
                        if (task.uri == null) {
                            zos.putNextEntry(buildEntry(task.entryName, task.lastModified));
                            zos.closeEntry();
                            continue;
                        }

                        InputStream is;
                        try {
                            is = getContentResolver().openInputStream(task.uri);
                        } catch (Exception e) {
                            Log.w(TAG, "Cannot open: " + task.uri, e);
                            failures.add(task.entryName + " (unreadable)");
                            continue;
                        }

                        if (is == null) {
                            failures.add(task.entryName + " (null stream)");
                            continue;
                        }

                        try (BufferedInputStream bis = new BufferedInputStream(is, 65536)) {
                            zos.putNextEntry(buildEntry(task.entryName, task.lastModified));
                            byte[] buffer = new byte[65536];
                            int count;
                            while ((count = bis.read(buffer)) != -1) {
                                zos.write(buffer, 0, count);
                            }
                            zos.closeEntry();
                        }
                    }
                }

                mainHandler.post(() -> {
                    binding.progressBar.setProgress(total);
                    if (!failures.isEmpty()) {
                        showSkippedWarning(failures);
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
        });
    }

    /**
     * Recursively walks a directory URI via DocumentFile and appends ZipTask entries.
     * Preserves the full folder hierarchy inside the ZIP (e.g. "Photos/2024/img.jpg").
     */
    private void collectDirectory(Uri dirUri, String prefix,
                                  List<ZipTask> tasks, List<String> failures) {
        DocumentFile dir = null;

        // Try tree URI first (ACTION_OPEN_DOCUMENT_TREE result)
        try {
            dir = DocumentFile.fromTreeUri(this, dirUri);
        } catch (Exception ignored) {}

        // Fall back to single-uri form
        if (dir == null || !dir.isDirectory()) {
            try {
                dir = DocumentFile.fromSingleUri(this, dirUri);
            } catch (Exception ignored) {}
        }

        if (dir == null || !dir.isDirectory()) {
            failures.add(prefix + " (could not open directory)");
            return;
        }

        // Add a directory marker so empty directories are preserved in the ZIP
        tasks.add(new ZipTask(null, prefix, dir.lastModified()));

        DocumentFile[] children = dir.listFiles();
        if (children == null) return;

        for (DocumentFile child : children) {
            if (child == null) continue;
            String childName = child.getName();
            if (childName == null || childName.isEmpty()) continue;
            childName = sanitizeEntryName(childName);

            if (child.isDirectory()) {
                collectDirectory(child.getUri(), prefix + childName + "/", tasks, failures);
            } else {
                tasks.add(new ZipTask(child.getUri(),
                                      prefix + childName,
                                      child.lastModified()));
            }
        }
    }

    /**
     * Builds a ZipEntry with the last-modified timestamp preserved.
     * Uses setTime() for broad API compatibility (minSdk 21).
     */
    private ZipEntry buildEntry(String name, long lastModifiedMs) {
        ZipEntry entry = new ZipEntry(name);
        if (lastModifiedMs > 0) {
            entry.setTime(lastModifiedMs);
        }
        return entry;
    }

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    /** One entry destined for the ZIP archive. */
    private static class ZipTask {
        Uri    uri;          // null = directory marker (no content to write)
        String entryName;
        long   lastModified; // ms epoch; 0 = unknown

        ZipTask(Uri uri, String entryName, long lastModified) {
            this.uri          = uri;
            this.entryName    = entryName;
            this.lastModified = lastModified;
        }
    }

    // -------------------------------------------------------------------------
    // URI / metadata helpers
    // -------------------------------------------------------------------------

    /** Returns true when the URI points to a directory rather than a plain file. */
    private boolean isDirectory(Uri uri) {
        if (!"content".equals(uri.getScheme())) return false;
        try {
            String mime = getContentResolver().getType(uri);
            if ("vnd.android.document/directory".equals(mime)) return true;
            if ("vnd.android.cursor.dir/file".equals(mime))    return true;
            String path = uri.getPath();
            if (path != null && path.endsWith("/")) return true;
        } catch (Exception e) {
            Log.w(TAG, "isDirectory check failed for " + uri, e);
        }
        // DocumentFile fallback for tree URIs
        try {
            DocumentFile df = DocumentFile.fromSingleUri(this, uri);
            if (df != null && df.isDirectory()) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Returns the human-readable display name for a URI. */
    private String getDisplayName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) result = cursor.getString(idx);
                }
            } catch (Exception e) {
                Log.w(TAG, "getDisplayName failed", e);
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        return result;
    }

    /** Returns the last-modified timestamp in milliseconds (0 if unavailable). */
    private long getLastModified(Uri uri) {
        if (!"content".equals(uri.getScheme())) return 0;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                for (String col : new String[]{"last_modified", "date_modified", "_modified"}) {
                    int idx = cursor.getColumnIndex(col);
                    if (idx != -1) {
                        long val = cursor.getLong(idx);
                        // Some providers store epoch-seconds, others epoch-milliseconds
                        return (val > 0 && val < 1_000_000_000_000L) ? val * 1000L : val;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getLastModified failed", e);
        }
        return 0;
    }

    /** Strips path separators and control characters; never returns an empty string. */
    private String sanitizeEntryName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        name = name.replaceAll("^\\.+", "");
        name = name.replaceAll("[\\x00-\\x1F\\x7F]", "_");
        return name.isEmpty() ? "file" : name;
    }

    /**
     * Returns a collision-free ZIP entry name.
     * Directory markers (ending in "/") are never modified.
     */
    private String deduplicateName(String raw, Map<String, Integer> nameCount) {
        if (raw.endsWith("/")) return raw;
        String key = raw.toLowerCase();
        if (!nameCount.containsKey(key)) {
            nameCount.put(key, 1);
            return raw;
        }
        int n = nameCount.get(key) + 1;
        nameCount.put(key, n);
        int dot = raw.lastIndexOf('.');
        if (dot > 0) return raw.substring(0, dot) + " (" + n + ")" + raw.substring(dot);
        return raw + " (" + n + ")";
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void setStatus(String msg) {
        binding.statusText.setText(msg);
    }

    private void launchShare() {
        Uri contentUri = FileProvider.getUriForFile(
            this, getPackageName() + ".provider", tempZipFile);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/zip");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            shareLauncher.launch(
                Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)));
        } catch (ActivityNotFoundException e) {
            showErrorAndFinish(getString(R.string.error_no_share_app));
        }
    }

    private void showSkippedWarning(List<String> skipped) {
        binding.progressBar.setVisibility(View.GONE);
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
        binding.progressBar.setVisibility(View.GONE);
        setStatus(message);
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_dismiss, v -> finish())
            .show();
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

    // -------------------------------------------------------------------------
    // API-level compat wrappers (getParcelableExtra deprecated in API 33)
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private <T extends android.os.Parcelable> T getParcelableExtraCompat(
            Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, clazz);
        }
        return intent.getParcelableExtra(key);
    }

    @SuppressWarnings("deprecation")
    private <T extends android.os.Parcelable> ArrayList<T> getParcelableArrayListExtraCompat(
            Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableArrayListExtra(key, clazz);
        }
        return intent.getParcelableArrayListExtra(key);
    }

    // -------------------------------------------------------------------------
    // Cache / lifecycle cleanup
    // -------------------------------------------------------------------------

    private void clearCache() {
        try {
            deleteDir(getCacheDir());
        } catch (Exception e) {
            Log.w(TAG, "clearCache failed", e);
        }
    }

    private void deleteDir(File dir) {
        if (dir == null) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) deleteDir(child);
            }
        }
        dir.delete();
    }
}
