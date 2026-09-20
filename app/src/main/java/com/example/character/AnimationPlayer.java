package com.example.character;

import com.example.engine.SceneObject;
import com.example.engine.Transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnimationPlayer {
    private Skeleton skeleton;
    private final List<SceneObject> targetObjects = new ArrayList<>();
    private SceneObject rootObject;
    private final Map<String, AnimationClip> clipLibrary = new HashMap<>();
    private AnimationClip activeClip;
    private float currentTimeSeconds = 0f;
    private float customDurationSeconds = 0f;
    private boolean isPlaying = false;

    public AnimationPlayer() {
        loadDefaultClips();
    }

    public AnimationPlayer(Skeleton skeleton) {
        this.skeleton = skeleton;
        loadDefaultClips();
    }

    public AnimationPlayer(SceneObject rootObject) {
        this.rootObject = rootObject;
        if (rootObject != null) {
            this.targetObjects.add(rootObject);
        }
        loadDefaultClips();
    }

    public AnimationPlayer(List<SceneObject> targetObjects) {
        if (targetObjects != null) {
            this.targetObjects.addAll(targetObjects);
        }
        loadDefaultClips();
    }

    public void setSkeleton(Skeleton skeleton) {
        this.skeleton = skeleton;
    }

    public void setRootObject(SceneObject rootObject) {
        this.rootObject = rootObject;
        this.targetObjects.clear();
        if (rootObject != null) {
            this.targetObjects.add(rootObject);
        }
    }

    public void setTargetObjects(List<SceneObject> objects) {
        this.targetObjects.clear();
        if (objects != null) {
            this.targetObjects.addAll(objects);
        }
    }

    public void addTargetObject(SceneObject obj) {
        if (obj != null && !this.targetObjects.contains(obj)) {
            this.targetObjects.add(obj);
        }
    }

    public void setCustomDuration(float durationSeconds) {
        this.customDurationSeconds = Math.max(0f, durationSeconds);
    }

    public float getDurationSeconds() {
        if (activeClip != null && activeClip.getDurationSeconds() > 0f) {
            return activeClip.getDurationSeconds();
        }
        if (customDurationSeconds > 0f) {
            return customDurationSeconds;
        }
        return 3.5f; // Fallback sequence length
    }

    public void loadDefaultClips() {
        clipLibrary.clear();

        // 1. Idle Animation Clip (Subtle Breathing & Spine Offset)
        AnimationClip idle = new AnimationClip("idle", 2.0f, true);
        AnimationTrack spineTrack = new AnimationTrack("SPINE")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(1.0f, 0f, 0.02f, 0f, 2f, 0f, 0f))
                .addKeyframe(new Keyframe(2.0f, 0f, 0f, 0f, 0f, 0f, 0f));
        idle.addTrack(spineTrack);
        clipLibrary.put("idle", idle);

        // 2. Walk Animation Clip (Anatomical Bipedal Gait Cycle)
        AnimationClip walk = new AnimationClip("walk", 1.2f, true);

        AnimationTrack lThighTrack = new AnimationTrack("LEFT_THIGH")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, 25f, 0f, 0f))
                .addKeyframe(new Keyframe(0.3f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(0.6f, 0f, 0f, 0f, -25f, 0f, 0f))
                .addKeyframe(new Keyframe(0.9f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(1.2f, 0f, 0f, 0f, 25f, 0f, 0f));

        AnimationTrack rThighTrack = new AnimationTrack("RIGHT_THIGH")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, -25f, 0f, 0f))
                .addKeyframe(new Keyframe(0.3f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(0.6f, 0f, 0f, 0f, 25f, 0f, 0f))
                .addKeyframe(new Keyframe(0.9f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(1.2f, 0f, 0f, 0f, -25f, 0f, 0f));

        AnimationTrack lCalfTrack = new AnimationTrack("LEFT_CALF")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(0.3f, 0f, 0f, 0f, 35f, 0f, 0f))
                .addKeyframe(new Keyframe(0.6f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(1.2f, 0f, 0f, 0f, 0f, 0f, 0f));

        AnimationTrack rCalfTrack = new AnimationTrack("RIGHT_CALF")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(0.6f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(0.9f, 0f, 0f, 0f, 35f, 0f, 0f))
                .addKeyframe(new Keyframe(1.2f, 0f, 0f, 0f, 0f, 0f, 0f));

        AnimationTrack lArmTrack = new AnimationTrack("LEFT_UPPER_ARM")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, -15f, 0f, 0f))
                .addKeyframe(new Keyframe(0.6f, 0f, 0f, 0f, 15f, 0f, 0f))
                .addKeyframe(new Keyframe(1.2f, 0f, 0f, 0f, -15f, 0f, 0f));

        AnimationTrack rArmTrack = new AnimationTrack("RIGHT_UPPER_ARM")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, 15f, 0f, 0f))
                .addKeyframe(new Keyframe(0.6f, 0f, 0f, 0f, -15f, 0f, 0f))
                .addKeyframe(new Keyframe(1.2f, 0f, 0f, 0f, 15f, 0f, 0f));

        walk.addTrack(lThighTrack).addTrack(rThighTrack).addTrack(lCalfTrack).addTrack(rCalfTrack)
            .addTrack(lArmTrack).addTrack(rArmTrack);
        clipLibrary.put("walk", walk);

        // 3. Run Animation Clip (Fast Gait Cycle)
        AnimationClip run = new AnimationClip("run", 0.7f, true);
        AnimationTrack lRunThigh = new AnimationTrack("LEFT_THIGH")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, 40f, 0f, 0f))
                .addKeyframe(new Keyframe(0.35f, 0f, 0f, 0f, -40f, 0f, 0f))
                .addKeyframe(new Keyframe(0.7f, 0f, 0f, 0f, 40f, 0f, 0f));

        AnimationTrack rRunThigh = new AnimationTrack("RIGHT_THIGH")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, -40f, 0f, 0f))
                .addKeyframe(new Keyframe(0.35f, 0f, 0f, 0f, 40f, 0f, 0f))
                .addKeyframe(new Keyframe(0.7f, 0f, 0f, 0f, -40f, 0f, 0f));

        run.addTrack(lRunThigh).addTrack(rRunThigh);
        clipLibrary.put("run", run);

        // 4. Jump Animation Clip
        AnimationClip jump = new AnimationClip("jump", 1.0f, false);
        AnimationTrack rootJumpTrack = new AnimationTrack("ROOT")
                .addKeyframe(new Keyframe(0.0f, 0f, 0f, 0f, 0f, 0f, 0f))
                .addKeyframe(new Keyframe(0.5f, 0f, 0.8f, 0f, -10f, 0f, 0f))
                .addKeyframe(new Keyframe(1.0f, 0f, 0f, 0f, 0f, 0f, 0f));
        jump.addTrack(rootJumpTrack);
        clipLibrary.put("jump", jump);
    }

    public void playClip(String clipName) {
        if (clipName == null) return;
        String key = clipName.toLowerCase().trim();
        if (clipLibrary.containsKey(key)) {
            this.activeClip = clipLibrary.get(key);
            this.currentTimeSeconds = 0f;
            this.isPlaying = true;
        }
    }

    public void pause() { isPlaying = false; }
    public void resume() { isPlaying = true; }
    
    public void stop() {
        isPlaying = false;
        currentTimeSeconds = 0f;
        resetSkeletonToDefaultPose();
    }

    public void seek(float timeSeconds) {
        float maxDuration = getDurationSeconds();
        this.currentTimeSeconds = Math.max(0f, Math.min(maxDuration, timeSeconds));
        evaluateAndApplyKeyframes();
    }

    public void update(float deltaTimeSeconds) {
        if (!isPlaying) return;

        float maxDuration = getDurationSeconds();
        currentTimeSeconds += deltaTimeSeconds;

        if (currentTimeSeconds > maxDuration) {
            boolean looping = (activeClip != null && activeClip.isLooping()) || activeClip == null;
            if (looping && maxDuration > 0.0001f) {
                currentTimeSeconds %= maxDuration;
            } else {
                currentTimeSeconds = maxDuration;
                isPlaying = false;
            }
        }

        evaluateAndApplyKeyframes();
    }

    /**
     * Resets all skeletal bone nodes and scene objects to default transforms.
     */
    private void resetSkeletonToDefaultPose() {
        if (skeleton != null) {
            for (Bone bone : skeleton.getAllBones()) {
                if (bone != null && bone.getLocalTransform() != null) {
                    bone.getLocalTransform().reset();
                }
            }
            skeleton.updateWorldTransforms();
        }
    }

    /**
     * Evaluates keyframes and applies them across both skeletal rigs and SceneObject hierarchies.
     */
    private void evaluateAndApplyKeyframes() {
        // 1. Skeletal Bone Keyframe Evaluation Pass
        if (activeClip != null && skeleton != null) {
            for (AnimationTrack track : activeClip.getTracks()) {
                if (track == null || track.getKeyframes().isEmpty()) continue;

                Bone bone = skeleton.getBoneBySemanticName(track.getBoneSemanticName());
                if (bone == null || bone.getLocalTransform() == null) continue;

                applyInterpolatedTrackToTransform(track, bone.getLocalTransform());
            }
            skeleton.updateWorldTransforms();
        }

        // 2. Node Transform Pass for SceneObjects & Multi-Part Meshes (e.g., Vehicles, Empties, Wheels)
        if (activeClip != null && (!targetObjects.isEmpty() || rootObject != null)) {
            for (AnimationTrack track : activeClip.getTracks()) {
                if (track == null || track.getKeyframes().isEmpty()) continue;

                String targetName = track.getBoneSemanticName();
                SceneObject targetObj = findSceneObjectByName(targetName);

                if (targetObj != null && targetObj.getTransform() != null) {
                    applyInterpolatedTrackToTransform(track, targetObj.getTransform());
                }
            }
        }

        // 3. Evaluate Direct Animation Tracks Embedded on SceneObjects
        List<SceneObject> candidates = new ArrayList<>(targetObjects);
        if (rootObject != null && !candidates.contains(rootObject)) {
            candidates.add(rootObject);
        }

        for (SceneObject obj : candidates) {
            evaluateSceneObjectTracksRecursively(obj, currentTimeSeconds);
        }
    }

    private void applyInterpolatedTrackToTransform(AnimationTrack track, Transform transform) {
        List<Keyframe> keyframes = track.getKeyframes();
        if (keyframes.isEmpty()) return;

        if (keyframes.size() == 1) {
            Keyframe kf = keyframes.get(0);
            float[] t = kf.getTranslation();
            float[] r = kf.getRotationDegrees();
            transform.setPosition(t[0], t[1], t[2]);
            transform.setRotation(r[0], r[1], r[2]);
            return;
        }

        // Clamped Keyframe Evaluation: Pre-emptively clamp bounds to prevent out-of-range snapping/jittering
        Keyframe firstKf = keyframes.get(0);
        Keyframe lastKf = keyframes.get(keyframes.size() - 1);

        if (currentTimeSeconds <= firstKf.getTimestampSeconds()) {
            float[] t = firstKf.getTranslation();
            float[] r = firstKf.getRotationDegrees();
            transform.setPosition(t[0], t[1], t[2]);
            transform.setRotation(r[0], r[1], r[2]);
            return;
        }

        if (currentTimeSeconds >= lastKf.getTimestampSeconds()) {
            float[] t = lastKf.getTranslation();
            float[] r = lastKf.getRotationDegrees();
            transform.setPosition(t[0], t[1], t[2]);
            transform.setRotation(r[0], r[1], r[2]);
            return;
        }

        Keyframe prevKf = firstKf;
        Keyframe nextKf = lastKf;

        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (currentTimeSeconds >= keyframes.get(i).getTimestampSeconds() && 
                currentTimeSeconds <= keyframes.get(i + 1).getTimestampSeconds()) {
                prevKf = keyframes.get(i);
                nextKf = keyframes.get(i + 1);
                break;
            }
        }

        float t0 = prevKf.getTimestampSeconds();
        float t1 = nextKf.getTimestampSeconds();
        float duration = t1 - t0;
        float factor = duration > 0.0001f ? (currentTimeSeconds - t0) / duration : 0f;
        factor = Math.max(0f, Math.min(1f, factor));

        // Smooth Position Translation
        float[] p0 = prevKf.getTranslation();
        float[] p1 = nextKf.getTranslation();
        float tx = p0[0] + factor * (p1[0] - p0[0]);
        float ty = p0[1] + factor * (p1[1] - p0[1]);
        float tz = p0[2] + factor * (p1[2] - p0[2]);

        // Shortest-Path Modular Angular Interpolation (Prevents 360-degree flipping & shaking)
        float[] r0 = prevKf.getRotationDegrees();
        float[] r1 = nextKf.getRotationDegrees();

        float diffX = (r1[0] - r0[0]) % 360.0f;
        if (diffX > 180.0f) diffX -= 360.0f;
        if (diffX < -180.0f) diffX += 360.0f;
        float rx = r0[0] + factor * diffX;

        float diffY = (r1[1] - r0[1]) % 360.0f;
        if (diffY > 180.0f) diffY -= 360.0f;
        if (diffY < -180.0f) diffY += 360.0f;
        float ry = r0[1] + factor * diffY;

        float diffZ = (r1[2] - r0[2]) % 360.0f;
        if (diffZ > 180.0f) diffZ -= 360.0f;
        if (diffZ < -180.0f) diffZ += 360.0f;
        float rz = r0[2] + factor * diffZ;

        transform.setPosition(tx, ty, tz);
        transform.setRotation(rx, ry, rz);
    }

    private void evaluateSceneObjectTracksRecursively(SceneObject obj, float time) {
        if (obj == null) return;

        obj.evaluateAnimationAtTime(time);

        if (obj.getChildren() != null) {
            for (SceneObject child : obj.getChildren()) {
                evaluateSceneObjectTracksRecursively(child, time);
            }
        }
    }

    private SceneObject findSceneObjectByName(String name) {
        if (name == null || name.trim().isEmpty()) return null;

        List<SceneObject> searchPool = new ArrayList<>(targetObjects);
        if (rootObject != null && !searchPool.contains(rootObject)) {
            searchPool.add(rootObject);
        }

        for (SceneObject root : searchPool) {
            SceneObject found = searchRecursive(root, name);
            if (found != null) return found;
        }
        return null;
    }

    private SceneObject searchRecursive(SceneObject current, String name) {
        if (current == null) return null;
        if (name.equalsIgnoreCase(current.getName()) || name.equalsIgnoreCase(current.getId())) {
            return current;
        }
        if (current.getChildren() != null) {
            for (SceneObject child : current.getChildren()) {
                SceneObject found = searchRecursive(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void addClip(AnimationClip clip) {
        if (clip != null && clip.getName() != null) {
            clipLibrary.put(clip.getName().toLowerCase().trim(), clip);
        }
    }

    public AnimationClip getActiveClip() { return activeClip; }
    public float getCurrentTimeSeconds() { return currentTimeSeconds; }
    public boolean isPlaying() { return isPlaying; }
    public Map<String, AnimationClip> getClipLibrary() { return clipLibrary; }
}