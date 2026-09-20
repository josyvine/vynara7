package com.example.engine;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.example.character.Bone;
import com.example.character.Character;
import com.example.character.CharacterSpecification;
import com.example.character.Skeleton;
import com.example.utils.VynaraLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GLTFImporter {
    private static final int GLB_MAGIC = 0x46546C67; // 'glTF' in ASCII Little-Endian
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A; // 'JSON' in ASCII Little-Endian
    private static final int CHUNK_TYPE_BIN = 0x004E4942;  // 'BIN\0' in ASCII Little-Endian

    public static class ImportResult {
        private final List<SceneObject> sceneObjects;
        private final List<Character> characters;

        public ImportResult(List<SceneObject> sceneObjects, List<Character> characters) {
            this.sceneObjects = sceneObjects;
            this.characters = characters;
        }

        public List<SceneObject> getSceneObjects() {
            return sceneObjects;
        }

        public List<Character> getCharacters() {
            return characters;
        }

        public boolean isEmpty() {
            return sceneObjects.isEmpty() && characters.isEmpty();
        }
    }

    public static ImportResult loadFromFile(File glbFile) throws Exception {
        if (glbFile == null || !glbFile.exists()) {
            throw new IllegalArgumentException("Target GLB file does not exist.");
        }
        try (InputStream is = new BufferedInputStream(new FileInputStream(glbFile))) {
            return loadFromStream(is);
        }
    }

    public static ImportResult loadFromStream(InputStream inputStream) throws Exception {
        byte[] fullBytes = readAllBytes(inputStream);
        ByteBuffer buffer = ByteBuffer.wrap(fullBytes).order(ByteOrder.LITTLE_ENDIAN);

        if (buffer.remaining() < 12) {
            throw new IllegalArgumentException("Invalid GLB container: File is too small for standard header.");
        }

        int magic = buffer.getInt();
        if (magic != GLB_MAGIC) {
            throw new IllegalArgumentException("Invalid GLB header magic. Expected 0x46546C67, got: 0x" + Integer.toHexString(magic));
        }

        int version = buffer.getInt();
        int totalLength = buffer.getInt();

        VynaraLogger.system("GLTFImporter: Parsing GLB binary version: " + version + ", bytes: " + totalLength);

        JSONObject jsonMetadata = null;
        byte[] binaryDataChunk = null;

        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 8) break;
            int chunkLength = buffer.getInt();
            int chunkType = buffer.getInt();

            if (chunkLength < 0 || chunkLength > buffer.remaining()) {
                break;
            }

            byte[] chunkData = new byte[chunkLength];
            buffer.get(chunkData);

            if (chunkType == CHUNK_TYPE_JSON && jsonMetadata == null) {
                String jsonStr = new String(chunkData, StandardCharsets.UTF_8);
                jsonMetadata = new JSONObject(jsonStr);
            } else if (chunkType == CHUNK_TYPE_BIN && binaryDataChunk == null) {
                binaryDataChunk = chunkData;
            }
        }

        if (jsonMetadata == null) {
            throw new IllegalArgumentException("Corrupted GLB file: JSON chunk missing.");
        }
        if (binaryDataChunk == null) {
            binaryDataChunk = new byte[0];
        }

        return parseGLTFStructure(jsonMetadata, binaryDataChunk);
    }

    private static ImportResult parseGLTFStructure(JSONObject json, byte[] binaryBuffer) throws Exception {
        List<SceneObject> sceneObjects = new ArrayList<>();
        List<Character> characters = new ArrayList<>();

        JSONArray bufferViewsJson = json.optJSONArray("bufferViews");
        JSONArray accessorsJson = json.optJSONArray("accessors");
        JSONArray meshesJson = json.optJSONArray("meshes");
        JSONArray materialsJson = json.optJSONArray("materials");
        JSONArray nodesJson = json.optJSONArray("nodes");
        JSONArray skinsJson = json.optJSONArray("skins");
        JSONArray imagesJson = json.optJSONArray("images");
        JSONArray texturesJson = json.optJSONArray("textures");
        JSONArray animationsJson = json.optJSONArray("animations");

        // 1. Decode Embedded Image Buffers into Bitmaps
        List<Bitmap> decodedBitmaps = new ArrayList<>();
        if (imagesJson != null && bufferViewsJson != null) {
            for (int i = 0; i < imagesJson.length(); i++) {
                JSONObject imgObj = imagesJson.getJSONObject(i);
                Bitmap bitmap = null;

                if (imgObj.has("bufferView")) {
                    int bvIdx = imgObj.getInt("bufferView");
                    if (bvIdx < bufferViewsJson.length()) {
                        JSONObject bv = bufferViewsJson.getJSONObject(bvIdx);
                        int byteOffset = bv.optInt("byteOffset", 0);
                        int byteLength = bv.getInt("byteLength");

                        if (byteOffset + byteLength <= binaryBuffer.length) {
                            try {
                                BitmapFactory.Options opts = new BitmapFactory.Options();
                                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                                bitmap = BitmapFactory.decodeByteArray(binaryBuffer, byteOffset, byteLength, opts);
                            } catch (OutOfMemoryError oom) {
                                try {
                                    BitmapFactory.Options opts = new BitmapFactory.Options();
                                    opts.inSampleSize = 2;
                                    bitmap = BitmapFactory.decodeByteArray(binaryBuffer, byteOffset, byteLength, opts);
                                } catch (Throwable ignored) {}
                            } catch (Exception e) {
                                VynaraLogger.e("GLTFImporter: Failed decoding embedded texture #" + i, e);
                            }
                        }
                    }
                }
                decodedBitmaps.add(bitmap);
            }
        }

        // 2. Parse Materials & Link Diffuse/Albedo Textures
        List<Material> parsedMaterials = new ArrayList<>();
        if (materialsJson != null) {
            for (int i = 0; i < materialsJson.length(); i++) {
                JSONObject matObj = materialsJson.getJSONObject(i);
                String matName = matObj.optString("name", "Mat_" + i);
                float r = 0.8f, g = 0.8f, b = 0.8f, a = 1.0f;
                float metallic = 0.1f, roughness = 0.5f;
                Bitmap baseTextureBitmap = null;

                String alphaMode = matObj.optString("alphaMode", "OPAQUE");

                JSONObject pbr = matObj.optJSONObject("pbrMetallicRoughness");
                if (pbr != null) {
                    JSONArray baseColorArr = pbr.optJSONArray("baseColorFactor");
                    if (baseColorArr != null && baseColorArr.length() >= 3) {
                        r = (float) baseColorArr.getDouble(0);
                        g = (float) baseColorArr.getDouble(1);
                        b = (float) baseColorArr.getDouble(2);
                        if (baseColorArr.length() >= 4) {
                            a = (float) baseColorArr.getDouble(3);
                        }
                    }
                    metallic = (float) pbr.optDouble("metallicFactor", 0.1);
                    roughness = (float) pbr.optDouble("roughnessFactor", 0.5);

                    JSONObject baseTexObj = pbr.optJSONObject("baseColorTexture");
                    if (baseTexObj != null && texturesJson != null) {
                        int texIdx = baseTexObj.optInt("index", -1);
                        if (texIdx >= 0 && texIdx < texturesJson.length()) {
                            JSONObject texObj = texturesJson.getJSONObject(texIdx);
                            int sourceImgIdx = texObj.optInt("source", -1);
                            if (sourceImgIdx >= 0 && sourceImgIdx < decodedBitmaps.size()) {
                                baseTextureBitmap = decodedBitmaps.get(sourceImgIdx);
                            }
                        }
                    }
                }

                if ("BLEND".equalsIgnoreCase(alphaMode) && a >= 1.0f) {
                    a = 0.85f;
                }

                Material material = new Material("mat_" + i, matName, r, g, b, a);
                material.setMetallic(metallic);
                material.setRoughness(roughness);
                if (baseTextureBitmap != null) {
                    material.setTextureBitmap(baseTextureBitmap);
                }
                parsedMaterials.add(material);
            }
        }

        // 3. Parse Mesh Primitives & Material Indices
        Map<Integer, List<Mesh>> parsedMeshesMap = new HashMap<>();
        Map<Integer, List<Integer>> meshMaterialIndicesMap = new HashMap<>();

        if (meshesJson != null) {
            for (int m = 0; m < meshesJson.length(); m++) {
                JSONObject meshObj = meshesJson.getJSONObject(m);
                JSONArray primitives = meshObj.optJSONArray("primitives");

                List<Mesh> subMeshes = new ArrayList<>();
                List<Integer> matIndices = new ArrayList<>();

                if (primitives != null) {
                    for (int p = 0; p < primitives.length(); p++) {
                        JSONObject prim = primitives.getJSONObject(p);
                        JSONObject attributes = prim.optJSONObject("attributes");

                        float[] positions = null;
                        float[] normals = null;
                        float[] uvs = null;
                        short[] indices = null;

                        if (attributes != null) {
                            if (attributes.has("POSITION")) {
                                int posAccessorIdx = attributes.getInt("POSITION");
                                positions = readFloatAccessor(posAccessorIdx, accessorsJson, bufferViewsJson, binaryBuffer);
                            }

                            if (attributes.has("NORMAL")) {
                                int normAccessorIdx = attributes.getInt("NORMAL");
                                normals = readFloatAccessor(normAccessorIdx, accessorsJson, bufferViewsJson, binaryBuffer);
                            }

                            if (attributes.has("TEXCOORD_0")) {
                                int uvAccessorIdx = attributes.getInt("TEXCOORD_0");
                                uvs = readFloatAccessor(uvAccessorIdx, accessorsJson, bufferViewsJson, binaryBuffer);
                            }
                        }

                        if (prim.has("indices")) {
                            int indicesAccessorIdx = prim.getInt("indices");
                            indices = readShortAccessor(indicesAccessorIdx, accessorsJson, bufferViewsJson, binaryBuffer);
                        }

                        if (positions == null) {
                            positions = new float[]{-0.5f, 0, 0,  0.5f, 0, 0,  0, 1.0f, 0};
                        }
                        if (normals == null) {
                            normals = new float[positions.length];
                            for (int n = 0; n < normals.length; n += 3) {
                                normals[n] = 0; normals[n+1] = 1.0f; normals[n+2] = 0;
                            }
                        }
                        if (uvs == null) {
                            uvs = new float[(positions.length / 3) * 2];
                        }
                        if (indices == null) {
                            int vertexCount = positions.length / 3;
                            indices = new short[vertexCount];
                            for (int s = 0; s < vertexCount; s++) {
                                indices[s] = (short) s;
                            }
                        }

                        Mesh mesh = new Mesh(positions, normals, uvs, indices);
                        subMeshes.add(mesh);

                        int matIdx = prim.optInt("material", -1);
                        matIndices.add(matIdx);
                    }
                }
                parsedMeshesMap.put(m, subMeshes);
                meshMaterialIndicesMap.put(m, matIndices);
            }
        }

        // 4. Parse Bone Skeletons
        Map<Integer, Bone> boneNodeMap = new HashMap<>();
        List<Skeleton> parsedSkeletons = new ArrayList<>();

        if (skinsJson != null && nodesJson != null) {
            for (int s = 0; s < skinsJson.length(); s++) {
                JSONObject skinObj = skinsJson.getJSONObject(s);
                JSONArray joints = skinObj.optJSONArray("joints");

                if (joints != null && joints.length() > 0) {
                    Bone rootBone = null;
                    for (int j = 0; j < joints.length(); j++) {
                        int nodeIdx = joints.getInt(j);
                        JSONObject nodeObj = nodesJson.getJSONObject(nodeIdx);
                        String boneName = nodeObj.optString("name", "bone_" + nodeIdx);

                        Bone bone = new Bone("bone_" + nodeIdx, boneName);
                        boneNodeMap.put(nodeIdx, bone);
                        if (j == 0) {
                            rootBone = bone;
                        }
                    }

                    for (int j = 0; j < joints.length(); j++) {
                        int nodeIdx = joints.getInt(j);
                        JSONObject nodeObj = nodesJson.getJSONObject(nodeIdx);
                        JSONArray children = nodeObj.optJSONArray("children");
                        if (children != null) {
                            Bone parentBone = boneNodeMap.get(nodeIdx);
                            for (int c = 0; c < children.length(); c++) {
                                int childNodeIdx = children.getInt(c);
                                Bone childBone = boneNodeMap.get(childNodeIdx);
                                if (parentBone != null && childBone != null) {
                                    parentBone.addChild(childBone);
                                }
                            }
                        }
                    }

                    if (rootBone != null) {
                        parsedSkeletons.add(new Skeleton(rootBone));
                    }
                }
            }
        }

        // 5. Assemble Scene Nodes with Matching Specific Materials (Build Node Hierarchy)
        Map<Integer, SceneObject> nodeObjectMap = new HashMap<>();
        List<SceneObject> allPrimaryObjects = new ArrayList<>();

        if (nodesJson != null) {
            for (int n = 0; n < nodesJson.length(); n++) {
                JSONObject nodeObj = nodesJson.getJSONObject(n);
                String nodeName = nodeObj.optString("name", "node_" + n);
                String lowerName = nodeName.toLowerCase(Locale.US);

                SceneObject primaryObject = null;

                if (nodeObj.has("mesh")) {
                    int meshIdx = nodeObj.getInt("mesh");
                    List<Mesh> subMeshes = parsedMeshesMap.get(meshIdx);
                    List<Integer> matIndices = meshMaterialIndicesMap.get(meshIdx);

                    if (subMeshes != null && !subMeshes.isEmpty()) {
                        for (int p = 0; p < subMeshes.size(); p++) {
                            Mesh mesh = subMeshes.get(p);
                            int assignedMatIdx = (matIndices != null && p < matIndices.size()) ? matIndices.get(p) : -1;

                            Material mat;
                            if (assignedMatIdx >= 0 && assignedMatIdx < parsedMaterials.size()) {
                                mat = parsedMaterials.get(assignedMatIdx);
                            } else if (!parsedMaterials.isEmpty()) {
                                mat = parsedMaterials.get(0);
                            } else {
                                mat = new Material("mat_def_" + n + "_" + p, "Default", 0.8f, 0.8f, 0.8f, 1.0f);
                            }

                            SceneObject sceneObject = new SceneObject("obj_" + n + "_" + p, nodeName + (p > 0 ? "_sub_" + p : ""), "MESH", mesh, mat);

                            // Auto-hide volumetric fog boxes or domain meshes
                            if (lowerName.contains("fog") || lowerName.contains("volumetric") || lowerName.contains("domain") || lowerName.contains("atmosphere")) {
                                sceneObject.setVisible(false);
                            }

                            if (p == 0) {
                                primaryObject = sceneObject;
                                applyNodeTransformToObject(nodeObj, primaryObject);
                            } else {
                                if (primaryObject != null) {
                                    primaryObject.addChild(sceneObject);
                                }
                            }

                            if (nodeObj.has("skin") && !parsedSkeletons.isEmpty()) {
                                CharacterSpecification spec = new CharacterSpecification("HUMANOID", nodeName);
                                Character character = new Character("char_" + n, spec, sceneObject, parsedSkeletons.get(0));
                                characters.add(character);
                            }
                        }
                    }
                } else {
                    // Create an empty locator / transform node
                    primaryObject = new SceneObject("empty_node_" + n, nodeName, "EMPTY", null, null);
                    applyNodeTransformToObject(nodeObj, primaryObject);
                }

                if (primaryObject != null) {
                    nodeObjectMap.put(n, primaryObject);
                    allPrimaryObjects.add(primaryObject);
                }
            }

            // 5b. Map Parent-Child Relationships Across the Entire Scene Graph
            for (int n = 0; n < nodesJson.length(); n++) {
                JSONObject nodeObj = nodesJson.getJSONObject(n);
                JSONArray children = nodeObj.optJSONArray("children");
                if (children != null) {
                    SceneObject parentObj = nodeObjectMap.get(n);
                    if (parentObj != null) {
                        for (int c = 0; c < children.length(); c++) {
                            int childNodeIdx = children.getInt(c);
                            SceneObject childObj = nodeObjectMap.get(childNodeIdx);
                            if (childObj != null) {
                                parentObj.addChild(childObj);
                            }
                        }
                    }
                }
            }

            // 5c. Parse glTF Animation Channels directly to their targeted SceneObjects
            if (animationsJson != null && accessorsJson != null && bufferViewsJson != null) {
                for (int a = 0; a < animationsJson.length(); a++) {
                    JSONObject animObj = animationsJson.optJSONObject(a);
                    if (animObj == null) continue;

                    JSONArray samplers = animObj.optJSONArray("samplers");
                    JSONArray channels = animObj.optJSONArray("channels");

                    if (samplers != null && channels != null) {
                        for (int c = 0; c < channels.length(); c++) {
                            JSONObject channel = channels.optJSONObject(c);
                            if (channel == null) continue;

                            JSONObject target = channel.optJSONObject("target");
                            if (target == null) continue;

                            int nodeIdx = target.optInt("node", -1);
                            String path = target.optString("path", "");
                            int samplerIdx = channel.optInt("sampler", -1);

                            if (nodeIdx >= 0 && samplerIdx >= 0 && samplerIdx < samplers.length()) {
                                SceneObject targetObj = nodeObjectMap.get(nodeIdx);
                                if (targetObj == null) continue;

                                JSONObject sampler = samplers.optJSONObject(samplerIdx);
                                if (sampler == null) continue;

                                int inputAccessorIdx = sampler.optInt("input", -1);
                                int outputAccessorIdx = sampler.optInt("output", -1);

                                if (inputAccessorIdx >= 0 && outputAccessorIdx >= 0) {
                                    float[] times = readFloatAccessor(inputAccessorIdx, accessorsJson, bufferViewsJson, binaryBuffer);
                                    float[] rawValues = readFloatAccessor(outputAccessorIdx, accessorsJson, bufferViewsJson, binaryBuffer);

                                    if (times != null && rawValues != null && times.length > 0) {
                                        if ("rotation".equalsIgnoreCase(path)) {
                                            int numKeys = times.length;
                                            float[] finalValues = new float[numKeys * 3];
                                            for (int k = 0; k < numKeys; k++) {
                                                int qOffset = k * 4;
                                                if (qOffset + 3 < rawValues.length) {
                                                    float qx = rawValues[qOffset];
                                                    float qy = rawValues[qOffset + 1];
                                                    float qz = rawValues[qOffset + 2];
                                                    float qw = rawValues[qOffset + 3];
                                                    float[] euler = quaternionToEulerDegrees(qx, qy, qz, qw);
                                                    finalValues[k * 3] = euler[0];
                                                    finalValues[k * 3 + 1] = euler[1];
                                                    finalValues[k * 3 + 2] = euler[2];
                                                }
                                            }
                                            // Unroll angles across consecutive keyframes to prevent Gimbal Lock flips
                                            for (int k = 1; k < numKeys; k++) {
                                                for (int axis = 0; axis < 3; axis++) {
                                                    float diff = finalValues[k * 3 + axis] - finalValues[(k - 1) * 3 + axis];
                                                    while (diff > 180.0f) {
                                                        finalValues[k * 3 + axis] -= 360.0f;
                                                        diff = finalValues[k * 3 + axis] - finalValues[(k - 1) * 3 + axis];
                                                    }
                                                    while (diff < -180.0f) {
                                                        finalValues[k * 3 + axis] += 360.0f;
                                                        diff = finalValues[k * 3 + axis] - finalValues[(k - 1) * 3 + axis];
                                                    }
                                                }
                                            }
                                            targetObj.addAnimationTrack("rotation", times, finalValues);
                                        } else if ("translation".equalsIgnoreCase(path)) {
                                            targetObj.addAnimationTrack("translation", times, rawValues);
                                        } else if ("scale".equalsIgnoreCase(path)) {
                                            targetObj.addAnimationTrack("scale", times, rawValues);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5d. Only return Root-level SceneObjects (nested children are rendered recursively via matrix hierarchy)
            for (SceneObject obj : allPrimaryObjects) {
                if (obj.getParent() == null) {
                    sceneObjects.add(obj);
                }
            }
        }

        // Fallback safety mapping if node structure parser was bypassed
        if (sceneObjects.isEmpty() && characters.isEmpty() && !parsedMeshesMap.isEmpty()) {
            for (Map.Entry<Integer, List<Mesh>> entry : parsedMeshesMap.entrySet()) {
                int i = entry.getKey();
                List<Mesh> subMeshes = entry.getValue();
                List<Integer> matIndices = meshMaterialIndicesMap.get(i);

                if (subMeshes != null) {
                    for (int p = 0; p < subMeshes.size(); p++) {
                        int assignedMatIdx = (matIndices != null && p < matIndices.size()) ? matIndices.get(p) : -1;
                        Material mat = (assignedMatIdx >= 0 && assignedMatIdx < parsedMaterials.size()) 
                                ? parsedMaterials.get(assignedMatIdx) 
                                : (parsedMaterials.isEmpty() ? new Material("mat_def", "Default", 0.8f, 0.8f, 0.8f, 1.0f) : parsedMaterials.get(0));

                        SceneObject obj = new SceneObject("imported_obj_" + i + "_" + p, "Imported Mesh " + i + " Primitive " + p, "MESH", subMeshes.get(p), mat);
                        sceneObjects.add(obj);
                    }
                }
            }
        }

        VynaraLogger.system("GLTFImporter: Import complete (" + sceneObjects.size() + " root objects, " + characters.size() + " rigged characters)");
        return new ImportResult(sceneObjects, characters);
    }

    private static float[] readFloatAccessor(int accessorIndex, JSONArray accessors, JSONArray bufferViews, byte[] binaryData) throws Exception {
        JSONObject accessor = accessors.getJSONObject(accessorIndex);
        int count = accessor.getInt("count");
        String type = accessor.getString("type");
        int bufferViewIndex = accessor.getInt("bufferView");
        int byteOffset = accessor.optInt("byteOffset", 0);

        JSONObject bufferView = bufferViews.getJSONObject(bufferViewIndex);
        int viewByteOffset = bufferView.optInt("byteOffset", 0);

        int componentsPerElement = getComponentCount(type);
        float[] result = new float[count * componentsPerElement];

        ByteBuffer bb = ByteBuffer.wrap(binaryData).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(viewByteOffset + byteOffset);

        for (int i = 0; i < result.length; i++) {
            result[i] = bb.getFloat();
        }
        return result;
    }

    private static short[] readShortAccessor(int accessorIndex, JSONArray accessors, JSONArray bufferViews, byte[] binaryData) throws Exception {
        JSONObject accessor = accessors.getJSONObject(accessorIndex);
        int count = accessor.getInt("count");
        int componentType = accessor.getInt("componentType");
        int bufferViewIndex = accessor.getInt("bufferView");
        int byteOffset = accessor.optInt("byteOffset", 0);

        JSONObject bufferView = bufferViews.getJSONObject(bufferViewIndex);
        int viewByteOffset = bufferView.optInt("byteOffset", 0);

        short[] result = new short[count];
        ByteBuffer bb = ByteBuffer.wrap(binaryData).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(viewByteOffset + byteOffset);

        for (int i = 0; i < count; i++) {
            if (componentType == 5123) { // UNSIGNED_SHORT
                result[i] = (short) (bb.getShort() & 0xFFFF);
            } else if (componentType == 5125) { // UNSIGNED_INT
                long intVal = bb.getInt() & 0xFFFFFFFFL;
                result[i] = (short) (intVal & 0xFFFF);
            } else if (componentType == 5121) { // UNSIGNED_BYTE
                result[i] = (short) (bb.get() & 0xFF);
            } else {
                result[i] = bb.getShort();
            }
        }
        return result;
    }

    private static int getComponentCount(String type) {
        switch (type) {
            case "SCALAR": return 1;
            case "VEC2": return 2;
            case "VEC3": return 3;
            case "VEC4":
            case "MAT2": return 4;
            case "MAT3": return 9;
            case "MAT4": return 16;
            default: return 1;
        }
    }

    private static void applyNodeTransformToObject(JSONObject nodeObj, SceneObject object) {
        Transform transform = object.getTransform();
        if (transform == null) return;

        // 1. Full 4x4 Column-Major Matrix Decomposition (Crucial for Assimp converted FBX models)
        JSONArray matrix = nodeObj.optJSONArray("matrix");
        if (matrix != null && matrix.length() >= 16) {
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) {
                m[i] = (float) matrix.optDouble(i, (i % 5 == 0) ? 1.0 : 0.0);
            }

            // Translation vector (column 3: indices 12, 13, 14)
            float px = m[12];
            float py = m[13];
            float pz = m[14];

            // Scale is column vectors magnitudes
            float sx = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
            float sy = (float) Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]);
            float sz = (float) Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);

            // Extract normalized rotation matrix
            float r00 = sx > 0.0001f ? m[0] / sx : 1.0f;
            float r10 = sx > 0.0001f ? m[1] / sx : 0.0f;
            float r20 = sx > 0.0001f ? m[2] / sx : 0.0f;

            float r01 = sy > 0.0001f ? m[4] / sy : 0.0f;
            float r11 = sy > 0.0001f ? m[5] / sy : 1.0f;
            float r21 = sy > 0.0001f ? m[6] / sy : 0.0f;

            float r02 = sz > 0.0001f ? m[8] / sz : 0.0f;
            float r12 = sz > 0.0001f ? m[9] / sz : 0.0f;
            float r22 = sz > 0.0001f ? m[10] / sz : 1.0f;

            // Convert 3x3 rotation matrix to Euler angles (degrees)
            float rx, ry, rz;
            if (Math.abs(r20) < 0.99999f) {
                ry = (float) -Math.asin(r20);
                rx = (float) Math.atan2(r21 / Math.cos(ry), r22 / Math.cos(ry));
                rz = (float) Math.atan2(r10 / Math.cos(ry), r00 / Math.cos(ry));
            } else {
                rz = 0.0f;
                if (r20 <= -0.99999f) {
                    ry = (float) (Math.PI / 2.0);
                    rx = (float) Math.atan2(r01, r02);
                } else {
                    ry = (float) (-Math.PI / 2.0);
                    rx = (float) Math.atan2(-r01, -r02);
                }
            }

            transform.setPosition(px, py, pz);
            transform.setRotation((float) Math.toDegrees(rx), (float) Math.toDegrees(ry), (float) Math.toDegrees(rz));
            transform.setScale(sx, sy, sz);
            return;
        }

        // 2. Standard TRS Properties
        JSONArray translation = nodeObj.optJSONArray("translation");
        if (translation != null && translation.length() >= 3) {
            transform.setPosition(
                    (float) translation.optDouble(0, 0.0),
                    (float) translation.optDouble(1, 0.0),
                    (float) translation.optDouble(2, 0.0)
            );
        }

        JSONArray rotation = nodeObj.optJSONArray("rotation");
        if (rotation != null && rotation.length() >= 4) {
            float qx = (float) rotation.optDouble(0, 0.0);
            float qy = (float) rotation.optDouble(1, 0.0);
            float qz = (float) rotation.optDouble(2, 0.0);
            float qw = (float) rotation.optDouble(3, 1.0);

            float[] euler = quaternionToEulerDegrees(qx, qy, qz, qw);
            transform.setRotation(euler[0], euler[1], euler[2]);
        }

        JSONArray scale = nodeObj.optJSONArray("scale");
        if (scale != null && scale.length() >= 3) {
            transform.setScale(
                    (float) scale.optDouble(0, 1.0),
                    (float) scale.optDouble(1, 1.0),
                    (float) scale.optDouble(2, 1.0)
            );
        }
    }

    private static float[] quaternionToEulerDegrees(float x, float y, float z, float w) {
        float[] euler = new float[3];

        double sinr_cosp = 2.0 * (w * x + y * z);
        double cosr_cosp = 1.0 - 2.0 * (x * x + y * y);
        euler[0] = (float) Math.toDegrees(Math.atan2(sinr_cosp, cosr_cosp));

        double sinp = 2.0 * (w * y - z * x);
        if (Math.abs(sinp) >= 1.0) {
            euler[1] = (float) Math.toDegrees(Math.copySign(Math.PI / 2.0, sinp));
        } else {
            euler[1] = (float) Math.toDegrees(Math.asin(sinp));
        }

        double cosy_cosp = 2.0 * (w * z + x * y);
        double siny_cosp = 1.0 - 2.0 * (y * y + z * z);
        euler[2] = (float) Math.toDegrees(Math.atan2(siny_cosp, cosy_cosp));

        return euler;
    }

    private static byte[] readAllBytes(InputStream inputStream) throws Exception {
        byte[] buffer = new byte[16384];
        int bytesRead;
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        return outputStream.toByteArray();
    }
}