package com.example.engine;

import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SceneObject {
    private String id;
    private String name;
    private String semanticType; // PRIMITIVE, STRUCTURE, HOUSE, SOFA, CHARACTER, CREATURE, LIGHT, CAMERA, EMPTY
    private Transform transform;
    private Mesh mesh;
    private Material material;
    private boolean isVisible = true;
    private boolean isSelected = false;

    private SceneObject parent;
    private final List<SceneObject> children = new ArrayList<>();

    // Animation Track Support (for keyframed transform motion: translation, rotation, scale)
    public static class KeyframeTrack {
        public final String path; // "translation", "rotation", "scale"
        public final float[] times;
        public final float[] values; // 3 floats per keyframe for translation/scale, or Euler degrees for rotation

        public KeyframeTrack(String path, float[] times, float[] values) {
            this.path = path != null ? path.toLowerCase(Locale.US) : "translation";
            this.times = times;
            this.values = values;
        }
    }

    private final List<KeyframeTrack> animationTracks = new ArrayList<>();
    private Transform restTransform;
    private final float[] worldMatrix = new float[16];

    public SceneObject(String id, String name, String semanticType, Mesh mesh, Material material) {
        this.id = id != null ? id : "obj_" + System.currentTimeMillis();
        this.name = name != null ? name : "Scene Node";
        this.semanticType = semanticType != null ? semanticType : "PRIMITIVE";
        this.mesh = mesh;
        this.material = material;
        this.transform = new Transform();
        Matrix.setIdentityM(this.worldMatrix, 0);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getSemanticType() { return semanticType; }
    public Transform getTransform() { return transform; }
    public Mesh getMesh() { return mesh; }
    public Material getMaterial() { return material; }
    public boolean isVisible() { return isVisible; }
    public boolean isSelected() { return isSelected; }

    public void setName(String name) { this.name = name; }
    public void setSemanticType(String semanticType) { this.semanticType = semanticType; }
    public void setMesh(Mesh mesh) { this.mesh = mesh; }
    public void setMaterial(Material material) { this.material = material; }
    public void setVisible(boolean visible) { isVisible = visible; }
    public void setSelected(boolean selected) { isSelected = selected; }
    public void setTransform(Transform transform) {
        if (transform != null) {
            this.transform = transform;
        }
    }

    public void addChild(SceneObject child) {
        if (child != null && !children.contains(child)) {
            child.parent = this;
            children.add(child);
        }
    }

    public void removeChild(SceneObject child) {
        if (child != null) {
            child.parent = null;
            children.remove(child);
        }
    }

    public List<SceneObject> getChildren() { return children; }
    public SceneObject getParent() { return parent; }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    public boolean isRoot() {
        return parent == null;
    }

    // --- Animation Track API ---

    public void addAnimationTrack(String targetPath, float[] times, float[] values) {
        if (targetPath != null && times != null && values != null && times.length > 0) {
            animationTracks.add(new KeyframeTrack(targetPath, times, values));
            if (restTransform == null && transform != null) {
                restTransform = new Transform();
                restTransform.setPosition(transform.getPx(), transform.getPy(), transform.getPz());
                restTransform.setRotation(transform.getRx(), transform.getRy(), transform.getRz());
                restTransform.setScale(transform.getSx(), transform.getSy(), transform.getSz());
            }
        }
    }

    public List<KeyframeTrack> getAnimationTracks() {
        return animationTracks;
    }

    public boolean hasAnimation() {
        if (!animationTracks.isEmpty()) return true;
        for (SceneObject child : children) {
            if (child != null && child.hasAnimation()) return true;
        }
        return false;
    }

    public float getMaxAnimationDuration() {
        float max = 0f;
        for (KeyframeTrack track : animationTracks) {
            if (track.times != null && track.times.length > 0) {
                float last = track.times[track.times.length - 1];
                if (last > max) max = last;
            }
        }
        for (SceneObject child : children) {
            if (child != null) {
                float childMax = child.getMaxAnimationDuration();
                if (childMax > max) max = childMax;
            }
        }
        return max;
    }

    /**
     * Bridges with AnimationPlayer and external timeline controllers.
     */
    public void evaluateAnimationAtTime(float timeSeconds) {
        updateAnimation(timeSeconds);
    }

    /**
     * Evaluates animation tracks at the specified timestamp (in seconds)
     * and updates node transforms accordingly. Recursively updates child nodes.
     */
    public void updateAnimation(float timeSeconds) {
        if (!animationTracks.isEmpty()) {
            for (KeyframeTrack track : animationTracks) {
                float[] sample = sampleTrack(track, timeSeconds);
                if (sample != null && sample.length >= 3) {
                    if ("translation".equals(track.path)) {
                        transform.setPosition(sample[0], sample[1], sample[2]);
                    } else if ("rotation".equals(track.path)) {
                        transform.setRotation(sample[0], sample[1], sample[2]);
                    } else if ("scale".equals(track.path)) {
                        transform.setScale(sample[0], sample[1], sample[2]);
                    }
                }
            }
        }

        for (SceneObject child : children) {
            if (child != null) {
                child.updateAnimation(timeSeconds);
            }
        }
    }

    public void resetToRestTransform() {
        if (restTransform != null && transform != null) {
            transform.setPosition(restTransform.getPx(), restTransform.getPy(), restTransform.getPz());
            transform.setRotation(restTransform.getRx(), restTransform.getRy(), restTransform.getRz());
            transform.setScale(restTransform.getSx(), restTransform.getSy(), restTransform.getSz());
        }
        for (SceneObject child : children) {
            if (child != null) {
                child.resetToRestTransform();
            }
        }
    }

    /**
     * Calculates the full 4x4 World Transformation Matrix by multiplying
     * this object's local matrix by all parent matrices up to the root.
     */
    public float[] getWorldMatrix() {
        float[] local = transform.getModelMatrix();
        if (parent != null) {
            float[] parentWorld = parent.getWorldMatrix();
            Matrix.multiplyMM(worldMatrix, 0, parentWorld, 0, local, 0);
        } else {
            System.arraycopy(local, 0, worldMatrix, 0, 16);
        }
        return worldMatrix;
    }

    private float[] sampleTrack(KeyframeTrack track, float t) {
        if (track.times == null || track.times.length == 0 || track.values == null) return null;
        int numKeys = track.times.length;
        int stride = track.values.length / numKeys;
        if (stride < 3) return null;

        if (t <= track.times[0]) {
            float[] result = new float[stride];
            System.arraycopy(track.values, 0, result, 0, stride);
            return result;
        }
        if (t >= track.times[numKeys - 1]) {
            float[] result = new float[stride];
            System.arraycopy(track.values, (numKeys - 1) * stride, result, 0, stride);
            return result;
        }

        int idx = 0;
        for (int i = 0; i < numKeys - 1; i++) {
            if (t >= track.times[i] && t <= track.times[i + 1]) {
                idx = i;
                break;
            }
        }

        float t0 = track.times[idx];
        float t1 = track.times[idx + 1];
        float alpha = (t1 > t0) ? (t - t0) / (t1 - t0) : 0.0f;

        float[] result = new float[stride];
        int offset0 = idx * stride;
        int offset1 = (idx + 1) * stride;

        for (int c = 0; c < stride; c++) {
            float v0 = track.values[offset0 + c];
            float v1 = track.values[offset1 + c];
            result[c] = v0 + (v1 - v0) * alpha;
        }
        return result;
    }

    /**
     * Deep copies this scene object node and recursively duplicates its children sub-graph.
     */
    public SceneObject cloneNode(String newId, String newName) {
        Material clonedMat = null;
        if (this.material != null) {
            clonedMat = this.material.cloneMaterial(
                    this.material.getId() + "_copy_" + System.currentTimeMillis(),
                    this.material.getName() + " (Copy)"
            );
        }

        SceneObject copy = new SceneObject(newId, newName, this.semanticType, this.mesh, clonedMat);
        copy.setVisible(this.isVisible);
        
        if (this.transform != null) {
            copy.getTransform().setPosition(this.transform.getPx(), this.transform.getPy(), this.transform.getPz());
            copy.getTransform().setRotation(this.transform.getRx(), this.transform.getRy(), this.transform.getRz());
            copy.getTransform().setScale(this.transform.getSx(), this.transform.getSy(), this.transform.getSz());
        }

        for (KeyframeTrack track : this.animationTracks) {
            copy.addAnimationTrack(track.path, track.times.clone(), track.values.clone());
        }

        for (SceneObject child : children) {
            if (child != null) {
                String childNewId = child.getId() + "_copy_" + System.currentTimeMillis();
                String childNewName = child.getName() + " (Copy)";
                copy.addChild(child.cloneNode(childNewId, childNewName));
            }
        }

        return copy;
    }
}