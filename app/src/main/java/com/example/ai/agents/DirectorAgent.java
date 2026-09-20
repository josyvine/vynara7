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
                         .append("SPATIAL & KINEMATIC RULES TO DECOMPOSE DYNAMICALLY:\n")
                         .append("1. CONTEXTUAL SCALE: Identify the subject category from the prompt and model name (supercar, airplane, boat, humanoid, creature, prop). Set real-world target length.\n")
                         .append("2. ENVIRONMENT & PATH: If a road/track/runway/terrain is requested, define pathType, length (e.g. 500m, 1000m, 5000m), terrain elevation, and curvature (Bézier curves, hairpin bends, coastal cliffs, etc.).\n")
                         .append("3. GROUNDING & MOTION: Snap asset flush to surface at start position, bind to master 'Model_Root', and animate displacement matching user prompt across the full timeline.\n")
                         .append("4. CINEMATIC SHOT: Define camera lens, focal length, tracking style (e.g. low-angle tarmac pursuit at 15cm height, drone overhead, 3/4 chase), and lighting.\n");
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
        promptBuilder.append("DIRECTIVE: Generate an expansive cinematic production plan matching the prompt. ")
                     .append("Extract custom path curves (Bézier curves for sweeping or hairpin roads, runways, stages), ")
                     .append("custom length (e.g. 1km - 5km), real-world scale normalization, dynamic camera tracking, and lighting.");

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
        return "You are the 3D Master Art Director & Spatial Architect for Vynara 3D Studio.\n" +
                "YOUR ROLE:\n" +
                "- You analyze the user's prompt, imported 3D asset metadata, and reference photos to decompose the scene into an unconstrained, highly dynamic 4-Worker specification.\n" +
                "- NEVER force a generic 250m flat straight box. You have full freedom to design 1km to 5km+ sweeping expressways, hairpin mountain passes, coastal cliffs, airport runways, ocean waters, or cyberpunk cityscapes based on the prompt.\n\n" +
                "DYNAMIC DIRECTIVES:\n" +
                "1. Subject & Scale Normalization:\n" +
                "   - Classify subject category (supercar, airplane, boat, humanoid, creature, architecture, prop).\n" +
                "   - Determine real-world target dimension (e.g. 4.5m car, 60m jet, 1.8m humanoid, 15m boat).\n" +
                "2. Environment & Path Geometry:\n" +
                "   - Define pathType ('spline_curve', 'hairpin_mountain', 'coastal_track', 'airport_runway', 'open_sky', 'pedestal').\n" +
                "   - Set pathLengthMeters based on prompt (e.g. 1000m for expressway, 5000m for long mountain drive).\n" +
                "   - Set terrainType ('coastal_cliff', 'mountain_pass', 'desert', 'cyber_city', 'studio').\n" +
                "3. Timeline & Motion Dynamics:\n" +
                "   - Define animation duration (e.g. 5.0s, 10.0s, 150-300 frames @ 30fps) allowing high-speed motion to unfold.\n" +
                "   - Define camera tracking style (e.g. ground-level chase at 0.15m height alongside rear wheel, drone overhead, 3/4 chase).\n" +
                "4. Optics & Shading:\n" +
                "   - Conforming to Blender 4.2+ Principled BSDF socket names and AgX color science.\n\n" +
                "OUTPUT RAW STRICT JSON ONLY (NO MARKDOWN FENCES):\n" +
                "{\n" +
                "  \"sceneType\": \"string\",\n" +
                "  \"mood\": \"string\",\n" +
                "  \"visualStyleNotes\": \"string\",\n" +
                "  \"subjectCategory\": \"supercar | aircraft | marine | humanoid | architecture | prop\",\n" +
                "  \"targetSubjectLengthMeters\": 4.5,\n" +
                "  \"pathType\": \"spline_curve | hairpin_mountain | coastal_track | airport_runway | pedestal\",\n" +
                "  \"pathLengthMeters\": 1000.0,\n" +
                "  \"pathWidthMeters\": 14.0,\n" +
                "  \"terrainType\": \"coastal_cliff | mountain_pass | desert | cyber_city | studio\",\n" +
                "  \"terrainElevationMeters\": 25.0,\n" +
                "  \"animationDurationSeconds\": 5.0,\n" +
                "  \"totalFrames\": 150,\n" +
                "  \"fps\": 30,\n" +
                "  \"cameraTrackingStyle\": \"ground_level_chase | drone_overhead | orbit | flyby\",\n" +
                "  \"cameraHeightMeters\": 0.20,\n" +
                "  \"cameraDistanceMeters\": 6.5,\n" +
                "  \"workers\": {\n" +
                "    \"w1_structure\": \"string description for Worker 1\",\n" +
                "    \"w2_details\": \"string description for Worker 2\",\n" +
                "    \"w3_materials\": \"string description for Worker 3\",\n" +
                "    \"w4_cinematics\": \"string description for Worker 4\"\n" +
                "  },\n" +
                "  \"camera\": {\n" +
                "    \"focalLengthMm\": 35.0,\n" +
                "    \"apertureFStop\": 2.8,\n" +
                "    \"focusDistance\": 5.0,\n" +
                "    \"position\": [-3.8, -7.0, 2.0],\n" +
                "    \"target\": [0.0, 0.0, 0.8]\n" +
                "  },\n" +
                "  \"lighting\": {\n" +
                "    \"useVolumetrics\": true,\n" +
                "    \"volumetricDensity\": 0.015,\n" +
                "    \"sunElevation\": 18.0,\n" +
                "    \"sunAzimuth\": 45.0,\n" +
                "    \"sunIntensity\": 6.0,\n" +
                "    \"ambientColorHex\": \"#202835\"\n" +
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

        float pathLen = (spec != null) ? spec.getPathLengthMeters() : 1000.0f;
        int totalFrames = (spec != null) ? spec.getTotalFrames() : 150;
        float subSize = (spec != null) ? spec.getTargetSubjectLengthMeters() : 4.5f;

        switch (workerIndex) {
            case 1: // Worker 1: Core Structure, Real-World Sizing & Custom Path/Road
                sb.append("\nTASK: WORKER 1 (STRUCTURE, CONTEXTUAL SCALE & PATH GEOMETRY)\n")
                  .append("- If an imported 3D model exists in 'inputs/', import it using `bpy.ops.import_scene.gltf(filepath='inputs/input_model.glb')` (or 'input_model.glb').\n")
                  .append("- NORMALIZE SIZE: Compute combined bounding box across all imported sub-meshes. Scale assembly so total length is exactly ")
                  .append(subSize).append(" meters. Bake transforms using `bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)`.\n")
                  .append("- ENVIRONMENT GEOMETRY: Build dynamic road/runway/path matching the prompt. If curves/hairpins or sweeping expressways are requested, generate a Blender Bézier curve spline (`bpy.data.curves.new`) extruded to ")
                  .append(pathLen).append(" meters with thickness to prevent Z-fighting.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 2: // Worker 2: Placement, Master Root Parenting & Kinetic Animation
                sb.append("\nTASK: WORKER 2 (PLACEMENT, PARENTING & DYNAMIC ANIMATION)\n")
                  .append("- PLACEMENT: Snap bottom-most contact point of asset flush to surface at Z=0.0, centered in lane.\n")
                  .append("- PARENTING: Create master Empty object 'Model_Root' at (0, 0, 0) with clean (1,1,1) scale. Parent all asset sub-meshes keeping relative assembly intact.\n")
                  .append("- ANIMATION: Animate 'Model_Root' moving forward from frame 1 to frame ").append(totalFrames)
                  .append(" along the custom path distance with linear/eased interpolation.\n")
                  .append("- SUB-PART KINEMATICS: If wheels, propellers, or rotors exist, bake rotational keyframes per frame to prevent quaternion interpolation jitter.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 3: // Worker 3: PBR Materials & Shaders
                sb.append("\nTASK: WORKER 3 (PBR MATERIALS & SHADERS)\n")
                  .append("- Configure Principled BSDF materials using Blender 4.2+ socket names ('Transmission Weight', 'Roughness', 'Metallic', 'Base Color').\n")
                  .append("- Create high-detail micro-textures on asphalt, pavement, paint, metals, glass, or organic surfaces.\n")
                  .append("- Output raw Blender Python code inside ```python.");
                break;
            case 4: // Worker 4: Cinematics, Camera Optics, Motion Blur & Lighting
            default:
                sb.append("\nTASK: WORKER 4 (CINEMATICS, CAMERA TRACKING & LIGHTING)\n")
                  .append("- CAMERA SETUP: Position camera in world coordinates matching prompt style (e.g. low-angle chase, drone overhead) with a `TRACK_TO` constraint targeting 'Model_Root'.\n")
                  .append("- Animate camera moving alongside the subject from frame 1 to frame ").append(totalFrames).append(".\n")
                  .append("- Configure lens (35mm / 20mm anamorphic), Depth of Field (f/2.8), and optical motion blur (`scene.render.use_motion_blur = True`).\n")
                  .append("- LIGHTING: Enable World background nodes with ambient sky radiance (`bpy.context.scene.world.use_nodes = True`) and add high-energy Sun light (energy >= 5.5).\n")
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