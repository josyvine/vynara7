package com.example.cloud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.example.ai.ApiKeyManager;
import com.example.asset.Asset;
import com.example.runtime.ProjectRuntime;
import com.example.utils.VynaraLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class GitHubWorkflowBridge {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    // 300 seconds HTTP socket timeout for network resilience
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final long POLLING_INTERVAL_MS = 2500; // Optimized polling interval (2.5s)
    private static final long MAX_POLLING_DURATION_MS = 600000; // 10 minutes timeout (supports high-fidelity renders)
    private static final int MAX_ARTIFACT_RETRY_ATTEMPTS = 10; // 10 retries window for run-specific artifact indexing
    private static final long ARTIFACT_RETRY_DELAY_MS = 1500; // 1.5 seconds between artifact retries

    private static volatile String sLastBlenderError = null;
    private static volatile String sLastBlenderTraceback = null;
    private static volatile File sLastRenderImage = null;
    private static volatile File sLastRenderVideo = null;

    private final OkHttpClient httpClient;
    private final Handler mainHandler;

    public interface WorkflowDispatchCallback {
        void onDispatched(String eventType, String assetId);
        void onError(String errorMessage);
    }

    public interface ArtifactDownloadCallback {
        void onProgress(int percentage, long bytesRead, long totalBytes);
        void onSuccess(File downloadedFile);
        void onError(String errorMessage);

        default void onScriptExecutionFailed(String errorTraceback) {
            onError("Blender Execution Error: " + errorTraceback);
        }

        default void onRenderPreviewReady(File renderPreviewFile) {}

        default void onVideoReady(File videoFile) {}
    }

    public interface ConnectionTestCallback {
        void onSuccess(String repoFullName, boolean hasWorkflowAccess);
        void onError(String errorMessage);
    }

    public interface WorkflowPollingCallback {
        void onStatusUpdate(String status, String details);
        void onProgress(int percentage, long bytesRead, long totalBytes);
        void onSuccess(File downloadedFile);
        void onError(String errorMessage);

        default void onScriptExecutionFailed(long runId, String errorTraceback) {
            onError("Blender Execution Error: " + errorTraceback);
        }

        default void onRenderPreviewReady(File renderPreviewFile) {}

        default void onVideoReady(File videoFile) {}
    }

    public GitHubWorkflowBridge() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static String getLastBlenderError() {
        return sLastBlenderError;
    }

    public static String getLastBlenderTraceback() {
        return sLastBlenderTraceback;
    }

    public static File getLatestRenderImage() {
        return sLastRenderImage;
    }

    public static File getLatestRenderVideo() {
        return sLastRenderVideo;
    }

    public static void clearLastBlenderError() {
        sLastBlenderError = null;
        sLastBlenderTraceback = null;
        sLastRenderImage = null;
        sLastRenderVideo = null;
    }

    // --- Overloaded Context-Aware Methods (Auto-fetch Stored Token & Keys) ---

    public void testConnection(Context context, String repository, ConnectionTestCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        testConnection(repository, token, callback);
    }

    public void dispatchGenerationWorkflow(Context context,
                                           String repository,
                                           String eventType,
                                           String assetId,
                                           String bpyScript,
                                           WorkflowDispatchCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        dispatchGenerationWorkflow(repository, token, eventType, assetId, bpyScript, callback);
    }

    public void dispatchGenerationWorkflowWithModel(Context context,
                                                    String repository,
                                                    String eventType,
                                                    String assetId,
                                                    String bpyScript,
                                                    File inputModelFile,
                                                    WorkflowDispatchCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        dispatchGenerationWorkflowWithModel(repository, token, eventType, assetId, bpyScript, inputModelFile, callback);
    }

    /**
     * Context-aware dispatch with full Architecture B parameter forwarding (user prompt, model choice, API key).
     */
    public void dispatchGenerationWorkflowWithModel(Context context,
                                                    String repository,
                                                    String eventType,
                                                    String assetId,
                                                    String bpyScript,
                                                    File inputModelFile,
                                                    boolean isRawScript,
                                                    String pipelineMode,
                                                    String userPrompt,
                                                    WorkflowDispatchCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        String selectedModel = null;
        String geminiApiKey = null;
        try {
            ApiKeyManager keyMgr = ApiKeyManager.getInstance(context);
            if (keyMgr != null) {
                selectedModel = keyMgr.getSelectedModel();
                geminiApiKey = keyMgr.getApiKey();
            }
        } catch (Throwable ignored) {}

        dispatchGenerationWorkflowWithModel(repository, token, eventType, assetId, bpyScript, inputModelFile,
                isRawScript, pipelineMode, userPrompt, selectedModel, geminiApiKey, callback);
    }

    public void dispatchCustomScriptWorkflow(Context context,
                                             String repository,
                                             String assetId,
                                             String customScript,
                                             WorkflowDispatchCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        dispatchGenerationWorkflowWithModel(repository, token, "vynara_generate", assetId, customScript, null, true, "OPTION_A", null, null, null, callback);
    }

    public void dispatchModularGenerationWorkflow(Context context,
                                                  String repository,
                                                  String eventType,
                                                  String assetId,
                                                  String w1Structure,
                                                  String w2Details,
                                                  String w3Materials,
                                                  String w4Cinematics,
                                                  WorkflowDispatchCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        dispatchModularGenerationWorkflow(repository, token, eventType, assetId, w1Structure, w2Details, w3Materials, w4Cinematics, callback);
    }

    public void downloadWorkflowArtifact(Context context,
                                         String repository,
                                         String assetId,
                                         File destinationFile,
                                         ArtifactDownloadCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        downloadWorkflowArtifact(repository, token, assetId, destinationFile, callback);
    }

    public void awaitWorkflowAndDownloadArtifact(Context context,
                                                String repository,
                                                String assetId,
                                                File destinationFile,
                                                WorkflowPollingCallback callback) {
        awaitWorkflowAndDownloadArtifact(context, repository, assetId, destinationFile, -1, callback);
    }

    public void awaitWorkflowAndDownloadArtifact(Context context,
                                                String repository,
                                                String assetId,
                                                File destinationFile,
                                                long excludedRunId,
                                                WorkflowPollingCallback callback) {
        String token = GitHubOAuthService.getAccessToken(context);
        awaitWorkflowAndDownloadArtifact(repository, token, assetId, destinationFile, excludedRunId, callback);
    }

    // --- Standard Core Methods ---

    public void testConnection(String repository, String personalAccessToken, ConnectionTestCallback callback) {
        if (repository == null || repository.trim().isEmpty()) {
            callback.onError("Repository cannot be empty. Format: owner/repo");
            return;
        }
        if (personalAccessToken == null || personalAccessToken.trim().isEmpty()) {
            callback.onError("GitHub Access Token is empty. Please sign in or provide a token.");
            return;
        }

        String targetUrl = "https://api.github.com/repos/" + repository.trim();

        Request request = new Request.Builder()
                .url(targetUrl)
                .header("Authorization", "Bearer " + personalAccessToken.trim())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Network connection failure: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        String errorMsg = "GitHub API Error [" + response.code() + "]: " + response.message();
                        mainHandler.post(() -> callback.onError(errorMsg));
                        return;
                    }

                    String jsonStr = responseBody.string();
                    JSONObject repoObj = new JSONObject(jsonStr);
                    String fullName = repoObj.optString("full_name", repository);
                    JSONObject permissions = repoObj.optJSONObject("permissions");
                    boolean hasPushAccess = permissions != null && (permissions.optBoolean("push", false) || permissions.optBoolean("admin", false));

                    mainHandler.post(() -> callback.onSuccess(fullName, hasPushAccess));
                } catch (Exception ex) {
                    mainHandler.post(() -> callback.onError("Failed to parse repository response: " + ex.getMessage()));
                }
            }
        });
    }

    public void dispatchGenerationWorkflow(String repository,
                                           String personalAccessToken,
                                           String eventType,
                                           String assetId,
                                           String bpyScript,
                                           WorkflowDispatchCallback callback) {
        dispatchGenerationWorkflowWithModel(repository, personalAccessToken, eventType, assetId, bpyScript, null, callback);
    }

    public void dispatchGenerationWorkflowWithModel(String repository,
                                                    String personalAccessToken,
                                                    String eventType,
                                                    String assetId,
                                                    String bpyScript,
                                                    File inputModelFile,
                                                    WorkflowDispatchCallback callback) {
        dispatchGenerationWorkflowWithModel(repository, personalAccessToken, eventType, assetId, bpyScript, inputModelFile, false, null, callback);
    }

    public void dispatchGenerationWorkflowWithModel(String repository,
                                                    String personalAccessToken,
                                                    String eventType,
                                                    String assetId,
                                                    String bpyScript,
                                                    File inputModelFile,
                                                    boolean isRawScript,
                                                    String pipelineMode,
                                                    WorkflowDispatchCallback callback) {
        dispatchGenerationWorkflowWithModel(repository, personalAccessToken, eventType, assetId, bpyScript, inputModelFile, isRawScript, pipelineMode, null, null, null, callback);
    }

    public void dispatchGenerationWorkflowWithModel(String repository,
                                                    String personalAccessToken,
                                                    String eventType,
                                                    String assetId,
                                                    String bpyScript,
                                                    File inputModelFile,
                                                    boolean isRawScript,
                                                    String pipelineMode,
                                                    String userPrompt,
                                                    String selectedModel,
                                                    String geminiApiKey,
                                                    WorkflowDispatchCallback callback) {
        if (repository == null || repository.trim().isEmpty() || personalAccessToken == null || personalAccessToken.trim().isEmpty()) {
            callback.onError("GitHub credentials are not properly configured.");
            return;
        }

        boolean isRaw = isRawScript || (bpyScript != null && (bpyScript.contains("is_raw_script=True") || bpyScript.startsWith("# VYNARA_PIPELINE: OPTION_A")));
        String effectivePipelineMode = (pipelineMode != null && !pipelineMode.isEmpty()) ? pipelineMode : (isRaw ? "OPTION_A" : "PROCEDURAL_PYTHON");

        if (!isRaw && (inputModelFile == null || !inputModelFile.exists())) {
            try {
                ProjectRuntime runtime = ProjectRuntime.getInstance();
                if (runtime != null) {
                    Asset activeAsset = runtime.getActiveSelectedAsset();
                    if (activeAsset != null && activeAsset.getFilePath() != null) {
                        File candidateFile = new File(activeAsset.getFilePath());
                        if (candidateFile.exists() && candidateFile.length() > 0) {
                            inputModelFile = candidateFile;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (inputModelFile != null && inputModelFile.exists() && inputModelFile.length() > 0 && !isRaw) {
            uploadModelAndDispatch(repository, personalAccessToken, eventType, assetId, bpyScript, inputModelFile, isRaw, effectivePipelineMode, userPrompt, selectedModel, geminiApiKey, callback);
        } else {
            executeDispatchCall(repository, personalAccessToken, eventType, assetId, bpyScript, null, isRaw, effectivePipelineMode, userPrompt, selectedModel, geminiApiKey, callback);
        }
    }

    private void uploadModelAndDispatch(String repository,
                                        String personalAccessToken,
                                        String eventType,
                                        String assetId,
                                        String bpyScript,
                                        File modelFile,
                                        boolean isRawScript,
                                        String pipelineMode,
                                        String userPrompt,
                                        String selectedModel,
                                        String geminiApiKey,
                                        WorkflowDispatchCallback callback) {
        String ext = ".glb";
        String origName = modelFile.getName().toLowerCase(Locale.US);
        if (origName.endsWith(".fbx")) ext = ".fbx";
        else if (origName.endsWith(".obj")) ext = ".obj";
        else if (origName.endsWith(".gltf")) ext = ".gltf";

        final String targetPath = "inputs/input_model" + ext;
        final String contentsUrl = "https://api.github.com/repos/" + repository.trim() + "/contents/" + targetPath;

        VynaraLogger.system("GitHubWorkflowBridge: Checking repository state for: " + targetPath);

        Request getShaReq = new Request.Builder()
                .url(contentsUrl)
                .header("Authorization", "Bearer " + personalAccessToken.trim())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(getShaReq).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                performPutModel(repository, personalAccessToken, eventType, assetId, bpyScript, modelFile, targetPath, null, isRawScript, pipelineMode, userPrompt, selectedModel, geminiApiKey, callback);
            }

            @Override
            public void onResponse(Call call, Response response) {
                String existingSha = null;
                long remoteSize = -1;
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject obj = new JSONObject(response.body().string());
                        existingSha = obj.optString("sha", null);
                        remoteSize = obj.optLong("size", -1);
                    } catch (Exception ignored) {}
                }
                response.close();

                String localGitBlobSha = computeGitBlobSha(modelFile);
                if (existingSha != null && localGitBlobSha != null 
                        && existingSha.equalsIgnoreCase(localGitBlobSha) 
                        && remoteSize == modelFile.length()) {
                    VynaraLogger.system("GitHubWorkflowBridge: 3D model already synced in repository (" + modelFile.length() + " bytes). Bypassing redundant upload.");
                    executeDispatchCall(repository, personalAccessToken, eventType, assetId, bpyScript, targetPath, isRawScript, pipelineMode, userPrompt, selectedModel, geminiApiKey, callback);
                } else {
                    VynaraLogger.system("GitHubWorkflowBridge: Uploading 3D asset (" + modelFile.length() + " bytes) to repository: " + targetPath);
                    performPutModel(repository, personalAccessToken, eventType, assetId, bpyScript, modelFile, targetPath, existingSha, isRawScript, pipelineMode, userPrompt, selectedModel, geminiApiKey, callback);
                }
            }
        });
    }

    private void performPutModel(String repository,
                                 String personalAccessToken,
                                 String eventType,
                                 String assetId,
                                 String bpyScript,
                                 File modelFile,
                                 String targetPath,
                                 String existingSha,
                                 boolean isRawScript,
                                 String pipelineMode,
                                 String userPrompt,
                                 String selectedModel,
                                 String geminiApiKey,
                                 WorkflowDispatchCallback callback) {
        try {
            byte[] fileBytes = new byte[(int) modelFile.length()];
            try (FileInputStream fis = new FileInputStream(modelFile)) {
                int read = fis.read(fileBytes);
                if (read <= 0) throw new IOException("Empty model file");
            }

            String b64Content = Base64.encodeToString(fileBytes, Base64.NO_WRAP);

            JSONObject putPayload = new JSONObject();
            putPayload.put("message", "Upload 3D model for render [Asset: " + assetId + "]");
            putPayload.put("content", b64Content);
            if (existingSha != null && !existingSha.isEmpty()) {
                putPayload.put("sha", existingSha);
            }

            String putUrl = "https://api.github.com/repos/" + repository.trim() + "/contents/" + targetPath;
            RequestBody body = RequestBody.create(putPayload.toString(), JSON_MEDIA_TYPE);

            Request putReq = new Request.Builder()
                    .url(putUrl)
                    .header("Authorization", "Bearer " + personalAccessToken.trim())
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Vynara-3D-Studio-Android")
                    .put(body)
                    .build();

            httpClient.newCall(putReq).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    String err = "Model file upload failed: " + e.getMessage();
                    VynaraLogger.e("GitHubWorkflowBridge: " + err);
                    mainHandler.post(() -> callback.onError(err));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful() || response.code() == 200 || response.code() == 201) {
                            VynaraLogger.system("GitHubWorkflowBridge: Successfully uploaded 3D model (" + targetPath + ")");
                            executeDispatchCall(repository, personalAccessToken, eventType, assetId, bpyScript, targetPath, isRawScript, pipelineMode, userPrompt, selectedModel, geminiApiKey, callback);
                        } else {
                            String err = "Model upload rejected by GitHub [HTTP " + response.code() + "]: " + response.message();
                            VynaraLogger.e("GitHubWorkflowBridge: " + err);
                            mainHandler.post(() -> callback.onError(err));
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception ex) {
            String err = "Error preparing model upload: " + ex.getMessage();
            VynaraLogger.e("GitHubWorkflowBridge: " + err, ex);
            mainHandler.post(() -> callback.onError(err));
        }
    }

    private void executeDispatchCall(String repository,
                                     String personalAccessToken,
                                     String eventType,
                                     String assetId,
                                     String bpyScript,
                                     String uploadedModelPath,
                                     boolean isRawScript,
                                     String pipelineMode,
                                     String userPrompt,
                                     String selectedModel,
                                     String geminiApiKey,
                                     WorkflowDispatchCallback callback) {
        String dispatchUrl = "https://api.github.com/repos/" + repository.trim() + "/dispatches";

        try {
            JSONObject clientPayload = new JSONObject();
            clientPayload.put("asset_id", assetId);

            String safeScript = bpyScript != null ? bpyScript : "";
            if (!safeScript.isEmpty()) {
                try {
                    String b64Script = Base64.encodeToString(safeScript.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    clientPayload.put("bpy_script", "b64:" + b64Script);
                    if (b64Script.length() < 16000) {
                        clientPayload.put("bpy_script_b64", b64Script);
                    }
                } catch (Exception e) {
                    clientPayload.put("bpy_script", safeScript);
                }
            }

            if (uploadedModelPath != null && !uploadedModelPath.isEmpty()) {
                clientPayload.put("model_path", uploadedModelPath);
            }

            if (isRawScript) {
                clientPayload.put("is_raw_script", true);
                clientPayload.put("isRawUserScript", true);
            }

            if (pipelineMode != null && !pipelineMode.isEmpty()) {
                clientPayload.put("pipeline_mode", pipelineMode);
            }

            // Architecture B payload parameters
            if (userPrompt != null && !userPrompt.trim().isEmpty()) {
                clientPayload.put("user_prompt", userPrompt.trim());
                clientPayload.put("prompt", userPrompt.trim());
            }

            if (selectedModel != null && !selectedModel.trim().isEmpty()) {
                clientPayload.put("selected_model", selectedModel.trim());
            }

            if (geminiApiKey != null && !geminiApiKey.trim().isEmpty()) {
                clientPayload.put("gemini_api_key", geminiApiKey.trim());
            }

            clientPayload.put("timestamp", System.currentTimeMillis());

            JSONObject rootPayload = new JSONObject();
            rootPayload.put("event_type", (eventType != null && !eventType.trim().isEmpty()) ? eventType : "vynara_generate");
            rootPayload.put("client_payload", clientPayload);

            RequestBody body = RequestBody.create(rootPayload.toString(), JSON_MEDIA_TYPE);

            Request request = new Request.Builder()
                    .url(dispatchUrl)
                    .header("Authorization", "Bearer " + personalAccessToken.trim())
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Vynara-3D-Studio-Android")
                    .post(body)
                    .build();

            VynaraLogger.system("GitHubWorkflowBridge: Dispatching workflow to: " + dispatchUrl + " with assetId: " + assetId + " (isRawScript=" + isRawScript + ")");

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    VynaraLogger.e("Workflow dispatch failed: " + e.getMessage(), e);
                    mainHandler.post(() -> callback.onError("Failed to dispatch workflow: " + e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.code() == 204 || response.isSuccessful()) {
                            VynaraLogger.system("GitHubWorkflowBridge: Workflow dispatched successfully (HTTP " + response.code() + ")");
                            mainHandler.post(() -> callback.onDispatched(eventType, assetId));
                        } else {
                            String err = "GitHub returned HTTP " + response.code() + " (" + response.message() + ")";
                            VynaraLogger.e(err);
                            mainHandler.post(() -> callback.onError(err));
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception ex) {
            callback.onError("Failed to assemble dispatch payload: " + ex.getMessage());
        }
    }

    public void dispatchModularGenerationWorkflow(String repository,
                                                  String personalAccessToken,
                                                  String eventType,
                                                  String assetId,
                                                  String w1Structure,
                                                  String w2Details,
                                                  String w3Materials,
                                                  String w4Cinematics,
                                                  WorkflowDispatchCallback callback) {
        if (repository == null || repository.trim().isEmpty() || personalAccessToken == null || personalAccessToken.trim().isEmpty()) {
            callback.onError("GitHub credentials are not properly configured.");
            return;
        }

        String dispatchUrl = "https://api.github.com/repos/" + repository.trim() + "/dispatches";

        try {
            JSONObject clientPayload = new JSONObject();
            clientPayload.put("asset_id", assetId);
            clientPayload.put("w1StructureScript", w1Structure != null ? w1Structure : "");
            clientPayload.put("w2DetailsScript", w2Details != null ? w2Details : "");
            clientPayload.put("w3MaterialsScript", w3Materials != null ? w3Materials : "");
            clientPayload.put("w4CinematicsScript", w4Cinematics != null ? w4Cinematics : "");
            clientPayload.put("timestamp", System.currentTimeMillis());

            JSONObject rootPayload = new JSONObject();
            rootPayload.put("event_type", (eventType != null && !eventType.trim().isEmpty()) ? eventType : "vynara_generate");
            rootPayload.put("client_payload", clientPayload);

            RequestBody body = RequestBody.create(rootPayload.toString(), JSON_MEDIA_TYPE);

            Request request = new Request.Builder()
                    .url(dispatchUrl)
                    .header("Authorization", "Bearer " + personalAccessToken.trim())
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Vynara-3D-Studio-Android")
                    .post(body)
                    .build();

            VynaraLogger.system("GitHubWorkflowBridge: Dispatching 4-Worker modular build to: " + dispatchUrl + " [Asset: " + assetId + "]");

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    VynaraLogger.e("Modular dispatch failed: " + e.getMessage(), e);
                    mainHandler.post(() -> callback.onError("Failed to dispatch workflow: " + e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.code() == 204 || response.isSuccessful()) {
                            VynaraLogger.system("GitHubWorkflowBridge: 4-Worker workflow dispatched successfully (HTTP " + response.code() + ")");
                            mainHandler.post(() -> callback.onDispatched(eventType, assetId));
                        } else {
                            String err = "GitHub returned HTTP " + response.code() + " (" + response.message() + ")";
                            VynaraLogger.e(err);
                            mainHandler.post(() -> callback.onError(err));
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception ex) {
            callback.onError("Failed to assemble modular dispatch payload: " + ex.getMessage());
        }
    }

    public void downloadWorkflowArtifact(String repository,
                                         String personalAccessToken,
                                         String assetId,
                                         File destinationFile,
                                         ArtifactDownloadCallback callback) {
        String artifactsUrl = "https://api.github.com/repos/" + repository.trim() + "/actions/artifacts";

        Request request = new Request.Builder()
                .url(artifactsUrl)
                .header("Authorization", "Bearer " + personalAccessToken.trim())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Artifact search failed: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        mainHandler.post(() -> callback.onError("Failed to list artifacts: HTTP " + response.code()));
                        return;
                    }

                    String json = responseBody.string();
                    JSONObject root = new JSONObject(json);
                    JSONArray artifacts = root.optJSONArray("artifacts");

                    if (artifacts == null || artifacts.length() == 0) {
                        mainHandler.post(() -> callback.onError("No build artifacts found in repository."));
                        return;
                    }

                    String downloadLocationUrl = null;
                    for (int i = 0; i < artifacts.length(); i++) {
                        JSONObject artifact = artifacts.getJSONObject(i);
                        String name = artifact.optString("name", "");
                        if (name.equalsIgnoreCase(assetId) || name.contains(assetId) || name.equalsIgnoreCase("model")) {
                            downloadLocationUrl = artifact.optString("archive_download_url", null);
                            break;
                        }
                    }

                    if (downloadLocationUrl == null && artifacts.length() > 0) {
                        downloadLocationUrl = artifacts.getJSONObject(0).optString("archive_download_url", null);
                    }

                    if (downloadLocationUrl == null) {
                        mainHandler.post(() -> callback.onError("Artifact matching assetId '" + assetId + "' not ready yet."));
                        return;
                    }

                    executeBinaryDownload(downloadLocationUrl, personalAccessToken, destinationFile, callback);
                } catch (Exception ex) {
                    mainHandler.post(() -> callback.onError("Failed to parse artifacts list: " + ex.getMessage()));
                }
            }
        });
    }

    public void downloadWorkflowArtifactForRun(String repository,
                                               String personalAccessToken,
                                               long runId,
                                               String assetId,
                                               File destinationFile,
                                               ArtifactDownloadCallback callback) {
        String runArtifactsUrl = "https://api.github.com/repos/" + repository.trim() + "/actions/runs/" + runId + "/artifacts";

        Request request = new Request.Builder()
                .url(runArtifactsUrl)
                .header("Authorization", "Bearer " + personalAccessToken.trim())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Run artifact search failed: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        mainHandler.post(() -> callback.onError("Failed to list run artifacts: HTTP " + response.code()));
                        return;
                    }

                    String json = responseBody.string();
                    JSONObject root = new JSONObject(json);
                    JSONArray artifacts = root.optJSONArray("artifacts");

                    if (artifacts == null || artifacts.length() == 0) {
                        mainHandler.post(() -> callback.onError("No artifacts produced for Run #" + runId));
                        return;
                    }

                    String downloadLocationUrl = null;
                    for (int i = 0; i < artifacts.length(); i++) {
                        JSONObject artifact = artifacts.getJSONObject(i);
                        String name = artifact.optString("name", "").toLowerCase(Locale.US);
                        if (name.equalsIgnoreCase(assetId) || name.contains(assetId.toLowerCase(Locale.US))
                                || name.contains("model") || name.contains("error") || name.contains("log")
                                || name.contains("cinematic") || artifacts.length() == 1) {
                            downloadLocationUrl = artifact.optString("archive_download_url", null);
                            break;
                        }
                    }

                    if (downloadLocationUrl == null && artifacts.length() > 0) {
                        downloadLocationUrl = artifacts.getJSONObject(0).optString("archive_download_url", null);
                    }

                    if (downloadLocationUrl == null) {
                        mainHandler.post(() -> callback.onError("Artifact not found for Run #" + runId));
                        return;
                    }

                    executeBinaryDownload(downloadLocationUrl, personalAccessToken, destinationFile, callback);
                } catch (Exception ex) {
                    mainHandler.post(() -> callback.onError("Failed to parse run artifacts: " + ex.getMessage()));
                }
            }
        });
    }

    public void awaitWorkflowAndDownloadArtifact(String repository,
                                                String personalAccessToken,
                                                String assetId,
                                                File destinationFile,
                                                WorkflowPollingCallback callback) {
        awaitWorkflowAndDownloadArtifact(repository, personalAccessToken, assetId, destinationFile, -1, callback);
    }

    /**
     * Polling method with explicit excludedRunId to prevent the Attempt 2 race condition.
     */
    public void awaitWorkflowAndDownloadArtifact(String repository,
                                                String personalAccessToken,
                                                String assetId,
                                                File destinationFile,
                                                long excludedRunId,
                                                WorkflowPollingCallback callback) {
        if (repository == null || repository.trim().isEmpty() || personalAccessToken == null || personalAccessToken.trim().isEmpty()) {
            callback.onError("GitHub credentials are not properly configured.");
            return;
        }

        final long dispatchTimeMs = System.currentTimeMillis();
        final long[] activeRunId = new long[]{-1};
        clearLastBlenderError();
        VynaraLogger.system("GitHubWorkflowBridge: Starting workflow execution monitoring for assetId: " + assetId 
                + (excludedRunId > 0 ? " (excluding Run #" + excludedRunId + ")" : ""));

        final Runnable[] pollRunnable = new Runnable[1];
        pollRunnable[0] = new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - dispatchTimeMs > MAX_POLLING_DURATION_MS) {
                    String timeoutMsg = "GitHub Actions workflow execution timed out after " + (MAX_POLLING_DURATION_MS / 1000) + "s.";
                    sLastBlenderError = "TimeoutError: Script execution timed out after " + (MAX_POLLING_DURATION_MS / 1000) + "s";
                    sLastBlenderTraceback = "TimeoutError: " + timeoutMsg + "\nHeadless Blender took too long executing procedural loops or GLTF export without completing.";
                    VynaraLogger.e("GitHubWorkflowBridge: " + timeoutMsg);

                    final long failedRunId = activeRunId[0];
                    mainHandler.post(() -> callback.onScriptExecutionFailed(failedRunId, sLastBlenderTraceback));
                    return;
                }

                String runsUrl = "https://api.github.com/repos/" + repository.trim() + "/actions/runs?per_page=10";

                Request request = new Request.Builder()
                        .url(runsUrl)
                        .header("Authorization", "Bearer " + personalAccessToken.trim())
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Vynara-3D-Studio-Android")
                        .get()
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        VynaraLogger.e("Workflow status check failed: " + e.getMessage());
                        mainHandler.postDelayed(pollRunnable[0], POLLING_INTERVAL_MS);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try (ResponseBody responseBody = response.body()) {
                            if (!response.isSuccessful() || responseBody == null) {
                                VynaraLogger.e("Workflow runs query failed: HTTP " + response.code());
                                mainHandler.postDelayed(pollRunnable[0], POLLING_INTERVAL_MS);
                                return;
                            }

                            String json = responseBody.string();
                            JSONObject root = new JSONObject(json);
                            JSONArray runs = root.optJSONArray("workflow_runs");

                            if (runs != null && runs.length() > 0) {
                                JSONObject targetRun = null;

                                SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                                isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

                                for (int i = 0; i < runs.length(); i++) {
                                    JSONObject r = runs.getJSONObject(i);
                                    long runId = r.optLong("id", 0);

                                    // Skip the previous failed run completely
                                    if (excludedRunId > 0 && runId == excludedRunId) {
                                        continue;
                                    }

                                    String createdAtStr = r.optString("created_at", "");
                                    long runCreatedAtMs = 0;
                                    try {
                                        if (!createdAtStr.isEmpty()) {
                                            runCreatedAtMs = isoFormat.parse(createdAtStr).getTime();
                                        }
                                    } catch (Exception ignored) {}

                                    String status = r.optString("status", "unknown");

                                    // Ignore runs that completed prior to this dispatch
                                    if ("completed".equalsIgnoreCase(status) && runCreatedAtMs < dispatchTimeMs) {
                                        continue;
                                    }

                                    // Strictly match runs dispatched for this session
                                    if (runCreatedAtMs >= (dispatchTimeMs - 1000)) {
                                        targetRun = r;
                                        break;
                                    }
                                }

                                if (targetRun != null) {
                                    String status = targetRun.optString("status", "unknown");
                                    String conclusion = targetRun.optString("conclusion", "null");
                                    long runId = targetRun.optLong("id", 0);
                                    activeRunId[0] = runId;

                                    VynaraLogger.system("GitHubWorkflowBridge: Active Run #" + runId + " Status: " + status + " Conclusion: " + conclusion);
                                    mainHandler.post(() -> callback.onStatusUpdate(status, "Run #" + runId + " [" + status + "]"));

                                    if ("completed".equalsIgnoreCase(status)) {
                                        VynaraLogger.system("GitHubWorkflowBridge: Workflow run #" + runId + " finished [" + conclusion + "]. Downloading artifacts...");
                                        mainHandler.post(() -> callback.onStatusUpdate("downloading", "Downloading worker artifacts..."));

                                        pollAndDownloadArtifact(repository, personalAccessToken, runId, assetId, destinationFile, callback, 1, MAX_ARTIFACT_RETRY_ATTEMPTS, conclusion);
                                        return;
                                    }
                                } else {
                                    VynaraLogger.system("GitHubWorkflowBridge: Waiting for new workflow run to be queued by GitHub Actions...");
                                    mainHandler.post(() -> callback.onStatusUpdate("queued", "Waiting for worker to start..."));
                                }
                            }

                            mainHandler.postDelayed(pollRunnable[0], POLLING_INTERVAL_MS);

                        } catch (Exception ex) {
                            VynaraLogger.e("Error parsing workflow status: " + ex.getMessage(), ex);
                            mainHandler.postDelayed(pollRunnable[0], POLLING_INTERVAL_MS);
                        }
                    }
                });
            }
        };

        mainHandler.post(pollRunnable[0]);
    }

    private void pollAndDownloadArtifact(String repository,
                                         String personalAccessToken,
                                         long runId,
                                         String assetId,
                                         File destinationFile,
                                         WorkflowPollingCallback callback,
                                         int attempt,
                                         int maxAttempts,
                                         String conclusion) {
        downloadWorkflowArtifactForRun(repository, personalAccessToken, runId, assetId, destinationFile, new ArtifactDownloadCallback() {
            @Override
            public void onProgress(int percentage, long bytesRead, long totalBytes) {
                callback.onProgress(percentage, bytesRead, totalBytes);
            }

            @Override
            public void onSuccess(File downloadedFile) {
                VynaraLogger.system("GitHubWorkflowBridge: Artifact extracted successfully: " + downloadedFile.getAbsolutePath());
                if (sLastRenderImage != null) {
                    callback.onRenderPreviewReady(sLastRenderImage);
                }
                if (sLastRenderVideo != null) {
                    callback.onVideoReady(sLastRenderVideo);
                }
                callback.onSuccess(downloadedFile);
            }

            @Override
            public void onRenderPreviewReady(File renderPreviewFile) {
                callback.onRenderPreviewReady(renderPreviewFile);
            }

            @Override
            public void onVideoReady(File videoFile) {
                callback.onVideoReady(videoFile);
            }

            @Override
            public void onScriptExecutionFailed(String errorTraceback) {
                VynaraLogger.e("GitHubWorkflowBridge: Blender script failure captured from artifact: " + errorTraceback);
                mainHandler.post(() -> callback.onScriptExecutionFailed(runId, errorTraceback));
            }

            @Override
            public void onError(String errorMessage) {
                if (attempt < maxAttempts) {
                    VynaraLogger.system("GitHubWorkflowBridge: Waiting for run artifact indexing (attempt " + attempt + "/" + maxAttempts + ")...");
                    mainHandler.post(() -> callback.onStatusUpdate("indexing", "Waiting for artifact indexing (" + attempt + "/" + maxAttempts + ")..."));
                    mainHandler.postDelayed(() -> pollAndDownloadArtifact(repository, personalAccessToken, runId, assetId, destinationFile, callback, attempt + 1, maxAttempts, conclusion), ARTIFACT_RETRY_DELAY_MS);
                } else {
                    if ("failure".equalsIgnoreCase(conclusion)) {
                        VynaraLogger.system("GitHubWorkflowBridge: No artifact zip found for failed run. Attempting raw runner log extraction...");
                        fetchRunJobLogsFallback(repository, personalAccessToken, runId, callback);
                    } else {
                        String finalError = (sLastBlenderTraceback != null && !sLastBlenderTraceback.isEmpty())
                                ? "Blender Worker Error: " + sLastBlenderTraceback
                                : "Artifact download failed: " + errorMessage;
                        VynaraLogger.e("GitHubWorkflowBridge: " + finalError);
                        mainHandler.post(() -> callback.onError(finalError));
                    }
                }
            }
        });
    }

    private void fetchRunJobLogsFallback(String repository,
                                         String personalAccessToken,
                                         long runId,
                                         WorkflowPollingCallback callback) {
        String jobsUrl = "https://api.github.com/repos/" + repository.trim() + "/actions/runs/" + runId + "/jobs";

        Request request = new Request.Builder()
                .url(jobsUrl)
                .header("Authorization", "Bearer " + personalAccessToken.trim())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Failed to query run jobs: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        mainHandler.post(() -> callback.onError("Runner failed with HTTP " + response.code()));
                        return;
                    }

                    JSONObject json = new JSONObject(responseBody.string());
                    JSONArray jobs = json.optJSONArray("jobs");
                    if (jobs == null || jobs.length() == 0) {
                        mainHandler.post(() -> callback.onError("No job information found for Run #" + runId));
                        return;
                    }

                    long failedJobId = -1;
                    for (int i = 0; i < jobs.length(); i++) {
                        JSONObject j = jobs.getJSONObject(i);
                        if ("failure".equalsIgnoreCase(j.optString("conclusion", ""))) {
                            failedJobId = j.optLong("id", -1);
                            break;
                        }
                    }

                    if (failedJobId == -1) {
                        failedJobId = jobs.getJSONObject(0).optLong("id", -1);
                    }

                    downloadRawJobLog(repository, personalAccessToken, runId, failedJobId, callback);
                } catch (Exception ex) {
                    mainHandler.post(() -> callback.onError("Failed to parse jobs: " + ex.getMessage()));
                }
            }
        });
    }

    private void downloadRawJobLog(String repository,
                                   String personalAccessToken,
                                   long runId,
                                   long jobId,
                                   WorkflowPollingCallback callback) {
        String logsUrl = "https://api.github.com/repos/" + repository.trim() + "/actions/jobs/" + jobId + "/logs";

        Request request = new Request.Builder()
                .url(logsUrl)
                .header("Authorization", "Bearer " + personalAccessToken.trim())
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Failed to retrieve runner logs: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        mainHandler.post(() -> callback.onError("Failed to download runner logs: HTTP " + response.code()));
                        return;
                    }

                    String logText = responseBody.string();
                    String extractedTraceback = extractTraceback(logText);

                    sLastBlenderTraceback = extractedTraceback;
                    sLastBlenderError = extractErrorLine(extractedTraceback);

                    VynaraLogger.e("GitHubWorkflowBridge: Extracted traceback from runner raw log:\n" + extractedTraceback);
                    mainHandler.post(() -> callback.onScriptExecutionFailed(runId, extractedTraceback));
                }
            }
        });
    }

    private void executeBinaryDownload(String downloadUrl,
                                       String token,
                                       File destinationFile,
                                       ArtifactDownloadCallback callback) {
        Request request = new Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer " + token.trim())
                .header("User-Agent", "Vynara-3D-Studio-Android")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("Failed to download artifact binary: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> callback.onError("Artifact download failed: HTTP " + response.code()));
                    return;
                }

                ResponseBody body = response.body();
                long totalBytes = body.contentLength();

                File tempZipFile = new File(destinationFile.getParentFile(), destinationFile.getName() + ".zip");

                try (InputStream inputStream = body.byteStream();
                     FileOutputStream outputStream = new FileOutputStream(tempZipFile)) {

                    byte[] buffer = new byte[8192];
                    long totalBytesRead = 0;
                    int bytesRead;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;

                        if (totalBytes > 0) {
                            int progress = (int) ((totalBytesRead * 100) / totalBytes);
                            long currentRead = totalBytesRead;
                            mainHandler.post(() -> callback.onProgress(progress, currentRead, totalBytes));
                        }
                    }
                    outputStream.flush();

                    boolean extracted = extractGlbFromZip(tempZipFile, destinationFile);
                    if (tempZipFile.exists()) {
                        tempZipFile.delete();
                    }

                    if (extracted && destinationFile.exists() && destinationFile.length() > 0) {
                        if (sLastRenderImage != null) {
                            callback.onRenderPreviewReady(sLastRenderImage);
                        }
                        if (sLastRenderVideo != null) {
                            callback.onVideoReady(sLastRenderVideo);
                        }
                        mainHandler.post(() -> callback.onSuccess(destinationFile));
                    } else {
                        String failureDetails = (sLastBlenderTraceback != null && !sLastBlenderTraceback.isEmpty())
                                ? sLastBlenderTraceback
                                : sLastBlenderError;

                        if (failureDetails != null && !failureDetails.isEmpty()) {
                            mainHandler.post(() -> callback.onScriptExecutionFailed(failureDetails));
                        } else {
                            String errorMsg = "Extracted 3D model is missing or invalid. Check diagnostic console for internal worker logs.";
                            mainHandler.post(() -> callback.onError(errorMsg));
                        }
                    }

                } catch (Exception ex) {
                    if (tempZipFile.exists()) {
                        tempZipFile.delete();
                    }
                    mainHandler.post(() -> callback.onError("Error saving artifact: " + ex.getMessage()));
                }
            }
        });
    }

    private boolean extractGlbFromZip(File zipFile, File destinationGlbFile) {
        boolean glbFound = false;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                String fileName = entry.getName().toLowerCase(Locale.US);

                if (fileName.endsWith(".glb") || fileName.endsWith(".gltf")) {
                    if (destinationGlbFile.getParentFile() != null && !destinationGlbFile.getParentFile().exists()) {
                        destinationGlbFile.getParentFile().mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(destinationGlbFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        fos.flush();
                    }
                    glbFound = true;
                } else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                    String renderName = destinationGlbFile.getName();
                    int dotIdx = renderName.lastIndexOf('.');
                    String baseName = (dotIdx > 0) ? renderName.substring(0, dotIdx) : renderName;
                    File destinationImgFile = new File(destinationGlbFile.getParentFile(), baseName + ".png");

                    if (destinationImgFile.getParentFile() != null && !destinationImgFile.getParentFile().exists()) {
                        destinationImgFile.getParentFile().mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(destinationImgFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        fos.flush();
                        sLastRenderImage = destinationImgFile;
                        VynaraLogger.system("GitHubWorkflowBridge: Extracted preview render: " + destinationImgFile.getName());
                    }
                } else if (fileName.endsWith(".mp4") || fileName.endsWith(".mov") || fileName.endsWith(".webm")) {
                    String videoName = destinationGlbFile.getName();
                    int dotIdx = videoName.lastIndexOf('.');
                    String baseName = (dotIdx > 0) ? videoName.substring(0, dotIdx) : videoName;
                    File destinationVideoFile = new File(destinationGlbFile.getParentFile(), baseName + ".mp4");

                    if (destinationVideoFile.getParentFile() != null && !destinationVideoFile.getParentFile().exists()) {
                        destinationVideoFile.getParentFile().mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(destinationVideoFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        fos.flush();
                        sLastRenderVideo = destinationVideoFile;
                        VynaraLogger.system("GitHubWorkflowBridge: Extracted cinematic video: " + destinationVideoFile.getName());
                    }
                } else if (fileName.contains("error.txt") || fileName.contains("blender_execution.log") || fileName.endsWith(".log") || fileName.contains("traceback")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    String logContent = baos.toString(StandardCharsets.UTF_8.name());

                    if (fileName.contains("error.txt") || sLastBlenderTraceback == null) {
                        sLastBlenderTraceback = extractTraceback(logContent);
                        sLastBlenderError = extractErrorLine(sLastBlenderTraceback);
                    }

                    String[] lines = logContent.split("\\r?\\n");
                    int totalLines = lines.length;
                    int tailStartIndex = Math.max(0, totalLines - 40);

                    VynaraLogger.system("========== BLENDER WORKER INTERNAL LOG START (" + totalLines + " lines) ==========");
                    for (int i = 0; i < totalLines; i++) {
                        String line = lines[i];
                        if (line == null || line.trim().isEmpty()) continue;
                        String lowerLine = line.toLowerCase(Locale.US);
                        boolean isErrorLine = lowerLine.contains("error") || lowerLine.contains("exception")
                                || lowerLine.contains("traceback") || lowerLine.contains("failed")
                                || lowerLine.contains("syntaxerror");

                        if (isErrorLine) {
                            VynaraLogger.e("[BLENDER_WORKER] " + line.trim());
                        } else if (i >= tailStartIndex) {
                            VynaraLogger.cloud("[BLENDER_WORKER] " + line.trim());
                        }
                    }
                    VynaraLogger.system("========== BLENDER WORKER INTERNAL LOG END ==========");
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            VynaraLogger.e("ZIP extraction error: " + e.getMessage(), e);
        }
        return glbFound;
    }

    private static String extractTraceback(String logText) {
        if (logText == null || logText.trim().isEmpty()) {
            return "Blender execution failed with unknown error.";
        }
        String[] lines = logText.split("\\r?\\n");
        StringBuilder tb = new StringBuilder();
        boolean capturing = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Traceback (most recent call last):")) {
                capturing = true;
                tb.setLength(0);
            }
            if (capturing) {
                tb.append(trimmed).append("\n");
                if (trimmed.matches("^[A-Za-z0-9_]+Error:.*") || trimmed.matches("^[A-Za-z0-9_]+Exception:.*")) {
                    capturing = false;
                }
            }
        }

        if (tb.length() > 0) {
            return tb.toString().trim();
        }
        return logText.trim();
    }

    private static String extractErrorLine(String traceback) {
        if (traceback == null || traceback.trim().isEmpty()) return "Unknown Error";
        String[] lines = traceback.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (l.contains("Error:") || l.contains("Exception:")) {
                return l;
            }
        }
        return lines[lines.length - 1].trim();
    }

    private static String computeGitBlobSha(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            String header = "blob " + file.length() + "\0";
            md.update(header.getBytes(StandardCharsets.US_ASCII));

            try (InputStream is = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
            }

            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static File getAssociatedRenderImage(File glbFile) {
        if (glbFile == null || glbFile.getParentFile() == null) return null;
        String name = glbFile.getName();
        int dotIdx = name.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? name.substring(0, dotIdx) : name;
        File img = new File(glbFile.getParentFile(), baseName + ".png");
        return (img.exists() && img.length() > 0) ? img : null;
    }

    public static File getAssociatedRenderVideo(File glbFile) {
        if (glbFile == null || glbFile.getParentFile() == null) return null;
        String name = glbFile.getName();
        int dotIdx = name.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? name.substring(0, dotIdx) : name;
        File vid = new File(glbFile.getParentFile(), baseName + ".mp4");
        return (vid.exists() && vid.length() > 0) ? vid : null;
    }
}