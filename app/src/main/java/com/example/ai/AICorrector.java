package com.example.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.example.engine.Scene;
import com.example.tools.ToolExecutor;
import com.example.tools.ToolOperation;
import com.example.utils.VynaraLogger;
import com.example.validation.ValidationResult;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AICorrector {
    private final ToolExecutor toolExecutor;
    private final AIOrchestrator aiOrchestrator;
    private final Scene activeScene;

    // Increased from 35s to 90s to comfortably handle Gemini server traffic spikes without premature aborts
    private static final int SCRIPT_REPAIR_TIMEOUT_SECONDS = 90;

    public AICorrector(ToolExecutor toolExecutor, AIOrchestrator aiOrchestrator) {
        this(toolExecutor, aiOrchestrator, null);
    }

    public AICorrector(ToolExecutor toolExecutor, AIOrchestrator aiOrchestrator, Scene activeScene) {
        this.toolExecutor = toolExecutor;
        this.aiOrchestrator = aiOrchestrator;
        this.activeScene = activeScene;
    }

    /**
     * Evaluates validation inspection results and executes the internal AI correction loop:
     * Generate -> Validate -> Inspect -> Problem Detection -> Repair Selection -> Correction.
     */
    public boolean applyCorrections(List<ValidationResult> inspectionResults) {
        if (inspectionResults == null || inspectionResults.isEmpty()) {
            return true;
        }

        boolean allCorrectionsSuccessful = true;

        for (ValidationResult vr : inspectionResults) {
            if (vr.getSeverity() == ValidationResult.Severity.ERROR ||
                vr.getSeverity() == ValidationResult.Severity.CRITICAL) {

                boolean repairExecuted = executeIntelligenceDrivenRepair(vr);
                if (!repairExecuted) {
                    allCorrectionsSuccessful = false;
                }
            }
        }

        return allCorrectionsSuccessful;
    }

    /**
     * SOLUTION B: AI Script Corrector (Async)
     * Analyzes the faulty Blender script and the terminal traceback from error.txt,
     * working seamlessly whether a prompt was supplied or omitted.
     */
    public void correctBlenderScript(String userPrompt,
                                     String failedScript,
                                     String errorTraceback,
                                     final GeminiApiClient.ApiCallback<String> callback) {
        if (aiOrchestrator == null || aiOrchestrator.getApiKeyManager() == null || !aiOrchestrator.getApiKeyManager().hasApiKey()) {
            if (callback != null) {
                callback.onError("Cannot repair script: Gemini API key is missing or unconfigured.");
            }
            return;
        }

        String repairInstruction = buildBlenderRepairSystemInstruction();
        String repairPrompt = buildBlenderRepairUserPrompt(userPrompt, failedScript, errorTraceback);

        VynaraLogger.system("AICorrector: Dispatching single-turn script repair request to Gemini...");

        aiOrchestrator.getApiClient().generateContent(
                aiOrchestrator.getApiKeyManager().getApiKey(),
                aiOrchestrator.getApiKeyManager().getSelectedModel(),
                repairInstruction,
                repairPrompt,
                new GeminiApiClient.ApiCallback<String>() {
                    @Override
                    public void onSuccess(String result) {
                        String cleanedScript = cleanAndValidateScript(result);
                        if (cleanedScript.isEmpty()) {
                            VynaraLogger.e("AICorrector: Gemini returned an empty or invalid repair script.");
                            if (callback != null) {
                                callback.onError("Gemini returned empty or invalid repair script.");
                            }
                        } else {
                            VynaraLogger.system("AICorrector: Successfully repaired Python script (" + cleanedScript.length() + " chars).");
                            if (callback != null) {
                                callback.onSuccess(cleanedScript);
                            }
                        }
                    }

                    @Override
                    public void onError(String error) {
                        VynaraLogger.e("AICorrector: Script repair failed from Gemini API: " + error);
                        if (callback != null) {
                            callback.onError(error);
                        }
                    }
                }
        );
    }

    /**
     * SOLUTION B: AI Script Corrector (Sync / Blocking)
     */
    public String correctBlenderScriptSync(String userPrompt, String failedScript, String errorTraceback) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> repairedScriptRef = new AtomicReference<>(null);

        correctBlenderScript(userPrompt, failedScript, errorTraceback, new GeminiApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                repairedScriptRef.set(result);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                VynaraLogger.e("AICorrector (Sync): Repair error: " + error);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(SCRIPT_REPAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                VynaraLogger.e("AICorrector (Sync): Script repair timed out after " + SCRIPT_REPAIR_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            VynaraLogger.e("AICorrector (Sync): Repair interrupted: " + e.getMessage());
        }

        return repairedScriptRef.get();
    }

    /**
     * VISUAL CRITIQUE & REFINEMENT (Async)
     * Compares the Cycles preview render against the reference photo using Gemini Vision to spot and fix
     * aesthetic defects. Supports prompt-free scripts.
     */
    public void critiqueAndRefineBlenderScript(String userPrompt,
                                              String currentScript,
                                              File referenceImageFile,
                                              File renderPreviewFile,
                                              final GeminiApiClient.ApiCallback<String> callback) {
        if (aiOrchestrator == null || aiOrchestrator.getApiKeyManager() == null || !aiOrchestrator.getApiKeyManager().hasApiKey()) {
            if (callback != null) callback.onError("Cannot critique scene: Gemini API key missing.");
            return;
        }

        String safePrompt = (userPrompt != null && !userPrompt.trim().isEmpty())
                ? userPrompt
                : "Custom user-supplied Blender Python script. Refine geometry curvature, beveling, materials, and lighting based on the visual render preview.";

        safePrompt += "\nDYNAMIC AUDIT: Verify that the 3D subject sits grounded on the road/terrain/pedestal, camera tracks smoothly with clear line-of-sight, World background sky radiance is active, and materials reflect natural lighting.";

        String b64Ref = encodeImageFileToBase64(referenceImageFile);
        String b64Render = encodeImageFileToBase64(renderPreviewFile);

        VynaraLogger.system("AICorrector: Dispatching multimodal visual critique to Gemini Vision...");

        aiOrchestrator.getApiClient().critiqueAndRefineRender(
                aiOrchestrator.getApiKeyManager().getApiKey(),
                aiOrchestrator.getApiKeyManager().getSelectedModel(),
                safePrompt,
                currentScript,
                b64Ref,
                b64Render,
                new GeminiApiClient.ApiCallback<String>() {
                    @Override
                    public void onSuccess(String result) {
                        String cleaned = cleanAndValidateScript(result);
                        if (cleaned.isEmpty()) {
                            if (callback != null) callback.onError("Gemini returned empty refined script.");
                        } else {
                            VynaraLogger.system("AICorrector: Visual critique successfully refined Python script (" + cleaned.length() + " chars).");
                            if (callback != null) callback.onSuccess(cleaned);
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        VynaraLogger.e("AICorrector: Visual refinement failed: " + errorMessage);
                        if (callback != null) callback.onError(errorMessage);
                    }
                }
        );
    }

    /**
     * VISUAL CRITIQUE & REFINEMENT (Sync / Blocking)
     */
    public String critiqueAndRefineBlenderScriptSync(String userPrompt,
                                                    String currentScript,
                                                    File referenceImageFile,
                                                    File renderPreviewFile) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> refinedScriptRef = new AtomicReference<>(null);

        critiqueAndRefineBlenderScript(userPrompt, currentScript, referenceImageFile, renderPreviewFile, new GeminiApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                refinedScriptRef.set(result);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                VynaraLogger.e("AICorrector (Sync): Visual critique error: " + error);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(SCRIPT_REPAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                VynaraLogger.e("AICorrector (Sync): Visual critique timed out after " + SCRIPT_REPAIR_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            VynaraLogger.e("AICorrector (Sync): Interrupted: " + e.getMessage());
        }

        return refinedScriptRef.get();
    }

    private String buildBlenderRepairSystemInstruction() {
        return "You are an elite Blender Python (`bpy`) core engineer and debugger specializing in automated 3D asset generation.\n" +
                "A cloud worker running headless Blender 4.2+ failed with a runtime exception or traceback while executing a script.\n" +
                "Your objective is to fix the exact error identified in the traceback, preserve all dynamic 3D meshes, custom spline curves, terrain, materials, and motion from the code, and output the entire corrected script.\n\n" +
                "CRITICAL DEBUGGING REQUIREMENTS:\n" +
                "1. Output ONLY the fully corrected, executable Python script inside a single ```python ... ``` block. No conversational filler or explanations.\n" +
                "2. Read the error traceback carefully and fix the specific failing line, parameter, enum, or syntax.\n" +
                "3. PRESERVE ENVIRONMENT & PATH GEOMETRY: If the script contains custom Bézier curves, sweeping expressways, hairpin mountain roads, 1km-5km distances, runways, or custom terrain, PRESERVE THEM COMPLETELY. Do not overwrite custom geometry with a flat box.\n" +
                "4. BLENDER 4.2+ COMPLIANCE:\n" +
                "   - Under AgX, view_settings.look must be one of: 'None', 'AgX - Punchy', 'AgX - High Contrast', 'AgX - Medium High Contrast', 'AgX - Base Contrast', 'AgX - Low Contrast'.\n" +
                "   - Socket names conform strictly to Blender 4.2+ ('Transmission Weight', 'Roughness', 'Metallic', 'Base Color').\n" +
                "   - Principled BSDF 'Base Color' socket is strictly RGBA (requires 4-tuple: `(R, G, B, 1.0)`). NEVER pass a 3-tuple to Base Color default_value.\n" +
                "   - If `update_from_data` failed, use `obj.data.update()`.\n" +
                "   - If `node_nodes` failed, use `mat.use_nodes` or `mat.node_tree.nodes`.\n" +
                "5. ASSET INGESTION & FORMAT FALLBACKS:\n" +
                "   - If the error states `ASCII FBX files are not supported`, replace `import_scene.fbx` with `bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')` (or 'input_model.glb').\n" +
                "6. LIGHTING & CAMERA:\n" +
                "   - Ensure `bpy.context.scene.world.use_nodes = True` and link an active Background shader with ambient radiance (strength >= 1.2) so renders are never black.\n" +
                "   - Ensure camera `clip_end = 10000.0` (10 km) and points at the subject using a `TRACK_TO` constraint.\n" +
                "7. COMPLETE SCENE EXPORT:\n" +
                "   - Ensure `bpy.ops.export_scene.gltf(filepath='output/model.glb', export_format='GLB', export_apply=False, export_skins=True, export_animations=True, export_materials='EXPORT')` is placed at the VERY END of the script so all scene objects are saved.";
    }

    private String buildBlenderRepairUserPrompt(String userPrompt, String failedScript, String errorTraceback) {
        String safePrompt = (userPrompt != null && !userPrompt.trim().isEmpty())
                ? userPrompt
                : "Custom user-supplied Blender Python script. Fix syntax and API errors while preserving all 3D mesh objects, spline paths, and scene composition.";

        StringBuilder sb = new StringBuilder();
        sb.append("=== WHAT WAS BEING BUILT (USER PROMPT / GOAL) ===\n")
          .append(safePrompt)
          .append("\n\n");

        sb.append("=== EXACT BLENDER TERMINAL ERROR / TRACEBACK (FROM error.txt) ===\n")
          .append(errorTraceback != null ? errorTraceback : "Unknown execution failure")
          .append("\n\n");

        if (errorTraceback != null) {
            if (errorTraceback.contains("ASCII FBX")) {
                sb.append("HEALING DIRECTIVE FOR ASCII FBX:\n")
                  .append("- Replace `bpy.ops.import_scene.fbx(filepath='inputs/input_model.fbx')` with `bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')` (or 'input_model.glb').\n\n");
            }
            if (errorTraceback.contains("High Contrast")) {
                sb.append("HEALING DIRECTIVE FOR COLOR LOOK:\n")
                  .append("- Replace `scene.view_settings.look = 'High Contrast'` with `scene.view_settings.look = 'AgX - High Contrast'`.\n\n");
            }
            if (errorTraceback.contains("update_from_data")) {
                sb.append("HEALING DIRECTIVE FOR UPDATE FROM DATA:\n")
                  .append("- Replace `obj.update_from_data()` with `obj.data.update()`.\n\n");
            }
            if (errorTraceback.contains("node_nodes")) {
                sb.append("HEALING DIRECTIVE FOR MATERIAL NODES:\n")
                  .append("- Replace `mat.node_nodes` with `mat.use_nodes = True` and `nodes = mat.node_tree.nodes`.\n\n");
            }
            if (errorTraceback.contains("should contain 4 items, not 3") || errorTraceback.contains("Base Color")) {
                sb.append("HEALING DIRECTIVE FOR BASE COLOR 4-TUPLE:\n")
                  .append("- Pass a 4-element RGBA tuple `(r, g, b, 1.0)` to `bsdf.inputs['Base Color'].default_value` including alpha.\n\n");
            }
            if (errorTraceback.toLowerCase().contains("timeout") || errorTraceback.contains("600s")) {
                sb.append("HEALING DIRECTIVE FOR TIMEOUT OPTIMIZATION:\n")
                  .append("- Batch or join repeated mesh primitives using bmesh or bpy.ops.object.join(), reduce per-object operator loops, and clamp Cycles bounces to 4.\n\n");
            }
        }

        sb.append("HEALING DIRECTIVE FOR DYNAMIC SCENE INTEGRATION:\n")
          .append("- Preserve user-defined path geometry, curves, terrain, distances (e.g. 1km - 5km), and animation duration.\n")
          .append("- Ensure `bpy.context.scene.world.use_nodes = True` with background sky radiance and sun lighting so render output is never pitch black.\n")
          .append("- Ensure `export_scene.gltf` runs at the very end so all objects are written to 'output/model.glb'.\n\n");

        sb.append("=== THE FAULTY SCRIPT THAT FAILED ===\n")
          .append(failedScript != null ? failedScript : "# No script content");

        return sb.toString();
    }

    private String cleanAndValidateScript(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "";
        }

        String cleaned = rawResponse.trim();

        if (cleaned.startsWith("```python")) {
            cleaned = cleaned.substring("```python".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        cleaned = cleaned.trim();

        if (!cleaned.contains("import bpy") && !cleaned.contains("bpy.")) {
            int idx = cleaned.indexOf("import bpy");
            if (idx >= 0) {
                cleaned = cleaned.substring(idx).trim();
            }
        }

        // Auto-sanitize update_from_data and node_nodes
        cleaned = cleaned.replace(".update_from_data()", ".data.update()")
                         .replace(".node_nodes", ".use_nodes");

        // Auto-sanitize legacy color looks to Blender 4.2 AgX
        cleaned = cleaned.replaceAll("view_settings\\.look\\s*=\\s*['\"]High Contrast['\"]", "view_settings.look = 'AgX - High Contrast'");
        cleaned = cleaned.replaceAll("view_settings\\.look\\s*=\\s*['\"]Medium High Contrast['\"]", "view_settings.look = 'AgX - Medium High Contrast'");
        cleaned = cleaned.replaceAll("view_settings\\.look\\s*=\\s*['\"]Very High Contrast['\"]", "view_settings.look = 'AgX - Very High Contrast'");
        cleaned = cleaned.replaceAll("view_settings\\.look\\s*=\\s*['\"]Base Contrast['\"]", "view_settings.look = 'AgX - Base Contrast'");
        cleaned = cleaned.replaceAll("view_settings\\.look\\s*=\\s*['\"]Punchy['\"]", "view_settings.look = 'AgX - Punchy'");

        // Auto-sanitize unquoted f-strings
        cleaned = cleaned.replaceAll("(?<=[=\\s,(])f([a-zA-Z0-9_]+\\{[^}\"\\n]+\\}[a-zA-Z0-9_]*)", "f\"$1\"");

        // Auto-sanitize hallucinated mesh, camera, and light operators
        cleaned = cleaned.replace("bpy.ops.object.mesh.", "bpy.ops.mesh.");
        cleaned = cleaned.replace("bpy.ops.light.add(", "bpy.ops.object.light_add(");
        cleaned = cleaned.replace("bpy.ops.camera.add(", "bpy.ops.object.camera_add(");
        cleaned = cleaned.replace(".primitive_cube_create(", ".primitive_cube_add(")
                         .replace(".primitive_plane_create(", ".primitive_plane_add(")
                         .replace(".primitive_cylinder_create(", ".primitive_cylinder_add(")
                         .replace(".primitive_cone_create(", ".primitive_cone_add(")
                         .replace(".primitive_uv_sphere_create(", ".primitive_uv_sphere_add(");

        // Auto-sanitize Blender 4.2+ Principled BSDF socket changes
        cleaned = cleaned.replace("['Transmission'].default_value", "['Transmission Weight'].default_value")
                         .replace("['Subsurface'].default_value", "['Subsurface Weight'].default_value")
                         .replace("['Specular'].default_value", "['Specular IOR Level'].default_value");
        cleaned = cleaned.replaceAll("inputs\\[['\"]Transmission['\"]\\]", "inputs['Transmission Weight']")
                         .replaceAll("inputs\\[['\"]Subsurface['\"]\\]", "inputs['Subsurface Weight']")
                         .replaceAll("inputs\\[['\"]Specular['\"]\\]", "inputs['Specular IOR Level']");

        // Auto-sanitize Base Color ensuring it is ALWAYS a 4-tuple (RGBA)
        cleaned = cleaned.replaceAll(
                "(inputs\\s*\\[\\s*['\"]Base Color['\"]\\s*\\]\\.default_value\\s*=\\s*\\(\\s*[-+]?[0-9]*\\.?[0-9]+\\s*,\\s*[-+]?[0-9]*\\.?[0-9]+\\s*,\\s*[-+]?[0-9]*\\.?[0-9]+\\s*)\\)",
                "$1, 1.0)"
        );

        // Auto-sanitize hallucinated object.keyframe_[xyz] axis assignments
        cleaned = cleaned.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([xX])\\s*=\\s*([^\\n;]+)", "$1.location.x = $3; $1.keyframe_insert(data_path='location', index=0)")
                         .replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([yY])\\s*=\\s*([^\\n;]+)", "$1.location.y = $3; $1.keyframe_insert(data_path='location', index=1)")
                         .replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([zZ])\\s*=\\s*([^\\n;]+)", "$1.location.z = $3; $1.keyframe_insert(data_path='location', index=2)")
                         .replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_location\\s*=\\s*([^\\n;]+)", "$1.location = $2; $1.keyframe_insert(data_path='location')")
                         .replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_rotation\\s*=\\s*([^\\n;]+)", "$1.rotation_euler = $2; $1.keyframe_insert(data_path='rotation_euler')")
                         .replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_scale\\s*=\\s*([^\\n;]+)", "$1.scale = $2; $1.keyframe_insert(data_path='scale')");

        return cleaned.trim();
    }

    private String encodeImageFileToBase64(File file) {
        if (file == null || !file.exists() || file.length() == 0) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            int maxDim = Math.max(options.outWidth, options.outHeight);
            int inSampleSize = 1;
            while (maxDim / inSampleSize > 1024) {
                inSampleSize *= 2;
            }

            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] bytes = baos.toByteArray();
            bitmap.recycle();

            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            VynaraLogger.e("AICorrector: Failed to encode image to base64: " + e.getMessage());
            return null;
        }
    }

    /**
     * Consults Gemini AI for a local repair plan, falling back to local deterministic repairs if offline.
     */
    private boolean executeIntelligenceDrivenRepair(ValidationResult vr) {
        if (vr == null || vr.getMessage() == null || toolExecutor == null) {
            return false;
        }

        if (aiOrchestrator == null || aiOrchestrator.getApiKeyManager() == null || !aiOrchestrator.getApiKeyManager().hasApiKey()) {
            return executeLocalDeterministicRepair(vr);
        }

        String sceneContextJson = activeScene != null ? AIContext.buildSceneContextJson(activeScene) : "{}";
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean repairSuccess = new AtomicBoolean(false);

        aiOrchestrator.requestCorrectionPlan(vr.getMessage(), vr.getCategory().name(), sceneContextJson, new GeminiApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String jsonResult) {
                try {
                    JSONObject opObj = new JSONObject(jsonResult);
                    String toolId = opObj.optString("toolId", null);

                    if (toolId != null && !toolId.trim().isEmpty()) {
                        ToolOperation repairOp = new ToolOperation(toolId);
                        JSONObject paramsObj = opObj.optJSONObject("parameters");
                        if (paramsObj != null) {
                            java.util.Iterator<String> keys = paramsObj.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                Object val = paramsObj.opt(key);
                                if (val != null) {
                                    repairOp.setParam(key, val);
                                }
                            }
                        }
                        boolean executed = toolExecutor.executeOperation(repairOp);
                        repairSuccess.set(executed);
                    } else {
                        repairSuccess.set(executeLocalDeterministicRepair(vr));
                    }
                } catch (Exception e) {
                    repairSuccess.set(executeLocalDeterministicRepair(vr));
                }
                latch.countDown();
            }

            @Override
            public void onError(String errorMessage) {
                repairSuccess.set(executeLocalDeterministicRepair(vr));
                latch.countDown();
            }
        });

        try {
            boolean ok = latch.await(5, TimeUnit.SECONDS);
            if (!ok) {
                return executeLocalDeterministicRepair(vr);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return executeLocalDeterministicRepair(vr);
        }

        return repairSuccess.get();
    }

    private boolean executeLocalDeterministicRepair(ValidationResult vr) {
        String msg = vr.getMessage().toLowerCase();

        // 1. Missing or Degenerate Mesh Repair
        if (msg.contains("mesh") || msg.contains("vertex") || msg.contains("vertices")) {
            ToolOperation repairMeshOp = new ToolOperation("geometry.create_primitive")
                    .setParam("type", "cube")
                    .setParam("width", 1.5f)
                    .setParam("height", 1.5f)
                    .setParam("depth", 1.5f);
            return toolExecutor.executeOperation(repairMeshOp);
        }

        // 2. Missing Material Shading Repair
        if (msg.contains("material") || msg.contains("color") || msg.contains("shader")) {
            ToolOperation repairMatOp = new ToolOperation("material.set_properties")
                    .setParam("colorHex", "#A0A5BD")
                    .setParam("metallic", 0.1f)
                    .setParam("roughness", 0.5f);
            return toolExecutor.executeOperation(repairMatOp);
        }

        // 3. Unbound Skin or Weight Normalization Repair
        if (msg.contains("skin") || msg.contains("weight") || msg.contains("skeleton")) {
            ToolOperation bindOp = new ToolOperation("skeleton.bind");
            return toolExecutor.executeOperation(bindOp);
        }

        // 4. Default Fallback Re-validation Tool
        ToolOperation checkOp = new ToolOperation("validation.check_mesh");
        return toolExecutor.executeOperation(checkOp);
    }

    public ToolExecutor getToolExecutor() {
        return toolExecutor;
    }
}