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

    private static final int SCRIPT_REPAIR_TIMEOUT_SECONDS = 35;

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
     * aesthetic defects (boxiness, wheel alignment, bad lighting). Supports prompt-free scripts.
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
                : "Custom user-supplied Blender Python script (Prompt omitted). Refine geometry curvature, beveling, materials, and lighting based on the visual render preview.";

        safePrompt += "\nCRITICAL SPATIAL AUDIT: Verify that car total length is exactly 4.5m, the road is 14m wide x 250m long flat at Z=0.0 with center dashes at Z=0.005 UP (0,0,1), tires sit flush on road at Z=0.0 starting at Y=5.0, car is parented to 'Model_Root' animated driving forward along Y to 80m, and camera tracks car from low dramatic 3/4 front angle.";

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
                "Your objective is to fix the exact error identified in the traceback, preserve all 3D assets/materials from the code, and output the entire corrected script.\n\n" +
                "CRITICAL REQUIREMENTS & KNOWLEDGE GATES:\n" +
                "1. Output ONLY the fully corrected, executable Python script inside a single ```python ... ``` block. No conversational filler, greetings, or explanations.\n" +
                "2. Read the error traceback carefully and fix the specific failing line, parameter, enum, or syntax.\n" +
                "3. BLENDER 4.2+ COLOR MANAGEMENT ENUMS:\n" +
                "   - Under the AgX view transform, the valid looks are strictly: 'None', 'AgX - Punchy', 'AgX - High Contrast', 'AgX - Medium High Contrast', 'AgX - Base Contrast', 'AgX - Low Contrast'.\n" +
                "   - NEVER set `scene.view_settings.look = 'High Contrast'`. ALWAYS set `scene.view_settings.look = 'AgX - High Contrast'`.\n" +
                "4. ASSET INGESTION & ASCII FBX LIMITATION:\n" +
                "   - If the error states `ASCII FBX files are not supported`, DO NOT retry `bpy.ops.import_scene.fbx`!\n" +
                "   - The worker environment auto-converts ASCII FBX files into GLB format at 'inputs/input_model.glb' (or 'input_model.glb').\n" +
                "   - Replace the failing import call with: `bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')`.\n" +
                "5. API GUARDS:\n" +
                "   - Mesh primitives must use `bpy.ops.mesh.primitive_..._add` (never use `_create` or `bpy.ops.object.mesh.`).\n" +
                "   - Lights must use `bpy.ops.object.light_add(type=...)`. Valid types: ('POINT', 'SUN', 'SPOT', 'AREA').\n" +
                "   - Texture types in `bpy.data.textures.new(...)` MUST be one of: ('NONE', 'BLEND', 'CLOUDS', 'DISTORTED_NOISE', 'IMAGE', 'MAGIC', 'MARBLE', 'MUSGRAVE', 'NOISE', 'STUCCI', 'VORONOI', 'WOOD').\n" +
                "   - Principled BSDF socket names must conform to Blender 4.2+ ('Transmission Weight', 'Roughness', 'Metallic', 'Specular IOR Level').\n" +
                "6. VEHICLE SPATIAL, HIGHWAY ROAD & ANIMATION MANDATES (STRICT 5 RULES):\n" +
                "   - RULE 1: CAR SCALE NORMALIZATION: Imported cars are modeled in oversized millimeter/centimeter units. Calculate combined bounding box across all imported car sub-meshes. Scale the entire assembly down so its total length is exactly real-world automotive size: 4.5 meters. Apply all scale transforms.\n" +
                "   - RULE 2: ROAD SCALE & ALIGNMENT: Build a realistic multi-lane asphalt highway matching the 4.5m car: 14 meters wide and at least 250 meters long, flat on ground at Z=0.0 running straight along Y-axis with rotation (0,0,0). Add center lane dashes at Z=0.005 with normal pointing straight UP (0,0,1). NEVER OMIT THE ROAD FROM THE SCENE.\n" +
                "   - RULE 3: PLACING CAR ON ROAD: Center car in driving lane at X=0.0. Snap bottom-most point of tires flush on top of road at Z=0.0. Start car near beginning of road at Y=5.0.\n" +
                "   - RULE 4: PARENTING & DRIVING ANIMATION: Create master Empty 'Model_Root' at car base. Parent all imported car sub-meshes to 'Model_Root' keeping relative assembly offsets intact. Animate 'Model_Root' driving along Y-axis from Y=5.0 at frame 1 to Y=80.0 at frame 60 using location keyframes. Find all wheel/tire meshes using clean syntax `_wheel_keys = ['wheel', 'tire', 'rim', 'tyre', 'disc']` and animate them spinning around their axles proportional to driving speed.\n" +
                "   - RULE 5: CINEMATIC CAMERA & LIGHTING: Parent camera to 'Model_Root' at offset `(-3.8, -7.0, 2.2)` with a `TRACK_TO` constraint targeting 'Model_Root'. Add bright Sun light (energy >= 5.0) and world sky background radiance (strength >= 1.2) so the scene is NEVER pitch black.\n" +
                "7. MANDATORY COMPLETE SCENE EXPORT:\n" +
                "   - You MUST ensure `bpy.ops.export_scene.gltf(filepath='output/model.glb', export_format='GLB', export_apply=False, export_skins=True, export_animations=True, export_materials='EXPORT')` is placed at the VERY END of the script so BOTH the car and the highway road are exported into 'output/model.glb'.\n" +
                "   - Do not return partial snippets or placeholders like `# ... rest of code`. Return the full complete scene script.";
    }

    private String buildBlenderRepairUserPrompt(String userPrompt, String failedScript, String errorTraceback) {
        String safePrompt = (userPrompt != null && !userPrompt.trim().isEmpty())
                ? userPrompt
                : "Custom user-supplied Blender Python script (Prompt omitted by user). Fix syntax and API errors while preserving all 3D mesh objects, highway road, and scene composition.";

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
            if (errorTraceback.toLowerCase().contains("syntaxerror") || errorTraceback.contains("line 232")) {
                sb.append("HEALING DIRECTIVE FOR SYNTAX ERROR ON WHEEL OBJECT FILTERING:\n")
                  .append("- Fix the list comprehension using standard, valid syntax:\n")
                  .append("  _wheel_keys = ['wheel', 'tire', 'rim', 'tyre', 'disc']\n")
                  .append("  _wheel_objs = [m for m in _car_meshes if any(wk in m.name.lower() for wk in _wheel_keys)]\n\n");
            }
            if (errorTraceback.toLowerCase().contains("timeout") || errorTraceback.contains("600s")) {
                sb.append("HEALING DIRECTIVE FOR TIMEOUT OPTIMIZATION:\n")
                  .append("- Batch or join repeated mesh primitives using bmesh or bpy.ops.object.join(), reduce per-object operator loops, and clamp Cycles bounces.\n\n");
            }
        }

        sb.append("HEALING DIRECTIVE FOR VEHICLE SCENE INTEGRATION:\n")
          .append("- Enforce Rule 1: Car length normalized to 4.5m across combined bounding box, scale transforms applied.\n")
          .append("- Enforce Rule 2: Multi-lane asphalt road 14m wide x 250m long, Z=0.0, rotation (0,0,0), center dashes at Z=0.005 UP (0,0,1). DO NOT DROP THE ROAD.\n")
          .append("- Enforce Rule 3: Centered at X=0.0, tires flush on road at Z=0.0, start at Y=5.0.\n")
          .append("- Enforce Rule 4: Master Empty 'Model_Root' parents all car sub-meshes, location keyframed Y=5.0 (frame 1) to Y=80.0 (frame 60), wheels spinning.\n")
          .append("- Enforce Rule 5: Tracking camera parented to 'Model_Root' at (-3.8, -7.0, 2.2) with TRACK_TO constraint. Add bright SUN light and world radiance so the video and preview are NEVER pitch black.\n")
          .append("- Ensure `export_scene.gltf` runs at the very end so BOTH the car and the highway road are in 'output/model.glb'.\n\n");

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
        cleaned = cleaned.replace(".primitive_cube_create(", ".primitive_cube_add(");
        cleaned = cleaned.replace(".primitive_plane_create(", ".primitive_plane_add(");
        cleaned = cleaned.replace(".primitive_cylinder_create(", ".primitive_cylinder_add(");
        cleaned = cleaned.replace(".primitive_cone_create(", ".primitive_cone_add(");
        cleaned = cleaned.replace(".primitive_uv_sphere_create(", ".primitive_uv_sphere_add(");

        // Auto-sanitize Blender 4.2+ Principled BSDF socket changes
        cleaned = cleaned.replace("['Transmission'].default_value", "['Transmission Weight'].default_value");
        cleaned = cleaned.replace("['Subsurface'].default_value", "['Subsurface Weight'].default_value");
        cleaned = cleaned.replace("['Specular'].default_value", "['Specular IOR Level'].default_value");
        cleaned = cleaned.replaceAll("inputs\\[['\"]Transmission['\"]\\]", "inputs['Transmission Weight']");
        cleaned = cleaned.replaceAll("inputs\\[['\"]Subsurface['\"]\\]", "inputs['Subsurface Weight']");
        cleaned = cleaned.replaceAll("inputs\\[['\"]Specular['\"]\\]", "inputs['Specular IOR Level']");

        // Auto-sanitize hallucinated object.keyframe_[xyz] axis assignments
        cleaned = cleaned.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([xX])\\s*=\\s*([^\\n;]+)", "$1.location.x = $3; $1.keyframe_insert(data_path='location', index=0)");
        cleaned = cleaned.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([yY])\\s*=\\s*([^\\n;]+)", "$1.location.y = $3; $1.keyframe_insert(data_path='location', index=1)");
        cleaned = cleaned.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([zZ])\\s*=\\s*([^\\n;]+)", "$1.location.z = $3; $1.keyframe_insert(data_path='location', index=2)");
        cleaned = cleaned.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_location\\s*=\\s*([^\\n;]+)", "$1.location = $2; $1.keyframe_insert(data_path='location')");
        cleaned = cleaned.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_rotation\\s*=\\s*([^\\n;]+)", "$1.rotation_euler = $2; $1.keyframe_insert(data_path='rotation_euler')");
        cleaned = cleaned.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_scale\\s*=\\s*([^\\n;]+)", "$1.scale = $2; $1.keyframe_insert(data_path='scale')");

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