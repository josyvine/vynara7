package com.example.ai.agents;

import com.example.ai.protocol.AIDirectorSpec;
import com.example.utils.VynaraLogger;

public class BlenderWorkerAgent {

    private static volatile String sLastMasterScript = null;
    private static volatile String sLastUserPrompt = null;
    private static volatile String sLastAssetId = null;
    private static volatile AIDirectorSpec sLastDirectorSpec = null;

    public static class WorkerScripts {
        public final String heroScript;
        public final String environmentScript;
        public final String lightingAndRenderScript;
        public final String compositeMasterScript;

        // Dynamic 4-Worker Aliases
        public final String structureScript;
        public final String detailsScript;
        public final String materialsScript;
        public final String cinematicsScript;

        public WorkerScripts(String hero, String env, String light, String master) {
            this.heroScript = hero;
            this.environmentScript = env;
            this.lightingAndRenderScript = light;
            this.compositeMasterScript = master;

            this.structureScript = hero;
            this.detailsScript = env;
            this.materialsScript = "";
            this.cinematicsScript = light;
        }

        public WorkerScripts(String structure, String details, String materials, String cinematics, String master) {
            this.heroScript = structure;
            this.environmentScript = details;
            this.lightingAndRenderScript = cinematics;
            this.compositeMasterScript = master;

            this.structureScript = structure;
            this.detailsScript = details;
            this.materialsScript = materials;
            this.cinematicsScript = cinematics;
        }
    }

    /**
     * Wraps Gemini's dynamic Python script with headless scene initialization,
     * contextual environment, cinematic lighting, CPU-safe Cycles settings, and standardized GLB export.
     */
    public static WorkerScripts wrapDynamicScript(String dynamicScript, AIDirectorSpec spec, String assetId) {
        VynaraLogger.system("BlenderWorkerAgent: Wrapping dynamic AI script for asset [" + assetId + "]");

        // Strip any premature export calls from dynamic script so model.glb is never exported before the environment is built
        String safeDynamicScript = (dynamicScript != null) 
                ? dynamicScript.replaceAll("(?m)^\\s*bpy\\.ops\\.export_scene\\.gltf\\([^)]*\\)", "# Stripped premature export")
                : "";

        String w1 = (!safeDynamicScript.trim().isEmpty()) 
                ? safeDynamicScript.trim() 
                : "# Note: Dynamic asset generation synthesized in master pipeline.\n";
        String w2 = buildWorker2DetailsScript(spec);
        String w3 = buildWorker3LightingAndRenderScript(spec);

        String master = buildMasterScript(w1, w2, w3, spec, "Dynamic AI Master Build");

        recordExecution(spec != null ? spec.getGenerationSource() : "Dynamic Scene", master, assetId, spec);

        return new WorkerScripts(w1, w2, w3, master);
    }

    public static String wrapDynamicScript(String dynamicScript, AIDirectorSpec spec) {
        return wrapDynamicScript(dynamicScript, spec, "asset_" + System.currentTimeMillis()).compositeMasterScript;
    }

    /**
     * Solution B: Wraps Gemini's repaired script for Attempt 2 re-dispatch.
     */
    public static WorkerScripts wrapRepairedScript(String repairedScript, AIDirectorSpec spec, String assetId) {
        VynaraLogger.system("BlenderWorkerAgent: Wrapping repaired script for Attempt 2 [" + assetId + "]");
        return wrapDynamicScript(repairedScript, spec, assetId);
    }

    /**
     * Synthesizes modular worker scripts governed by the Director's Spec.
     * Operates purely dynamically without hardcoded vehicle or highway templates.
     */
    public static WorkerScripts generateModularScripts(String userPrompt, AIDirectorSpec spec, String assetId) {
        VynaraLogger.system("BlenderWorkerAgent: Spawning dynamic modular worker scripts for [" + assetId + "]");

        String w1 = buildWorker1StructureScript(userPrompt, spec);
        String w2 = buildWorker2DetailsScript(spec);
        String w3 = buildWorker3LightingAndRenderScript(spec);

        String master = buildMasterScript(w1, w2, w3, spec, "Master Build");

        recordExecution(userPrompt, master, assetId, spec);

        return new WorkerScripts(w1, w2, w3, master);
    }

