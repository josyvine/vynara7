package com.example.ui;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.MainActivity;
import com.example.R;
import com.example.ai.AIProductionController;
import com.example.ai.GeminiApiClient;
import com.example.ai.agents.BlenderWorkerAgent;
import com.example.ai.protocol.AIPipelineMode;
import com.example.cloud.GitHubWorkflowBridge;
import com.example.tasks.ExecutionEngine;
import com.example.tasks.ProductionPlan;
import com.example.tasks.TaskGraph;
import com.example.tasks.TaskNode;
import com.example.tools.ToolExecutor;
import com.example.utils.VynaraLogger;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ProductionFragment extends Fragment {

    private static final String ARG_PROMPT = "arg_prompt";
    private static final String ARG_STYLE = "arg_style";
    private static final String ARG_ENGINE = "arg_engine";
    private static final String ARG_PIPELINE_MODE = "arg_pipeline_mode";
    private static final String ARG_REF_IMAGES = "arg_ref_images";

    private TextView tvProjectTitle;
    private TextView tvStatus;
    private TextView tvTaskCounter;
    private TextView tvProgressPercent;
    private ProgressBar progressBar;
    private RecyclerView rvTasks;
    private TaskNodeAdapter adapter;

    private String prompt = "3D Asset Creation";
    private String style = "Photorealistic";
    private String targetEngine = "OpenGL ES / GLTF";
    private String pipelineModeId = AIPipelineMode.PROCEDURAL_PYTHON.getId();
    private ArrayList<String> referenceImageUris = new ArrayList<>();

    private AIProductionController controller;
    private ProductionPlan activePlan;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface CheckpointFeedbackCallback {
        void onRequestChanges(String userFeedback);
        void onApprove();
    }

    public static ProductionFragment newInstance(String prompt, String style, String targetEngine, String pipelineModeId, List<String> referenceImageUris) {
        ProductionFragment fragment = new ProductionFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROMPT, prompt);
        args.putString(ARG_STYLE, style);
        args.putString(ARG_ENGINE, targetEngine);
        args.putString(ARG_PIPELINE_MODE, pipelineModeId);
        args.putStringArrayList(ARG_REF_IMAGES, referenceImageUris != null ? new ArrayList<>(referenceImageUris) : new ArrayList<>());
        fragment.setArguments(args);
        return fragment;
    }

    public static ProductionFragment newInstance(String prompt, String style, String targetEngine, List<String> referenceImageUris) {
        return newInstance(prompt, style, targetEngine, AIPipelineMode.PROCEDURAL_PYTHON.getId(), referenceImageUris);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            prompt = getArguments().getString(ARG_PROMPT, prompt);
            style = getArguments().getString(ARG_STYLE, style);
            targetEngine = getArguments().getString(ARG_ENGINE, targetEngine);
            pipelineModeId = getArguments().getString(ARG_PIPELINE_MODE, AIPipelineMode.PROCEDURAL_PYTHON.getId());
            referenceImageUris = getArguments().getStringArrayList(ARG_REF_IMAGES);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_production, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvProjectTitle = view.findViewById(R.id.tv_project_title);
        tvStatus = view.findViewById(R.id.tv_current_task_status);
        tvTaskCounter = view.findViewById(R.id.tv_task_counter);
        tvProgressPercent = view.findViewById(R.id.tv_progress_percent);
        progressBar = view.findViewById(R.id.progress_production);
        rvTasks = view.findViewById(R.id.rv_tasks);

        AIPipelineMode activeMode = AIPipelineMode.fromDisplayNameSafe(pipelineModeId);
        VynaraLogger.system("ProductionFragment: Active pipeline mode -> " + activeMode.getDisplayName() + " [" + activeMode.getId() + "]");

        // Format prompt title with ellipsis if long to keep header clean
        String displayTitle = prompt;
        if (displayTitle != null && displayTitle.length() > 60) {
            displayTitle = displayTitle.substring(0, 60) + "...";
        }
        tvProjectTitle.setText("Creating: " + displayTitle);

        rvTasks.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TaskNodeAdapter();
        rvTasks.setAdapter(adapter);

        // Initialize Controller bound to shared ProjectRuntime
        controller = new AIProductionController(requireContext());

        // Wire Solution B Failure Interceptor to ExecutionEngine
        setupSelfCorrectionInterceptor();

        // Check if an existing plan is ALREADY IN PROGRESS (Safe TaskGraph check; no isRunning() call)
        ProductionPlan existingPlan = controller.getCurrentPlan();
        boolean isPlanActive = false;
        if (existingPlan != null && existingPlan.getTaskGraph() != null) {
            int completed = existingPlan.getTaskGraph().getCompletedCount();
            int total = existingPlan.getTaskGraph().getTotalCount();
            isPlanActive = (total > 0 && completed < total);
        }

        if (isPlanActive) {
            VynaraLogger.system("ProductionFragment: Re-attaching to ongoing background generation...");
            activePlan = existingPlan;
            adapter.setTasks(activePlan.getTaskGraph().getAllNodes());
            progressBar.setIndeterminate(false);

            int completed = activePlan.getTaskGraph().getCompletedCount();
            int total = activePlan.getTaskGraph().getTotalCount();
            int percent = total > 0 ? (int) (((float) completed / total) * 100) : 0;

            progressBar.setProgress(percent);
            tvProgressPercent.setText(percent + "%");
            tvTaskCounter.setText("Tasks: " + completed + " / " + total);
            tvStatus.setText("Executing: " + activePlan.getProjectName());

            resumeExecutionListener();
            setupActionButtons(view);
            return;
        }

        // Set UI loading state for new generation
        progressBar.setIndeterminate(true);
        boolean isCustomScript = prompt != null && prompt.startsWith("Custom Script:");

        if (isCustomScript) {
            tvStatus.setText("Packaging custom Python script for execution...");
        } else if (activeMode.isNeural()) {
            tvStatus.setText("Option C: Initializing Neural Image-to-3D Reconstruction...");
        } else if (activeMode.isInteractive()) {
            tvStatus.setText("Option B2: Preparing Interactive AI Design Checkpoints...");
        } else if (activeMode.isAgentic()) {
            tvStatus.setText("Option B1: Initializing Autonomous AI Vision Design Loop...");
        } else {
            tvStatus.setText("Option A: Devising 3D production plan with Gemini...");
        }

        // Generate Plan with strict pipeline mode routing
        controller.generatePlanWithGemini(prompt, style, targetEngine, activeMode.getId(), referenceImageUris, new GeminiApiClient.ApiCallback<ProductionPlan>() {
            @Override
            public void onSuccess(ProductionPlan plan) {
                handler.post(() -> {
                    activePlan = plan;
                    progressBar.setIndeterminate(false);
                    if (activePlan != null && activePlan.getTaskGraph() != null) {
                        adapter.setTasks(activePlan.getTaskGraph().getAllNodes());
                        startRealExecutionPipeline();
                    } else {
                        tvStatus.setText("AI Error: Generated production plan was empty.");
                        VynaraLogger.e("ProductionFragment: Received null or empty TaskGraph from controller.");
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                    tvStatus.setText("AI Error: Generation Failed.");
                    VynaraLogger.e("ProductionFragment: Plan generation failed -> " + errorMessage);
                    Toast.makeText(getContext(), "AI Workflow Halted: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });

        setupActionButtons(view);
    }

    private void setupActionButtons(View view) {
        Button btnPause = view.findViewById(R.id.btn_pause_production);
        if (btnPause != null) {
            btnPause.setOnClickListener(v -> {
                ExecutionEngine engine = controller.getExecutionEngine();
                if (engine.isPaused()) {
                    engine.resume();
                    btnPause.setText("Pause");
                    VynaraLogger.system("ProductionFragment: Pipeline execution resumed by user.");
                    Toast.makeText(getContext(), "Pipeline Resumed", Toast.LENGTH_SHORT).show();
                } else {
                    engine.pause();
                    btnPause.setText("Resume");
                    VynaraLogger.system("ProductionFragment: Pipeline execution paused by user.");
                    Toast.makeText(getContext(), "Pipeline Paused", Toast.LENGTH_SHORT).show();
                }
            });
        }

        Button btnCancel = view.findViewById(R.id.btn_cancel_production);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                VynaraLogger.system("ProductionFragment: Generation cancelled by user.");
                controller.getExecutionEngine().cancel();
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).clearActiveProduction();
                    ((MainActivity) getActivity()).navigateToCreate();
                }
            });
        }

        Button btnViewStudio = view.findViewById(R.id.btn_open_in_studio);
        if (btnViewStudio != null) {
            btnViewStudio.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToStudio();
                }
            });
        }
    }

    /**
     * Resumes listening to an ongoing background execution when returning from other tabs.
     */
    private void resumeExecutionListener() {
        startRealExecutionPipeline();
    }

    /**
     * SOLUTION B: Configures the autonomous AI self-correction interceptor on the execution engine.
     */
    private void setupSelfCorrectionInterceptor() {
        ExecutionEngine engine = controller.getExecutionEngine();
        if (engine == null) return;

        engine.setTaskFailureInterceptor((task, graph) -> {
            if (!task.canRetry()) {
                VynaraLogger.e("ProductionFragment: Task [" + task.getId() + "] exceeded max self-correction retries.");
                return false;
            }

            // 1. Resolve Traceback from all available sources
            String traceback = GitHubWorkflowBridge.getLastBlenderTraceback();
            if (traceback == null || traceback.trim().isEmpty()) {
                traceback = GitHubWorkflowBridge.getLastBlenderError();
            }
            if (traceback == null || traceback.trim().isEmpty()) {
                traceback = task.getErrorMessage();
            }
            if (traceback == null || traceback.trim().isEmpty()) {
                traceback = "ExecutionTimeout or Headless Worker Error: Execution timed out or halted without an explicit Python traceback.";
            }

            // 2. Resolve Script from Operation params, Master script, or local Cache file
            String failedScript = task.getRepairedScript();

            if (failedScript == null || failedScript.trim().isEmpty()) {
                if (task.getOperation() != null) {
                    Object scriptObj = task.getOperation().getParam("bpyScript", null);
                    if (scriptObj == null) {
                        scriptObj = task.getOperation().getParam("blender_script", null);
                    }
                    if (scriptObj != null) {
                        failedScript = String.valueOf(scriptObj);
                    }
                }
            }

            if (failedScript == null || failedScript.trim().isEmpty()) {
                failedScript = BlenderWorkerAgent.getLastMasterScript();
            }

            // Fallback: Read from local script cache if uploaded by user
            if (failedScript == null || failedScript.trim().isEmpty()) {
                try {
                    File cachedScript = new File(requireContext().getCacheDir(), "scripts/custom_user_script.py");
                    if (cachedScript.exists() && cachedScript.length() > 0) {
                        byte[] b = new byte[(int) cachedScript.length()];
                        try (FileInputStream fis = new FileInputStream(cachedScript)) {
                            int r = fis.read(b);
                            if (r > 0) {
                                failedScript = new String(b, 0, r, StandardCharsets.UTF_8);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (failedScript == null || failedScript.trim().isEmpty()) {
                VynaraLogger.e("ProductionFragment: Self-correction missing script context. Cannot repair.");
                return false;
            }

            handler.post(() -> tvStatus.setText("AI Self-Correction: Optimizing and repairing script..."));

            String repairPrompt;
            boolean isTimeout = traceback.toLowerCase().contains("timeout") || traceback.contains("600s") || traceback.contains("timed out");

            if (isTimeout) {
                repairPrompt = "PERFORMANCE OPTIMIZATION: The Blender script timed out during headless execution. " +
                        "Optimize the code to run in under 60 seconds: batch or join repeated mesh primitives (e.g. windows, floors, mullions, wheels) using bmesh or bpy.ops.object.join(), reduce individual scene object counts, avoid per-object loops with bpy.ops transform applications, and cleanly export to output/model.glb.";
            } else {
                repairPrompt = (prompt != null && !prompt.trim().isEmpty())
                        ? prompt
                        : "Custom Blender Python script. Fix all runtime errors, missing operators, or syntax issues.";
            }

            String repairedScript = controller.getAiCorrector().correctBlenderScriptSync(repairPrompt, failedScript, traceback);

            if (repairedScript == null || repairedScript.trim().isEmpty()) {
                VynaraLogger.e("ProductionFragment: Gemini could not resolve script traceback.");
                return false;
            }

            task.incrementRetryCount();
            task.setRepairedScript(repairedScript);
            task.setLastTraceback(traceback);
            task.setStatus(TaskNode.Status.RETRYING);

            VynaraLogger.logSelfCorrectionRepair();

            handler.post(() -> tvStatus.setText("AI Self-Correction: Re-dispatching build (Attempt 2)..."));

            if (task.getOperation() != null) {
                task.getOperation().setParam("bpyScript", repairedScript);
                task.getOperation().setParam("blender_script", repairedScript);
            }

            ToolExecutor executor = controller.getToolExecutor() != null 
                    ? controller.getToolExecutor() 
                    : controller.getRuntime().getToolExecutor();

            boolean retrySuccess = executor != null && executor.executeOperation(task.getOperation());

            if (retrySuccess) {
                VynaraLogger.system("ProductionFragment: Run 2 succeeded! 3D model built with 0 errors.");
                return true;
            } else {
                VynaraLogger.e("ProductionFragment: Attempt 2 build failed after repair.");
                return false;
            }
        });
    }

    /**
     * OPTION B2: Displays the interactive checkpoint review dialog on device.
     */
    public void showCheckpointReviewDialog(File renderSnapshotFile, String stepBadgeText, String aiCritiqueNotes, CheckpointFeedbackCallback callback) {
        if (getContext() == null || getActivity() == null || getActivity().isFinishing()) return;

        handler.post(() -> {
            Dialog dialog = new Dialog(requireContext());
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(R.layout.dialog_checkpoint_review);

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.setCancelable(false);

            ImageView ivRender = dialog.findViewById(R.id.iv_checkpoint_render);
            TextView tvBadge = dialog.findViewById(R.id.tv_checkpoint_step_badge);
            TextView tvNotes = dialog.findViewById(R.id.tv_ai_critique_notes);
            EditText etFeedback = dialog.findViewById(R.id.et_user_feedback);
            Button btnRequestChanges = dialog.findViewById(R.id.btn_request_changes);
            Button btnApproveFinalize = dialog.findViewById(R.id.btn_approve_finalize);
            View btnCancel = dialog.findViewById(R.id.btn_cancel_review);

            if (tvBadge != null && stepBadgeText != null) {
                tvBadge.setText(stepBadgeText);
            }
            if (tvNotes != null && aiCritiqueNotes != null) {
                tvNotes.setText(aiCritiqueNotes);
            }

            if (ivRender != null && renderSnapshotFile != null && renderSnapshotFile.exists()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(renderSnapshotFile.getAbsolutePath());
                    if (bmp != null) {
                        ivRender.setImageBitmap(bmp);
                    }
                } catch (Exception e) {
                    VynaraLogger.e("ProductionFragment: Failed decoding render snapshot for dialog: " + e.getMessage());
                }
            }

            if (btnRequestChanges != null) {
                btnRequestChanges.setOnClickListener(v -> {
                    String feedbackText = (etFeedback != null) ? etFeedback.getText().toString().trim() : "";
                    if (feedbackText.isEmpty()) {
                        feedbackText = "Refine proportions and align vertices closer to the reference image.";
                    }
                    VynaraLogger.execution("ProductionFragment: User submitted checkpoint feedback: " + feedbackText);
                    dialog.dismiss();
                    if (callback != null) {
                        callback.onRequestChanges(feedbackText);
                    }
                });
            }

            if (btnApproveFinalize != null) {
                btnApproveFinalize.setOnClickListener(v -> {
                    VynaraLogger.system("ProductionFragment: User approved checkpoint. Finalizing 3D model...");
                    dialog.dismiss();
                    if (callback != null) {
                        callback.onApprove();
                    }
                });
            }

            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> {
                    VynaraLogger.system("ProductionFragment: User dismissed checkpoint review dialog.");
                    dialog.dismiss();
                });
            }

            dialog.show();
        });
    }

    /**
     * Executes the background tool execution pipeline on the shared runtime.
     */
    private void startRealExecutionPipeline() {
        if (activePlan == null || activePlan.getTaskGraph() == null) {
            tvStatus.setText("AI Error: Failed to compile production plan.");
            VynaraLogger.e("ProductionFragment: Active plan or TaskGraph is null. Execution aborted.");
            return;
        }

        controller.executeCurrentPlan(new ExecutionEngine.ExecutionCallback() {
            @Override
            public void onTaskUpdated(TaskNode node, TaskGraph graph) {
                handler.post(() -> {
                    if (node != null) {
                        if (node.getStatus() == TaskNode.Status.RETRYING) {
                            tvStatus.setText("AI Self-Correction: Repaired script. Re-dispatching build...");
                        } else {
                            tvStatus.setText("Executing: " + node.getTitle());
                        }
                        adapter.setTasks(graph.getAllNodes());
                    }
                    int completed = graph.getCompletedCount();
                    int total = graph.getTotalCount();
                    int percent = (int) (((float) completed / total) * 100);

                    progressBar.setProgress(percent);
                    tvProgressPercent.setText(percent + "%");
                    tvTaskCounter.setText("Tasks: " + completed + " / " + total);
                });
            }

            @Override
            public void onGraphCompleted(TaskGraph graph) {
                handler.post(() -> {
                    tvStatus.setText("AI Status: All Tasks Completed Successfully! ✦");
                    progressBar.setProgress(100);
                    tvProgressPercent.setText("100%");
                    tvTaskCounter.setText("Tasks: " + graph.getTotalCount() + " / " + graph.getTotalCount());
                    VynaraLogger.system("ProductionFragment: TaskGraph completed all tasks cleanly.");
                    
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).clearActiveProduction();
                    }
                    
                    Toast.makeText(getContext(), "3D Generation Complete!", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    tvStatus.setText("AI Error: Pipeline Halted.");
                    VynaraLogger.e("ProductionFragment: Pipeline execution error: " + errorMessage);
                    Toast.makeText(getContext(), "Workflow halted: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
    }
}