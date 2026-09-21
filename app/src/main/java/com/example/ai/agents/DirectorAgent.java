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
     * Phase 1: Formulates the comprehensive dynamic 4-worker scene specification using Gemini Vision.
     * Deconstructs any prompt and reference into custom procedural curves, terrains, scales, and timelines.
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
            promptBuilder.append("ACTIVE 3D MODEL ATTACHED: The scene contains an imported 3D asset: ")
                         .append(String.join(", ", attached3DModels))
                         .append(". Worker 1 will import the model from 'inputs/input_model.glb' (or 'input_model.glb').\n")
                         .append("UNIVERSAL SPATIAL & KINEMATIC RULES TO DECOMPOSE DYNAMICALLY:\n")
                         .append("1. CONTEXTUAL SCALE: Identify the subject category from the prompt and model name (humanoid, creature, prop, product, architecture, vehicle, aircraft). Set realistic real-world target dimensions (e.g. mug=0.1m, human=1.8m, car=4.5m, building=30m).\n")
                         .append("2. ENVIRONMENT & STAGING: Design surrounding geometry, terrain, or studio backdrop matching the prompt (pedestal, room, natural terrain, street, open landscape, or infinite studio plane).\n")
                         .append("3. GROUNDING & MOTION: Snap the asset's lowest point flush to surface at Z=0.0, bind to master 'Model_Root', and define movement/animation matching user prompt across the full timeline.\n")
                         .append("4. CINEMATIC SHOT: Define camera lens, focal length, tracking style (e.g. turntable orbit, action tracking, cinematic dolly, aerial view), and atmospheric lighting.\n");
        }

        if (!base64Images.isEmpty()) {
            promptBuilder.append("VISUAL REFERENCE ATTACHED: Inspect the attached reference image(s). ")
                         .append("Deconstruct the actual physical geometry, contours, surfaces, materials, and lighting atmosphere.\n");
        }

        VynaraLogger.system("DirectorAgent: Formulating dynamic 4-Worker scene spec via Gemini Vision [" + activeModel + "] with " + base64Images.size() + " image(s)...");

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
                            
                            VynaraLogger.system("DirectorAgent: Dynamic multi-agent specification formulated for [" + spec.getSubjectCategory() + " / " + spec.getPathType() + "].");
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
     * Autonomous Asset Pipeline: Generates a complete cinematic production spec for any imported asset.
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
        promptBuilder.append("DIRECTIVE: Generate a universal cinematic production plan matching the prompt. ")
                     .append("Define real-world scale normalization, surrounding environment/staging, dynamic camera tracking, and lighting.");

        VynaraLogger.system("DirectorAgent: Formulating autonomous asset spec for [" + modelName + "]...");

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

                            VynaraLogger.system("DirectorAgent: Autonomous spec ready for [" + spec.getSubjectCategory() + "].");
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
        return "You are the Universal 3D Master Art Director & Spatial Architect for Vynara 3D Studio.\n" +
                "YOUR ROLE:\n" +
                "- You analyze the user's prompt, imported 3D asset metadata, and reference photos to decompose any subject into an unconstrained, highly dynamic 4-Worker specification.\n" +
                "- Never force arbitrary vehicle highways or road rigs unless a vehicle/road is explicitly requested by the prompt. Adapt the environment, scale, and camera to the subject naturally (e.g. tabletop for props, room for furniture, landscape for nature, city/studio for characters).\n\n" +
                "DYNAMIC DIRECTIVES:\n" +
                "1. Subject & Scale Normalization:\n" +
                "   - Classify subject category (humanoid, creature, prop, product, architecture, vehicle, aircraft).\n" +
                "   - Determine real-world target dimension in meters based on the actual object (e.g. coffee mug = 0.1m, shoe = 0.3m, sword = 1.0m, human = 1.8m, car = 4.5m, building = 30m).\n" +
                "2. Environment & Staging Geometry:\n" +
                "   - Define pathType ('pedestal', 'studio_backdrop', 'spline_curve', 'terrain_path', 'architectural_interior', 'open_expanse').\n" +
                "   - Set pathLengthMeters based on prompt scale (e.g. 5m for room/pedestal, 50m for street, 500m for long pursuit).\n" +
                "   - Set terrainType ('studio', 'room_interior', 'outdoor_ground', 'nature_landscape', 'cyber_city', 'coastal_cliff').\n" +
                "3. Timeline & Motion Dynamics:\n" +
                "   - Define animation duration in seconds and frame count based on prompt intent (e.g. 2.0s = 48-60 frames for turntable, 5.0s = 120-150 frames for cinematic action).\n" +
                "   - Define camera tracking style ('turntable_orbit', 'action_follow', 'cinematic_dolly', 'stationary_pan', 'drone_overhead').\n" +
                "4. Optics & Shading:\n" +
                "   - Conforming to Blender 4.2+ Principled BSDF socket names ('Base Color' RGBA, 'Metallic', 'Roughness') and AgX color science.\n\n" +
                "OUTPUT RAW STRICT JSON ONLY (NO MARKDOWN FENCES):\n" +
                "{\n" +
                "  \"sceneType\": \"string\",\n" +
                "  \"mood\": \"string\",\n" +
                "  \"visualStyleNotes\": \"string\",\n" +
                "  \"subjectCategory\": \"humanoid | creature | prop | product | architecture | vehicle | aircraft\",\n" +
                "  \"targetSubjectLengthMeters\": 1.8,\n" +
                "  \"pathType\": \"pedestal | studio_backdrop | spline_curve | terrain_path | architectural_interior\",\n" +
                "  \"pathLengthMeters\": 10.0,\n" +
                "  \"pathWidthMeters\": 5.0,\n" +
                "  \"terrainType\": \"studio | room_interior | outdoor_ground | nature_landscape | cyber_city\",\n" +
                "  \"terrainElevationMeters\": 0.0,\n" +
                "  \"animationDurationSeconds\": 3.0,\n" +
                "  \"totalFrames\": 72,\n" +
                "  \"fps\": 24,\n" +
                "  \"cameraTrackingStyle\": \"turntable_orbit | action_follow | cinematic_dolly | stationary_pan | drone_overhead\",\n" +
                "  \"cameraHeightMeters\": 1.2,\n" +
                "  \"cameraDistanceMeters\": 4.0,\n" +
                "  \"workers\": {\n" +
                "    \"w1_structure\": \"string description for Worker 1\",\n" +
                "    \"w2_details\": \"string description for Worker 2\",\n" +
                "    \"w3_materials\": \"string description for Worker 3\",\n" +
                "    \"w4_cinematics\": \"string description for Worker 4\"\n" +
                "  },\n" +
                "  \"camera\": {\n" +
                "    \"focalLengthMm\": 50.0,\n" +
                "    \"apertureFStop\": 2.8,\n" +
                "    \"focusDistance\": 4.0,\n" +
                "    \"position\": [0.0, -4.0, 1.5],\n" +
                "    \"target\": [0.0, 0.0, 0.8]\n" +
                "  },\n" +
                "  \"lighting\": {\n" +
                "    \"useVolumetrics\": false,\n" +
                "    \"volumetricDensity\": 0.01,\n" +
                "    \"sunElevation\": 45.0,\n" +
                "    \"sunAzimuth\": 30.0,\n" +
                "    \"sunIntensity\": 4.5,\n" +
                "    \"ambientColorHex\": \"#303030\"\n" +
                "  },\n" +
                "  \"palette\": {\n" +
                "    \"primaryColorHex\": \"#FFFFFF\",\n" +
                "    \"secondaryColorHex\": \"#333333\",\n" +
                "    \"accentColorHex\": \"#00E5FF\"\n" +
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
     * Generates a targeted procedural code-synthesis instruction for each specialized worker agent.
     */
    public static String buildWorkerPrompt(AIDirectorSpec spec, int workerIndex, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("SCENE GOAL: ").append(userPrompt).append("\n");
        if (spec != null) {
            sb.append("SUBJECT: ").append(spec.getSubjectCategory())
              .append(" (Target Size: ").append(spec.getTargetSubjectLengthMeters()).append("m)\n");
            sb.append("ENVIRONMENT: ").append(spec.getPathType())
              .append(" (Length: ").append(spec.getPathLengthMeters()).append("m, Terrain: ")
              .append(spec.getTerrainType()).append(")\n");
            sb.append("TIMELINE: ").append(spec.getAnimationDurationSeconds())
              .append("s (Frames: 1 to ").append(spec.getTotalFrames()).append(" @ ").append(spec.getFps()).append("fps)\n");
            sb.append("PALETTE: Primary=").append(spec.getPrimaryColorHex())
              .append(", Secondary=").append(spec.getSecondaryColorHex()).append("\n");
        }

        int totalFrames = (spec != null) ? spec.getTotalFrames() : 72;
        float subSize = (spec != null) ? spec.getTargetSubjectLengthMeters() : 1.8f;
        String trackingStyle = (spec != null && spec.getCameraTrackingStyle() != null) ? spec.getCameraTrackingStyle() : "turntable_orbit";

        switch (workerIndex) {
            case 1: // Worker 1: Core Structure, Real-World Sizing & Environment Staging
                sb.append("\nTASK: WORKER 1 (STRUCTURE, CONTEXTUAL SCALE & ENVIRONMENT STAGING)\n")
                  .append("- If an imported 3D model exists in 'inputs/', import it using `bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')` (or 'input_model.glb').\n")
                  .append("- NORMALIZE SIZE: Compute combined bounding box across all imported sub-meshes. Scale assembly so its primary dimension matches the target size of ")
                  .append(subSize).append(" meters. Bake transforms using `bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)`.\n")
                  .append("- ENVIRONMENT GEOMETRY: Build staging geometry (pedestal, ground plane, room, or terrain) matching the prompt. Ensure contact base sits flush at Z = 0.0.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 2: // Worker 2: Placement, Master Root Parenting & Kinetic Animation
                sb.append("\nTASK: WORKER 2 (PLACEMENT, PARENTING & DYNAMIC ANIMATION)\n")
                  .append("- PLACEMENT: Snap bottom-most contact point of asset flush to surface at Z=0.0.\n")
                  .append("- PARENTING: Create master Empty object 'Model_Root' at (0, 0, 0). Parent all asset sub-meshes keeping relative offsets intact.\n")
                  .append("- ANIMATION: Keyframe animation from frame 1 to frame ").append(totalFrames)
                  .append(" matching the prompt intent (e.g. 360 rotation for turntables, forward translation for moving subjects, or bone keyframes for rigs).\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 3: // Worker 3: PBR Materials & Shaders
                sb.append("\nTASK: WORKER 3 (PBR MATERIALS & SHADERS)\n")
                  .append("- Configure Principled BSDF materials using Blender 4.2+ socket names: 'Base Color' (4-element RGBA tuple: r, g, b, 1.0), 'Metallic', 'Roughness'.\n")
                  .append("- Apply authentic PBR materials to environment and prop surfaces matching the requested visual style.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 4: // Worker 4: Cinematics, Camera Optics, Motion Blur & Lighting
            default:
                sb.append("\nTASK: WORKER 4 (CINEMATICS, CAMERA TRACKING & LIGHTING)\n")
                  .append("- CAMERA SETUP: Position camera to showcase the subject using tracking style '").append(trackingStyle)
                  .append("'. Add a `TRACK_TO` constraint targeting 'Model_Root' if tracking or orbiting.\n")
                  .append("- Animate camera from frame 1 to frame ").append(totalFrames).append(".\n")
                  .append("- Configure lens, Depth of Field, and motion blur (`scene.render.use_motion_blur = True`, `scene.render.motion_blur_shutter = 0.5`).\n")
                  .append("- LIGHTING: Enable World background nodes and position key lights to illuminate the subject with professional 3-point or ambient studio lighting.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
        }

        return sb.toString();
    }

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