    private static String buildMasterScript(String w1, String w2, String w3, AIDirectorSpec spec, String title) {
        StringBuilder master = new StringBuilder();
        master.append("# ==========================================\n");
        master.append("# Vynara Autonomous 3D Studio - ").append(title).append("\n");
        if (spec != null) {
            master.append("# Source: ").append(spec.getGenerationSource()).append("\n");
            master.append("# Subject: ").append(spec.getSubjectCategory()).append(" | Staging: ").append(spec.getPathType()).append("\n");
            master.append("# Total Frames: ").append(spec.getTotalFrames()).append(" @ ").append(spec.getFps()).append("fps\n");
        }
        master.append("# ==========================================\n\n");
        master.append("import bpy, os, math, random, sys, traceback, mathutils\n");
        master.append("import addon_utils\n\n");

        master.append("os.makedirs('output', exist_ok=True)\n\n");

        master.append("try:\n");
        master.append("    # Clean scene completely\n");
        master.append("    bpy.ops.object.select_all(action='SELECT')\n");
        master.append("    bpy.ops.object.delete(use_global=False)\n\n");

        master.append("    # --- WORKER 1: STRUCTURE & HERO GEOMETRY ---\n");
        master.append(indentPythonCode(w1, 1)).append("\n\n");

        master.append("    # --- WORKER 2: SUB-PARTS, PROPS & DETAILS ---\n");
        master.append(indentPythonCode(w2, 1)).append("\n\n");

        master.append("    # --- WORKER 3: LIGHTING, CAMERA & CYCLES RENDER ---\n");
        master.append(indentPythonCode(w3, 1)).append("\n\n");

        master.append("except Exception as execution_error:\n");
        master.append("    err_msg = traceback.format_exc()\n");
        master.append("    print('[BLENDER_FATAL_ERROR]\\n' + err_msg, file=sys.stderr)\n");
        master.append("    with open('output/error.txt', 'w', encoding='utf-8') as ef:\n");
        master.append("        ef.write(err_msg)\n");
        master.append("    sys.exit(1)\n");

        return master.toString();
    }

