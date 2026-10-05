package dev.veedo.llmwear.mobile;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class ModelStore {
    private static final AtomicBoolean importing = new AtomicBoolean();
    private static volatile String importStatus = "";

    static File modelFile(Context context) {
        return new File(context.getFilesDir(), "models/model.litertlm");
    }

    static String modelName(Context context) {
        return context.getSharedPreferences("model", Context.MODE_PRIVATE)
                .getString("name", "Imported model");
    }

    static boolean useGpu(Context context) {
        return context.getSharedPreferences("model", Context.MODE_PRIVATE).getBoolean("gpu", false);
    }

    static void setUseGpu(Context context, boolean value) {
        context.getSharedPreferences("model", Context.MODE_PRIVATE).edit().putBoolean("gpu", value).apply();
    }

    static boolean isImporting() {
        return importing.get();
    }

    static String importStatus() {
        return importStatus;
    }

    static void importModel(Context context, Uri uri) {
        if (LlmApiService.isActive() || !importing.compareAndSet(false, true)) {
            return;
        }
        Context app = context.getApplicationContext();
        importStatus = "Importing model...";
        new Thread(() -> {
            File target = modelFile(app);
            File temporary = new File(target.getParentFile(), "import.part");
            try {
                String name = "model.litertlm";
                long declaredSize = -1;
                try (Cursor cursor = app.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            name = cursor.getString(nameIndex);
                        }
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            declaredSize = cursor.getLong(sizeIndex);
                        }
                    }
                }
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".litertlm")) {
                    throw new IllegalArgumentException("Choose a .litertlm file");
                }
                Files.createDirectories(target.getParentFile().toPath());
                long bytes = 0;
                long lastUpdate = 0;
                MessageDigest digest = ModelIntegrity.sha256();
                try (InputStream input = app.getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) {
                        throw new IllegalStateException("Cannot read the selected file");
                    }
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        bytes += count;
                        if (System.currentTimeMillis() - lastUpdate > 300) {
                            importStatus = "Importing: " + bytes / (1024 * 1024) + " MB";
                            lastUpdate = System.currentTimeMillis();
                        }
                    }
                    output.getFD().sync();
                }
                String sha256 = ModelIntegrity.hex(digest.digest());
                ModelIntegrity.verify(name, bytes, declaredSize, sha256);
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                app.getSharedPreferences("model", Context.MODE_PRIVATE).edit().putString("name", name).commit();
                importStatus = "Model imported";
            } catch (Exception e) {
                importStatus = "Import failed: " + e.getMessage();
            } finally {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (Exception ignored) {
                }
                importing.set(false);
            }
        }, "model-import").start();
    }
}
