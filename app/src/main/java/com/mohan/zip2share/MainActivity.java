package com.mohan.zip2share;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

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
import android.widget.ProgressBar;
import android.widget.TextView;

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
    private ProgressBar progressBar;
    private View rootView;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<Intent> shareLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootView      = findViewById(R.id.rootLayout);
        statusText    = findViewById(R.id.statusText);
        fileCountText = findViewById(R.id.fileCountText);
        progressBar   = findViewById(R.id.progressBar);

        // Modern activity-result API (replaces deprecated startActivityForResult)
        shareLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> cleanupAndFinish()
        );

        clearCache();
        handleIncomingIntent(getIntent());
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
                createZipFromFiles(list);
            } else {
                showErrorAndFinish(getString(R.string.error_no_files));
            }

        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            ArrayList<Uri> fileUris = getParcelableArrayListExtraCompat(
                intent, Intent.EXTRA_STREAM, Uri.class);
            if (fileUris != null && !fileUris.isEmpty()) {
                createZipFromFiles(fileUris);
            } else {
                showErrorAndFinish(getString(R.string.error_no_files));
            }
        }
        // Launched directly (MAIN launcher): just display idle UI — no-op here
    }

    // -------------------------------------------------------------------------
    // ZIP creation
    // -------------------------------------------------------------------------

    private void createZipFromFiles(List<Uri> fileUris) {
        // Separate directories (unsupported) from regular files up front
        List<Uri>    validUris   = new ArrayList<>();
        List<String> skippedDirs = new ArrayList<>();

        for (Uri uri : fileUris) {
            if (isDirectory(uri)) {
                String name = getFileName(uri);
                skippedDirs.add(name != null ? name : uri.toString());
            } else {
                validUris.add(uri);
            }
        }

        if (validUris.isEmpty()) {
            showErrorAndFinish(getString(R.string.error_directories_only));
            return;
        }

        // Show progress UI
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        fileCountText.setVisibility(View.VISIBLE);
        fileCountText.setText(getString(R.string.status_file_count, validUris.size()));
        setStatus(getString(R.string.status_zipping));

        final List<Uri>    urisToZip = validUris;
        final List<String> skipped   = skippedDirs;

        executor.execute(() -> {
            try {
                String zipFileName = "zip2share_" + System.currentTimeMillis() + ".zip";
                tempZipFile = new File(getCacheDir(), zipFileName);

                // Track names already used to handle duplicates gracefully
                Map<String, Integer> nameCount = new HashMap<>();
                List<String> unreadable = new ArrayList<>(skipped); // combine warnings

                int total = urisToZip.size();

                try (FileOutputStream fos = new FileOutputStream(tempZipFile);
                     ZipOutputStream  zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

                    for (int i = 0; i < total; i++) {
                        final int cur = i + 1;
                        mainHandler.post(() -> {
                            setStatus(getString(R.string.status_zipping_progress, cur, total));
                            progressBar.setIndeterminate(false);
                            progressBar.setMax(total);
                            progressBar.setProgress(cur);
                        });

                        Uri    uri     = urisToZip.get(i);
                        String rawName = getFileName(uri);
                        if (rawName == null || rawName.isEmpty()) {
                            rawName = "file_" + System.currentTimeMillis();
                        }
                        rawName = sanitizeEntryName(rawName);

                        // Deduplicate: "photo.jpg" → "photo (2).jpg" → "photo (3).jpg" …
                        String entryName = deduplicateName(rawName, nameCount);

                        InputStream is;
                        try {
                            is = getContentResolver().openInputStream(uri);
                        } catch (Exception e) {
                            Log.w(TAG, "Cannot open: " + uri, e);
                            final String fn = rawName;
                            mainHandler.post(() -> unreadable.add(fn + " (unreadable)"));
                            continue;
                        }

                        if (is == null) {
                            final String fn = rawName;
                            mainHandler.post(() -> unreadable.add(fn + " (null stream)"));
                            continue;
                        }

                        try (BufferedInputStream bis = new BufferedInputStream(is)) {
                            zos.putNextEntry(new ZipEntry(entryName));
                            byte[] buffer = new byte[8192];
                            int    count;
                            while ((count = bis.read(buffer)) != -1) {
                                zos.write(buffer, 0, count);
                            }
                            zos.closeEntry();
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
        });
    }

    // -------------------------------------------------------------------------
    // File/URI helpers
    // -------------------------------------------------------------------------

    /** Returns true when the URI points to a directory (not a shareable file). */
    private boolean isDirectory(Uri uri) {
        if (!"content".equals(uri.getScheme())) return false;
        try {
            String mimeType = getContentResolver().getType(uri);
            if ("vnd.android.document/directory".equals(mimeType)) return true;
            String path = uri.getPath();
            if (path != null && path.endsWith("/")) return true;
        } catch (Exception e) {
            Log.w(TAG, "isDirectory check failed for " + uri, e);
        }
        return false;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) result = cursor.getString(idx);
                }
            } catch (Exception e) {
                Log.w(TAG, "getFileName failed", e);
            }
        }
        if (result == null) result = uri.getLastPathSegment();
        return result;
    }

    /** Strip path prefixes that some content providers embed in display names. */
    private String sanitizeEntryName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        name = name.replaceAll("^\\.+", "");
        return name.isEmpty() ? "file" : name;
    }

    /**
     * Returns a unique ZIP entry name.
     * "photo.jpg" → already used → "photo (2).jpg" → "photo (3).jpg" …
     */
    private String deduplicateName(String raw, Map<String, Integer> nameCount) {
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
        statusText.setText(msg);
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

    // -------------------------------------------------------------------------
    // API-level compat wrappers for getParcelableExtra (deprecated in API 33)
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private <T> T getParcelableExtraCompat(Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, clazz);
        }
        return intent.getParcelableExtra(key);
    }

    @SuppressWarnings("deprecation")
    private <T> ArrayList<T> getParcelableArrayListExtraCompat(
            Intent intent, String key, Class<T> clazz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableArrayListExtra(key, clazz);
        }
        return intent.getParcelableArrayListExtra(key);
    }

    // -------------------------------------------------------------------------
    // Lifecycle & cleanup
    // -------------------------------------------------------------------------

    /**
     * Only clear the cache dir, not the entire data dir.
     * Deleting app data/ can wipe shared_prefs, databases, and libs — dangerous.
     */
    private void clearCache() {
        try {
            deleteDir(getCacheDir());
        } catch (Exception e) {
            Log.w(TAG, "clearCache failed", e);
        }
    }

    /** Recursively deletes a directory. Safe against null listFiles(). */
    private void deleteDir(File dir) {
        if (dir == null) return;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();   // can be null on permission error
            if (children != null) {
                for (File child : children) deleteDir(child);
            }
        }
        dir.delete();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
