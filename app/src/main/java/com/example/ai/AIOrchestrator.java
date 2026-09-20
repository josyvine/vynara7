package com.example.ai;

import com.example.ai.agents.BlenderWorkerAgent;
import com.example.ai.agents.DirectorAgent;
import com.example.ai.protocol.AIDirectorSpec;
import com.example.ai.protocol.AIProductionPlan;
import com.example.ai.protocol.AIProductionRequest;
import com.example.asset.Asset;
import com.example.cloud.CloudProvider;
import com.example.knowledge.KnowledgeEntry;
import com.example.knowledge.KnowledgeManager;
import com.example.runtime.ProjectRuntime;
import com.example.tasks.ProductionPlan;
import com.example.tasks.TaskNode;
import com.example.tools.ToolDefinition;
import com.example.tools.ToolOperation;
import com.example.tools.ToolParameter;
import com.example.tools.ToolRegistry;
import com.example.utils.VynaraLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AIOrchestrator {
    private final GeminiApiClient apiClient;
    private final ApiKeyManager apiKeyManager;
    private final KnowledgeManager knowledgeManager;
    private final PromptInterpreter promptInterpreter;
    private final DirectorAgent directorAgent;

    public AIOrchestrator(GeminiApiClient apiClient, ApiKeyManager apiKeyManager, KnowledgeManager knowledgeManager) {
        this.apiClient = apiClient;
        this.apiKeyManager = apiKeyManager;
        this.knowledgeManager = knowledgeManager;
        this.promptInterpreter = new PromptInterpreter(knowledgeManager);
        this.directorAgent = new DirectorAgent(apiClient, apiKeyManager);
    }

    public ProductionPlan planProduction(String userPrompt, String style, String targetEngine) {
        return planProduction(userPrompt, style, targetEngine, new ArrayList<>());
    }

    public ProductionPlan planProduction(String userPrompt, String style, String targetEngine, List<String> referenceImageUris) {
        return promptInterpreter.createProductionPlan(userPrompt, style, targetEngine, referenceImageUris);
    }

    /**
     * Checks if the user prompt is an exact match for one of the 5 curated Demo Presets.
     * Prevents keyword collision while guaranteeing the 5 showcase demos remain fully functional.
     */
    public static boolean isDemoPreset(String prompt) {
        if (prompt == null) return false;
        String p = prompt.trim().toLowerCase(Locale.US);
        return p.contains("realistic modern villa with a swimming pool")
                || p.contains("stylized rigged superhero character")
                || p.contains("animated quadruped dog model")
                || p.contains("modern luxury leather sofa")
                || p.contains("high-detail tropical village environment")
                || p.equals("modern villa & pool")
                || p.equals("modern villa & swimming pool")
                || p.equals("rigged superhero")
                || p.equals("animated dog")
                || p.equals("leather sofa")
                || p.equals("tropical village");
    }

    /**
     * Executes the Autonomous Production Pipeline:
     * Phase 1: DirectorAgent inspects prompt & visual references to formulate the spatial/visual contract.
     * Phase 2: If Demo Preset, uses the curated demo script. If Custom Prompt, dispatches live to Gemini
     *          to write custom Blender Python (bpy) code.
     *          If an imported 3D asset is detected, Gemini is strictly instructed to import the asset
     *          and script the environment/camera/motion around it without generating primitive cubes.
     * Phase 3: Wraps the Python code with CPU-safe Cycles settings, camera rigs, and GLB export.
     */
    public void planProductionWithGemini(final AIProductionRequest request, final GeminiApiClient.ApiCallback<ProductionPlan> callback) {
        if (request == null || callback == null) return;

        if (!apiKeyManager.hasApiKey()) {
            String err = "Gemini API key is not configured in Settings. Generation halted.";
            VynaraLogger.e(err);
            callback.onError(err);
            return;
        }

        final boolean isBlenderNative = request.getTargetEngine() != null && 
                request.getTargetEngine().toLowerCase(Locale.US).contains("blender");

        VynaraLogger.system("AIOrchestrator: Initiating Phase 1 (Director Agent Specification)...");

        // Check if an imported 3D asset is explicitly bound to THIS production request
        String modelFilePath = null;
        boolean hasCustomScript = false;

        if (request.getReferenceImageUris() != null) {
            for (String uri : request.getReferenceImageUris()) {
                if (uri != null) {
                    if (uri.toLowerCase(Locale.US).endsWith(".py")) {
                        hasCustomScript = true;
                    }
                    if (uri.startsWith("model:")) {
                        modelFilePath = uri.substring(6);
                        break;
                    }
                    String lower = uri.toLowerCase(Locale.US);
                    if (lower.endsWith(".fbx") || lower.endsWith(".glb") || lower.endsWith(".gltf") || lower.endsWith(".obj")) {
                        modelFilePath = uri.startsWith("file://") ? uri.substring(7) : uri;
                        break;
                    }
                }
            }
        }

        // CRITICAL FIX: Only fallback to runtime active asset if NO custom script is attached
        // and a model was NOT explicitly omitted
        if (modelFilePath == null && !hasCustomScript) {
            try {
                ProjectRuntime runtime = ProjectRuntime.getInstance();
                if (runtime != null) {
                    Asset active = runtime.getActiveSelectedAsset();
                    if (active != null && active.getFilePath() != null) {
                        File candidate = new File(active.getFilePath());
                        if (candidate.exists() && candidate.length() > 0) {
                            modelFilePath = active.getFilePath();
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        final String activeModelPath = modelFilePath;

        // Phase 1: Formulate Director Specification
        directorAgent.formulateDirectorSpec(
                request.getUserPrompt(),
                request.getStyle(),
                request.getReferenceImageUris(),
                new DirectorAgent.DirectorCallback() {
                    @Override
                    public void onSpecReady(final AIDirectorSpec directorSpec) {
                        VynaraLogger.system("AIOrchestrator: Phase 1 Complete. Formulating Phase 2 execution graph...");

                        if (isBlenderNative) {
                            final String assetId = "asset_" + System.currentTimeMillis();
                            final ProductionPlan plan = promptInterpreter.createProductionPlan(
                                    request.getUserPrompt(), request.getStyle(), request.getTargetEngine(), request.getReferenceImageUris());

                            // Check if this is an explicit demo preset without custom reference images or models
                            boolean isDemo = isDemoPreset(request.getUserPrompt()) && !request.hasReferenceImages() && (activeModelPath == null);

                            if (isDemo) {
                                VynaraLogger.system("AIOrchestrator: Demo Preset recognized. Preserving curated demo production script.");
                                for (TaskNode node : plan.getTaskGraph().getAllNodes()) {
                                    if (node.getOperation() != null && 
                                            "blender.cloud_generate".equalsIgnoreCase(node.getOperation().getToolId())) {
                                        node.getOperation().setParam("assetId", assetId);
                                        node.getOperation().setParam("seedHero", directorSpec.getSeedHero());
                                        node.getOperation().setParam("seedVegetation", directorSpec.getSeedVegetation());
                                        node.getOperation().setParam("seedLighting", directorSpec.getSeedLighting());
                                        node.getOperation().setParam("directorSpec", directorSpec.toJson().toString());
                                    }
                                }
                                callback.onSuccess(plan);
                            } else {
                                // Phase 2: Dynamic AI Script Writer (Live Gemini Generation for custom creative prompts & imported models)
                                VynaraLogger.system("AIOrchestrator: Custom creative prompt detected. Engaging Phase 2 Dynamic AI Script Writer...");
                                dispatchDynamicScriptWriter(request.getUserPrompt(), request.getStyle(), directorSpec, activeModelPath, new GeminiApiClient.ApiCallback<String>() {
                                    @Override
                                    public void onSuccess(String dynamicBpyCode) {
                                        // Phase 3: Local Safety Wrapper with Scoped Normalization and Clean GLB Export
                                        String finalMasterScript = wrapDynamicScriptWithSafety(dynamicBpyCode, directorSpec, activeModelPath);

                                        for (TaskNode node : plan.getTaskGraph().getAllNodes()) {
                                            if (node.getOperation() != null && 
                                                    "blender.cloud_generate".equalsIgnoreCase(node.getOperation().getToolId())) {
                                                node.getOperation().setParam("assetId", assetId);
                                                node.getOperation().setParam("bpyScript", finalMasterScript);
                                                node.getOperation().setParam("w1HeroScript", dynamicBpyCode);
                                                node.getOperation().setParam("seedHero", directorSpec.getSeedHero());
                                                node.getOperation().setParam("seedVegetation", directorSpec.getSeedVegetation());
                                                node.getOperation().setParam("seedLighting", directorSpec.getSeedLighting());
                                                node.getOperation().setParam("directorSpec", directorSpec.toJson().toString());
                                            }
                                        }

                                        VynaraLogger.system("AIOrchestrator: Phase 3 Wrapper complete. Production plan ready for cloud dispatch.");
                                        callback.onSuccess(plan);
                                    }

                                    @Override
                                    public void onError(String errorMessage) {
                                        VynaraLogger.e("AIOrchestrator: Dynamic Script Writer failed: " + errorMessage);
                                        callback.onError(errorMessage);
                                    }
                                });
                            }
                        } else {
                            // Local OpenGL ES / GLTF execution path with Gemini planning
                            executeStructuredGeminiPlanning(request, directorSpec, callback);
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        VynaraLogger.e("AIOrchestrator: Director Agent halted: " + errorMessage);
                        callback.onError(errorMessage);
                    }
                }
        );
    }

    /**
     * Phase 2: Dispatches a live request to Gemini to author custom Blender Python (bpy) code.
     */
    private void dispatchDynamicScriptWriter(final String userPrompt, 
                                             final String style, 
                                             final AIDirectorSpec directorSpec,
                                             final String importedModelPath,
                                             final GeminiApiClient.ApiCallback<String> callback) {
        VynaraLogger.system("AIOrchestrator: Synthesizing live Blender Python code via Gemini Script Writer...");

        final boolean hasImportedModel = (importedModelPath != null && !importedModelPath.trim().isEmpty());
        String modelExt = ".glb";
        if (hasImportedModel) {
            String lower = importedModelPath.toLowerCase(Locale.US);
            if (lower.endsWith(".fbx")) modelExt = ".fbx";
            else if (lower.endsWith(".obj")) modelExt = ".obj";
            else if (lower.endsWith(".gltf")) modelExt = ".gltf";
        }

        String promptLower = userPrompt != null ? userPrompt.toLowerCase(Locale.US) : "";
        boolean isVehicleIntent = hasImportedModel || promptLower.contains("car") || promptLower.contains("vehicle") 
                || promptLower.contains("drive") || promptLower.contains("r8") || promptLower.contains("speed");

        StringBuilder sysInstBuilder = new StringBuilder();
        sysInstBuilder.append("You are an expert 3D technical director and rigging engineer using Blender's Python API (`bpy`).\n");
        sysInstBuilder.append("Generate production-grade, error-free Python code for Blender 4.2+.\n");
        sysInstBuilder.append("CRITICAL SYNTAX & OPERATOR RULES:\n");
        sysInstBuilder.append("1. Output ONLY executable Python code inside a single ```python code block. No explanations outside the block.\n");

        if (hasImportedModel && isVehicleIntent) {
            sysInstBuilder.append("2. CRITICAL - USER IMPORTED CAR MODEL DETECTED:\n");
            sysInstBuilder.append("   - The runner normalizes uploaded models as 'inputs/input_model.glb'.\n");
            sysInstBuilder.append("   - DO NOT GENERATE MESH PRIMITIVES (CUBES, CYLINDERS, SPHERES) FOR THE CAR BODY. The geometry already exists!\n");
            sysInstBuilder.append("   - STRICT SPATIAL & ANIMATION RULES TO IMPLEMENT IN SCRIPT:\n");
            sysInstBuilder.append("     RULE 1: CAR SCALE NORMALIZATION:\n");
            sysInstBuilder.append("       Import the car file:\n");
            sysInstBuilder.append("       import os, bpy, mathutils\n");
            sysInstBuilder.append("       _before = set(bpy.data.objects)\n");
            sysInstBuilder.append("       if os.path.exists('inputs/input_model.glb'):\n");
            sysInstBuilder.append("           bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')\n");
            sysInstBuilder.append("       elif os.path.exists('input_model.glb'):\n");
            sysInstBuilder.append("           bpy.ops.import_scene.gltf(filepath='input_model.glb')\n");
            sysInstBuilder.append("       elif os.path.exists('inputs/input_model").append(modelExt).append("'):\n");
            if (".fbx".equals(modelExt)) {
                sysInstBuilder.append("           try:\n");
                sysInstBuilder.append("               bpy.ops.import_scene.fbx(filepath='inputs/input_model.fbx')\n");
                sysInstBuilder.append("           except Exception as fe:\n");
                sysInstBuilder.append("               print(f'FBX import note: {fe}')\n");
            } else if (".obj".equals(modelExt)) {
                sysInstBuilder.append("           bpy.ops.wm.obj_import(filepath='inputs/input_model.obj')\n");
            } else {
                sysInstBuilder.append("           bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')\n");
            }
            sysInstBuilder.append("       imported_objs = [o for o in bpy.data.objects if o not in _before and o.type == 'MESH']\n");
            sysInstBuilder.append("       Compute aggregate bounding box across all imported car meshes.\n");
            sysInstBuilder.append("       Scale the entire car assembly down so its total length is exactly real-world automotive size: 4.5 meters.\n");
            sysInstBuilder.append("     RULE 2: ROAD SCALE & ALIGNMENT:\n");
            sysInstBuilder.append("       Build a realistic multi-lane asphalt highway plane that matches the 4.5m car.\n");
            sysInstBuilder.append("       The road must be 14 meters wide (X axis) and at least 250 meters long (Y axis).\n");
            sysInstBuilder.append("       It must lie flat on the ground at Z = 0.0, running straight along the Y-axis with rotation (0, 0, 0).\n");
            sysInstBuilder.append("       Add center lane dashes at Z = 0.005 with normal pointing straight UP (0, 0, 1).\n");
            sysInstBuilder.append("     RULE 3: PLACING CAR ON ROAD:\n");
            sysInstBuilder.append("       Center the car in the driving lane at X = 0.0.\n");
            sysInstBuilder.append("       Snap the bottom-most point of the tires to sit flush on top of the road surface at Z = 0.0.\n");
            sysInstBuilder.append("       Start the car near the beginning of the road at Y = 5.0.\n");
            sysInstBuilder.append("     RULE 4: PARENTING & DRIVING ANIMATION:\n");
            sysInstBuilder.append("       Create a master Empty object at the car's base named 'Model_Root'.\n");
            sysInstBuilder.append("       Parent all imported car sub-meshes to this 'Model_Root' (keeping their relative assembly offsets intact).\n");
            sysInstBuilder.append("       Animate 'Model_Root' driving forward along the road (Y-axis) from Y = 5.0 at frame 1 to Y = 80.0 at frame 60 using location keyframes.\n");
            sysInstBuilder.append("       Find all wheel/tire meshes and animate them spinning around their axles proportional to the driving speed.\n");
            sysInstBuilder.append("     RULE 5: CINEMATIC CAMERA:\n");
            sysInstBuilder.append("       Place a camera tracking the car from a low, dramatic, three-quarter front angle.\n");
            sysInstBuilder.append("       Keyframe the camera moving with the car down the highway to create a high-speed cinematic sequence.\n");
        } else if (hasImportedModel) {
            sysInstBuilder.append("2. CRITICAL - USER IMPORTED 3D ASSET DETECTED:\n");
            sysInstBuilder.append("   - Import model from 'inputs/input_model.glb' (or .obj/.fbx). Do not replace it with cubes.\n");
            sysInstBuilder.append("   - Inspect dimensions, ground contact to Z=0.0, and construct lighting/cameras matching the asset type.\n");
        } else {
            sysInstBuilder.append("2. Construct real, detailed, multi-part 3D geometry matching the user's prompt (e.g., body, sub-parts, trim, walls, terrain, character anatomy).\n");
            sysInstBuilder.append("   NEVER generate a generic single cube, bevelled box, or placeholder. Build authentic multi-component structures.\n");
        }

        sysInstBuilder.append("3. DYNAMIC SPATIAL PLACEMENT: Inspect the primary subject's dimensions. Align its contact base naturally with the ground level (Z = 0). Never allow subjects to clip or submerge into the ground surface.\n");
        sysInstBuilder.append("4. NO MESH VOLUMETRIC CUBES: NEVER create polygonal mesh boxes or cubes (`primitive_cube_add`) for fog or volumetrics. Volumetric effects must strictly use world shader nodes (`ShaderNodeVolumePrincipled`) connected to `World Output`.\n");
        sysInstBuilder.append("5. Create Principled BSDF materials using Blender 4.2+ socket names: 'Transmission Weight', 'Roughness', 'Metallic', 'Base Color'.\n");
        sysInstBuilder.append("   In ShaderNodeBackground, the output socket is named 'Background' (bg.outputs['Background']), NEVER 'Color'.\n");
        sysInstBuilder.append("6. CRITICAL COLOR MANAGEMENT: In Blender 4.2, default view transform is 'AgX'. Valid looks are: 'AgX - High Contrast', 'AgX - Punchy', 'AgX - Base Contrast', 'None'.\n");
        sysInstBuilder.append("   NEVER set `scene.view_settings.look = 'High Contrast'`. ALWAYS write: `scene.view_settings.look = 'AgX - High Contrast'`.\n");
        sysInstBuilder.append("   NEVER assign `scene.sequencer_colorspace_settings` (it is read-only). Set exposure via `scene.view_settings.exposure`, NEVER `scene.exposure`.\n");
        sysInstBuilder.append("7. ANIMATION CONDITIONAL RULE: Only configure frame ranges (`frame_start = 1`, `frame_end = 60`) if animation or motion was explicitly requested. For static models, leave as a single frame.\n");
        sysInstBuilder.append("8. NEVER output unquoted f-strings like `fName_{i}`. All f-strings MUST have double quotes: `f\"Name_{i}\"`.\n");
        sysInstBuilder.append("9. Use correct standard Blender mesh operators: `bpy.ops.mesh.primitive_cube_add`, `bpy.ops.mesh.primitive_plane_add`, `bpy.ops.mesh.primitive_cylinder_add`.\n");
        sysInstBuilder.append("10. Lighting & Camera operators: ALWAYS use `bpy.ops.object.light_add(type='SUN'|'POINT'|'SPOT'|'AREA', location=...)` and `bpy.ops.object.camera_add(location=...)`.\n");
        sysInstBuilder.append("11. Do not include GUI/context-dependent operators that fail in headless mode.\n");
        sysInstBuilder.append("12. Organize objects cleanly with descriptive names and parent them logically.\n");
        sysInstBuilder.append("13. HEADLESS RUNNER CPU MANDATE: Never call get_devices(). Always use: `bpy.context.scene.cycles.device = 'CPU'`.\n");
        sysInstBuilder.append("14. SHADER NODES CREATION RULE: ALWAYS use: `node = nodes.new(type='ShaderNodeOutputMaterial')` and set its location via `node.location = (x, y)`.");

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("USER PROMPT: ").append(userPrompt).append("\n");
        promptBuilder.append("STYLE: ").append(style).append("\n");
        if (hasImportedModel && isVehicleIntent) {
            promptBuilder.append("IMPORTED 3D ASSET STATUS: A user 3D car model is normalized as 'inputs/input_model.glb'. ")
                         .append("Import it, normalize assembly scale to exactly 4.5m length, build a 14m x 250m highway at Z=0 with center lane dashes at Z=0.005, ")
                         .append("snap tire bottoms flush to Z=0 at X=0 starting at Y=5.0, parent all sub-meshes to 'Model_Root', ")
                         .append("animate 'Model_Root' driving from Y=5.0 (frame 1) to Y=80.0 (frame 60) with spinning wheels, ")
                         .append("and rig a low 3/4 front tracking camera moving with the car down the highway. Do not replace the model with cubes!\n");
        }
        promptBuilder.append("DIRECTOR SPECIFICATION:\n");
        promptBuilder.append("- Scene Type: ").append(directorSpec.getSceneType()).append("\n");
        promptBuilder.append("- Mood: ").append(directorSpec.getMood()).append("\n");
        promptBuilder.append("- Primary Color Hex: ").append(directorSpec.getPrimaryColorHex()).append("\n");
        promptBuilder.append("- Secondary Color Hex: ").append(directorSpec.getSecondaryColorHex()).append("\n");
        promptBuilder.append("- Camera Focal Length: ").append(directorSpec.getFocalLengthMm()).append("mm\n");
        promptBuilder.append("- Sun Intensity: ").append(directorSpec.getSunIntensity()).append("\n");
        promptBuilder.append("- Volumetric Fog: ").append(directorSpec.isUseVolumetrics()).append("\n");
        promptBuilder.append("- Hero Seed: ").append(directorSpec.getSeedHero()).append("\n");
        promptBuilder.append("- Environment Seed: ").append(directorSpec.getSeedVegetation()).append("\n\n");
        promptBuilder.append("Now generate the complete Blender Python script to direct and produce this asset scene.");

        apiClient.generateContent(
                apiKeyManager.getApiKey(),
                apiKeyManager.getSelectedModel(),
                sysInstBuilder.toString(),
                promptBuilder.toString(),
                new GeminiApiClient.ApiCallback<String>() {
                    @Override
                    public void onSuccess(String result) {
                        String cleaned = cleanPythonCode(result);
                        if (cleaned.isEmpty()) {
                            callback.onError("Gemini Script Writer returned empty code.");
                        } else {
                            VynaraLogger.system("AIOrchestrator: Phase 2 Complete. Dynamic Python script synthesized (" + cleaned.length() + " chars).");
                            callback.onSuccess(cleaned);
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        VynaraLogger.e("AIOrchestrator: Phase 2 Script Writer failed: " + errorMessage);
                        callback.onError("AI Script Writer Error: " + errorMessage);
                    }
                }
        );
    }

    /**
     * Phase 3: Wraps Gemini's dynamic modeling script with headless scene initialization,
     * contextual environment, CPU-safe Cycles settings, AgX color management, and GLB export.
     * Vehicle highway, scale normalization, and driving rigs are strictly conditioned on vehicle scenes.
     */
    private String wrapDynamicScriptWithSafety(String dynamicCode, AIDirectorSpec spec, String importedModelPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ==========================================\n");
        sb.append("# Vynara Autonomous 3D Studio - Dynamic AI Build\n");
        sb.append("# Scene Type: ").append(spec != null ? spec.getSceneType() : "Custom").append("\n");
        sb.append("# ==========================================\n\n");
        sb.append("import bpy\n");
        sb.append("import os\n");
        sb.append("import math\n");
        sb.append("import random\n");
        sb.append("import sys\n");
        sb.append("import mathutils\n");
        sb.append("import addon_utils\n\n");
        sb.append("try:\n");
        sb.append("    addon_utils.enable('archimesh')\n");
        sb.append("    addon_utils.enable('rigify')\n");
        sb.append("except Exception as e:\n");
        sb.append("    print(f'Addon activation note: {e}')\n\n");
        sb.append("os.makedirs('output', exist_ok=True)\n\n");

        sb.append("# Reset scene completely\n");
        sb.append("bpy.ops.object.select_all(action='SELECT')\n");
        sb.append("bpy.ops.object.delete(use_global=False)\n\n");

        sb.append("# Enforce Headless CPU Cycles Device\n");
        sb.append("try:\n");
        sb.append("    bpy.context.scene.render.engine = 'CYCLES'\n");
        sb.append("    bpy.context.scene.cycles.device = 'CPU'\n");
        sb.append("except Exception: pass\n\n");

        sb.append("# --- DYNAMIC AI MESH & SCENE GENERATION ---\n");
        sb.append(dynamicCode).append("\n\n");

        // Determine if this scene is actually a vehicle scene
        String sceneType = (spec != null && spec.getSceneType() != null) ? spec.getSceneType().toLowerCase(Locale.US) : "";
        boolean isVehicleScene = (importedModelPath != null && !importedModelPath.isEmpty())
                || sceneType.contains("vehicle") || sceneType.contains("car") || sceneType.contains("drive") || sceneType.contains("r8");

        if (isVehicleScene) {
            sb.append("# =========================================================\n");
            sb.append("# VEHICLE SCENE POST-PROCESS: 4.5m SCALE, ROAD & DRIVING RIG\n");
            sb.append("# =========================================================\n");
            sb.append("try:\n");
            sb.append("    bpy.context.scene.frame_set(1)\n");
            sb.append("    _env_keys = ['road', 'highway', 'asphalt', 'ground', 'stripe', 'lane', 'marking', 'dash', 'guardrail', 'barrier', 'curb', 'sidewalk', 'terrain', 'plane', 'sky', 'light', 'lamp', 'camera']\n");
            sb.append("    _car_meshes = [o for o in bpy.data.objects if o.type == 'MESH' and not any(k in o.name.lower() for k in _env_keys)]\n\n");

            sb.append("    # 1. NON-DESTRUCTIVE ASSEMBLY-LEVEL SCALING & GROUND SNAP\n");
            sb.append("    if _car_meshes:\n");
            sb.append("        _min_x, _min_y, _min_z = float('inf'), float('inf'), float('inf')\n");
            sb.append("        _max_x, _max_y, _max_z = float('-inf'), float('-inf'), float('-inf')\n");
            sb.append("        for _m in _car_meshes:\n");
            sb.append("            for _corner in _m.bound_box:\n");
            sb.append("                _w = _m.matrix_world @ mathutils.Vector(_corner)\n");
            sb.append("                _min_x = min(_min_x, _w.x); _max_x = max(_max_x, _w.x)\n");
            sb.append("                _min_y = min(_min_y, _w.y); _max_y = max(_max_y, _w.y)\n");
            sb.append("                _min_z = min(_min_z, _w.z); _max_z = max(_max_z, _w.z)\n\n");

            sb.append("        _dim_x = _max_x - _min_x\n");
            sb.append("        _dim_y = _max_y - _min_y\n");
            sb.append("        _car_length = max(_dim_x, _dim_y)\n");
            sb.append("        _center_x = (_min_x + _max_x) * 0.5\n");
            sb.append("        _center_y = (_min_y + _max_y) * 0.5\n");
            sb.append("        _bottom_z = _min_z\n\n");

            sb.append("        # Parent all sub-meshes to Model_Root without destroying relative child offsets\n");
            sb.append("        _root = bpy.data.objects.get('Model_Root')\n");
            sb.append("        if not _root:\n");
            sb.append("            _root = bpy.data.objects.new('Model_Root', None)\n");
            sb.append("            _root.empty_display_type = 'PLAIN_AXES'\n");
            sb.append("            bpy.context.collection.objects.link(_root)\n");
            sb.append("        _root.animation_data_clear()\n");
            sb.append("        _root.location = (_center_x, _center_y, _bottom_z)\n");
            sb.append("        _root.rotation_euler = (0.0, 0.0, 0.0)\n");
            sb.append("        _root.scale = (1.0, 1.0, 1.0)\n");
            sb.append("        bpy.context.view_layer.update()\n");
            sb.append("        for _m in _car_meshes:\n");
            sb.append("            if not _m.parent:\n");
            sb.append("                _m.parent = _root\n");
            sb.append("                _m.matrix_parent_inverse = _root.matrix_world.inverted()\n\n");

            sb.append("        # Scale assembly rigidly to 4.5m automotive size\n");
            sb.append("        if _car_length > 0.001:\n");
            sb.append("            _ratio = 4.5 / _car_length\n");
            sb.append("            _root.scale = (_ratio, _ratio, _ratio)\n");
            sb.append("            print(f'Vynara: Scaled vehicle Model_Root to 4.5m length (ratio: {_ratio:.6f})')\n\n");

            sb.append("        # Center car in driving lane at X=0, flush at Z=0, start at Y=5.0\n");
            sb.append("        bpy.context.scene.frame_start = 1\n");
            sb.append("        bpy.context.scene.frame_end = 60\n");
            sb.append("        _root.location = (0.0, 5.0, 0.0)\n");
            sb.append("        _root.keyframe_insert(data_path='location', frame=1)\n");
            sb.append("        _root.location = (0.0, 80.0, 0.0)\n");
            sb.append("        _root.keyframe_insert(data_path='location', frame=60)\n");
            sb.append("        if _root.animation_data and _root.animation_data.action:\n");
            sb.append("            for _fc in _root.animation_data.action.fcurves:\n");
            sb.append("                for _kp in _fc.keyframe_points: _kp.interpolation = 'LINEAR'\n\n");

            sb.append("        # Animate wheel spin around axle (distance = 75m, radius = 0.35m -> -214.28 rad)\n");
            sb.append("        _wheel_keys = ['wheel', 'tire', 'rim', 'tyre', 'disc']\n");
            sb.append("        _wheel_objs = [m for m in _car_meshes if any(wk in m.name.lower() for wk in _wheel_keys)]\n");
            sb.append("        for _w_obj in _wheel_objs:\n");
            sb.append("            _w_obj.rotation_mode = 'XYZ'\n");
            sb.append("            _w_obj.animation_data_clear()\n");
            sb.append("            _w_obj.rotation_euler.x = 0.0\n");
            sb.append("            _w_obj.keyframe_insert(data_path='rotation_euler', frame=1)\n");
            sb.append("            _w_obj.rotation_euler.x = -214.28\n");
            sb.append("            _w_obj.keyframe_insert(data_path='rotation_euler', frame=60)\n");
            sb.append("            if _w_obj.animation_data and _w_obj.animation_data.action:\n");
            sb.append("                for _fc in _w_obj.animation_data.action.fcurves:\n");
            sb.append("                    for _kp in _fc.keyframe_points: _kp.interpolation = 'LINEAR'\n\n");

            sb.append("    # 2. ROAD SCALE & ALIGNMENT (14m wide x 250m long, Z=0.0, rotation (0,0,0))\n");
            sb.append("    _road_obj = None\n");
            sb.append("    for _o in list(bpy.data.objects):\n");
            sb.append("        if _o.type == 'MESH' and any(_k in _o.name.lower() for _k in ['road', 'highway', 'asphalt']):\n");
            sb.append("            _road_obj = _o; break\n");
            sb.append("    if not _road_obj:\n");
            sb.append("        bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0.0, 125.0, 0.0))\n");
            sb.append("        _road_obj = bpy.context.active_object\n");
            sb.append("        _road_obj.name = 'Highway_Road'\n");
            sb.append("    _road_obj.location = (0.0, 125.0, 0.0)\n");
            sb.append("    _road_obj.rotation_euler = (0.0, 0.0, 0.0)\n");
            sb.append("    _road_obj.dimensions = (14.0, 250.0, 0.0)\n");
            sb.append("    bpy.context.view_layer.objects.active = _road_obj\n");
            sb.append("    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)\n\n");

            sb.append("    # Center lane dashes at Z = 0.005 with normal pointing straight UP (0, 0, 1)\n");
            sb.append("    for _o in list(bpy.data.objects):\n");
            sb.append("        if _o.type == 'MESH' and any(_k in _o.name.lower() for _k in ['stripe', 'lane', 'marking', 'dash']):\n");
            sb.append("            bpy.data.objects.remove(_o, do_unlink=True)\n");
            sb.append("    _dash_mat = bpy.data.materials.new(name='Lane_Marking_Mat')\n");
            sb.append("    _dash_mat.use_nodes = True\n");
            sb.append("    _dash_bsdf = _dash_mat.node_tree.nodes.get('Principled BSDF')\n");
            sb.append("    if _dash_bsdf:\n");
            sb.append("        _dash_bsdf.inputs['Base Color'].default_value = (1.0, 1.0, 1.0, 1.0)\n");
            sb.append("        _dash_bsdf.inputs['Roughness'].default_value = 0.2\n");
            sb.append("    for _y_idx in range(1, 40):\n");
            sb.append("        _dash_y = _y_idx * 6.0\n");
            sb.append("        if _dash_y > 245.0: break\n");
            sb.append("        bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0.0, _dash_y, 0.005))\n");
            sb.append("        _dash = bpy.context.active_object\n");
            sb.append("        _dash.name = f'Lane_Dash_{_y_idx}'\n");
            sb.append("        _dash.rotation_euler = (0.0, 0.0, 0.0)\n");
            sb.append("        _dash.dimensions = (0.2, 3.0, 0.0)\n");
            sb.append("        if _dash.data.materials:\n");
            sb.append("            _dash.data.materials[0] = _dash_mat\n");
            sb.append("        else:\n");
            sb.append("            _dash.data.materials.append(_dash_mat)\n\n");

            sb.append("    # 5. CINEMATIC CAMERA: Low dramatic 3/4 front angle tracking camera moving with car\n");
            sb.append("    _cam_obj = bpy.context.scene.camera\n");
            sb.append("    if not _cam_obj:\n");
            sb.append("        _cam_data = bpy.data.cameras.new('CinematicCamera')\n");
            sb.append("        _cam_obj = bpy.data.objects.new('CinematicCamera', _cam_data)\n");
            sb.append("        bpy.context.collection.objects.link(_cam_obj)\n");
            sb.append("        bpy.context.scene.camera = _cam_obj\n");
            sb.append("    _cam_obj.data.lens = 35.0\n");
            sb.append("    _cam_obj.data.clip_end = 500.0\n");
            sb.append("    _cam_obj.constraints.clear()\n");
            sb.append("    _cam_obj.animation_data_clear()\n");
            sb.append("    _tt = _cam_obj.constraints.new(type='TRACK_TO')\n");
            sb.append("    _tt.target = _root\n");
            sb.append("    _tt.track_axis = 'TRACK_NEGATIVE_Z'\n");
            sb.append("    _tt.up_axis = 'UP_Y'\n");
            sb.append("    _cam_obj.location = (-2.8, 10.5, 0.95)\n");
            sb.append("    _cam_obj.keyframe_insert(data_path='location', frame=1)\n");
            sb.append("    _cam_obj.location = (-2.8, 85.5, 0.95)\n");
            sb.append("    _cam_obj.keyframe_insert(data_path='location', frame=60)\n");
            sb.append("    if _cam_obj.animation_data and _cam_obj.animation_data.action:\n");
            sb.append("        for _fc in _cam_obj.animation_data.action.fcurves:\n");
            sb.append("            for _kp in _fc.keyframe_points: _kp.interpolation = 'LINEAR'\n");
            sb.append("except Exception as _post_err: print(f'Vehicle post-process note: {_post_err}')\n\n");
        } else {
            // General / Architecture / Props grounding and camera framing
            sb.append("# --- GENERAL SCENE GROUNDING & CAMERA FRAMING ---\n");
            sb.append("try:\n");
            sb.append("    _all_meshes = [o for o in bpy.data.objects if o.type == 'MESH']\n");
            sb.append("    if _all_meshes:\n");
            sb.append("        _lowest_z = min([(_m.matrix_world @ mathutils.Vector(c)).z for _m in _all_meshes for c in _m.bound_box])\n");
            sb.append("        if _lowest_z < -0.01 or _lowest_z > 0.05:\n");
            sb.append("            for _m in _all_meshes:\n");
            sb.append("                if not _m.parent:\n");
            sb.append("                    _m.location.z -= _lowest_z\n");
            sb.append("except Exception as _g_err: print(f'Ground alignment note: {_g_err}')\n\n");

            float focalLength = (spec != null && spec.getFocalLengthMm() > 0) ? spec.getFocalLengthMm() : 50.0f;
            float[] camPos = (spec != null && spec.getCameraPosition() != null && spec.getCameraPosition().length >= 3)
                    ? spec.getCameraPosition() : new float[]{0.0f, -8.0f, 3.5f};

            sb.append("try:\n");
            sb.append("    if not bpy.context.scene.camera:\n");
            sb.append("        cam_data = bpy.data.cameras.new('CinematicCamera')\n");
            sb.append("        cam_data.lens = ").append(focalLength).append("\n");
            sb.append("        cam_obj = bpy.data.objects.new('Camera', cam_data)\n");
            sb.append("        bpy.context.collection.objects.link(cam_obj)\n");
            sb.append("        bpy.context.scene.camera = cam_obj\n");
            sb.append("        cam_obj.location = (").append(camPos[0]).append(", ").append(camPos[1]).append(", ").append(camPos[2]).append(")\n");
            sb.append("        cam_obj.rotation_euler = (math.radians(72), 0, 0)\n");
            sb.append("except Exception as ce: print(f'Camera setup note: {ce}')\n\n");
        }

        sb.append("# --- CINEMATIC LIGHTING ---\n");
        float sunIntensity = (spec != null && spec.getSunIntensity() > 0) ? spec.getSunIntensity() : 4.5f;

        sb.append("try:\n");
        sb.append("    sun_data = bpy.data.lights.new('Sun', type='SUN')\n");
        sb.append("    sun_data.energy = ").append(sunIntensity).append("\n");
        sb.append("    sun_obj = bpy.data.objects.new('SunLight', sun_data)\n");
        sb.append("    bpy.context.collection.objects.link(sun_obj)\n");
        sb.append("    sun_obj.rotation_euler = (math.radians(45), math.radians(15), math.radians(-30))\n");
        sb.append("except Exception as le: print(f'Lighting setup note: {le}')\n\n");

        sb.append("# --- COLOR MANAGEMENT (BLENDER 4.2+ AGX) ---\n");
        sb.append("try:\n");
        sb.append("    bpy.context.scene.view_settings.view_transform = 'AgX'\n");
        sb.append("    bpy.context.scene.view_settings.look = 'AgX - High Contrast'\n");
        sb.append("except Exception as ve: print(f'Color management note: {ve}')\n\n");

        if (spec != null && spec.isUseVolumetrics()) {
            sb.append("try:\n");
            sb.append("    world = bpy.context.scene.world\n");
            sb.append("    if world is None:\n");
            sb.append("        world = bpy.data.worlds.new('World')\n");
            sb.append("        bpy.context.scene.world = world\n");
            sb.append("    world.use_nodes = True\n");
            sb.append("    wnodes = world.node_tree.nodes\n");
            sb.append("    wlinks = world.node_tree.links\n");
            sb.append("    vol_node = wnodes.new('ShaderNodeVolumePrincipled')\n");
            sb.append("    vol_node.inputs['Density'].default_value = ").append(spec.getVolumetricDensity()).append("\n");
            sb.append("    w_output = wnodes.get('World Output')\n");
            sb.append("    if w_output:\n");
            sb.append("        wlinks.new(vol_node.outputs['Volume'], w_output.inputs['Volume'])\n");
            sb.append("except Exception as ve: print(f'Volumetric setup note: {ve}')\n\n");
        }

        sb.append("# --- STEP 1: EXPORT ANIMATION-SAFE CLEAN 3D GLB ---\n");
        sb.append("try:\n");
        sb.append("    for _obj in list(bpy.data.objects):\n");
        sb.append("        _n = _obj.name.lower()\n");
        sb.append("        if ('fog_domain' in _n or 'volume_box' in _n or 'atmosphere_domain' in _n) and _obj.type == 'MESH':\n");
        sb.append("            bpy.data.objects.remove(_obj, do_unlink=True)\n");
        sb.append("    bpy.ops.export_scene.gltf(filepath='output/model.glb', export_format='GLB', export_apply=False, export_skins=True, export_animations=True)\n");
        sb.append("    print('3D GLTF Export Successful: output/model.glb')\n");
        sb.append("except Exception as ge: print(f'GLTF export warning: {ge}')\n\n");

        sb.append("# --- STEP 2: HEADLESS-SAFE CPU CYCLES PREVIEW RENDER ---\n");
        sb.append("try:\n");
        sb.append("    if bpy.context.scene.camera:\n");
        sb.append("        bpy.context.scene.render.engine = 'CYCLES'\n");
        sb.append("        bpy.context.scene.cycles.device = 'CPU'\n");
        sb.append("        bpy.context.scene.cycles.samples = 16\n");
        sb.append("        bpy.context.scene.render.resolution_x = 1280\n");
        sb.append("        bpy.context.scene.render.resolution_y = 720\n");
        sb.append("        bpy.context.scene.render.filepath = 'output/render.png'\n");
        sb.append("        bpy.ops.render.render(write_still=True)\n");
        sb.append("        print('Cycles preview render complete: output/render.png')\n");
        sb.append("except Exception as re: print(f'Preview render note: {re}')\n");

        return sb.toString();
    }

    private String cleanPythonCode(String raw) {
        if (raw == null) return "";
        String code = raw.trim();
        if (code.startsWith("```python")) {
            code = code.substring(9);
        } else if (code.startsWith("```")) {
            code = code.substring(3);
        }
        if (code.endsWith("```")) {
            code = code.substring(0, code.length() - 3);
        }
        code = code.trim();

        // 1. Neutralize headless cycles get_devices() NoneType crash and force CPU
        code = code.replaceAll("(?m)^[ \\t]*.*cycles.*(?:device\\s*=\\s*['\"]GPU['\"]|get_devices\\(\\)).*$", "try:\n    bpy.context.scene.cycles.device = 'CPU'\nexcept Exception: pass");
        code = code.replace("get_devices()", "(bpy.context.preferences.addons['cycles'].preferences.get_devices() or [])");

        // 2. Auto-sanitize unquoted f-strings: e.g., fPool_LED_{i} -> f"Pool_LED_{i}"
        code = code.replaceAll("(?<=[=\\s,(])f([a-zA-Z0-9_]+\\{[^}\"\\n]+\\}[a-zA-Z0-9_]*)", "f\"$1\"");

        // 3. Auto-sanitize hallucinated combined object.mesh operator calls
        code = code.replace("bpy.ops.object.mesh.", "bpy.ops.mesh.");

        // 4. Auto-sanitize hallucinated lighting and camera operators
        code = code.replace("bpy.ops.light.add(", "bpy.ops.object.light_add(");
        code = code.replace("bpy.ops.camera.add(", "bpy.ops.object.camera_add(");
        code = code.replace(".primitive_cube_create(", ".primitive_cube_add(");
        code = code.replace(".primitive_plane_create(", ".primitive_plane_add(");
        code = code.replace(".primitive_cylinder_create(", ".primitive_cylinder_add(");
        code = code.replace(".primitive_cone_create(", ".primitive_cone_add(");
        code = code.replace(".primitive_uv_sphere_create(", ".primitive_uv_sphere_add(");

        // 5. Auto-sanitize Blender 4.2+ Principled BSDF socket changes
        code = code.replace("['Transmission'].default_value", "['Transmission Weight'].default_value");
        code = code.replace("['Subsurface'].default_value", "['Subsurface Weight'].default_value");
        code = code.replace("['Specular'].default_value", "['Specular IOR Level'].default_value");
        code = code.replaceAll("inputs\\[['\"]Transmission['\"]\\]", "inputs['Transmission Weight']");
        code = code.replaceAll("inputs\\[['\"]Subsurface['\"]\\]", "inputs['Subsurface Weight']");
        code = code.replaceAll("inputs\\[['\"]Specular['\"]\\]", "inputs['Specular IOR Level']");

        // 6. Auto-sanitize Background shader node output socket ('Color' -> 'Background')
        code = code.replaceAll("(\\.outputs\\[['\"])Color(['\"]\\]\\s*,\\s*[^,\\n]*?inputs\\[['\"])Surface(['\"]\\])", "$1Background$2Surface$3");
        code = code.replaceAll("(bg(?:_node)?\\.outputs\\[['\"])Color(['\"]\\])", "$1Background$2");

        // 7. Auto-sanitize scene exposure path: scene.exposure -> scene.view_settings.exposure
        code = code.replaceAll("(\\bscene)\\.exposure\\b", "$1.view_settings.exposure");

        // 8. Auto-sanitize read-only sequencer_colorspace_settings assignment
        code = code.replaceAll("(?m)^[ \\t]*.*?\\.sequencer_colorspace_settings\\s*=.*$", "pass");

        // 9. Auto-sanitize Blender 4.2+ AgX color look enums
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]High Contrast['\"]", "view_settings.look = 'AgX - High Contrast'");
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]Very High Contrast['\"]", "view_settings.look = 'AgX - Very High Contrast'");
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]Medium High Contrast['\"]", "view_settings.look = 'AgX - Medium High Contrast'");
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]Medium Low Contrast['\"]", "view_settings.look = 'AgX - Medium Low Contrast'");
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]Low Contrast['\"]", "view_settings.look = 'AgX - Low Contrast'");
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]Very Low Contrast['\"]", "view_settings.look = 'AgX - Very Low Contrast'");
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]Base Contrast['\"]", "view_settings.look = 'AgX - Base Contrast'");
        code = code.replaceAll("view_settings\\.look\\s*=\\s*['\"]Punchy['\"]", "view_settings.look = 'AgX - Punchy'");

        // 10. Auto-sanitize hallucinated object.keyframe_[xyz] axis assignments (e.g. stripe.keyframe_y = -100.0)
        code = code.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([xX])\\s*=\\s*([^\\n;]+)", "$1.location.x = $3; $1.keyframe_insert(data_path='location', index=0)");
        code = code.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([yY])\\s*=\\s*([^\\n;]+)", "$1.location.y = $3; $1.keyframe_insert(data_path='location', index=1)");
        code = code.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_([zZ])\\s*=\\s*([^\\n;]+)", "$1.location.z = $3; $1.keyframe_insert(data_path='location', index=2)");
        code = code.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_location\\s*=\\s*([^\\n;]+)", "$1.location = $2; $1.keyframe_insert(data_path='location')");
        code = code.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_rotation\\s*=\\s*([^\\n;]+)", "$1.rotation_euler = $2; $1.keyframe_insert(data_path='rotation_euler')");
        code = code.replaceAll("(?m)([a-zA-Z0-9_]+)\\.keyframe_scale\\s*=\\s*([^\\n;]+)", "$1.scale = $2; $1.keyframe_insert(data_path='scale')");

        // 11. Auto-heal fog material variable name typo: f_mat -> fog_mat
        code = code.replace("f_mat.node_tree", "fog_mat.node_tree");

        // 12. Auto-sanitize hallucinated nodes.ShaderNode* constructor syntax
        code = code.replaceAll("(?m)([a-zA-Z0-9_]*nodes?)\\.(ShaderNode[A-Za-z0-9_]+)\\(\\s*location\\s*=\\s*(\\([^)]+\\))\\s*\\)", "$1.new(type='$2')");
        code = code.replaceAll("(?m)([a-zA-Z0-9_]*nodes?)\\.(ShaderNode[A-Za-z0-9_]+)\\(\\)", "$1.new(type='$2')");

        return code.trim();
    }

    private void executeStructuredGeminiPlanning(final AIProductionRequest request, 
                                                 final AIDirectorSpec directorSpec, 
                                                 final GeminiApiClient.ApiCallback<ProductionPlan> callback) {
        ToolRegistry registry = new ToolRegistry();
        StringBuilder toolManifestBuilder = new StringBuilder();
        toolManifestBuilder.append("AUTHORITATIVE REGISTERED COMMANDS:\n");
        for (ToolDefinition tool : registry.getRegisteredTools().values()) {
            if (tool.isAvailable()) {
                toolManifestBuilder.append("- Tool ID: \"").append(tool.getId()).append("\"\n");
                toolManifestBuilder.append("  Description: ").append(tool.getDescription()).append("\n");
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    toolManifestBuilder.append("  Accepted Parameters: ");
                    for (ToolParameter param : tool.getParameters()) {
                        toolManifestBuilder.append(param.getName()).append(" (").append(param.getType()).append("), ");
                    }
                    toolManifestBuilder.setLength(toolManifestBuilder.length() - 2);
                    toolManifestBuilder.append("\n");
                }
            }
        }

        List<KnowledgeEntry> knowledgeEntries = knowledgeManager.retrieveAllKnowledgeForPrompt(request.getUserPrompt());
        StringBuilder contextBuilder = new StringBuilder();
        if (!knowledgeEntries.isEmpty()) {
            contextBuilder.append("KNOWLEDGE BLUEPRINTS:\n");
            for (KnowledgeEntry entry : knowledgeEntries) {
                contextBuilder.append("- Domain: ").append(entry.getName()).append("\n");
                contextBuilder.append("  Components: ").append(entry.getComponents()).append("\n");
            }
        }

        CloudProvider activeProvider = apiKeyManager.getComputeProvider();
        String providerContext = "ACTIVE COMPUTE PIPELINE: " + activeProvider.getDisplayName() + "\n";

        String systemInstruction = "You are Vynara Autonomous 3D Technical Director.\n" +
                "KNOWLEDGE vs. TOOL vs. TASK CONTRACT:\n" +
                "- Tools are the ONLY executable operations. Execute ONLY registered tools.\n" +
                "- Ensure character mesh creation ALWAYS precedes skeleton binding and rigging.\n\n" +
                "DIRECTOR SCENE SPECIFICATION:\n" + directorSpec.toJson().toString() + "\n\n" +
                providerContext + "\n" +
                toolManifestBuilder.toString() + "\n\n" +
                "RETURN A STRICT JSON OBJECT REPRESENTING THE PRODUCTION PLAN.\n" +
                "REQUIRED JSON SCHEMA:\n" +
                "{\n" +
                "  \"intent\": \"string\",\n" +
                "  \"sceneType\": \"string\",\n" +
                "  \"quality\": \"string\",\n" +
                "  \"objects\": [ { \"name\": \"string\", \"components\": [\"string\"], \"dimensions\": {\"width\": 0.0, \"height\": 0.0, \"depth\": 0.0} } ],\n" +
                "  \"materials\": [ { \"name\": \"string\", \"colorHex\": \"#FFFFFF\", \"metallic\": 0.0, \"roughness\": 0.5, \"opacity\": 1.0 } ],\n" +
                "  \"lighting\": \"string\",\n" +
                "  \"camera\": \"string\",\n" +
                "  \"characters\": [ { \"species\": \"string\", \"riggingRequired\": true, \"animationRequired\": true } ],\n" +
                "  \"requiredTools\": [ { \"toolId\": \"string\", \"description\": \"string\", \"parameters\": {} } ],\n" +
                "  \"validationRules\": [ \"string\" ]\n" +
                "}";

        String promptWithContext = "USER PROMPT: " + request.getUserPrompt() +
                "\nSTYLE: " + request.getStyle() +
                "\nTARGET ENGINE: " + request.getTargetEngine() +
                "\n\n" + contextBuilder.toString();

        VynaraLogger.system("Asynchronously dispatching structured 3D plan request to Google Gemini API...");

        apiClient.generateStructuredJson(apiKeyManager.getApiKey(), apiKeyManager.getSelectedModel(), systemInstruction, promptWithContext, new GeminiApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String jsonResult) {
                try {
                    String cleanJson = jsonResult.trim();
                    if (cleanJson.startsWith("```json")) cleanJson = cleanJson.substring(7);
                    if (cleanJson.startsWith("```")) cleanJson = cleanJson.substring(3);
                    if (cleanJson.endsWith("```")) cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                    cleanJson = cleanJson.trim();

                    JSONObject root = new JSONObject(cleanJson);
                    AIProductionPlan structuredPlan = AIProductionPlan.fromJson(root);
                    ProductionPlan executablePlan = promptInterpreter.convertStructuredPlanToExecutablePlan(request, structuredPlan);
                    callback.onSuccess(executablePlan);
                } catch (Exception e) {
                    VynaraLogger.e("Plan compilation exception: " + e.getMessage(), e);
                    callback.onError("Failed to parse Gemini production plan: " + e.getMessage());
                }
            }

            @Override
            public void onError(String errorMessage) {
                VynaraLogger.e("Gemini API connection error: " + errorMessage);
                callback.onError("Gemini API connection error: " + errorMessage);
            }
        });
    }

    /**
     * Direct Blender Python script generation using Director + Dynamic AI Script Writer pipeline.
     */
    public void planBlenderProduction(final String prompt, final GeminiApiClient.ApiCallback<String> callback) {
        if (!apiKeyManager.hasApiKey()) {
            callback.onError("Gemini API key missing. Please configure it in Settings.");
            return;
        }

        directorAgent.formulateDirectorSpec(prompt, "Photorealistic", new ArrayList<>(), new DirectorAgent.DirectorCallback() {
            @Override
            public void onSpecReady(final AIDirectorSpec spec) {
                if (isDemoPreset(prompt)) {
                    ProductionPlan demoPlan = promptInterpreter.createProductionPlan(prompt, "Photorealistic", "Blender Native");
                    for (TaskNode node : demoPlan.getTaskGraph().getAllNodes()) {
                        if (node.getOperation() != null && "blender.cloud_generate".equalsIgnoreCase(node.getOperation().getToolId())) {
                            String script = node.getOperation().getStringParam("bpyScript", "");
                            if (script != null && !script.isEmpty()) {
                                callback.onSuccess(script);
                                return;
                            }
                        }
                    }
                }

                // Dynamic Generation for custom prompts
                dispatchDynamicScriptWriter(prompt, "Photorealistic", spec, null, new GeminiApiClient.ApiCallback<String>() {
                    @Override
                    public void onSuccess(String dynamicBpyCode) {
                        String wrappedScript = wrapDynamicScriptWithSafety(dynamicBpyCode, spec, null);
                        callback.onSuccess(wrappedScript);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        callback.onError(errorMessage);
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                callback.onError(errorMessage);
            }
        });
    }

    /**
     * Studio Assistant: Translates human requests into object updates OR dynamically spawns new objects.
     */
    public void processNaturalLanguageStudioEdit(String editPrompt, String activeSceneContextJson, final GeminiApiClient.ApiCallback<String> callback) {
        if (!apiKeyManager.hasApiKey()) {
            callback.onError("Gemini API Key missing. Please set it in Settings.");
            return;
        }

        String sysInst = "You are Vynara Studio Assistant. Interpret direct 3D requests on the active scene.\n" +
                "You can either EDIT an existing object OR CREATE a new object.\n" +
                "If the user asks to add/create something (e.g. 'add a green tree', 'add a chair', 'add light'):\n" +
                "Set \"action\": \"CREATE\", specify \"creationType\" (e.g. 'tree', 'chair', 'house', 'primitive_cube', 'primitive_sphere'), \"name\": \"string\", \"position\": { \"px\": 0.0, \"py\": 0.0, \"pz\": 0.0 }, and \"material\": { \"colorHex\": \"#FFFFFF\", \"metallic\": 0.0, \"roughness\": 0.5 }.\n" +
                "If the user asks to modify an existing object (e.g. 'make it wider', 'change color to blue', 'rotate 90 degrees'):\n" +
                "Set \"action\": \"EDIT\", \"targetObjectId\": \"string\", \"transform\": { \"px\": 0.0, \"py\": 0.0, \"pz\": 0.0, \"rx\": 0.0, \"ry\": 0.0, \"rz\": 0.0, \"sx\": 1.0, \"sy\": 1.0, \"sz\": 1.0 }, \"material\": { \"colorHex\": \"#FFFFFF\", \"metallic\": 0.0, \"roughness\": 0.5 }.\n" +
                "JSON FORMAT:\n" +
                "{\n" +
                "  \"action\": \"CREATE\" or \"EDIT\",\n" +
                "  \"targetObjectId\": \"string\",\n" +
                "  \"creationType\": \"string\",\n" +
                "  \"name\": \"string\",\n" +
                "  \"transform\": { \"px\": 0.0, \"py\": 0.0, \"pz\": 0.0, \"rx\": 0.0, \"ry\": 0.0, \"rz\": 0.0, \"sx\": 1.0, \"sy\": 1.0, \"sz\": 1.0 },\n" +
                "  \"material\": { \"colorHex\": \"#FFFFFF\", \"metallic\": 0.0, \"roughness\": 0.5, \"opacity\": 1.0 }\n" +
                "}";

        String fullPrompt = "SCENE CONTEXT:\n" + activeSceneContextJson + "\n\nUSER REQUEST: " + editPrompt;

        VynaraLogger.system("Asynchronously dispatching Studio Assistant request to Google Gemini API...");

        apiClient.generateStructuredJson(apiKeyManager.getApiKey(), apiKeyManager.getSelectedModel(), sysInst, fullPrompt, new GeminiApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleanJson = result.trim();
                if (cleanJson.startsWith("```json")) cleanJson = cleanJson.substring(7);
                if (cleanJson.startsWith("```")) cleanJson = cleanJson.substring(3);
                if (cleanJson.endsWith("```")) cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                callback.onSuccess(cleanJson.trim());
            }

            @Override
            public void onError(String errorMessage) {
                VynaraLogger.e("Studio Assistant connection error: " + errorMessage);
                callback.onError(errorMessage);
            }
        });
    }

    /**
     * AI Correction Loop: Consults Gemini to repair scene issues without guessing.
     */
    public void requestCorrectionPlan(String validationMessage, String validationCategory, String sceneContextJson, final GeminiApiClient.ApiCallback<String> callback) {
        if (!apiKeyManager.hasApiKey()) {
            callback.onError("API key missing. Cannot use AI for corrections.");
            return;
        }

        String sysInst = "You are Vynara AI Corrector. A validation error occurred in the 3D scene.\n" +
                "Review the Scene Context and Error Message. Determine the best repair strategy from the registered ToolRegistry.\n" +
                "Return a STRICT JSON object representing the repair tool operation:\n" +
                "{\n" +
                "  \"toolId\": \"string (e.g., geometry.create_primitive, material.set_properties, skeleton.bind)\",\n" +
                "  \"parameters\": { \"key\": \"value\" }\n" +
                "}";

        String prompt = "ERROR CATEGORY: " + validationCategory + "\n" +
                        "ERROR MESSAGE: " + validationMessage + "\n\n" +
                        "SCENE CONTEXT:\n" + sceneContextJson;

        VynaraLogger.system("Asynchronously dispatching AI Repair Request to Google Gemini API...");

        apiClient.generateStructuredJson(apiKeyManager.getApiKey(), apiKeyManager.getSelectedModel(), sysInst, prompt, new GeminiApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                String cleanJson = result.trim();
                if (cleanJson.startsWith("```json")) cleanJson = cleanJson.substring(7);
                if (cleanJson.startsWith("```")) cleanJson = cleanJson.substring(3);
                if (cleanJson.endsWith("```")) cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                callback.onSuccess(cleanJson.trim());
            }

            @Override
            public void onError(String errorMessage) {
                VynaraLogger.e("AI Repair error: " + errorMessage);
                callback.onError(errorMessage);
            }
        });
    }

    public GeminiApiClient getApiClient() { return apiClient; }
    public ApiKeyManager getApiKeyManager() { return apiKeyManager; }
    public KnowledgeManager getKnowledgeManager() { return knowledgeManager; }
    public PromptInterpreter getPromptInterpreter() { return promptInterpreter; }
    public DirectorAgent getDirectorAgent() { return directorAgent; }
}