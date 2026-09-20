package com.example.asset;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.example.utils.VynaraLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class AssetManager {
    private static final String CACHE_SUBDIR = "models_cache";

    private final List<Asset> assets = new ArrayList<>();
    private final OkHttpClient httpClient;
    private final Handler mainHandler;

    public interface OnAssetReadyListener {
        void onProgress(int percentage);
        void onSuccess(File assetFile);
        void onError(String message);
    }

    public AssetManager() {
        // Extended timeouts for streaming multi-megabyte 3D files reliably
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setAssets(List<Asset> loadedAssets) {
        assets.clear();
        if (loadedAssets != null) {
            assets.addAll(loadedAssets);
        }
    }

    public List<Asset> getAssets() { 
        return assets; 
    }

    public void addAsset(Asset a) {
        if (a != null && !containsAsset(a.getId())) {
            assets.add(0, a); // Add newest generated assets to the top
        }
    }

    public boolean removeAsset(String assetId) {
        if (assetId == null || assetId.trim().isEmpty()) {
            return false;
        }
        return assets.removeIf(a -> a.getId().equals(assetId));
    }

    public Asset getAssetById(String assetId) {
        if (assetId == null || assetId.trim().isEmpty()) {
            return null;
        }
        for (Asset a : assets) {
            if (a.getId().equals(assetId)) {
                return a;
            }
        }
        return null;
    }

    public boolean containsAsset(String assetId) {
        return getAssetById(assetId) != null;
    }

    public List<Asset> getAssetsByCategory(String category) {
        List<Asset> filtered = new ArrayList<>();
        if (category == null || category.trim().isEmpty() || "ALL".equalsIgnoreCase(category)) {
            return new ArrayList<>(assets);
        }
        for (Asset a : assets) {
            if (category.equalsIgnoreCase(a.getCategory())) {
                filtered.add(a);
            }
        }
        return filtered;
    }

    public List<Asset> searchAssets(String query) {
        List<Asset> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(assets);
        }
        String q = query.toLowerCase().trim();
        for (Asset a : assets) {
            if ((a.getName() != null && a.getName().toLowerCase().contains(q)) ||
                (a.getCategory() != null && a.getCategory().toLowerCase().contains(q)) ||
                (a.getFormat() != null && a.getFormat().toLowerCase().contains(q))) {
                results.add(a);
            }
        }
        return results;
    }

    public void clearAssets() {
        assets.clear();
    }

    // ==========================================
    // Local Ingestion & Storage Access
    // ==========================================

    /**
     * Imports a user-selected 3D model directly from an Android content Uri into
     * internal app storage, infers metadata, inspects header format, and registers it into the catalog.
     */
    public Asset importAssetFromUri(Context context, Uri uri, String fileName) {
        if (context == null || uri == null) {
            VynaraLogger.e("AssetManager: Cannot import asset with null context or URI");
            return null;
        }

        File cacheDir = new File(context.getFilesDir(), CACHE_SUBDIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        String safeFileName = (fileName != null && !fileName.trim().isEmpty())
                ? fileName.trim()
                : "imported_" + System.currentTimeMillis() + ".glb";

        // Prevent file collision
        File destFile = new File(cacheDir, safeFileName);
        if (destFile.exists()) {
            String nameWithoutExt = safeFileName;
            String ext = "";
            int dotIdx = safeFileName.lastIndexOf('.');
            if (dotIdx != -1) {
                nameWithoutExt = safeFileName.substring(0, dotIdx);
                ext = safeFileName.substring(dotIdx);
            }
            safeFileName = nameWithoutExt + "_" + System.currentTimeMillis() + ext;
            destFile = new File(cacheDir, safeFileName);
        }

        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(destFile)) {

            if (is == null) {
                VynaraLogger.e("AssetManager: Failed to open input stream for URI: " + uri);
                return null;
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();

            VynaraLogger.system("AssetManager: Imported " + destFile.getName() + " (" + destFile.length() + " bytes)");

            String id = "asset_" + System.currentTimeMillis();
            String name = sanitizeDisplayName(safeFileName);
            String category = inferCategory(safeFileName);
            String format = inferFormat(safeFileName);
            String fileSizeStr = formatFileSize(destFile.length());

            // Header Inspection for FBX format verification
            if ("FBX".equalsIgnoreCase(format)) {
                if (isBinaryFbx(destFile)) {
                    VynaraLogger.system("AssetManager: Confirmed Binary FBX format for " + destFile.getName());
                } else {
                    VynaraLogger.system("AssetManager: Detected ASCII FBX format for " + destFile.getName() + " (will be auto-normalized to GLB in cloud pipeline)");
                }
            }

            Asset asset;
            try {
                asset = new Asset(id, name, category, format, fileSizeStr, destFile.getAbsolutePath(), 0);
            } catch (Throwable t) {
                // Fallback to 5-param constructor if version variations exist
                asset = new Asset(id, name, category, format, destFile.getAbsolutePath());
            }

            addAsset(asset);
            return asset;

        } catch (Exception e) {
            VynaraLogger.e("AssetManager: Error importing asset from URI: " + e.getMessage(), e);
            if (destFile.exists()) {
                destFile.delete();
            }
            return null;
        }
    }

    /**
     * Inspects the file header to check if an FBX file is standard Binary (starts with "Kaydara FBX Binary")
     * or unsupported plaintext ASCII FBX.
     */
    private boolean isBinaryFbx(File file) {
        if (file == null || !file.exists() || file.length() < 21) return false;
        try (InputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[23];
            int read = fis.read(header);
            if (read >= 18) {
                String prefix = new String(header, 0, Math.min(read, 20), StandardCharsets.US_ASCII);
                return prefix.startsWith("Kaydara FBX Binary");
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String sanitizeDisplayName(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot != -1) {
            base = base.substring(0, dot);
        }
        return base.replace('_', ' ').replace('-', ' ').trim();
    }

    private String inferCategory(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("car") || lower.contains("auto") || lower.contains("vehicle") ||
            lower.contains("truck") || lower.contains("sedan") || lower.contains("coupe") ||
            lower.contains("motor") || lower.contains("bmw") || lower.contains("audi") ||
            lower.contains("r8") || lower.contains("porsche") || lower.contains("ferrari") ||
            lower.contains("lambo") || lower.contains("mercedes") || lower.contains("ford")) {
            return "Vehicle";
        } else if (lower.contains("char") || lower.contains("hero") || lower.contains("biped") ||
                   lower.contains("human") || lower.contains("person") || lower.contains("man") ||
                   lower.contains("girl")) {
            return "Character";
        } else if (lower.contains("dog") || lower.contains("cat") || lower.contains("creature") ||
                   lower.contains("animal") || lower.contains("monster")) {
            return "Creature";
        } else if (lower.contains("road") || lower.contains("street") || lower.contains("track") ||
                   lower.contains("terrain") || lower.contains("ground") || lower.contains("tree") ||
                   lower.contains("plant") || lower.contains("grass") || lower.contains("env")) {
            return "Environment";
        } else if (lower.contains("house") || lower.contains("building") || lower.contains("villa") ||
                   lower.contains("tower") || lower.contains("bridge") || lower.contains("wall")) {
            return "Architecture";
        } else if (lower.contains("chair") || lower.contains("table") || lower.contains("sofa") ||
                   lower.contains("desk") || lower.contains("furniture")) {
            return "Furniture";
        }
        return "Objects";
    }

    private String inferFormat(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot != -1 && dot < fileName.length() - 1) {
            return fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
        }
        return "GLB";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.ROOT, "%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    // ==========================================
    // On-Demand Asset Streaming & Disk Caching
    // ==========================================

    public void fetchAssetOnDemand(Context context, String assetId, String downloadUrl, OnAssetReadyListener listener) {
        if (context == null || assetId == null || assetId.trim().isEmpty()) {
            if (listener != null) listener.onError("Invalid asset identification or context.");
            return;
        }

        File cacheDir = new File(context.getFilesDir(), CACHE_SUBDIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        String fileName = assetId.endsWith(".glb") ? assetId : assetId + ".glb";
        File localFile = new File(cacheDir, fileName);

        // Fast Path: Check if model is already stored locally on disk
        if (localFile.exists() && localFile.length() > 0) {
            VynaraLogger.system("AssetManager: Cache hit for asset: " + assetId + " (" + localFile.length() + " bytes)");
            if (listener != null) {
                listener.onProgress(100);
                listener.onSuccess(localFile);
            }
            return;
        }

        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            if (listener != null) {
                listener.onError("Asset is not cached locally and no remote download URL was provided.");
            }
            return;
        }

        VynaraLogger.system("AssetManager: Streaming asset on-demand from: " + downloadUrl);

        Request request = new Request.Builder()
                .url(downloadUrl.trim())
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                VynaraLogger.e("Asset stream connection error: " + e.getMessage(), e);
                if (listener != null) {
                    mainHandler.post(() -> listener.onError("Network stream failed: " + e.getMessage()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError("Server returned HTTP " + response.code()));
                    }
                    response.close();
                    return;
                }

                ResponseBody body = response.body();
                long totalBytes = body.contentLength();

                try (InputStream is = body.byteStream();
                     FileOutputStream fos = new FileOutputStream(localFile)) {

                    byte[] buffer = new byte[8192];
                    long totalBytesRead = 0;
                    int read;

                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                        totalBytesRead += read;

                        if (totalBytes > 0 && listener != null) {
                            int progress = (int) ((totalBytesRead * 100) / totalBytes);
                            mainHandler.post(() -> listener.onProgress(progress));
                        }
                    }
                    fos.flush();

                    if (localFile.exists() && localFile.length() > 0) {
                        Asset downloadedAsset = new Asset(assetId, assetId, "STREAMED", "GLB", localFile.getAbsolutePath());
                        addAsset(downloadedAsset);

                        if (listener != null) {
                            mainHandler.post(() -> listener.onSuccess(localFile));
                        }
                    } else {
                        if (listener != null) {
                            mainHandler.post(() -> listener.onError("Downloaded file is empty."));
                        }
                    }

                } catch (Exception ex) {
                    if (localFile.exists()) {
                        localFile.delete();
                    }
                    VynaraLogger.e("Failed writing streamed asset to storage", ex);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError("File storage error: " + ex.getMessage()));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    public File getLocalCachedFile(Context context, String assetId) {
        if (context == null || assetId == null) return null;
        File cacheDir = new File(context.getFilesDir(), CACHE_SUBDIR);
        String fileName = assetId.endsWith(".glb") ? assetId : assetId + ".glb";
        File file = new File(cacheDir, fileName);
        return file.exists() ? file : null;
    }

    public boolean isAssetCached(Context context, String assetId) {
        File file = getLocalCachedFile(context, assetId);
        return file != null && file.length() > 0;
    }
}