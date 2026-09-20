package com.example.ui;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.MainActivity;
import com.example.R;
import com.example.character.Character;
import com.example.cloud.GitHubWorkflowBridge;
import com.example.engine.Camera;
import com.example.engine.GLTFImporter;
import com.example.engine.Material;
import com.example.engine.Scene;
import com.example.engine.SceneObject;
import com.example.engine.StudioGLRenderer;
import com.example.engine.ThreeDEngine;
import com.example.export.GLTFExporter;
import com.example.runtime.ProjectRuntime;
import com.example.utils.VynaraLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StudioFragment extends Fragment {

    private GLSurfaceView glSurfaceView;
    private StudioGLRenderer renderer;
    private ProjectRuntime runtime;
    private ThreeDEngine engine;
    
    private TextView tvStats;
    private TextView tvSelectedInfo;
    private TextView tvAnimTime;
    private SeekBar seekbarTimeline;
    private ImageButton btnAnimPlay;
    private boolean isPlaying = false;
    private float currentPlaybackTime = 0.0f;
    private float maxTimelineDuration = 0.0f;

    private android.os.Handler animHandler;
    private Runnable animRunnable;
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector doubleTapDetector;

    private File currentRenderImageFile = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_studio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Fetch unified project runtime instance
        if (getActivity() instanceof MainActivity) {
            runtime = ((MainActivity) getActivity()).getProjectRuntime();
        } else {
            runtime = ProjectRuntime.getInstance(requireContext());
        }

        engine = runtime.getEngine();
        animHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        glSurfaceView = view.findViewById(R.id.gl_surface_view);
        tvStats = view.findViewById(R.id.tv_studio_poly_stats);
        tvSelectedInfo = view.findViewById(R.id.tv_selected_object_info);
        tvAnimTime = view.findViewById(R.id.tv_anim_time);
        seekbarTimeline = view.findViewById(R.id.seekbar_timeline);
        btnAnimPlay = view.findViewById(R.id.btn_anim_play);

        // Setup OpenGL ES 2.0 Viewport Renderer with context preservation
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setPreserveEGLContextOnPause(true); // Keeps VRAM textures & shaders alive across tab navigation

        renderer = new StudioGLRenderer(engine.getSceneManager(), engine.getCameraManager(), engine.getLightManager());
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // Enable Touch Viewport Camera Orbit Navigation with Pinch-to-Zoom & Double-Tap Re-center
        setupViewportTouchOrbitGesture();

        updateStudioStatsUI();
        syncActiveSceneAnimationDuration();

        // Undo & Redo transaction history
        View btnUndo = view.findViewById(R.id.btn_undo);
        if (btnUndo != null) {
            btnUndo.setOnClickListener(v -> {
                if (runtime.getUndoManager().undo()) {
                    updateStudioStatsUI();
                    syncActiveSceneAnimationDuration();
                    Toast.makeText(getContext(), "Undo Successful", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Nothing to undo", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnRedo = view.findViewById(R.id.btn_redo);
        if (btnRedo != null) {
            btnRedo.setOnClickListener(v -> {
                if (runtime.getRedoManager().redo()) {
                    updateStudioStatsUI();
                    syncActiveSceneAnimationDuration();
                    Toast.makeText(getContext(), "Redo Successful", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Nothing to redo", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Real GLTF scene exporter
        View btnExport = view.findViewById(R.id.btn_export_gltf);
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> exportActiveSceneToLocalGltf());
        }

        // Viewport Transform Tool Controls
        View btnSelect = view.findViewById(R.id.btn_tool_select);
        if (btnSelect != null) {
            btnSelect.setOnClickListener(v -> {
                SceneObject selected = engine.getSceneManager().getSelectedObject();
                if (selected != null) {
                    tvSelectedInfo.setText("Selected: " + selected.getName() + " (" + selected.getSemanticType() + ")");
                } else {
                    List<SceneObject> objs = engine.getSceneManager().getAllObjects();
                    if (!objs.isEmpty()) {
                        engine.getSceneManager().selectObject(objs.get(0));
                        tvSelectedInfo.setText("Selected: " + objs.get(0).getName());
                    } else {
                        tvSelectedInfo.setText("No object selected");
                    }
                }
            });
        }

        View btnMove = view.findViewById(R.id.btn_tool_move);
        if (btnMove != null) {
            btnMove.setOnClickListener(v -> {
                SceneObject selected = engine.getSceneManager().getSelectedObject();
                if (selected != null) {
                    runtime.getTransactionManager().beginTransaction("Translate Object");
                    selected.getTransform().translate(0.5f, 0f, 0f);
                    runtime.getTransactionManager().commitTransaction();
                    Toast.makeText(getContext(), "Translated selected object (+0.5 X)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Please select an object first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnRotate = view.findViewById(R.id.btn_tool_rotate);
        if (btnRotate != null) {
            btnRotate.setOnClickListener(v -> {
                SceneObject selected = engine.getSceneManager().getSelectedObject();
                if (selected != null) {
                    runtime.getTransactionManager().beginTransaction("Rotate Object");
                    selected.getTransform().rotate(0f, 15f, 0f);
                    runtime.getTransactionManager().commitTransaction();
                    Toast.makeText(getContext(), "Rotated selected object (+15 deg Yaw)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Please select an object first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View btnScale = view.findViewById(R.id.btn_tool_scale);
        if (btnScale != null) {
            btnScale.setOnClickListener(v -> {
                SceneObject selected = engine.getSceneManager().getSelectedObject();
                if (selected != null) {
                    runtime.getTransactionManager().beginTransaction("Scale Object");
                    selected.getTransform().scaleBy(1.1f, 1.1f, 1.1f);
                    runtime.getTransactionManager().commitTransaction();
                    Toast.makeText(getContext(), "Scaled selected object (+10% Uniform)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Please select an object first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        try {
            int zoomResId = getResources().getIdentifier("btn_tool_zoom", "id", requireContext().getPackageName());
            if (zoomResId == 0) {
                zoomResId = getResources().getIdentifier("btn_tool_focus", "id", requireContext().getPackageName());
            }
            if (zoomResId != 0) {
                View btnZoom = view.findViewById(zoomResId);
                if (btnZoom != null) {
                    btnZoom.setOnClickListener(v -> {
                        autoFrameHeroOrScene();
                        Toast.makeText(getContext(), "Camera centered on 3D subject", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        } catch (Throwable ignored) {}

        View btnHierarchy = view.findViewById(R.id.btn_tool_hierarchy);
        if (btnHierarchy != null) {
            btnHierarchy.setOnClickListener(v -> {
                int totalObjects = engine.getSceneManager().getAllObjects().size();
                int totalLights = engine.getLightManager().getLights().size();
                Toast.makeText(getContext(), "Scene Graph: " + totalObjects + " Nodes, " + totalLights + " Lights, 1 Camera", Toast.LENGTH_LONG).show();
            });
        }

        // Universal animation loop: updates scrubber, time text, and node transform keyframes across entire hierarchy
        animRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying && maxTimelineDuration > 0.05f) {
                    currentPlaybackTime += 0.033f;
                    if (currentPlaybackTime > maxTimelineDuration) {
                        currentPlaybackTime = 0.0f; // Loop seamlessly
                    }

                    if (tvAnimTime != null) {
                        tvAnimTime.setText(String.format(Locale.US, "%.1fs / %.1fs", currentPlaybackTime, maxTimelineDuration));
                    }
                    if (seekbarTimeline != null && maxTimelineDuration > 0.001f) {
                        int progress = (int) ((currentPlaybackTime / maxTimelineDuration) * 100);
                        seekbarTimeline.setProgress(progress);
                    }

                    // Update node transform animation tracks across the entire flat hierarchy (car driving, wheel rotation, motion paths)
                    Scene activeScene = (engine != null && engine.getSceneManager() != null) 
                            ? engine.getSceneManager().getActiveScene() : null;
                    if (activeScene != null) {
                        synchronized (activeScene) {
                            for (SceneObject obj : activeScene.getFlatObjectList()) {
                                if (obj != null) {
                                    obj.updateAnimation(currentPlaybackTime);
                                }
                            }
                        }
                        // Update world transforms so all child meshes follow parent transforms immediately
                        engine.getSceneManager().updateWorldTransforms();
                    }

                    // Update character kinematic players if characters exist
                    for (Character c : runtime.getCharacterManager().getCharacterMap().values()) {
                        if (c.getAnimationPlayer() != null) {
                            c.getAnimationPlayer().update(0.033f);
                        }
                    }

                    animHandler.postDelayed(this, 33);
                } else {
                    isPlaying = false;
                    if (btnAnimPlay != null) {
                        btnAnimPlay.setImageResource(android.R.drawable.ic_media_play);
                    }
                }
            }
        };

        if (btnAnimPlay != null) {
            btnAnimPlay.setOnClickListener(v -> {
                if (maxTimelineDuration <= 0.05f) {
                    Toast.makeText(getContext(), "Static 3D Scene (No Animation Tracks)", Toast.LENGTH_SHORT).show();
                    return;
                }

                isPlaying = !isPlaying;
                btnAnimPlay.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
                
                for (Character c : runtime.getCharacterManager().getCharacterMap().values()) {
                    if (c.getAnimationPlayer() != null) {
                        if (isPlaying) {
                            c.getAnimationPlayer().resume();
                        } else {
                            c.getAnimationPlayer().pause();
                        }
                    }
                }
                
                if (isPlaying) {
                    animHandler.post(animRunnable);
                } else {
                    animHandler.removeCallbacks(animRunnable);
                }
            });
        }

        if (seekbarTimeline != null) {
            seekbarTimeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (maxTimelineDuration <= 0.05f) {
                        return;
                    }

                    float seconds = (progress / 100.0f) * maxTimelineDuration;
                    currentPlaybackTime = seconds;
                    if (tvAnimTime != null) {
                        tvAnimTime.setText(String.format(Locale.US, "%.1fs / %.1fs", seconds, maxTimelineDuration));
                    }
                    
                    if (fromUser) {
                        // Seek node animations across all flat scene nodes on user timeline scrub
                        Scene activeScene = (engine != null && engine.getSceneManager() != null) 
                                ? engine.getSceneManager().getActiveScene() : null;
                        if (activeScene != null) {
                            synchronized (activeScene) {
                                for (SceneObject obj : activeScene.getFlatObjectList()) {
                                    if (obj != null) {
                                        obj.updateAnimation(seconds);
                                    }
                                }
                            }
                            engine.getSceneManager().updateWorldTransforms();
                        }

                        for (Character c : runtime.getCharacterManager().getCharacterMap().values()) {
                            if (c.getAnimationPlayer() != null) {
                                c.getAnimationPlayer().seek(seconds);
                            }
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // AI Assistant Dialog Launcher
        View btnAi = view.findViewById(R.id.btn_ai_studio_assistant);
        if (btnAi != null) {
            btnAi.setOnClickListener(v -> {
                AiAssistantDialogFragment dialog = new AiAssistantDialogFragment();
                dialog.show(getChildFragmentManager(), "AiAssistantDialog");
            });
        }
    }

    /**
     * Inspects all active scene graph objects and skeletal character players.
     * If animation tracks exist, configures the timeline scrubber matching the model's actual duration.
     * If the model is a static procedural asset (e.g. skyscraper, villa, furniture), disables the scrubber.
     */
    public void syncActiveSceneAnimationDuration() {
        if (engine == null || engine.getSceneManager() == null) return;
        Scene activeScene = engine.getSceneManager().getActiveScene();
        if (activeScene == null) return;

        float maxTrackDuration = 0f;
        synchronized (activeScene) {
            for (SceneObject obj : activeScene.getFlatObjectList()) {
                if (obj != null) {
                    float d = obj.getMaxAnimationDuration();
                    if (d > maxTrackDuration) {
                        maxTrackDuration = d;
                    }
                }
            }
        }

        final float finalDuration = maxTrackDuration;
        if (finalDuration > 0.05f) {
            maxTimelineDuration = finalDuration;
            if (tvAnimTime != null) {
                tvAnimTime.setText(String.format(Locale.US, "%.1fs / %.1fs", currentPlaybackTime, maxTimelineDuration));
            }
            if (seekbarTimeline != null) {
                seekbarTimeline.setEnabled(true);
                seekbarTimeline.setAlpha(1.0f);
            }
        } else {
            // Pure static model (e.g. skyscraper, villa, prop)
            maxTimelineDuration = 0f;
            currentPlaybackTime = 0f;
            isPlaying = false;
            animHandler.removeCallbacks(animRunnable);
            if (btnAnimPlay != null) {
                btnAnimPlay.setImageResource(android.R.drawable.ic_media_play);
            }
            if (tvAnimTime != null) {
                tvAnimTime.setText("0.0s / Static");
            }
            if (seekbarTimeline != null) {
                seekbarTimeline.setProgress(0);
                seekbarTimeline.setEnabled(false);
                seekbarTimeline.setAlpha(0.35f);
            }
        }
        updateStudioStatsUI();
    }

    private void setupViewportTouchOrbitGesture() {
        if (glSurfaceView == null) return;

        doubleTapDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                autoFrameHeroOrScene();
                Toast.makeText(getContext(), "Camera centered on 3D subject", Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                if (Float.isNaN(scaleFactor) || Float.isInfinite(scaleFactor) || scaleFactor <= 0.001f) return false;

                if (engine != null && engine.getCameraManager() != null) {
                    Camera camera = engine.getCameraManager().getActiveCamera();
                    if (camera != null) {
                        camera.zoom(scaleFactor);
                    }
                }
                return true;
            }

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }
        });

        glSurfaceView.setOnTouchListener(new View.OnTouchListener() {
            private float previousTouchX;
            private float previousTouchY;
            private int activePointerId = MotionEvent.INVALID_POINTER_ID;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) return false;

                doubleTapDetector.onTouchEvent(event);
                scaleGestureDetector.onTouchEvent(event);

                int action = event.getActionMasked();

                switch (action) {
                    case MotionEvent.ACTION_DOWN: {
                        int pointerIndex = event.getActionIndex();
                        activePointerId = event.getPointerId(pointerIndex);
                        previousTouchX = event.getX(pointerIndex);
                        previousTouchY = event.getY(pointerIndex);
                        v.performClick();
                        return true;
                    }

                    case MotionEvent.ACTION_POINTER_DOWN: {
                        int pointerIndex = event.getActionIndex();
                        activePointerId = event.getPointerId(pointerIndex);
                        previousTouchX = event.getX(pointerIndex);
                        previousTouchY = event.getY(pointerIndex);
                        return true;
                    }

                    case MotionEvent.ACTION_MOVE: {
                        if (event.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()) {
                            int pointerIndex = event.findPointerIndex(activePointerId);
                            if (pointerIndex == -1) {
                                pointerIndex = 0;
                                activePointerId = event.getPointerId(pointerIndex);
                            }

                            float x = event.getX(pointerIndex);
                            float y = event.getY(pointerIndex);

                            float deltaX = x - previousTouchX;
                            float deltaY = y - previousTouchY;

                            if (!Float.isNaN(deltaX) && !Float.isNaN(deltaY) && Math.abs(deltaX) < 100f && Math.abs(deltaY) < 100f) {
                                if (engine != null && engine.getCameraManager() != null) {
                                    Camera camera = engine.getCameraManager().getActiveCamera();
                                    if (camera != null) {
                                        camera.orbit(deltaX * 0.006f, deltaY * 0.006f);
                                    }
                                }
                            }

                            previousTouchX = x;
                            previousTouchY = y;
                        } else if (event.getPointerCount() > 1) {
                            int pointerIndex = event.findPointerIndex(activePointerId);
                            if (pointerIndex != -1) {
                                previousTouchX = event.getX(pointerIndex);
                                previousTouchY = event.getY(pointerIndex);
                            }
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_POINTER_UP: {
                        int pointerIndex = event.getActionIndex();
                        int pointerId = event.getPointerId(pointerIndex);
                        if (pointerId == activePointerId) {
                            int newPointerIndex = (pointerIndex == 0) ? 1 : 0;
                            if (newPointerIndex < event.getPointerCount()) {
                                activePointerId = event.getPointerId(newPointerIndex);
                                previousTouchX = event.getX(newPointerIndex);
                                previousTouchY = event.getY(newPointerIndex);
                            }
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        activePointerId = MotionEvent.INVALID_POINTER_ID;
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private float[] getAbsoluteWorldMatrix(SceneObject obj) {
        if (obj == null || obj.getTransform() == null) return null;
        SceneObject parent = obj.getParent();
        float[] parentWorld = (parent != null) ? getAbsoluteWorldMatrix(parent) : null;
        return obj.getTransform().getWorldMatrix(parentWorld);
    }

    public void autoFrameHeroOrScene() {
        if (engine == null || engine.getCameraManager() == null) return;
        Camera cam = engine.getCameraManager().getActiveCamera();
        if (cam == null) return;

        Scene activeScene = engine.getSceneManager().getActiveScene();
        if (activeScene == null || activeScene.getObjects().isEmpty()) {
            cam.reset();
            return;
        }

        List<SceneObject> flatList;
        synchronized (activeScene) {
            flatList = new ArrayList<>(activeScene.getFlatObjectList());
        }

        // Priority 1: Focus on Vehicle or Hero subject (ignoring the long road/terrain geometry)
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        boolean hasSubject = false;

        for (SceneObject obj : flatList) {
            if (obj == null || !obj.isVisible() || obj.getMesh() == null || obj.getMesh().getVertices() == null) continue;
            
            String nameLower = obj.getName() != null ? obj.getName().toLowerCase(Locale.US) : "";
            // Ignore environment planes, long highway road, and guardrails when computing hero subject focus
            if (nameLower.contains("road") || nameLower.contains("highway") || nameLower.contains("guardrail") 
                    || nameLower.contains("asphalt") || nameLower.contains("stripe") || nameLower.contains("ground")) {
                continue;
            }

            float[] verts = obj.getMesh().getVertices();
            if (verts.length == 0) continue;

            float[] worldMat = getAbsoluteWorldMatrix(obj);
            if (worldMat == null) continue;

            for (int v = 0; v < verts.length; v += 3) {
                float vx = worldMat[0] * verts[v] + worldMat[4] * verts[v + 1] + worldMat[8] * verts[v + 2] + worldMat[12];
                float vy = worldMat[1] * verts[v] + worldMat[5] * verts[v + 1] + worldMat[9] * verts[v + 2] + worldMat[13];
                float vz = worldMat[2] * verts[v] + worldMat[6] * verts[v + 1] + worldMat[10] * verts[v + 2] + worldMat[14];

                if (!Float.isNaN(vx) && !Float.isInfinite(vx)) {
                    if (vx < minX) minX = vx;
                    if (vx > maxX) maxX = vx;
                }
                if (!Float.isNaN(vy) && !Float.isInfinite(vy)) {
                    if (vy < minY) minY = vy;
                    if (vy > maxY) maxY = vy;
                }
                if (!Float.isNaN(vz) && !Float.isInfinite(vz)) {
                    if (vz < minZ) minZ = vz;
                    if (vz > maxZ) maxZ = vz;
                }
                hasSubject = true;
            }
        }

        // Fallback: If no distinct subject is found, frame all objects
        if (!hasSubject) {
            for (SceneObject obj : flatList) {
                if (obj == null || !obj.isVisible() || obj.getMesh() == null || obj.getMesh().getVertices() == null) continue;
                float[] verts = obj.getMesh().getVertices();
                float[] worldMat = getAbsoluteWorldMatrix(obj);
                if (worldMat == null) continue;

                for (int v = 0; v < verts.length; v += 3) {
                    float vx = worldMat[0] * verts[v] + worldMat[4] * verts[v + 1] + worldMat[8] * verts[v + 2] + worldMat[12];
                    float vy = worldMat[1] * verts[v] + worldMat[5] * verts[v + 1] + worldMat[9] * verts[v + 2] + worldMat[13];
                    float vz = worldMat[2] * verts[v] + worldMat[6] * verts[v + 1] + worldMat[10] * verts[v + 2] + worldMat[14];

                    if (!Float.isNaN(vx) && !Float.isInfinite(vx)) {
                        if (vx < minX) minX = vx;
                        if (vx > maxX) maxX = vx;
                    }
                    if (!Float.isNaN(vy) && !Float.isInfinite(vy)) {
                        if (vy < minY) minY = vy;
                        if (vy > maxY) maxY = vy;
                    }
                    if (!Float.isNaN(vz) && !Float.isInfinite(vz)) {
                        if (vz < minZ) minZ = vz;
                        if (vz > maxZ) maxZ = vz;
                    }
                    hasSubject = true;
                }
            }
        }

        if (hasSubject && !Float.isInfinite(minX) && !Float.isInfinite(maxX)) {
            cam.frameBounds(new float[]{minX, minY, minZ}, new float[]{maxX, maxY, maxZ});
        } else {
            cam.setTarget(0f, 1f, 0f);
            cam.setEye(0f, 4f, 8f);
        }
    }

    public boolean spawnProceduralObject(String type, String name, float x, float y, float z, String colorHex) {
        if (engine == null) return false;
        try {
            runtime.getTransactionManager().beginTransaction("Spawn " + name);

            if (x == 0f && y == 0f && z == 0f && engine.getCameraManager() != null) {
                Camera cam = engine.getCameraManager().getActiveCamera();
                if (cam != null && cam.getTarget() != null) {
                    float[] target = cam.getTarget();
                    x = target[0] + 0.8f;
                    y = target[1];
                    z = target[2] + 0.8f;
                }
            }

            SceneObject obj;
            String lowerType = type != null ? type.toLowerCase(Locale.US) : "cube";

            if (lowerType.contains("cube") || lowerType.contains("sphere") || lowerType.contains("cylinder") || lowerType.contains("plane")) {
                obj = engine.createPrimitive(lowerType.replace("primitive_", ""), 1.5f, 1.5f, 1.5f);
            } else {
                obj = engine.createProceduralStructure(lowerType, name != null ? name : "Prop");
            }

            if (obj != null) {
                obj.getTransform().setPosition(x, y, z);
                if (colorHex != null && !colorHex.isEmpty()) {
                    Material mat = new Material("mat_" + System.currentTimeMillis(), name + "_Mat", colorHex);
                    obj.setMaterial(mat);
                }
                engine.getSceneManager().updateWorldTransforms();
                runtime.getTransactionManager().commitTransaction();
                updateStudioStatsUI();
                syncActiveSceneAnimationDuration();
                return true;
            }
            runtime.getTransactionManager().rollbackTransaction();
        } catch (Exception e) {
            VynaraLogger.e("Failed to spawn procedural object: " + name, e);
        }
        return false;
    }

    public void clearScene() {
        if (engine != null && engine.getSceneManager() != null) {
            Scene activeScene = engine.getSceneManager().getActiveScene();
            if (activeScene != null) {
                synchronized (activeScene) {
                    activeScene.getObjects().clear();
                }
            }
            engine.getSceneManager().selectObject(null);
            if (runtime != null && runtime.getCharacterManager() != null) {
                runtime.getCharacterManager().getCharacterMap().clear();
            }
            updateStudioStatsUI();
            syncActiveSceneAnimationDuration();
        }
    }

    public void loadAndDisplayGLBFile(File glbFile) {
        if (glbFile == null || !glbFile.exists() || engine == null) return;

        try {
            VynaraLogger.system("StudioFragment: Loading external GLB into active scene: " + glbFile.getName());
            GLTFImporter.ImportResult result = GLTFImporter.loadFromFile(glbFile);

            runtime.getTransactionManager().beginTransaction("Import GLB Model");

            Scene activeScene = engine.getSceneManager().getActiveScene();
            float detectedMaxDuration = 0f;

            if (activeScene != null) {
                synchronized (activeScene) {
                    // Remove default placeholder cube if present to avoid dual-mesh stacking
                    activeScene.getObjects().removeIf(o -> "Cube".equalsIgnoreCase(o.getName()) || "default_cube".equalsIgnoreCase(o.getId()));
                    for (SceneObject obj : result.getSceneObjects()) {
                        activeScene.addObject(obj);
                        float d = obj.getMaxAnimationDuration();
                        if (d > detectedMaxDuration) {
                            detectedMaxDuration = d;
                        }
                    }
                }
            }

            for (Character ch : result.getCharacters()) {
                runtime.getCharacterManager().registerCharacter(ch);
            }

            // Dynamically set timeline length matching imported model tracks
            if (detectedMaxDuration > 0.05f) {
                maxTimelineDuration = detectedMaxDuration;
            } else {
                maxTimelineDuration = 0.0f;
            }
            currentPlaybackTime = 0.0f;

            engine.getSceneManager().updateWorldTransforms();

            // Dynamic subject auto-framing
            autoFrameHeroOrScene();

            // Check if accompanying Cycles render still image exists
            currentRenderImageFile = GitHubWorkflowBridge.getAssociatedRenderImage(glbFile);
            if (currentRenderImageFile != null) {
                VynaraLogger.system("StudioFragment: Photorealistic Cycles still render available at: " + currentRenderImageFile.getName());
            }

            runtime.getTransactionManager().commitTransaction();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    updateStudioStatsUI();
                    syncActiveSceneAnimationDuration();
                    String msg = "Imported: " + glbFile.getName();
                    if (currentRenderImageFile != null) {
                        msg += " (Cycles Render Ready)";
                    }
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                });
            }

        } catch (Exception e) {
            VynaraLogger.e("StudioFragment: Failed loading GLB file", e);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Import error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }

    public File getCurrentRenderImageFile() {
        return currentRenderImageFile;
    }

    public void updateStudioStatsUI() {
        if (tvStats != null && engine != null) {
            Scene activeScene = engine.getSceneManager().getActiveScene();
            int triangles = activeScene != null ? activeScene.getTotalTriangleCount() : 0;
            int vertices = activeScene != null ? activeScene.getTotalVertexCount() : 0;
            tvStats.setText("Tris: " + triangles + " | Verts: " + vertices);
        }
    }

    private void exportActiveSceneToLocalGltf() {
        if (getContext() == null || engine == null) return;

        try {
            Scene activeScene = engine.getSceneManager().getActiveScene();
            String gltfJson = GLTFExporter.exportSceneToGLTFJson(activeScene);

            File exportDir = new File(getContext().getExternalFilesDir(null), "exports");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                Toast.makeText(getContext(), "Failed to create export folder", Toast.LENGTH_SHORT).show();
                return;
            }

            File exportFile = new File(exportDir, "vynara_scene_" + System.currentTimeMillis() + ".gltf");
            FileOutputStream fos = new FileOutputStream(exportFile);
            fos.write(gltfJson.getBytes());
            fos.close();

            Toast.makeText(getContext(), "Scene exported to: " + exportFile.getName(), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(getContext(), "GLTF Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (glSurfaceView != null) glSurfaceView.onResume();
        updateStudioStatsUI();
        syncActiveSceneAnimationDuration();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (glSurfaceView != null) glSurfaceView.onPause();
        isPlaying = false;
        animHandler.removeCallbacks(animRunnable);
    }
}