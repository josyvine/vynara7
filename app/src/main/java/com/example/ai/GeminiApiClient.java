package com.example.ai;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiApiClient {
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // Master-Level Blender Procedural System Prompt
    private static final String BLENDER_SYSTEM_INSTRUCTION =
            "You are an elite 3D modeling and visual development engineer using Blender's Python API (`bpy`).\n" +
            "When given a creation prompt or reference image, generate ONLY executable, production-grade Python code for Blender.\n\n" +
            "CORE DIRECTIVES:\n" +
            "1. Start with `import bpy, math, sys, os, random`.\n" +
            "2. Always clear existing objects: `bpy.ops.object.select_all(action='SELECT')` and `bpy.ops.object.delete(use_global=False)`.\n" +
            "3. SURFACE QUALITY: Enable smooth shading (`bpy.ops.object.shade_smooth()`) on all curved/organic/vehicle surfaces. Add a BEVEL modifier (width=0.04, segments=3) on hard edges to capture specular highlights.\n" +
            "4. AUTOMOTIVE & WHEEL TRANSFORMS: Any wheel cylinder placed on a horizontal axle MUST be rotated 90 degrees on the X or Y axis (`rotation=(0, math.radians(90), 0)` or `(math.radians(90), 0, 0)`). Never leave wheels standing vertically on Z.\n" +
            "5. PBR MATERIALS: Use Blender 4.2+ Principled BSDF socket names (`Transmission Weight`, `Roughness`, `Metallic`). Create distinct materials for hero paint, tinted glass, tire rubber, and metal trim.\n" +
            "6. EXPORT: Ensure `bpy.ops.export_scene.gltf(filepath='output/model.glb', export_format='GLB', export_skins=True, export_animations=True)` runs at root level.\n" +
            "7. Output ONLY executable Python code inside a single ```python code block without extra commentary.";

    // Solution B: Targeted single-turn repair instruction for fixing terminal tracebacks
    private static final String BLENDER_REPAIR_SYSTEM_INSTRUCTION =
            "You are an expert Blender Python (`bpy`) debugger and autonomous code repair specialist.\n" +
            "A cloud runner executing headless Blender encountered a runtime error while executing a script.\n" +
            "You are provided:\n" +
            "1. The original creative user prompt (what was being built)\n" +
            "2. The exact Blender terminal error / traceback message from error.txt\n" +
            "3. The faulty Python script that failed\n\n" +
            "RULES:\n" +
            "1. Analyze the exact traceback line number and error message (e.g., enum mismatch, invalid operator, syntax error).\n" +
            "2. Fix the error while strictly preserving all geometry, lighting, materials, and GLB export commands from the prompt.\n" +
            "3. Output ONLY executable Python code inside a single ```python code block. Do NOT include explanations, conversational filler, or commentary.";

    // Visual Inspection & Viewport Critique Instruction (Claude MCP Style)
    private static final String BLENDER_VISUAL_CRITIQUE_INSTRUCTION =
            "You are a Senior 3D Lighting & Art Director inspecting a rendered Blender scene against an intended reference goal.\n" +
            "You are provided:\n" +
            "1. The original user prompt / concept\n" +
            "2. The rendered viewport preview image (`render.png`) produced by Blender Cycles\n" +
            "3. Optional visual reference photo\n" +
            "4. The current Blender Python script\n\n" +
            "CRITIQUE & REFINEMENT OBJECTIVE:\n" +
            "- Visually inspect the rendered image. Look for defects such as flat un-beveled boxes, incorrect wheel rotations, harsh flat lighting, or floating objects.\n" +
            "- Rewrite the Blender Python script to resolve these visual defects, adding bevels, correct rotations, PBR shader nodes, and balanced lighting.\n" +
            "- Output ONLY the complete, corrected Python script inside a single ```python block.";

    private final OkHttpClient client;
    private final Handler mainHandler;

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String errorMessage);
    }

    public GeminiApiClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void fetchModels(String apiKey, final ApiCallback<List<AIModel>> callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onError("Gemini API key is required.");
            return;
        }

        String url = BASE_URL + "models?key=" + apiKey.trim();
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                mainHandler.post(() -> callback.onError("Network error: " + e.getLocalizedMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    final String err = "HTTP " + response.code() + " from Gemini API";
                    mainHandler.post(() -> callback.onError(err));
                    response.close();
                    return;
                }

                try {
                    String bodyStr = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(bodyStr);
                    JSONArray modelsArray = json.optJSONArray("models");
                    final List<AIModel> modelList = new ArrayList<>();

                    if (modelsArray != null) {
                        for (int i = 0; i < modelsArray.length(); i++) {
                            JSONObject m = modelsArray.getJSONObject(i);
                            String rawName = m.optString("name", "");
                            String cleanName = rawName.startsWith("models/") ? rawName.substring(7) : rawName;
                            String displayName = m.optString("displayName", cleanName);
                            String description = m.optString("description", "");

                            if (!cleanName.isEmpty()) {
                                modelList.add(new AIModel(cleanName, displayName, description, true));
                            }
                        }
                    }

                    if (modelList.isEmpty()) {
                        modelList.add(new AIModel("gemini-2.5-flash", "gemini-2.5-flash", "Latest high-speed multimodal production model", true));
                        modelList.add(new AIModel("gemini-2.5-pro", "gemini-2.5-pro", "Advanced multi-agent reasoning model", true));
                        modelList.add(new AIModel("gemini-1.5-flash", "gemini-1.5-flash", "Standard fast production model", true));
                        modelList.add(new AIModel("gemini-1.5-pro", "gemini-1.5-pro", "Legacy reasoning model", true));
                    }

                    mainHandler.post(() -> callback.onSuccess(modelList));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError("Failed to parse models response: " + e.getMessage()));
                } finally {
                    response.close();
                }
            }
        });
    }

    public void testConnection(String apiKey, String modelId, final ApiCallback<Boolean> callback) {
        generateContent(apiKey, modelId, "You are a 3D creation assistant.", "Ping test. Respond with OK.", new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                callback.onSuccess(true);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    public void generateContent(String apiKey, String modelId, String systemInstruction, String userPrompt, final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, systemInstruction, userPrompt, null, false, null, callback);
    }

    public void generateContent(String apiKey, String modelId, String systemInstruction, String userPrompt, List<String> base64Images, final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, systemInstruction, userPrompt, base64Images, false, null, callback);
    }

    public void generateStructuredJson(String apiKey, String modelId, String systemInstruction, String userPrompt, final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, systemInstruction, userPrompt, null, true, null, callback);
    }

    public void generateStructuredJson(String apiKey, String modelId, String systemInstruction, String userPrompt, List<String> base64Images, final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, systemInstruction, userPrompt, base64Images, true, null, callback);
    }

    public void generateBlenderScript(String apiKey, String modelId, String userPrompt, final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, BLENDER_SYSTEM_INSTRUCTION, userPrompt, null, false, null, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleanedScript = cleanPythonOutput(result);
                callback.onSuccess(cleanedScript);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    public void generateBlenderScript(String apiKey, String modelId, String systemInstruction, String userPrompt, final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, systemInstruction, userPrompt, null, false, null, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleanedScript = cleanPythonOutput(result);
                callback.onSuccess(cleanedScript);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    public void generateBlenderScript(String apiKey, String modelId, String systemInstruction, String userPrompt, List<String> base64Images, final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, systemInstruction, userPrompt, base64Images, false, null, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleanedScript = cleanPythonOutput(result);
                callback.onSuccess(cleanedScript);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    /**
     * SOLUTION B: Dedicated Single-Turn Script Repair Call (Syntax / Traceback)
     */
    public void repairBlenderScript(String apiKey,
                                    String modelId,
                                    String userPrompt,
                                    String failedScript,
                                    String errorTraceback,
                                    final ApiCallback<String> callback) {
        StringBuilder repairPrompt = new StringBuilder();
        repairPrompt.append("=== WHAT WAS BEING BUILT (USER PROMPT) ===\n")
                .append(userPrompt != null ? userPrompt : "3D Scene Asset")
                .append("\n\n")
                .append("=== EXACT TERMINAL ERROR TRACEBACK (FROM error.txt) ===\n")
                .append(errorTraceback != null ? errorTraceback : "Unknown Blender Runtime Error")
                .append("\n\n")
                .append("=== FAULTY SCRIPT THAT FAILED ===\n")
                .append(failedScript != null ? failedScript : "");

        generateContentInternal(apiKey, modelId, BLENDER_REPAIR_SYSTEM_INSTRUCTION, repairPrompt.toString(), null, false, 0.15f, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleaned = cleanPythonOutput(result);
                callback.onSuccess(cleaned);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    /**
     * MULTIMODAL VISUAL FEEDBACK: Evaluates the rendered Cycles preview snapshot (`render.png`)
     * against the reference goal to visually diagnose and refine the 3D scene.
     */
    public void critiqueAndRefineRender(String apiKey,
                                        String modelId,
                                        String userPrompt,
                                        String currentScript,
                                        String base64ReferenceImage,
                                        String base64RenderPreview,
                                        final ApiCallback<String> callback) {
        List<String> images = new ArrayList<>();
        if (base64ReferenceImage != null && !base64ReferenceImage.isEmpty()) {
            images.add(base64ReferenceImage);
        }
        if (base64RenderPreview != null && !base64RenderPreview.isEmpty()) {
            images.add(base64RenderPreview);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("=== ORIGINAL USER GOAL ===\n").append(userPrompt).append("\n\n");
        prompt.append("=== CURRENT SCRIPT EXECUTED ===\n").append(currentScript).append("\n\n");
        prompt.append("IMAGE INPUTS: Image 1 is the reference goal (if provided). Image 2 is the actual render produced by Blender.\n");
        prompt.append("TASK: Analyze the render visual defects (lack of bevels, wrong wheel rotation, flat color). Return the complete polished Blender script inside ```python.");

        generateContentInternal(apiKey, modelId, BLENDER_VISUAL_CRITIQUE_INSTRUCTION, prompt.toString(), images, false, 0.2f, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleaned = cleanPythonOutput(result);
                callback.onSuccess(cleaned);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    /**
     * Synthesizes code for an individual specialized worker (W1, W2, W3, W4).
     */
    public void generateWorkerScript(String apiKey,
                                     String modelId,
                                     int workerIndex,
                                     String workerPrompt,
                                     List<String> base64Images,
                                     final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, BLENDER_SYSTEM_INSTRUCTION, workerPrompt, base64Images, false, 0.25f, new ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleaned = cleanPythonOutput(result);
                callback.onSuccess(cleaned);
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    private void generateContentInternal(String apiKey,
                                         String modelId,
                                         String systemInstruction,
                                         String userPrompt,
                                         List<String> base64Images,
                                         boolean enforceJson,
                                         final ApiCallback<String> callback) {
        generateContentInternal(apiKey, modelId, systemInstruction, userPrompt, base64Images, enforceJson, null, callback);
    }

    private void generateContentInternal(String apiKey,
                                         String modelId,
                                         String systemInstruction,
                                         String userPrompt,
                                         List<String> base64Images,
                                         boolean enforceJson,
                                         Float temperature,
                                         final ApiCallback<String> callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onError("Gemini API key is missing. Please configure it in Settings.");
            return;
        }

        if (modelId == null || modelId.trim().isEmpty()) {
            callback.onError("No active Gemini model selected. Please select a model in Settings.");
            return;
        }

        String targetModel = modelId.trim();
        String url = BASE_URL + "models/" + targetModel + ":generateContent?key=" + apiKey.trim();

        try {
            JSONObject root = new JSONObject();

            if (systemInstruction != null && !systemInstruction.trim().isEmpty()) {
                JSONObject systemInstObj = new JSONObject();
                JSONArray sysParts = new JSONArray();
                JSONObject sysPartObj = new JSONObject();
                sysPartObj.put("text", systemInstruction);
                sysParts.put(sysPartObj);
                systemInstObj.put("parts", sysParts);
                root.put("systemInstruction", systemInstObj);
            }

            JSONArray contents = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            JSONArray parts = new JSONArray();

            // Inject Base64 Image Parts for Gemini Vision
            if (base64Images != null && !base64Images.isEmpty()) {
                for (String b64 : base64Images) {
                    if (b64 != null && !b64.trim().isEmpty()) {
                        JSONObject inlineData = new JSONObject();
                        inlineData.put("mime_type", "image/jpeg");
                        inlineData.put("data", b64.trim());
                        JSONObject imgPart = new JSONObject();
                        imgPart.put("inline_data", inlineData);
                        parts.put(imgPart);
                    }
                }
            }

            // Inject User Prompt Text Part
            JSONObject partText = new JSONObject();
            partText.put("text", userPrompt);
            parts.put(partText);

            userMsg.put("parts", parts);
            contents.put(userMsg);
            root.put("contents", contents);

            JSONObject generationConfig = new JSONObject();
            boolean hasConfig = false;

            if (enforceJson) {
                generationConfig.put("responseMimeType", "application/json");
                hasConfig = true;
            }

            if (temperature != null) {
                generationConfig.put("temperature", temperature.doubleValue());
                hasConfig = true;
            }

            if (hasConfig) {
                root.put("generationConfig", generationConfig);
            }

            RequestBody body = RequestBody.create(root.toString(), JSON);
            Request request = new Request.Builder().url(url).post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, final IOException e) {
                    mainHandler.post(() -> callback.onError("Network error: " + e.getLocalizedMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        String detailedError = "HTTP Error " + response.code();
                        try {
                            String errBody = response.body() != null ? response.body().string() : "";
                            JSONObject errJson = new JSONObject(errBody);
                            JSONObject errorObj = errJson.optJSONObject("error");
                            if (errorObj != null) {
                                detailedError = errorObj.optString("message", detailedError);
                            }
                        } catch (Exception ignored) {}
                        
                        final String finalErr = detailedError;
                        mainHandler.post(() -> callback.onError(finalErr));
                        response.close();
                        return;
                    }

                    try {
                        String responseStr = response.body() != null ? response.body().string() : "";
                        JSONObject json = new JSONObject(responseStr);
                        JSONArray candidates = json.optJSONArray("candidates");

                        if (candidates != null && candidates.length() > 0) {
                            JSONObject firstCand = candidates.getJSONObject(0);
                            JSONObject content = firstCand.optJSONObject("content");

                            if (content != null) {
                                JSONArray resParts = content.optJSONArray("parts");

                                if (resParts != null && resParts.length() > 0) {
                                    String textResult = resParts.getJSONObject(0).optString("text", "");
                                    textResult = cleanOutput(textResult);
                                    final String finalResult = textResult;
                                    mainHandler.post(() -> callback.onSuccess(finalResult));
                                    return;
                                }
                            }
                        }

                        mainHandler.post(() -> callback.onError("No content returned in Gemini response."));
                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onError("Error parsing Gemini response: " + e.getMessage()));
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            callback.onError("Error constructing Gemini request: " + e.getMessage());
        }
    }

    public String cleanOutput(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```python")) {
            trimmed = trimmed.substring(9);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    public String cleanJsonOutput(String input) {
        return cleanOutput(input);
    }

    public String cleanPythonOutput(String input) {
        if (input == null) return "";
        String text = input.trim();

        // Extract code inside ```python ... ``` block if present
        int codeBlockStart = text.indexOf("```python");
        if (codeBlockStart != -1) {
            int contentStart = codeBlockStart + 9;
            int codeBlockEnd = text.indexOf("```", contentStart);
            if (codeBlockEnd != -1) {
                return text.substring(contentStart, codeBlockEnd).trim();
            } else {
                return text.substring(contentStart).trim();
            }
        }

        // Extract code inside generic ``` ... ``` block
        int genericBlockStart = text.indexOf("```");
        if (genericBlockStart != -1) {
            int contentStart = genericBlockStart + 3;
            int codeBlockEnd = text.indexOf("```", contentStart);
            if (codeBlockEnd != -1) {
                return text.substring(contentStart, codeBlockEnd).trim();
            } else {
                return text.substring(contentStart).trim();
            }
        }

        // If no code block markdown fences exist, trim any conversational preamble before import bpy
        int importIdx = text.indexOf("import bpy");
        if (importIdx > 0) {
            text = text.substring(importIdx);
        }

        return cleanOutput(text);
    }
}