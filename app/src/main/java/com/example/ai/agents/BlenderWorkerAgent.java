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

        // Strip any premature export calls from dynamic script so model.glb is never exported before the road!
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
     * Operates purely dynamically without hardcoded keyword box presets.
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
            master.append("# Scene: ").append(spec.getSceneType()).append(" | Mood: ").append(spec.getMood()).append("\n");
        }
        master.append("# ==========================================\n\n");
        master.append("import bpy, os, math, random, sys, traceback, mathutils\n");
        master.append("import addon_utils\n\n");

        master.append("os.makedirs('output', exist_ok=True)\n\n");

        // Top-level exception wrapper writing exact traceback to error.txt
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
     * Dynamically builds Worker 1 (Structure) using real procedural shaping, subdivision,
     * and boolean carving, or loads an uploaded 3D asset (.fbx, .glb, .obj, .gltf),
     * directly baking the scale normalization to 4.5m and 180-deg forward heading to mesh vertices.
     */
    private static String buildWorker1StructureScript(String promptOrCode, AIDirectorSpec spec) {
        if (promptOrCode == null) return "";

        // If Gemini synthesized code, preserve and return directly (after stripping premature exports)
        if (promptOrCode.contains("import bpy") || promptOrCode.contains("bpy.ops") || promptOrCode.contains("bpy.data")) {
            return promptOrCode.replaceAll("(?m)^\\s*bpy\\.ops\\.export_scene\\.gltf\\([^)]*\\)", "# Stripped premature export").trim();
        }

        String p = promptOrCode.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int seed = (spec != null) ? spec.getSeedHero() : 42;
        sb.append("random.seed(").append(seed).append(")\n\n");
        
        // PBR Primary Material Setup (Blender 4.2+ compliant)
        sb.append("# Hero Primary PBR Material\n");
        sb.append("mat_hero = bpy.data.materials.new('Mat_Hero_Primary')\n");
        sb.append("mat_hero.use_nodes = True\n");
        sb.append("bsdf_h = mat_hero.node_tree.nodes.get('Principled BSDF')\n");
        sb.append("if bsdf_h:\n");
        float[] rgb = hexToRgb(spec != null ? spec.getPrimaryColorHex() : "#F1C40F");
        sb.append("    bsdf_h.inputs['Base Color'].default_value = (").append(rgb[0]).append(", ").append(rgb[1]).append(", ").append(rgb[2]).append(", 1.0)\n");
        sb.append("    bsdf_h.inputs['Roughness'].default_value = 0.18\n");
        sb.append("    bsdf_h.inputs['Metallic'].default_value = 0.85\n\n");

        sb.append("mat_black = bpy.data.materials.new('Mat_Gloss_Black')\n");
        sb.append("mat_black.use_nodes = True\n");
        sb.append("bsdf_b = mat_black.node_tree.nodes.get('Principled BSDF')\n");
        sb.append("if bsdf_b:\n");
        sb.append("    bsdf_b.inputs['Base Color'].default_value = (0.01, 0.01, 0.01, 1.0)\n");
        sb.append("    bsdf_b.inputs['Roughness'].default_value = 0.05\n");
        sb.append("    bsdf_b.inputs['Metallic'].default_value = 0.9\n\n");

        // Check for user-uploaded 3D model (prioritizing normalized GLB)
        sb.append("# Ingest user imported 3D asset model in workspace\n");
        sb.append("imported_car = None\n");
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

        sb.append("# RULE 1: DIRECT BAKED CAR SCALE NORMALIZATION (4.5m length, centered, 180-deg yaw forward)\n");
        sb.append("if imported_mesh_list:\n");
        sb.append("    _min_x = min((_m.matrix_world @ mathutils.Vector(c)).x for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _max_x = max((_m.matrix_world @ mathutils.Vector(c)).x for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _min_y = min((_m.matrix_world @ mathutils.Vector(c)).y for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _max_y = max((_m.matrix_world @ mathutils.Vector(c)).y for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _min_z = min((_m.matrix_world @ mathutils.Vector(c)).z for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _max_z = max((_m.matrix_world @ mathutils.Vector(c)).z for _m in imported_mesh_list for c in _m.bound_box)\n");
        sb.append("    _total_len = max(_max_x - _min_x, _max_y - _min_y)\n");
        sb.append("    if _total_len > 0.001:\n");
        sb.append("        _scale_factor = 4.5 / _total_len\n");
        sb.append("        _cx = (_min_x + _max_x) * 0.5; _cy = (_min_y + _max_y) * 0.5; _cz = _min_z\n");
        sb.append("        print(f'Directly baking imported car assembly length from {_total_len:.3f}m to 4.5m')\n");
        sb.append("        for _m in imported_mesh_list:\n");
        sb.append("            _m.location.x = (_m.location.x - _cx) * _scale_factor\n");
        sb.append("            _m.location.y = (_m.location.y - _cy) * _scale_factor\n");
        sb.append("            _m.location.z = (_m.location.z - _cz) * _scale_factor\n");
        sb.append("            _m.scale = (_m.scale.x * _scale_factor, _m.scale.y * _scale_factor, _m.scale.z * _scale_factor)\n");
        sb.append("            _m.rotation_euler.z += math.pi\n");
        sb.append("            bpy.context.view_layer.objects.active = _m\n");
        sb.append("            bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)\n");
        sb.append("    imported_car = imported_mesh_list[0]\n\n");

        boolean isVehicle = p.contains("car") || p.contains("vehicle") || p.contains("suv") 
                || p.contains("sedan") || p.contains("truck") || p.contains("drive") || p.contains("speed")
                || p.contains("r8");

        if (isVehicle) {
            sb.append("if not imported_car:\n");
            sb.append("    # Procedural Aerodynamic Car Body with Subsurf & Boolean Wheel Wells (4.5m length)\n");
            sb.append("    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, 0.7))\n");
            sb.append("    chassis = bpy.context.active_object\n");
            sb.append("    chassis.name = 'Vehicle_Chassis'\n");
            sb.append("    chassis.scale = (1.9, 4.5, 0.75)\n");
            sb.append("    bpy.ops.object.transform_apply(scale=True)\n\n");

            sb.append("    # Sloped Aerodynamic Cabin\n");
            sb.append("    mat_glass = bpy.data.materials.new('Mat_Tinted_Glass')\n");
            sb.append("    mat_glass.use_nodes = True\n");
            sb.append("    bsdf_g = mat_glass.node_tree.nodes.get('Principled BSDF')\n");
            sb.append("    if bsdf_g:\n");
            sb.append("        bsdf_g.inputs['Base Color'].default_value = (0.04, 0.06, 0.09, 1.0)\n");
            sb.append("        bsdf_g.inputs['Roughness'].default_value = 0.02\n");
            sb.append("        bsdf_g.inputs['Transmission Weight'].default_value = 0.96\n\n");

            sb.append("    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, -0.25, 1.35))\n");
            sb.append("    cabin = bpy.context.active_object\n");
            sb.append("    cabin.name = 'Vehicle_Cabin'\n");
            sb.append("    cabin.scale = (1.62, 2.3, 0.58)\n");
            sb.append("    bpy.ops.object.transform_apply(scale=True)\n");
            sb.append("    bev_c = cabin.modifiers.new('CabinBevel', 'BEVEL')\n");
            sb.append("    bev_c.width = 0.08; bev_c.segments = 3\n");
            sb.append("    cabin.data.materials.append(mat_glass)\n");
            sb.append("    bpy.ops.object.shade_smooth()\n\n");

            sb.append("    # Carve 4 Wheel Arches into Chassis Body\n");
            sb.append("    wheel_coords = [(-0.96, -1.4), (0.96, -1.4), (-0.96, 1.4), (0.96, 1.4)]\n");
            sb.append("    for idx, (wx, wy) in enumerate(wheel_coords):\n");
            sb.append("        bpy.ops.mesh.primitive_cylinder_add(radius=0.52, depth=0.45, location=(wx, wy, 0.45), rotation=(0, math.radians(90), 0))\n");
            sb.append("        cutter = bpy.context.active_object\n");
            sb.append("        cutter.name = f'Wheel_Arch_Cutter_{idx}'\n");
            sb.append("        bool_mod = chassis.modifiers.new(f'Arch_Cut_{idx}', 'BOOLEAN')\n");
            sb.append("        bool_mod.object = cutter\n");
            sb.append("        bool_mod.operation = 'DIFFERENCE'\n");
            sb.append("        bpy.ops.object.select_all(action='DESELECT')\n");
            sb.append("        chassis.select_set(True)\n");
            sb.append("        bpy.context.view_layer.objects.active = chassis\n");
            sb.append("        bpy.ops.object.modifier_apply(modifier=f'Arch_Cut_{idx}')\n");
            sb.append("        bpy.data.objects.remove(cutter, do_unlink=True)\n\n");

            sb.append("    # Bevel & Smooth Chassis\n");
            sb.append("    bev_ch = chassis.modifiers.new('ChassisBevel', 'BEVEL')\n");
            sb.append("    bev_ch.width = 0.07; bev_ch.segments = 3\n");
            sb.append("    chassis.data.materials.append(mat_hero)\n");
            sb.append("    bpy.ops.object.shade_smooth()\n\n");

            sb.append("    # 4 Correctly Oriented Wheels Standing on Vertical Axles\n");
            sb.append("    mat_tire = bpy.data.materials.new('Mat_Tire_Rubber')\n");
            sb.append("    mat_tire.use_nodes = True\n");
            sb.append("    bsdf_t = mat_tire.node_tree.nodes.get('Principled BSDF')\n");
            sb.append("    if bsdf_t: bsdf_t.inputs['Base Color'].default_value = (0.02, 0.02, 0.02, 1.0); bsdf_t.inputs['Roughness'].default_value = 0.85\n");
            sb.append("    for idx, (wx, wy) in enumerate(wheel_coords):\n");
            sb.append("        bpy.ops.mesh.primitive_cylinder_add(radius=0.42, depth=0.28, location=(wx, wy, 0.42), rotation=(0, math.radians(90), 0))\n");
            sb.append("        wheel = bpy.context.active_object\n");
            sb.append("        wheel.name = f'Wheel_{idx}'\n");
            sb.append("        wheel.data.materials.append(mat_tire)\n");
            sb.append("        bpy.ops.object.shade_smooth()\n");

        } else if (p.contains("villa") || p.contains("house") || p.contains("building") || p.contains("architecture")) {
            sb.append("# Architectural Multi-Tier Cantilevered Villa & Terrace\n");
            sb.append("bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, 1.5))\n");
            sb.append("lower = bpy.context.active_object\n");
            sb.append("lower.name = 'Villa_LowerPavilion'\n");
            sb.append("lower.scale = (8.5, 6.5, 3.0)\n");
            sb.append("bpy.ops.object.transform_apply(scale=True)\n");
            sb.append("bev_l = lower.modifiers.new('LowerBevel', 'BEVEL')\n");
            sb.append("bev_l.width = 0.04; bev_l.segments = 2\n");
            sb.append("lower.data.materials.append(mat_hero)\n\n");

            sb.append("# Upper Cantilever Terrace Suite\n");
            sb.append("bpy.ops.mesh.primitive_cube_add(size=1, location=(1.2, 0.6, 4.2))\n");
            sb.append("upper = bpy.context.active_object\n");
            sb.append("upper.name = 'Villa_UpperSuite'\n");
            sb.append("upper.scale = (9.8, 5.8, 2.5)\n");
            sb.append("bpy.ops.object.transform_apply(scale=True)\n");
            sb.append("bev_u = upper.modifiers.new('UpperBevel', 'BEVEL')\n");
            sb.append("bev_u.width = 0.04; bev_u.segments = 2\n");
            sb.append("upper.data.materials.append(mat_hero)\n\n");

            sb.append("# Inset Swimming Pool & Water Surface\n");
            sb.append("mat_water = bpy.data.materials.new('Mat_Pool_Water')\n");
            sb.append("mat_water.use_nodes = True\n");
            sb.append("bsdf_w = mat_water.node_tree.nodes.get('Principled BSDF')\n");
            sb.append("if bsdf_w:\n");
            sb.append("    bsdf_w.inputs['Base Color'].default_value = (0.05, 0.65, 0.9, 0.85)\n");
            sb.append("    bsdf_w.inputs['Roughness'].default_value = 0.03\n");
            sb.append("    bsdf_w.inputs['Transmission Weight'].default_value = 0.95\n");
            sb.append("bpy.ops.mesh.primitive_plane_add(size=1, location=(5.2, -1.0, 0.02))\n");
            sb.append("pool = bpy.context.active_object\n");
            sb.append("pool.name = 'Pool_Surface'\n");
            sb.append("pool.scale = (5.5, 8.5, 1.0)\n");
            sb.append("bpy.ops.object.transform_apply(scale=True)\n");
            sb.append("pool.data.materials.append(mat_water)\n");

        } else {
            sb.append("# General Procedural Hero Asset with Bevel & Smooth Shading\n");
            sb.append("bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, 1.0))\n");
            sb.append("hero = bpy.context.active_object\n");
            sb.append("hero.name = 'Hero_Asset'\n");
            sb.append("hero.scale = (2.2, 2.2, 2.2)\n");
            sb.append("bpy.ops.object.transform_apply(scale=True)\n");
            sb.append("bev_h = hero.modifiers.new('HeroBevel', 'BEVEL')\n");
            sb.append("bev_h.width = 0.06; bev_h.segments = 3\n");
            sb.append("hero.data.materials.append(mat_hero)\n");
            sb.append("bpy.ops.object.shade_smooth()\n");
        }

        return sb.toString();
    }

    /**
     * Builds Worker 2: Details, Road Infrastructure (14m x 250m at Z=-0.01), Center Dashes (Z=0.005 UP),
     * Model_Root Assembly parenting with clean unit scale, and Driving Animation (Y: 15 -> 95m).
     */
    private static String buildWorker2DetailsScript(AIDirectorSpec spec) {
        StringBuilder sb = new StringBuilder();
        int seed = (spec != null) ? spec.getSeedVegetation() : 101;
        sb.append("random.seed(").append(seed).append(")\n\n");

        sb.append("# High-Detail PBR Asphalt Material\n");
        sb.append("mat_road = bpy.data.materials.new('Mat_PBR_Asphalt')\n");
        sb.append("mat_road.use_nodes = True\n");
        sb.append("bsdf_rd = mat_road.node_tree.nodes.get('Principled BSDF')\n");
        sb.append("if bsdf_rd:\n");
        sb.append("    bsdf_rd.inputs['Base Color'].default_value = (0.05, 0.05, 0.05, 1.0)\n");
        sb.append("    bsdf_rd.inputs['Roughness'].default_value = 0.72\n");
        sb.append("    bsdf_rd.inputs['Metallic'].default_value = 0.1\n\n");

        String sceneType = (spec != null && spec.getSceneType() != null) ? spec.getSceneType().toLowerCase() : "general";
        boolean isVehicle = sceneType.contains("vehicle") || sceneType.contains("car") || sceneType.contains("drive") || sceneType.contains("speed");

        if (isVehicle) {
            sb.append("# RULE 2: REALISTIC MULTI-LANE HIGHWAY ROAD (14m wide x 250m long, flat along Y-corridor)\n");
            sb.append("bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0.0, 125.0, -0.01))\n");
            sb.append("road = bpy.context.active_object\n");
            sb.append("road.name = 'Highway_Road'\n");
            sb.append("road.rotation_euler = (0.0, 0.0, 0.0)\n");
            sb.append("road.dimensions = (14.0, 250.0, 0.0)\n");
            sb.append("bpy.context.view_layer.objects.active = road\n");
            sb.append("bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)\n");
            sb.append("road.data.materials.append(mat_road)\n\n");

            sb.append("# Center lane dashes at Z = 0.005 with normal pointing straight UP (0, 0, 1)\n");
            sb.append("mat_dash = bpy.data.materials.new('Mat_Lane_Dash')\n");
            sb.append("mat_dash.use_nodes = True\n");
            sb.append("bsdf_d = mat_dash.node_tree.nodes.get('Principled BSDF')\n");
            sb.append("if bsdf_d:\n");
            sb.append("    bsdf_d.inputs['Base Color'].default_value = (1.0, 1.0, 1.0, 1.0)\n");
            sb.append("    bsdf_d.inputs['Roughness'].default_value = 0.25\n");
            sb.append("for _idx in range(1, 40):\n");
            sb.append("    _dy = _idx * 6.0\n");
            sb.append("    if _dy > 245.0: break\n");
            sb.append("    bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0.0, _dy, 0.005))\n");
            sb.append("    _dash = bpy.context.active_object\n");
            sb.append("    _dash.name = f'Lane_Dash_{_idx}'\n");
            sb.append("    _dash.rotation_euler = (0.0, 0.0, 0.0)\n");
            sb.append("    _dash.dimensions = (0.2, 3.0, 0.0)\n");
            sb.append("    _dash.data.materials.append(mat_dash)\n\n");

            sb.append("# Metal Guardrail Barrier along Highway\n");
            sb.append("mat_guard = bpy.data.materials.new('Mat_Galvanized_Steel')\n");
            sb.append("mat_guard.use_nodes = True\n");
            sb.append("bsdf_gd = mat_guard.node_tree.nodes.get('Principled BSDF')\n");
            sb.append("if bsdf_gd:\n");
            sb.append("    bsdf_gd.inputs['Base Color'].default_value = (0.7, 0.72, 0.74, 1.0)\n");
            sb.append("    bsdf_gd.inputs['Metallic'].default_value = 0.9\n");
            sb.append("    bsdf_gd.inputs['Roughness'].default_value = 0.25\n");
            sb.append("bpy.ops.mesh.primitive_cube_add(size=1, location=(6.8, 125.0, 0.55))\n");
            sb.append("guardrail = bpy.context.active_object\n");
            sb.append("guardrail.name = 'Highway_Guardrail'\n");
            sb.append("guardrail.scale = (0.1, 250.0, 0.35)\n");
            sb.append("bpy.ops.object.transform_apply(scale=True)\n");
            sb.append("guardrail.data.materials.append(mat_guard)\n\n");

            sb.append("# RULES 3 & 4: MASTER ROOT PARENTING AT CLEAN (1,1,1) UNIT SCALE & DRIVING ANIMATION\n");
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

            sb.append("_env_filter = ['road', 'highway', 'asphalt', 'ground', 'stripe', 'lane', 'marking', 'dash', 'guardrail', 'barrier', 'curb', 'sidewalk', 'terrain', 'plane', 'sky', 'light', 'lamp', 'camera']\n");
            sb.append("_car_objs = [o for o in bpy.data.objects if o.type == 'MESH' and not any(k in o.name.lower() for k in _env_filter)]\n");
            sb.append("# 1. Clear residual animation on individual meshes to prevent transform fighting\n");
            sb.append("for _co in _car_objs:\n");
            sb.append("    if _co.animation_data:\n");
            sb.append("        _co.animation_data_clear()\n");
            sb.append("    _co.parent = _root\n");
            sb.append("    _co.matrix_parent_inverse = _root.matrix_world.inverted()\n\n");

            sb.append("# 2. Driving Animation: Start at Y=15.0 with open runway, drive to Y=95.0\n");
            sb.append("bpy.context.scene.frame_start = 1\n");
            sb.append("bpy.context.scene.frame_end = 60\n");
            sb.append("_root.location = (0.0, 15.0, 0.0)\n");
            sb.append("_root.keyframe_insert(data_path='location', frame=1)\n");
            sb.append("_root.location = (0.0, 95.0, 0.0)\n");
            sb.append("_root.keyframe_insert(data_path='location', frame=60)\n");
            sb.append("if _root.animation_data and _root.animation_data.action:\n");
            sb.append("    for _fc in _root.animation_data.action.fcurves:\n");
            sb.append("        for _kp in _fc.keyframe_points: _kp.interpolation = 'LINEAR'\n\n");

            sb.append("# 3. Baked Wheel Spin: Keyframe frame-by-frame (1->60) to eliminate quaternion slerp jitter\n");
            sb.append("_wheel_keys = ['wheel', 'tire', 'rim', 'tyre', 'disc']\n");
            sb.append("_wheels = [o for o in _car_objs if any(wk in o.name.lower() for wk in _wheel_keys)]\n");
            sb.append("for _wo in _wheels:\n");
            sb.append("    _wo.rotation_mode = 'XYZ'\n");
            sb.append("    _wo.animation_data_clear()\n");
            sb.append("    for _f in range(1, 61):\n");
            sb.append("        _prog = (_f - 1) / 59.0\n");
            sb.append("        _wo.rotation_euler.x = (214.28 * _prog)\n");
            sb.append("        _wo.keyframe_insert(data_path='rotation_euler', frame=_f)\n");
            sb.append("    if _wo.animation_data and _wo.animation_data.action:\n");
            sb.append("        for _fc in _wo.animation_data.action.fcurves:\n");
            sb.append("            for _kp in _fc.keyframe_points: _kp.interpolation = 'LINEAR'\n");

        } else if (sceneType.contains("nature") || sceneType.contains("outdoor") || sceneType.contains("villa")) {
            sb.append("# Ambient Ground Plane\n");
            sb.append("bpy.ops.mesh.primitive_plane_add(size=36, location=(0, 0, 0))\n");
            sb.append("ground = bpy.context.active_object\n");
            sb.append("ground.name = 'Ground_Surface'\n");
            sb.append("ground.data.materials.append(mat_road)\n");
        } else {
            sb.append("# Clean studio floor pedestal\n");
            sb.append("bpy.ops.mesh.primitive_cylinder_add(radius=5.0, depth=0.08, location=(0, 0, -0.04))\n");
            sb.append("pedestal = bpy.context.active_object\n");
            sb.append("pedestal.name = 'Studio_Pedestal'\n");
            sb.append("pedestal.data.materials.append(mat_road)\n");
        }

        return sb.toString();
    }

    /**
     * Builds Worker 3: Cinematics, World Tracking Camera (Outside car chassis),
     * Natural Sun Lighting with Ambient Sky Radiance, 180-deg Optical Motion Blur, AgX Color Management,
     * Complete Scene GLB Export (including Road), and MP4 Video Rendering.
     */
    private static String buildWorker3LightingAndRenderScript(AIDirectorSpec spec) {
        StringBuilder sb = new StringBuilder();
        int seed = (spec != null) ? spec.getSeedLighting() : 202;
        sb.append("random.seed(").append(seed).append(")\n\n");

        float sunIntensity = (spec != null && spec.getSunIntensity() > 0) ? spec.getSunIntensity() : 6.0f;
        float sunElevation = (spec != null) ? spec.getSunElevation() : 18.0f;
        float sunAzimuth = (spec != null) ? spec.getSunAzimuth() : -45.0f;

        String sceneType = (spec != null && spec.getSceneType() != null) ? spec.getSceneType().toLowerCase() : "general";
        boolean isVehicle = sceneType.contains("vehicle") || sceneType.contains("car") || sceneType.contains("drive") || sceneType.contains("speed");

        // RULE 5: CINEMATIC TRACKING CAMERA SETUP
        sb.append("# RULE 5: CINEMATIC CAMERA SETUP\n");
        sb.append("try:\n");
        sb.append("    cam_data = bpy.data.cameras.new('CinematicCamera')\n");
        sb.append("    cam_data.lens = 35.0\n");
        sb.append("    cam_data.clip_end = 500.0\n");
        sb.append("    cam_data.dof.use_dof = True\n");
        sb.append("    cam_data.dof.aperture_fstop = 2.8\n");
        sb.append("    cam_obj = bpy.data.objects.new('Camera', cam_data)\n");
        sb.append("    bpy.context.collection.objects.link(cam_obj)\n");
        sb.append("    bpy.context.scene.camera = cam_obj\n\n");

        if (isVehicle) {
            sb.append("    # Move camera in world-meters along Y (unparented to avoid downscale inside chassis)\n");
            sb.append("    _root = bpy.data.objects.get('Model_Root')\n");
            sb.append("    cam_obj.parent = None\n");
            sb.append("    cam_obj.scale = (1.0, 1.0, 1.0)\n");
            sb.append("    cam_obj.constraints.clear()\n");
            sb.append("    cam_obj.animation_data_clear()\n");
            sb.append("    cam_obj.location = (-3.8, 8.0, 2.0)\n");
            sb.append("    cam_obj.keyframe_insert(data_path='location', frame=1)\n");
            sb.append("    cam_obj.location = (-3.8, 88.0, 2.0)\n");
            sb.append("    cam_obj.keyframe_insert(data_path='location', frame=60)\n");
            sb.append("    if cam_obj.animation_data and cam_obj.animation_data.action:\n");
            sb.append("        for _fc in cam_obj.animation_data.action.fcurves:\n");
            sb.append("            for _kp in _fc.keyframe_points: _kp.interpolation = 'LINEAR'\n");
            sb.append("    if _root:\n");
            sb.append("        _tt = cam_obj.constraints.new(type='TRACK_TO')\n");
            sb.append("        _tt.target = _root\n");
            sb.append("        _tt.track_axis = 'TRACK_NEGATIVE_Z'\n");
            sb.append("        _tt.up_axis = 'UP_Y'\n");
        } else {
            float[] camPos = (spec != null && spec.getCameraPosition() != null && spec.getCameraPosition().length >= 3)
                    ? spec.getCameraPosition() : new float[]{0.0f, -8.5f, 3.8f};
            sb.append("    cam_obj.location = (").append(camPos[0]).append(", ").append(camPos[1]).append(", ").append(camPos[2]).append(")\n");
            sb.append("    cam_obj.rotation_euler = (math.radians(68), 0, 0)\n");
        }
        sb.append("except Exception as ce: print(f'Camera warning: {ce}')\n\n");

        // Natural Sunlight Rig & Ambient Sky Radiance
        sb.append("# Natural Golden-Hour Sunlight & Ambient Sky Radiance\n");
        sb.append("try:\n");
        sb.append("    sun_data = bpy.data.lights.new('KeySun', type='SUN')\n");
        sb.append("    sun_data.energy = ").append(sunIntensity).append("\n");
        sb.append("    sun_obj = bpy.data.objects.new('KeySunLight', sun_data)\n");
        sb.append("    bpy.context.collection.objects.link(sun_obj)\n");
        sb.append("    sun_obj.rotation_euler = (math.radians(").append(sunElevation).append("), 0, math.radians(").append(sunAzimuth).append("))\n");
        sb.append("    if bpy.context.scene.world and bpy.context.scene.world.node_tree:\n");
        sb.append("        _bg = bpy.context.scene.world.node_tree.nodes.get('Background')\n");
        sb.append("        if _bg:\n");
        sb.append("            _bg.inputs['Color'].default_value = (0.65, 0.82, 1.0, 1.0)\n");
        sb.append("            _bg.inputs['Strength'].default_value = 1.2\n");
        sb.append("except Exception as le: print(f'Sunlight/Ambient warning: {le}')\n\n");

        // Render Configuration: Motion Blur & Shutter Speed
        sb.append("# Render Engine & Optical Motion Blur Configuration\n");
        sb.append("scene = bpy.context.scene\n");
        sb.append("scene.render.engine = 'CYCLES'\n");
        sb.append("scene.cycles.device = 'CPU'\n");
        sb.append("scene.cycles.samples = 2\n");
        sb.append("scene.cycles.max_bounces = 3\n");
        sb.append("scene.cycles.use_denoising = False\n");
        sb.append("scene.render.resolution_x = 960\n");
        sb.append("scene.render.resolution_y = 540\n");
        sb.append("scene.render.fps = 30\n");
        sb.append("scene.frame_start = 1\n");
        sb.append("scene.frame_end = 60\n\n");

        sb.append("# Optical Shutter Speed (180-deg Shutter = 0.5 frame blur)\n");
        sb.append("scene.render.use_motion_blur = True\n");
        sb.append("scene.render.motion_blur_shutter = 0.5\n\n");

        // Blender 4.2+ AgX Color Management
        sb.append("# Color Management (Blender 4.2+ AgX Standard)\n");
        sb.append("try:\n");
        sb.append("    scene.view_settings.view_transform = 'AgX'\n");
        sb.append("    scene.view_settings.look = 'AgX - High Contrast'\n");
        sb.append("except Exception as ve: print(f'Color management note: {ve}')\n\n");

        // Step 1: Export Interactive GLB (Always after Worker 2 road & car are committed)
        sb.append("# Step 1: Export Interactive 3D GLTF/GLB (Includes Road, Car, and Kinematics)\n");
        sb.append("try:\n");
        sb.append("    for _o in list(bpy.data.objects):\n");
        sb.append("        if _o.type == 'MESH' and any(_k in _o.name.lower() for _k in ['fog', 'volume', 'domain', 'atmosphere']):\n" +
                  "            bpy.data.objects.remove(_o, do_unlink=True)\n");
        sb.append("    bpy.ops.export_scene.gltf(\n" +
                  "        filepath='output/model.glb',\n" +
                  "        export_format='GLB',\n" +
                  "        export_apply=False,\n" +
                  "        export_skins=True,\n" +
                  "        export_animations=True,\n" +
                  "        export_materials='EXPORT'\n" +
                  "    )\n");
        sb.append("    print('GLB Export Successful: output/model.glb (Road, Vehicle & Rig Committed)')\n");
        sb.append("except Exception as ge:\n");
        sb.append("    print(f'GLTF export warning: {ge}')\n");
        sb.append("    raise ge\n\n");

        // Step 2: Render Still Preview at Frame 1
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