    private static String indentPythonCode(String code, int indentLevels) {
        if (code == null || code.isEmpty()) return "";
        String indent = "    ".repeat(Math.max(0, indentLevels));
        String[] lines = code.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                sb.append("\n");
            } else {
                sb.append(indent).append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public static void recordExecution(String prompt, String masterScript, String assetId, AIDirectorSpec spec) {
        sLastUserPrompt = prompt;
        sLastMasterScript = masterScript;
        sLastAssetId = assetId;
        sLastDirectorSpec = spec;
    }

    public static String getLastMasterScript() { return sLastMasterScript; }
    public static String getLastUserPrompt() { return sLastUserPrompt; }
    public static String getLastAssetId() { return sLastAssetId; }
    public static AIDirectorSpec getLastDirectorSpec() { return sLastDirectorSpec; }

    /**
     * Dynamically builds Worker 1 (Structure) using universal procedural shaping,
     * or loads an uploaded 3D asset (.fbx, .glb, .obj, .gltf),
     * baking the contextual target scale dynamically to mesh vertices.
     */
    private static String buildWorker1StructureScript(String promptOrCode, AIDirectorSpec spec) {
        if (promptOrCode == null) return "";

        if (promptOrCode.contains("import bpy") || promptOrCode.contains("bpy.ops") || promptOrCode.contains("bpy.data")) {
            return promptOrCode.replaceAll("(?m)^\\s*bpy\\.ops\\.export_scene\\.gltf\\([^)]*\\)", "# Stripped premature export").trim();
        }

        String p = promptOrCode.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int seed = (spec != null) ? spec.getSeedHero() : 42;
        float targetLen = (spec != null && spec.getTargetSubjectLengthMeters() > 0) ? spec.getTargetSubjectLengthMeters() : 1.8f;

        sb.append("random.seed(").append(seed).append(")\n\n");
        
        // PBR Primary Material Setup (Blender 4.2+ compliant with RGBA 4-tuples)
        sb.append("# Primary PBR Material\n");
        sb.append("mat_hero = bpy.data.materials.new('Mat_Hero_Primary')\n");
        sb.append("mat_hero.use_nodes = True\n");
        sb.append("bsdf_h = mat_hero.node_tree.nodes.get('Principled BSDF')\n");
        sb.append("if bsdf_h:\n");
        float[] rgb = hexToRgb(spec != null ? spec.getPrimaryColorHex() : "#F1C40F");
        sb.append("    bsdf_h.inputs['Base Color'].default_value = (").append(rgb[0]).append(", ").append(rgb[1]).append(", ").append(rgb[2]).append(", 1.0)\n");
        sb.append("    bsdf_h.inputs['Roughness'].default_value = 0.25\n");
        sb.append("    bsdf_h.inputs['Metallic'].default_value = 0.5\n\n");

        sb.append("mat_secondary = bpy.data.materials.new('Mat_Secondary')\n");
        sb.append("mat_secondary.use_nodes = True\n");
        sb.append("bsdf_s = mat_secondary.node_tree.nodes.get('Principled BSDF')\n");
        sb.append("if bsdf_s:\n");
        float[] rgb2 = hexToRgb(spec != null ? spec.getSecondaryColorHex() : "#333333");
        sb.append("    bsdf_s.inputs['Base Color'].default_value = (").append(rgb2[0]).append(", ").append(rgb2[1]).append(", ").append(rgb2[2]).append(", 1.0)\n");
        sb.append("    bsdf_s.inputs['Roughness'].default_value = 0.35\n");
        sb.append("    bsdf_s.inputs['Metallic'].default_value = 0.2\n\n");

        // Check for user-uploaded 3D model
        sb.append("# Ingest user imported 3D asset model in workspace\n");
        sb.append("imported_asset = None\n");
        sb.append("glb_path = 'inputs/input_model.glb' if os.path.exists('inputs/input_model.glb') else ('input_model.glb' if os.path.exists('input_model.glb') else None)\n");
        sb.append("fbx_path = 'inputs/input_model.fbx' if os.path.exists('inputs/input_model.fbx') else ('input_model.fbx' if os.path.exists('input_model.fbx') else None)\n");
        sb.append("gltf_path = 'inputs/input_model.gltf' if os.path.exists('inputs/input_model.gltf') else ('input_model.gltf' if os.path.exists('input_model.gltf') else None)\n");
        sb.append("obj_path = 'inputs/input_model.obj' if os.path.exists('inputs/input_model.obj') else ('input_model.obj' if os.path.exists('input_model.obj') else None)\n\n");

        sb.append("imported_mesh_list = []\n");
        sb.append("if glb_path:\n");
        sb.append("    print(f'Loading normalized 3D GLB model: {glb_path}...')\n");
        sb.append("    _b4 = set(bpy.data.objects)\n");
        sb.append("    bpy.ops.import_scene.gltf(filepath=glb_path)\n");
        sb.append("    imported_mesh_list = [o for o in bpy.data.objects if o not in _b4 and o.type == 'MESH']\n");
        sb.append("elif fbx_path:\n");
        sb.append("    print(f'Loading user-provided 3D FBX model: {fbx_path}...')\n");
        sb.append("    try:\n");
        sb.append("        _b4 = set(bpy.data.objects)\n");
        sb.append("        bpy.ops.import_scene.fbx(filepath=fbx_path)\n");
        sb.append("        imported_mesh_list = [o for o in bpy.data.objects if o not in _b4 and o.type == 'MESH']\n");
        sb.append("    except Exception as fe:\n");
        sb.append("        print(f'FBX import fallback notice: {fe}')\n");
        sb.append("elif gltf_path:\n");
        sb.append("    print(f'Loading user-provided 3D GLTF model: {gltf_path}...')\n");
        sb.append("    _b4 = set(bpy.data.objects)\n");
        sb.append("    bpy.ops.import_scene.gltf(filepath=gltf_path)\n");
        sb.append("    imported_mesh_list = [o for o in bpy.data.objects if o not in _b4 and o.type == 'MESH']\n");
        sb.append("elif obj_path:\n");
        sb.append("    print(f'Loading user-provided 3D OBJ model: {obj_path}...')\n");
        sb.append("    _b4 = set(bpy.data.objects)\n");
        sb.append("    bpy.ops.wm.obj_import(filepath=obj_path)\n");
        sb.append("    imported_mesh_list = [o for o in bpy.data.objects if o not in _b4 and o.type == 'MESH']\n\n");

        sb.append("# UNIVERSAL CONTEXTUAL SCALE NORMALIZATION (Target Size: ").append(targetLen).append("m)\n");
        sb.append("if imported_mesh_list:\n");
        sb.append("    _min_x = min((_m.matrix_world @ mathutils.Vector(c)).x for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _max_x = max((_m.matrix_world @ mathutils.Vector(c)).x for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _min_y = min((_m.matrix_world @ mathutils.Vector(c)).y for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _max_y = max((_m.matrix_world @ mathutils.Vector(c)).y for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _min_z = min((_m.matrix_world @ mathutils.Vector(c)).z for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _max_z = max((_m.matrix_world @ mathutils.Vector(c)).z for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _max_dim = max(_max_x - _min_x, _max_y - _min_y, _max_z - _min_z)\n");
        sb.append("    if _max_dim > 0.001:\n");
        sb.append("        _scale_factor = ").append(targetLen).append(" / _max_dim\n");
        sb.append("        _cx = (_min_x + _max_x) * 0.5; _cy = (_min_y + _max_y) * 0.5; _cz = _min_z\n");
        sb.append("        print(f'Normalizing imported assembly primary dimension to ").append(targetLen).append("m')\n");
        sb.append("        for _m in imported_mesh_list:\n");
        sb.append("            _m.location.x = (_m.location.x - _cx) * _scale_factor\n");
        sb.append("            _m.location.y = (_m.location.y - _cy) * _scale_factor\n");
        sb.append("            _m.location.z = (_m.location.z - _cz) * _scale_factor\n");
        sb.append("            _m.scale = (_m.scale.x * _scale_factor, _m.scale.y * _scale_factor, _m.scale.z * _scale_factor)\n");
        sb.append("            bpy.context.view_layer.objects.active = _m\n");
        sb.append("            bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)\n");
        sb.append("    imported_asset = imported_mesh_list[0]\n\n");

        sb.append("if not imported_asset:\n");
        if (p.contains("villa") || p.contains("house") || p.contains("building") || p.contains("architecture")) {
            sb.append("    # Architectural Pavilion\n");
            sb.append("    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, ").append(targetLen * 0.5f).append("))\n");
            sb.append("    structure = bpy.context.active_object\n");
            sb.append("    structure.name = 'Structure_Base'\n");
            sb.append("    structure.scale = (").append(targetLen * 1.5f).append(", ").append(targetLen * 1.2f).append(", ").append(targetLen).append(")\n");
            sb.append("    bpy.ops.object.transform_apply(scale=True)\n");
            sb.append("    structure.data.materials.append(mat_hero)\n");
        } else {
            sb.append("    # Universal Hero Asset\n");
            sb.append("    bpy.ops.mesh.primitive_cylinder_add(radius=").append(targetLen * 0.4f).append(", depth=").append(targetLen).append(", location=(0, 0, ").append(targetLen * 0.5f).append("))\n");
            sb.append("    hero = bpy.context.active_object\n");
            sb.append("    hero.name = 'Hero_Asset'\n");
            sb.append("    hero.data.materials.append(mat_hero)\n");
            sb.append("    bpy.ops.object.shade_smooth()\n");
        }

        return sb.toString();
    }

    /**
     * Builds Worker 2: Details, Environment Staging (Pedestal, Room, or Ground),
     * Model_Root Assembly parenting, and universal prompt-driven motion.
     */
    private static String buildWorker2DetailsScript(AIDirectorSpec spec) {
        StringBuilder sb = new StringBuilder();
        int seed = (spec != null) ? spec.getSeedVegetation() : 101;
        float pathLen = (spec != null) ? spec.getPathLengthMeters() : 10.0f;
        int totalFrames = (spec != null) ? spec.getTotalFrames() : 72;

        sb.append("random.seed(").append(seed).append(")\n\n");

        sb.append("# PBR Environment Material\n");
        sb.append("mat_env = bpy.data.materials.new('Mat_PBR_Environment')\n");
        sb.append("mat_env.use_nodes = True\n");
        sb.append("bsdf_env = mat_env.node_tree.nodes.get('Principled BSDF')\n");
        sb.append("if bsdf_env:\n");
        sb.append("    bsdf_env.inputs['Base Color'].default_value = (0.2, 0.22, 0.25, 1.0)\n");
        sb.append("    bsdf_env.inputs['Roughness'].default_value = 0.6\n");
        sb.append("    bsdf_env.inputs['Metallic'].default_value = 0.1\n\n");

        String sceneType = (spec != null && spec.getSceneType() != null) ? spec.getSceneType().toLowerCase() : "general";

        sb.append("# UNIVERSAL STAGING & ENVIRONMENT GEOMETRY\n");
        if (sceneType.contains("nature") || sceneType.contains("outdoor") || sceneType.contains("landscape")) {
            sb.append("bpy.ops.mesh.primitive_plane_add(size=").append(Math.max(pathLen, 25.0f)).append(", location=(0, 0, 0))\n");
            sb.append("ground = bpy.context.active_object\n");
            sb.append("ground.name = 'Ground_Terrain'\n");
            sb.append("ground.data.materials.append(mat_env)\n\n");
        } else {
            sb.append("bpy.ops.mesh.primitive_cylinder_add(radius=4.0, depth=0.2, location=(0, 0, -0.1))\n");
            sb.append("pedestal = bpy.context.active_object\n");
            sb.append("pedestal.name = 'Studio_Pedestal'\n");
            sb.append("pedestal.data.materials.append(mat_env)\n\n");
        }

        sb.append("# MASTER ROOT PARENTING AT UNIT SCALE (1,1,1)\n");
        sb.append("_root = bpy.data.objects.get('Model_Root')\n");
        sb.append("if not _root:\n");
        sb.append("    _root = bpy.data.objects.new('Model_Root', None)\n");
        sb.append("    _root.empty_display_type = 'PLAIN_AXES'\n");
        sb.append("    bpy.context.collection.objects.link(_root)\n");
        sb.append("_root.animation_data_clear()\n");
        sb.append("_root.location = (0.0, 0.0, 0.0)\n");
        sb.append("_root.rotation_euler = (0.0, 0.0, 0.0)\n");
        sb.append("_root.scale = (1.0, 1.0, 1.0)\n");
        sb.append("bpy.context.view_layer.update()\n\n");

        sb.append("_env_filter = ['ground', 'terrain', 'plane', 'pedestal', 'sky', 'light', 'camera']\n");
        sb.append("_hero_objs = [o for o in bpy.data.objects if o.type == 'MESH' and not any(k in o.name.lower() for k in _env_filter)]\n");
        sb.append("for _ho in _hero_objs:\n");
        sb.append("    if _ho.animation_data:\n");
        sb.append("        _ho.animation_data_clear()\n");
        sb.append("    _ho.parent = _root\n");
        sb.append("    _ho.matrix_parent_inverse = _root.matrix_world.inverted()\n\n");

        sb.append("# Dynamic Timeline: 1 to ").append(totalFrames).append(" frames\n");
        sb.append("bpy.context.scene.frame_start = 1\n");
        sb.append("bpy.context.scene.frame_end = ").append(totalFrames).append("\n\n");

        String trackingStyle = (spec != null && spec.getCameraTrackingStyle() != null) ? spec.getCameraTrackingStyle() : "turntable_orbit";

        if ("turntable_orbit".equalsIgnoreCase(trackingStyle)) {
            sb.append("# Smooth 360-degree Turntable Rotation across timeline\n");
            sb.append("_root.rotation_mode = 'XYZ'\n");
            sb.append("_root.rotation_euler.z = 0.0\n");
            sb.append("_root.keyframe_insert(data_path='rotation_euler', index=2, frame=1)\n");
            sb.append("_root.rotation_euler.z = math.radians(360.0)\n");
            sb.append("_root.keyframe_insert(data_path='rotation_euler', index=2, frame=").append(totalFrames).append(")\n");
            sb.append("if _root.animation_data and _root.animation_data.action:\n");
            sb.append("    for _fc in _root.animation_data.action.fcurves:\n");
            sb.append("        for _kp in _fc.keyframe_points: _kp.interpolation = 'LINEAR'\n");
        } else {
            sb.append("# Subtle dynamic action sway\n");
            sb.append("_root.keyframe_insert(data_path='location', frame=1)\n");
            sb.append("_root.keyframe_insert(data_path='location', frame=").append(totalFrames).append(")\n");
        }

        return sb.toString();
    }

    /**
     * Builds Worker 3: Cinematics, Dynamic Camera, World Lighting, and Complete Scene Export.
     */
    private static String buildWorker3LightingAndRenderScript(AIDirectorSpec spec) {
        StringBuilder sb = new StringBuilder();
        int seed = (spec != null) ? spec.getSeedLighting() : 202;
        int totalFrames = (spec != null) ? spec.getTotalFrames() : 72;
        int fps = (spec != null) ? spec.getFps() : 24;
        float sunIntensity = (spec != null && spec.getSunIntensity() > 0) ? spec.getSunIntensity() : 4.5f;
        float sunElevation = (spec != null) ? spec.getSunElevation() : 45.0f;
        float sunAzimuth = (spec != null) ? spec.getSunAzimuth() : 30.0f;
        float camHeight = (spec != null && spec.getCameraHeightMeters() > 0) ? spec.getCameraHeightMeters() : 1.2f;
        float camDist = (spec != null && spec.getCameraDistanceMeters() > 0) ? spec.getCameraDistanceMeters() : 4.0f;

        sb.append("random.seed(").append(seed).append(")\n\n");

        sb.append("# UNIVERSAL CINEMATIC CAMERA SETUP\n");
        sb.append("try:\n");
        sb.append("    cam_data = bpy.data.cameras.new('CinematicCamera')\n");
        sb.append("    cam_data.lens = 50.0\n");
        sb.append("    cam_data.clip_end = 500.0\n");
        sb.append("    cam_data.dof.use_dof = True\n");
        sb.append("    cam_data.dof.aperture_fstop = 2.8\n");
        sb.append("    cam_obj = bpy.data.objects.new('Camera', cam_data)\n");
        sb.append("    bpy.context.collection.objects.link(cam_obj)\n");
        sb.append("    bpy.context.scene.camera = cam_obj\n\n");

        float[] camPos = (spec != null && spec.getCameraPosition() != null && spec.getCameraPosition().length >= 3)
                ? spec.getCameraPosition() : new float[]{0.0f, -camDist, camHeight};

        sb.append("    cam_obj.location = (").append(camPos[0]).append(", ").append(camPos[1]).append(", ").append(camPos[2]).append(")\n");
        sb.append("    _root = bpy.data.objects.get('Model_Root')\n");
        sb.append("    if _root:\n");
        sb.append("        _tt = cam_obj.constraints.new(type='TRACK_TO')\n");
        sb.append("        _tt.target = _root\n");
        sb.append("        _tt.track_axis = 'TRACK_NEGATIVE_Z'\n");
        sb.append("        _tt.up_axis = 'UP_Y'\n");
        sb.append("    else:\n");
        sb.append("        cam_obj.rotation_euler = (math.radians(72), 0, 0)\n");
        sb.append("except Exception as ce: print(f'Camera warning: {ce}')\n\n");

        // World Background & Sun Illumination
        sb.append("# Ambient World Radiance & Key Sun Illumination\n");
        sb.append("try:\n");
        sb.append("    sun_data = bpy.data.lights.new('KeySun', type='SUN')\n");
        sb.append("    sun_data.energy = ").append(sunIntensity).append("\n");
        sb.append("    sun_data.color = (1.0, 0.98, 0.95)\n");
        sb.append("    sun_obj = bpy.data.objects.new('KeySunLight', sun_data)\n");
        sb.append("    bpy.context.collection.objects.link(sun_obj)\n");
        sb.append("    sun_obj.rotation_euler = (math.radians(").append(sunElevation).append("), 0, math.radians(").append(sunAzimuth).append("))\n");
        sb.append("    if not bpy.context.scene.world:\n");
        sb.append("        bpy.context.scene.world = bpy.data.worlds.new('Vynara_World')\n");
        sb.append("    bpy.context.scene.world.use_nodes = True\n");
        sb.append("    _bg = bpy.context.scene.world.node_tree.nodes.get('Background')\n");
        sb.append("    if not _bg:\n");
        sb.append("        _bg = bpy.context.scene.world.node_tree.nodes.new('ShaderNodeBackground')\n");
        sb.append("        _out = bpy.context.scene.world.node_tree.nodes.get('World Output')\n");
        sb.append("        if not _out:\n");
        sb.append("            _out = bpy.context.scene.world.node_tree.nodes.new('ShaderNodeOutputWorld')\n");
        sb.append("        bpy.context.scene.world.node_tree.links.new(_bg.outputs['Background'], _out.inputs['Surface'])\n");
        sb.append("    _bg.inputs['Color'].default_value = (0.75, 0.85, 0.95, 1.0)\n");
        sb.append("    _bg.inputs['Strength'].default_value = 1.0\n");
        sb.append("except Exception as le: print(f'Sunlight/Ambient warning: {le}')\n\n");

        // Render Configuration
        sb.append("# Render Engine & Timeline Configuration\n");
        sb.append("scene = bpy.context.scene\n");
        sb.append("scene.render.engine = 'CYCLES'\n");
        sb.append("scene.cycles.device = 'CPU'\n");
        sb.append("scene.cycles.samples = 2\n");
        sb.append("scene.cycles.max_bounces = 3\n");
        sb.append("scene.cycles.use_denoising = False\n");
        sb.append("scene.render.resolution_x = 960\n");
        sb.append("scene.render.resolution_y = 540\n");
        sb.append("scene.render.fps = ").append(fps).append("\n");
        sb.append("scene.frame_start = 1\n");
        sb.append("scene.frame_end = ").append(totalFrames).append("\n\n");

        sb.append("# Optical Shutter Speed\n");
        sb.append("scene.render.use_motion_blur = True\n");
        sb.append("scene.render.motion_blur_shutter = 0.5\n\n");

        // Blender 4.2+ AgX Color Management
        sb.append("# Color Management (Blender 4.2+ AgX Standard)\n");
        sb.append("try:\n");
        sb.append("    scene.view_settings.view_transform = 'AgX'\n");
        sb.append("    scene.view_settings.look = 'AgX - High Contrast'\n");
        sb.append("except Exception as ve: print(f'Color management note: {ve}')\n\n");

        // Step 1: Export Complete Scene to model.glb
        sb.append("# Step 1: Export Complete Scene to model.glb\n");
        sb.append("try:\n");
        sb.append("    for _o in list(bpy.data.objects):\n");
        sb.append("        if _o.type == 'MESH' and any(_k in _o.name.lower() for _k in ['fog', 'volume', 'domain', 'atmosphere']):\n");
        sb.append("            bpy.data.objects.remove(_o, do_unlink=True)\n");
        sb.append("    bpy.ops.export_scene.gltf(\n");
        sb.append("        filepath='output/model.glb',\n");
        sb.append("        export_format='GLB',\n");
        sb.append("        export_apply=False,\n");
        sb.append("        export_skins=True,\n");
        sb.append("        export_animations=True,\n");
        sb.append("        export_materials='EXPORT'\n");
        sb.append("    )\n");
        sb.append("    print('GLB Export Successful: output/model.glb')\n");
        sb.append("except Exception as ge:\n");
        sb.append("    print(f'GLTF export warning: {ge}')\n");
        sb.append("    raise ge\n\n");

        // Step 2: Render Still Snapshot Frame at Frame 1
        sb.append("# Step 2: Render Still Snapshot Frame at Frame 1\n");
        sb.append("try:\n");
        sb.append("    scene.frame_set(1)\n");
        sb.append("    scene.render.filepath = 'output/render.png'\n");
        sb.append("    bpy.ops.render.render(write_still=True)\n");
        sb.append("    print('Preview snapshot complete: output/render.png')\n");
        sb.append("except Exception as re: print(f'Preview render note: {re}')\n\n");

        // Step 3: Render Cinematic MP4 Video Sequence
        sb.append("# Step 3: Render Photorealistic Motion-Blurred Video\n");
        sb.append("try:\n");
        sb.append("    scene.render.image_settings.file_format = 'FFMPEG'\n");
        sb.append("    scene.render.ffmpeg.format = 'MPEG4'\n");
        sb.append("    scene.render.ffmpeg.codec = 'H264'\n");
        sb.append("    scene.render.ffmpeg.constant_rate_factor = 'MEDIUM'\n");
        sb.append("    scene.render.ffmpeg.ffmpeg_preset = 'REALTIME'\n");
        sb.append("    scene.render.filepath = 'output/cinematic.mp4'\n");
        sb.append("    bpy.ops.render.render(animation=True)\n");
        sb.append("    print('Cinematic MP4 Video render complete: output/cinematic.mp4')\n");
        sb.append("except Exception as ve: print(f'Video render note: {ve}')\n");

        return sb.toString();
    }

    private static float[] hexToRgb(String hex) {
        if (hex == null || hex.isEmpty()) return new float[] { 0.5f, 0.5f, 0.5f };
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int c = (int) Long.parseLong(h, 16);
            float r = ((c >> 16) & 0xFF) / 255.0f;
            float g = ((c >> 8) & 0xFF) / 255.0f;
            float b = (c & 0xFF) / 255.0f;
            return new float[] { r, g, b };
        } catch (Exception e) {
            return new float[] { 0.5f, 0.5f, 0.5f };
        }
    }
}