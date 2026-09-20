package com.example.ai.agents;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.example.ai.ApiKeyManager;
import com.example.ai.GeminiApiClient;
import com.example.ai.protocol.AIDirectorSpec;
import com.example.utils.VynaraLogger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DirectorAgent {
    private final GeminiApiClient apiClient;
    private final ApiKeyManager apiKeyManager;

    public interface DirectorCallback {
        void onSpecReady(AIDirectorSpec spec);
        void onError(String errorMessage);
    }

    public DirectorAgent(GeminiApiClient apiClient, ApiKeyManager apiKeyManager) {
        this.apiClient = apiClient;
        this.apiKeyManager = apiKeyManager;
    }

    /**
     * Phase 1: Formulates the comprehensive 4-worker scene specification using Gemini Vision.
     * Analyzes reference photos to decompose any prompt into dynamic architectural/automotive layers.
     */
    public void formulateDirectorSpec(final String userPrompt,
                                      final String style,
                                      final List<String> referenceImageUris,
                                      final DirectorCallback callback) {
        if (callback == null) return;

        if (!apiKeyManager.hasApiKey()) {
            String msg = "DirectorAgent: Gemini API Key missing in Settings. Cannot run live AI generation.";
            VynaraLogger.e(msg);
            callback.onError(msg);
            return;
        }

        final String activeModel = apiKeyManager.getSelectedModel();

        // 1. Differentiate between 2D reference images and imported 3D models
        List<String> base64Images = new ArrayList<>();
        List<String> attached3DModels = new ArrayList<>();

        if (referenceImageUris != null && !referenceImageUris.isEmpty()) {
            for (String uriOrPath : referenceImageUris) {
                if (is3DModelUri(uriOrPath)) {
                    String modelName = extractModelName(uriOrPath);
                    attached3DModels.add(modelName);
                    VynaraLogger.system("DirectorAgent: Attached 3D model metadata identified: " + modelName);
                } else {
                    String b64 = readImageAsBase64(uriOrPath);
                    if (b64 != null && !b64.isEmpty()) {
                        base64Images.add(b64);
                    } else {
                        VynaraLogger.w("DirectorAgent: Reference image could not be converted to Base64: " + uriOrPath);
                    }
                }
            }
        }

        String systemInstruction = buildDirectorSystemInstruction();

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("USER PROMPT: ").append(userPrompt).append("\n");
        promptBuilder.append("REQUESTED STYLE: ").append(style).append("\n");

        if (!attached3DModels.isEmpty()) {
            promptBuilder.append("ACTIVE 3D MODEL ATTACHED: The scene contains an imported 3D car mesh asset: ")
                         .append(String.join(", ", attached3DModels))
                         .append(". Worker 1 must import the normalized asset (inputs/input_model.glb) into the scene rather than generating a replacement placeholder chassis.\n")
                         .append("CRITICAL SPATIAL & ANIMATION RULES TO MANDATE ACROSS WORKERS:\n")
                         .append("1. CAR SCALE NORMALIZATION: Calculate combined bounding box across all imported car sub-meshes and scale assembly down so total length is exactly 4.5 meters. Apply all scale transforms.\n")
                         .append("2. ROAD SCALE & ALIGNMENT: Build a 14m wide by 250m long multi-lane asphalt highway flat on ground at Z=0.0 running straight along Y-axis with rotation (0,0,0). Add center lane dashes at Z=0.005 with normal pointing straight UP (0,0,1).\n")
                         .append("3. PLACING CAR ON ROAD: Center the car in driving lane at X=0.0, snap tire bottoms flush to road at Z=0.0, and start at Y=5.0.\n")
                         .append("4. PARENTING & DRIVING ANIMATION: Create master Empty 'Model_Root' at base, parent all car sub-meshes keeping relative assembly offsets intact, animate driving along Y from Y=5.0 at frame 1 to Y=80.0 at frame 60, and animate wheel spin around axles proportional to speed.\n")
                         .append("5. CINEMATIC CAMERA: Place camera tracking car from a low, dramatic, three-quarter front angle, keyframed moving with car down highway.\n");
        }

        if (!base64Images.isEmpty()) {
            promptBuilder.append("VISUAL REFERENCE ATTACHED: Inspect the attached visual reference image(s). ")
                         .append("Deconstruct the actual physical geometry, automotive curves or architectural cantilever slabs, ")
                         .append("wheel designs, materials, and lighting atmosphere. Do not invent generic cubes.\n");
        }

        VynaraLogger.system("DirectorAgent: Formulating dynamic 4-Worker scene spec via Gemini Vision [" + activeModel + "] with " + base64Images.size() + " image(s)...");

        // 2. Dispatch Multimodal Structured Request
        apiClient.generateStructuredJson(
                apiKeyManager.getApiKey(),
                activeModel,
                systemInstruction,
                promptBuilder.toString(),
                base64Images,
                new GeminiApiClient.ApiCallback<String>() {
                    @Override
                    public void onSuccess(String jsonResult) {
                        try {
                            String cleanJson = jsonResult.trim();
                            if (cleanJson.startsWith("```json")) {
                                cleanJson = cleanJson.substring(7);
                            } else if (cleanJson.startsWith("```")) {
                                cleanJson = cleanJson.substring(3);
                            }
                            if (cleanJson.endsWith("```")) {
                                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                            }
                            cleanJson = cleanJson.trim();

                            JSONObject root = new JSONObject(cleanJson);
                            AIDirectorSpec spec = AIDirectorSpec.fromJson(root, activeModel);
                            
                            VynaraLogger.system("DirectorAgent: Dynamic multi-agent specification formulated successfully for [" + spec.getSceneType() + "].");
                            callback.onSpecReady(spec);
                        } catch (Exception e) {
                            String err = "DirectorAgent: Failed to parse Gemini specification: " + e.getMessage();
                            VynaraLogger.e(err, e);
                            callback.onError(err);
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        String err = "DirectorAgent: Google Gemini API error: " + errorMessage;
                        VynaraLogger.e(err);
                        callback.onError(err);
                    }
                }
        );
    }

    /**
     * Autonomous Driving / Cinematics Pipeline:
     * Generates a fully automated production plan for an imported asset (e.g. vehicle, character, prop)
     * without requiring any manual user rigging, tagging, or camera positioning.
     */
    public void formulateAutonomousAssetSpec(final String userPrompt,
                                            final String modelName,
                                            final String modelCategory,
                                            final DirectorCallback callback) {
        if (callback == null) return;

        if (!apiKeyManager.hasApiKey()) {
            String msg = "DirectorAgent: Gemini API Key missing in Settings. Cannot run autonomous generation.";
            VynaraLogger.e(msg);
            callback.onError(msg);
            return;
        }

        final String activeModel = apiKeyManager.getSelectedModel();
        String systemInstruction = buildDirectorSystemInstruction();

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("USER PROMPT: ").append(userPrompt).append("\n");
        promptBuilder.append("IMPORTED ASSET NAME: ").append(modelName).append("\n");
        promptBuilder.append("INFERRED CATEGORY: ").append(modelCategory).append("\n");
        promptBuilder.append("DIRECTIVE: Generate a high-speed, cinematic, photorealistic sequence. ")
                     .append("Import model from inputs/input_model.glb. Follow strict spatial and animation rules:\n")
                     .append("1. CAR SCALE NORMALIZATION: Calculate combined bounding box across all imported car meshes and scale assembly down so total length is exactly 4.5 meters. Apply all scale transforms.\n")
                     .append("2. ROAD SCALE & ALIGNMENT: Build multi-lane asphalt highway 14 meters wide and at least 250 meters long, flat at Z=0.0 running straight along Y-axis with rotation (0,0,0). Add center lane dashes at Z=0.005 with normal pointing straight UP (0,0,1).\n")
                     .append("3. PLACING CAR ON ROAD: Center car in driving lane at X=0.0, snap bottom-most point of tires flush on road surface at Z=0.0, start car at Y=5.0.\n")
                     .append("4. PARENTING & DRIVING ANIMATION: Create master Empty 'Model_Root' at base, parent all imported car sub-meshes keeping relative assembly offsets intact, animate 'Model_Root' driving along Y from Y=5.0 at frame 1 to Y=80.0 at frame 60, and animate wheel spinning around axles proportional to driving speed.\n")
                     .append("5. CINEMATIC CAMERA: Place camera tracking car from a low, dramatic, three-quarter front angle, keyframed moving with the car down highway.");

        VynaraLogger.system("DirectorAgent: Formulating autonomous asset animation spec for [" + modelName + "]...");

        apiClient.generateStructuredJson(
                apiKeyManager.getApiKey(),
                activeModel,
                systemInstruction,
                promptBuilder.toString(),
                new ArrayList<>(),
                new GeminiApiClient.ApiCallback<String>() {
                    @Override
                    public void onSuccess(String jsonResult) {
                        try {
                            String cleanJson = jsonResult.trim();
                            if (cleanJson.startsWith("```json")) {
                                cleanJson = cleanJson.substring(7);
                            } else if (cleanJson.startsWith("```")) {
                                cleanJson = cleanJson.substring(3);
                            }
                            if (cleanJson.endsWith("```")) {
                                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                            }
                            cleanJson = cleanJson.trim();

                            JSONObject root = new JSONObject(cleanJson);
                            AIDirectorSpec spec = AIDirectorSpec.fromJson(root, activeModel);

                            VynaraLogger.system("DirectorAgent: Autonomous spec ready for [" + spec.getSceneType() + "].");
                            callback.onSpecReady(spec);
                        } catch (Exception e) {
                            String err = "DirectorAgent: Failed to parse autonomous specification: " + e.getMessage();
                            VynaraLogger.e(err, e);
                            callback.onError(err);
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        String err = "DirectorAgent: Google Gemini API error: " + errorMessage;
                        VynaraLogger.e(err);
                        callback.onError(err);
                    }
                }
        );
    }

    private String buildDirectorSystemInstruction() {
        return "You are the 3D Master Art Director & Spatial Architect (like Fable 5 / SKILL.md).\n" +
                "YOUR ROLE:\n" +
                "- You NEVER write Python code or Blender operators directly.\n" +
                "- Your job is to analyze the user's prompt, imported asset context, and reference images, and decompose the scene into a structured 4-Worker dynamic specification.\n" +
                "- Never settle for generic primitives or low-poly cubes. Define aerodynamic curvatures, bevels, architectural cantilevers, and authentic wheel orientations.\n\n" +
                "CINEMATIC DIRECTIVES FOR VEHICLE SCENES:\n" +
                "1. Worker 1 (Structure, Model Scale & Road Geometry):\n" +
                "   - Import asset from 'inputs/input_model.glb' (or 'input_model.glb').\n" +
                "   - MANDATORY SCALE NORMALIZATION: Calculate combined bounding box across all imported car sub-meshes and scale the assembly down so its total length is exactly 4.5 meters. Apply all scale transforms.\n" +
                "   - Build multi-lane asphalt highway: 14 meters wide (X axis) and at least 250 meters long (Y axis), flat on ground at Z=0.0 running straight along Y-axis with rotation (0,0,0).\n" +
                "   - Add center lane dashes at Z=0.005 with normal pointing straight UP (0,0,1).\n" +
                "2. Worker 2 (Placement, Parenting, Kinematics & Driving Animation):\n" +
                "   - PLACEMENT: Center the car in driving lane at X=0.0, snap bottom-most point of tires flush to road at Z=0.0, start car at Y=5.0.\n" +
                "   - PARENTING: Create master Empty object 'Model_Root' at base, parent all imported car sub-meshes to 'Model_Root' keeping relative assembly offsets intact.\n" +
                "   - DRIVING ANIMATION: Animate 'Model_Root' driving along Y-axis from Y=5.0 at frame 1 to Y=80.0 at frame 60 with linear interpolation.\n" +
                "   - WHEEL ROTATION: Identify wheel/tire meshes and keyframe rotational spin around axles proportional to speed (e.g. -214.28 rad over 75m).\n" +
                "3. Worker 3 (PBR Materials & Shaders):\n" +
                "   - Conforming to Blender 4.2+ Principled BSDF socket names ('Transmission Weight', 'Roughness', 'Metallic', 'Base Color').\n" +
                "   - High-detail 4K asphalt with pebble bump, roughness variations, and bitumen specular.\n" +
                "   - Metallic car paint with clearcoat, darkened glass transmission, and matte tire rubber.\n" +
                "4. Worker 4 (Cinematics, Camera Optics & Lighting):\n" +
                "   - Low, dramatic, three-quarter front angle camera tracking the car.\n" +
                "   - Keyframe camera moving with the car down the highway (e.g. from Y=10.5 at frame 1 to Y=85.5 at frame 60) with Track To targeting Model_Root.\n" +
                "   - 35mm focal length to amplify cinematic motion and depth.\n" +
                "   - Enable 180-degree optical motion blur (shutter = 0.5) to streak road lines and spin wheels.\n" +
                "   - Low-horizon Sun lighting with rim-light highlights and strict Blender 4.2 AgX color management ('AgX - High Contrast').\n\n" +
                "OUTPUT RAW STRICT JSON ONLY (NO MARKDOWN FENCES):\n" +
                "{\n" +
                "  \"sceneType\": \"string\",\n" +
                "  \"mood\": \"string\",\n" +
                "  \"visualStyleNotes\": \"string\",\n" +
                "  \"objectCategory\": \"vehicle | architecture | character | nature | prop\",\n" +
                "  \"workers\": {\n" +
                "    \"w1_structure\": \"Import car, scale normalize to 4.5m, build 14m x 250m road flat at Z=0 with center dashes at Z=0.005 UP (0,0,1)\",\n" +
                "    \"w2_details\": \"Center at X=0, snap tires flush at Z=0, start at Y=5, parent to Model_Root, animate Y 5m to 80m, animate wheel spin\",\n" +
                "    \"w3_materials\": \"PBR shader properties: 4K asphalt, metallic car paint, glass transmission, tire rubber\",\n" +
                "    \"w4_cinematics\": \"Low dramatic 3/4 front angle tracking camera moving with car down highway, 180 deg motion blur, AgX sun lighting\"\n" +
                "  },\n" +
                "  \"camera\": {\n" +
                "    \"focalLengthMm\": 35.0,\n" +
                "    \"apertureFStop\": 2.8,\n" +
                "    \"focusDistance\": 5.5,\n" +
                "    \"position\": [-2.8, 10.5, 0.95],\n" +
                "    \"target\": [0.0, 5.0, 0.5]\n" +
                "  },\n" +
                "  \"lighting\": {\n" +
                "    \"useVolumetrics\": true,\n" +
                "    \"volumetricDensity\": 0.012,\n" +
                "    \"sunElevation\": 18.0,\n" +
                "    \"sunAzimuth\": -45.0,\n" +
                "    \"sunIntensity\": 5.5,\n" +
                "    \"ambientColorHex\": \"#1A2530\"\n" +
                "  },\n" +
                "  \"palette\": {\n" +
                "    \"primaryColorHex\": \"#D4AF37\",\n" +
                "    \"secondaryColorHex\": \"#222222\",\n" +
                "    \"accentColorHex\": \"#E74C3C\"\n" +
                "  },\n" +
                "  \"seeds\": {\n" +
                "    \"seedTerrain\": 101,\n" +
                "    \"seedHero\": 202,\n" +
                "    \"seedVegetation\": 303,\n" +
                "    \"seedLighting\": 404\n" +
                "  }\n" +
                "}";
    }

    /**
     * Generates a targeted code-synthesis instruction for each specialized worker agent.
     */
    public static String buildWorkerPrompt(AIDirectorSpec spec, int workerIndex, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("SCENE GOAL: ").append(userPrompt).append("\n");
        if (spec != null) {
            sb.append("SCENE TYPE: ").append(spec.getSceneType()).append(" | MOOD: ").append(spec.getMood()).append("\n");
            sb.append("PALETTE: Primary=").append(spec.getPrimaryColorHex())
              .append(", Secondary=").append(spec.getSecondaryColorHex()).append("\n");
        }

        switch (workerIndex) {
            case 1: // Worker 1: Core Structure, 4.5m Scale Normalization & 14m x 250m Highway
                sb.append("\nTASK: WORKER 1 (STRUCTURE, SCALE NORMALIZATION & ROAD)\n")
                  .append("- If an imported 3D asset is in 'inputs/', import it using `bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')` (or 'input_model.glb').\n")
                  .append("- RULE 1: CAR SCALE NORMALIZATION: Calculate combined bounding box across all imported car sub-meshes and scale assembly down so total length is exactly 4.5 meters. Apply all scale transforms.\n")
                  .append("- RULE 2: ROAD SCALE & ALIGNMENT: Build a multi-lane asphalt highway plane: 14 meters wide (X axis) and at least 250 meters long (Y axis). It must lie flat on ground at Z=0.0 running straight along Y-axis with rotation (0,0,0).\n")
                  .append("- Add center lane dashes at Z=0.005 with normal pointing straight UP (0,0,1).\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 2: // Worker 2: Placement, Master Root Parenting & Driving Animation
                sb.append("\nTASK: WORKER 2 (PLACEMENT, PARENTING & DRIVING ANIMATION)\n")
                  .append("- RULE 3: PLACING CAR ON ROAD: Center the car in driving lane at X=0.0, snap bottom-most point of tires flush to road surface at Z=0.0, start car at Y=5.0.\n")
                  .append("- RULE 4: PARENTING & DRIVING ANIMATION: Create master Empty object 'Model_Root' at car base. Parent all imported car sub-meshes to 'Model_Root' keeping relative assembly offsets intact.\n")
                  .append("- Animate 'Model_Root' driving forward along Y-axis from Y=5.0 at frame 1 to Y=80.0 at frame 60 using location keyframes with linear interpolation.\n")
                  .append("- Find all wheel/tire meshes and animate them spinning around their axles proportional to driving speed (distance=75m -> -214.28 rad).\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 3: // Worker 3: PBR Materials & Shaders
                sb.append("\nTASK: WORKER 3 (PBR MATERIALS)\n")
                  .append("- Configure Principled BSDF materials using Blender 4.2+ socket names (e.g. 'Transmission Weight', 'Roughness', 'Metallic', 'Base Color').\n")
                  .append("- Road: 4K asphalt procedural texture with pebble bump and roughness variations.\n")
                  .append("- Vehicle: Metallic car paint with clearcoat, matte rubber on tires, and chrome on rims.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 4: // Worker 4: Cinematics, Low 3/4 Front Tracking Camera, Motion Blur & Lighting
            default:
                sb.append("\nTASK: WORKER 4 (CINEMATICS, TRACKING CAMERA & LIGHTING)\n")
                  .append("- RULE 5: CINEMATIC CAMERA: Place camera tracking car from a low, dramatic, three-quarter front angle.\n")
                  .append("- Keyframe camera moving with the car down the highway (location from (-2.8, 10.5, 0.95) at frame 1 to (-2.8, 85.5, 0.95) at frame 60).\n")
                  .append("- Set Track To constraint targeting 'Model_Root'.\n")
                  .append("- Configure 35mm lens with Depth of Field (f/2.8).\n")
                  .append("- Enable Motion Blur in render settings (`scene.render.use_motion_blur = True`, shutter=0.5) to produce authentic speed streaks.\n")
                  .append("- Set color management look using Blender 4.2 AgX enums: `scene.view_settings.look = 'AgX - High Contrast'`. NEVER use legacy 'High Contrast'.\n")
                  .append("- Add low-elevation Sun light (18-25 deg) for golden rim highlights and render MP4 preview.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
        }

        return sb.toString();
    }

    /**
     * Determines whether a path or URI points to a 3D model rather than a 2D image.
     */
    private static boolean is3DModelUri(String uriOrPath) {
        if (uriOrPath == null || uriOrPath.trim().isEmpty()) return false;
        String lower = uriOrPath.toLowerCase(Locale.US);
        return lower.startsWith("model:")
                || lower.endsWith(".fbx")
                || lower.endsWith(".glb")
                || lower.endsWith(".gltf")
                || lower.endsWith(".obj")
                || lower.contains("models_cache");
    }

    /**
     * Extracts a human-readable asset filename from a model URI or path.
     */
    private static String extractModelName(String uriOrPath) {
        if (uriOrPath == null) return "Imported Model";
        String clean = uriOrPath;
        if (clean.startsWith("model:")) clean = clean.substring(6);
        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < clean.length() - 1) {
            clean = clean.substring(lastSlash + 1);
        }
        return clean;
    }

    /**
     * Resolves local file paths, URIs, or base64 strings, downscaling images to max 1024px dimension.
     */
    private String readImageAsBase64(String pathOrUri) {
        if (pathOrUri == null || pathOrUri.trim().isEmpty()) return null;

        String cleanPath = pathOrUri.trim();

        if (cleanPath.startsWith("file://")) {
            cleanPath = cleanPath.substring(7);
        }

        if (cleanPath.startsWith("data:image") && cleanPath.contains("base64,")) {
            return cleanPath.substring(cleanPath.indexOf("base64,") + 7).trim();
        }

        try {
            File imageFile = new File(cleanPath);
            if (!imageFile.exists() || imageFile.length() == 0) {
                if (cleanPath.length() > 100 && !cleanPath.contains(File.separator)) {
                    return cleanPath;
                }
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null;
            }

            int maxDim = Math.max(options.outWidth, options.outHeight);
            int inSampleSize = 1;
            while (maxDim / inSampleSize > 1024) {
                inSampleSize *= 2;
            }

            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);

            if (bitmap == null) return null;

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream);
            byte[] imageBytes = outputStream.toByteArray();
            bitmap.recycle();

            return Base64.encodeToString(imageBytes, Base64.NO_WRAP);

        } catch (Exception e) {
            VynaraLogger.e("DirectorAgent: Error reading reference image: " + e.getMessage());
            return null;
        }
    }
}