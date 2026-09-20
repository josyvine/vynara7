package com.example.ai;

import android.content.Context;
import android.net.Uri;

import com.example.ai.agents.DirectorAgent;
import com.example.ai.protocol.AIDirectorSpec;
import com.example.ai.protocol.AIPipelineMode;
import com.example.ai.protocol.AIProductionRequest;
import com.example.character.CharacterManager;
import com.example.cloud.CloudProvider;
import com.example.engine.ThreeDEngine;
import com.example.knowledge.KnowledgeManager;
import com.example.runtime.ProjectRuntime;
import com.example.tasks.ExecutionEngine;
import com.example.tasks.ProductionPlan;
import com.example.tasks.TaskNode;
import com.example.tools.ToolExecutor;
import com.example.tools.ToolOperation;
import com.example.tools.ToolRegistry;
import com.example.utils.VynaraLogger;
import com.example.validation.ValidationManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AIProductionController {
    private final Context context;
    private final ProjectRuntime runtime;
    private final ApiKeyManager apiKeyManager;
    private final GeminiApiClient apiClient;
    private final GeminiProvider geminiProvider;
    private final KnowledgeManager knowledgeManager;
    private final ToolRegistry toolRegistry;
    private final ThreeDEngine threeDEngine;
    private final CharacterManager characterManager;
    private final ValidationManager validationManager;
    private final ToolExecutor toolExecutor;
    private final ExecutionEngine executionEngine;
    private final AIOrchestrator orchestrator;

    // Director Agent & Self-Correction Subsystems
    private final DirectorAgent directorAgent;
    private final AICorrector aiCorrector;

    private static final int MAX_REPAIR_ATTEMPTS = 2;
    private int currentCorrectionAttempt = 1;

    private ProductionPlan currentPlan;

    public AIProductionController(Context context) {
        this.context = context.getApplicationContext();
        // Connect to the unified ProjectRuntime instance to eliminate split engine instances
        this.runtime = ProjectRuntime.getInstance(this.context);
        this.apiKeyManager = new ApiKeyManager(this.context);
        this.apiClient = new GeminiApiClient();
        this.geminiProvider = new GeminiProvider(apiClient);
        
        // Bind subsystems directly from the shared ProjectRuntime
        this.knowledgeManager = runtime.getKnowledgeManager();
        this.toolRegistry = runtime.getToolRegistry();
        this.threeDEngine = runtime.getEngine();
        this.characterManager = runtime.getCharacterManager();
        this.validationManager = runtime.getValidationManager();
        this.toolExecutor = runtime.getToolExecutor();
        this.executionEngine = runtime.getExecutionEngine();
        this.orchestrator = new AIOrchestrator(apiClient, apiKeyManager, knowledgeManager);

        // Director Agent & AICorrector
        this.directorAgent = new DirectorAgent(this.apiClient, this.apiKeyManager);
        this.aiCorrector = new AICorrector(this.toolExecutor, this.orchestrator, null);
    }

    public ProductionPlan generatePlan(String userPrompt, String style, String engine) {
        return generatePlan(userPrompt, style, engine, AIPipelineMode.PROCEDURAL_PYTHON.getId(), new ArrayList<>());
    }

    public ProductionPlan generatePlan(String userPrompt, String style, String engine, List<String> referenceImageUris) {
        return generatePlan(userPrompt, style, engine, AIPipelineMode.PROCEDURAL_PYTHON.getId(), referenceImageUris);
    }

    public ProductionPlan generatePlan(String userPrompt, String style, String engine, String pipelineModeId, List<String> referenceImageUris) {
        if (engine != null && (engine.toLowerCase().contains("blender") || engine.toLowerCase().contains("cloud"))) {
            apiKeyManager.saveComputeProvider(CloudProvider.GITHUB_ACTIONS);
        }

        AIPipelineMode mode = AIPipelineMode.fromDisplayNameSafe(pipelineModeId);
        VynaraLogger.system("AIProductionController: Compiling synchronous plan for -> " + mode.getDisplayName());

        List<String> resolvedUris = resolveReferenceUris(referenceImageUris);
        currentPlan = orchestrator.planProduction(userPrompt, style, engine, resolvedUris);

        String customScriptPath = findCustomScriptPath(resolvedUris);
        if (customScriptPath != null && currentPlan != null && currentPlan.getTaskGraph() != null) {
            String scriptText = readScriptContent(customScriptPath);
            injectCustomScriptIntoPlan(currentPlan, scriptText);
        }

        return currentPlan;
    }

    public void generatePlanWithGemini(String userPrompt, String style, String engine, List<String> referenceImageUris, final GeminiApiClient.ApiCallback<ProductionPlan> callback) {
        generatePlanWithGemini(userPrompt, style, engine, AIPipelineMode.PROCEDURAL_PYTHON.getId(), referenceImageUris, callback);
    }

    /**
     * CORE PIPELINE DISPATCHER: Strictly resolves the requested pipeline mode (Option A, B1, B2, or C).
     * Validates execution contracts and halts on missing prerequisites instead of silently degrading.
     */
    public void generatePlanWithGemini(String userPrompt,
                                       String style,
                                       String engine,
                                       String pipelineModeId,
                                       List<String> referenceImageUris,
                                       final GeminiApiClient.ApiCallback<ProductionPlan> callback) {
        if (callback == null) return;

        AIPipelineMode mode = AIPipelineMode.fromDisplayNameSafe(pipelineModeId);
        VynaraLogger.system("AIProductionController: Validating pipeline contract for [" + mode.getDisplayName() + "]...");

        if (engine != null && (engine.toLowerCase().contains("blender") || engine.toLowerCase().contains("cloud"))) {
            apiKeyManager.saveComputeProvider(CloudProvider.GITHUB_ACTIONS);
        }

        List<String> resolvedUris = resolveReferenceUris(referenceImageUris);
        String customScriptPath = findCustomScriptPath(resolvedUris);
        File firstRefImg = getFirstReferenceImageFile(resolvedUris);

        int refImageCount = 0;
        if (resolvedUris != null) {
            for (String u : resolvedUris) {
                if (u != null && !u.toLowerCase().endsWith(".py")) {
                    File f = new File(u);
                    if (f.exists() && f.length() > 0) refImageCount++;
                }
            }
        }

        boolean hasPrompt = (userPrompt != null && !userPrompt.trim().isEmpty());
        boolean hasScript = (customScriptPath != null);
        boolean hasCloudAuth = apiKeyManager.hasApiKey() 
                || (apiKeyManager.getGitHubPat() != null && !apiKeyManager.getGitHubPat().trim().isEmpty())
                || apiKeyManager.hasGitHubConfig();

        // STRICT PRE-FLIGHT CONTRACT VALIDATION (PREVENTS SILENT DEGRADATION TO FALLBACK)
        AIPipelineMode.ExecutionValidationStatus status = mode.validateExecutionContract(hasPrompt, hasScript, refImageCount, hasCloudAuth);
        if (!status.isValid()) {
            VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, "AIProductionController: Contract check REJECTED: " + status.getErrorMessage());
            callback.onError(status.getErrorMessage());
            return; // STRICT HALT: Stops immediately without generating fallback cubes!
        }

        // =========================================================================
        // PIPELINE OPTION C: NEURAL IMAGE-TO-3D RECONSTRUCTION
        // =========================================================================
        if (mode.isNeural()) {
            VynaraLogger.system("AIProductionController: [OPTION C] Directing workflow to Neural Image-to-3D Pipeline...");
            ProductionPlan neuralPlan = orchestrator.planProduction("Neural 3D: " + userPrompt, style, engine, resolvedUris);

            if (neuralPlan != null && neuralPlan.getTaskGraph() != null) {
                for (TaskNode node : neuralPlan.getTaskGraph().getAllNodes()) {
                    if (node.getOperation() != null && "blender.cloud_generate".equals(node.getOperation().getToolId())) {
                        ToolOperation newOp = new ToolOperation(mode.getToolId(), node.getOperation().getParameters());
                        newOp.setParam("imagePath", firstRefImg.getAbsolutePath());
                        newOp.setParam("pipelineMode", mode.getId());
                        node.setOperation(newOp);
                        node.setTitle("Neural 3D Reconstruction");
                        node.setDescription("Synthesizing watertight 3D polygon mesh from reference photo");
                        VynaraLogger.system("AIProductionController: Injected neural 3D task [" + node.getId() + "] referencing " + firstRefImg.getName());
                    }
                }
            }

            this.currentPlan = neuralPlan;
            callback.onSuccess(neuralPlan);
            return;
        }

        // =========================================================================
        // PIPELINE OPTION B2: INTERACTIVE AI DESIGN CHECKPOINTS (HUMAN-IN-THE-LOOP)
        // =========================================================================
        if (mode.isInteractive()) {
            VynaraLogger.system("AIProductionController: [OPTION B2] Initializing Interactive AI Designer Checkpoint Plan...");
            ProductionPlan interactivePlan = orchestrator.planProduction("Interactive Design: " + userPrompt, style, engine, resolvedUris);

            if (interactivePlan != null && interactivePlan.getTaskGraph() != null) {
                for (TaskNode node : interactivePlan.getTaskGraph().getAllNodes()) {
                    if (node.getOperation() != null && "blender.cloud_generate".equals(node.getOperation().getToolId())) {
                        ToolOperation newOp = new ToolOperation(mode.getToolId(), node.getOperation().getParameters());
                        newOp.setParam("agenticMode", true);
                        newOp.setParam("interactiveCheckpoint", true);
                        newOp.setParam("pipelineMode", mode.getId());
                        if (firstRefImg != null) {
                            newOp.setParam("referenceImagePath", firstRefImg.getAbsolutePath());
                        }
                        node.setOperation(newOp);
                        node.setTitle("Interactive AI Designer Checkpoint");
                        node.setDescription("Blockout generation with pause for mobile user critique");
                        VynaraLogger.system("AIProductionController: Configured interactive checkpoint task [" + node.getId() + "]");
                    }
                }
            }

            if (customScriptPath != null) {
                String scriptText = readScriptContent(customScriptPath);
                injectCustomScriptIntoPlan(interactivePlan, scriptText);
            }

            this.currentPlan = interactivePlan;
            callback.onSuccess(interactivePlan);
            return;
        }

        // =========================================================================
        // PIPELINE OPTION B1: AUTONOMOUS AI VISION DESIGN LOOP
        // =========================================================================
        if (mode.isAgentic()) {
            VynaraLogger.system("AIProductionController: [OPTION B1] Initializing Autonomous Vision-Feedback Design Plan...");
            ProductionPlan autoPlan = orchestrator.planProduction("Autonomous Design: " + userPrompt, style, engine, resolvedUris);

            if (autoPlan != null && autoPlan.getTaskGraph() != null) {
                for (TaskNode node : autoPlan.getTaskGraph().getAllNodes()) {
                    if (node.getOperation() != null && "blender.cloud_generate".equals(node.getOperation().getToolId())) {
                        ToolOperation newOp = new ToolOperation(mode.getToolId(), node.getOperation().getParameters());
                        newOp.setParam("agenticMode", true);
                        newOp.setParam("interactiveCheckpoint", false);
                        newOp.setParam("pipelineMode", mode.getId());
                        if (firstRefImg != null) {
                            newOp.setParam("referenceImagePath", firstRefImg.getAbsolutePath());
                        }
                        node.setOperation(newOp);
                        node.setTitle("Autonomous AI Vision Modeling");
                        node.setDescription("Multi-turn progressive mesh refinement using visual inspection");
                        VynaraLogger.system("AIProductionController: Configured autonomous agent task [" + node.getId() + "]");
                    }
                }
            }

            if (customScriptPath != null) {
                String scriptText = readScriptContent(customScriptPath);
                injectCustomScriptIntoPlan(autoPlan, scriptText);
            }

            this.currentPlan = autoPlan;
            callback.onSuccess(autoPlan);
            return;
        }

        // =========================================================================
        // PIPELINE OPTION A: PROCEDURAL PYTHON SCRIPT (STANDALONE SCRIPT UPLOAD)
        // =========================================================================
        if (customScriptPath != null) {
            VynaraLogger.system("AIProductionController: [OPTION A] Custom Python script detected [" + customScriptPath + "]. Direct dispatch mode.");
            ProductionPlan scriptPlan = orchestrator.planProduction(userPrompt, style, engine, resolvedUris);
            if (scriptPlan != null && scriptPlan.getTaskGraph() != null) {
                String scriptText = readScriptContent(customScriptPath);
                injectCustomScriptIntoPlan(scriptPlan, scriptText);
            }
            this.currentPlan = scriptPlan;
            callback.onSuccess(scriptPlan);
            return;
        }

        VynaraLogger.system("AIProductionController: [OPTION A] Querying Gemini for procedural 3D production plan...");
        AIProductionRequest request = new AIProductionRequest(userPrompt, style, engine);
        if (resolvedUris != null) {
            for (String uri : resolvedUris) {
                request.addReferenceImageUri(uri);
            }
        }

        orchestrator.planProductionWithGemini(request, new GeminiApiClient.ApiCallback<ProductionPlan>() {
            @Override
            public void onSuccess(ProductionPlan plan) {
                currentPlan = plan;
                callback.onSuccess(plan);
            }

            @Override
            public void onError(String errorMessage) {
                VynaraLogger.e("AIProductionController: Gemini planning failed: " + errorMessage);
                callback.onError(errorMessage);
            }
        });
    }

    public void executeCurrentPlan(ExecutionEngine.ExecutionCallback callback) {
        if (currentPlan != null && currentPlan.getTaskGraph() != null) {
            runtime.getTransactionManager().beginTransaction("Execute AI Plan: " + currentPlan.getProjectName());
            
            executionEngine.executeGraph(currentPlan.getTaskGraph(), new ExecutionEngine.ExecutionCallback() {
                @Override
                public void onTaskUpdated(com.example.tasks.TaskNode node, com.example.tasks.TaskGraph graph) {
                    if (callback != null) callback.onTaskUpdated(node, graph);
                }

                @Override
                public void onGraphCompleted(com.example.tasks.TaskGraph graph) {
                    runtime.getTransactionManager().commitTransaction();
                    if (callback != null) callback.onGraphCompleted(graph);
                }

                @Override
                public void onError(String errorMessage) {
                    runtime.getTransactionManager().rollbackTransaction();
                    if (callback != null) callback.onError(errorMessage);
                }
            });
        } else {
            if (callback != null) callback.onError("No active production plan to execute.");
        }
    }

    public void repairBlenderScript(String userPrompt,
                                    String failedScript,
                                    String errorTraceback,
                                    final GeminiApiClient.ApiCallback<String> callback) {
        if (aiCorrector == null) {
            if (callback != null) callback.onError("AICorrector subsystem is not initialized.");
            return;
        }

        if (currentCorrectionAttempt > MAX_REPAIR_ATTEMPTS) {
            String msg = "AI Self-Correction exceeded maximum attempts (" + MAX_REPAIR_ATTEMPTS + ").";
            VynaraLogger.e("AIProductionController: " + msg);
            if (callback != null) callback.onError(msg);
            return;
        }

        String safePrompt = (userPrompt != null && !userPrompt.trim().isEmpty())
                ? userPrompt
                : "Execute and fix this custom Blender Python script to build a clean 3D scene without syntax or operator errors.";

        VynaraLogger.system("AIProductionController: Initiating AI Self-Correction (Attempt " + currentCorrectionAttempt + "/" + MAX_REPAIR_ATTEMPTS + ")...");

        aiCorrector.correctBlenderScript(safePrompt, failedScript, errorTraceback, new GeminiApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String repairedScript) {
                currentCorrectionAttempt++;
                VynaraLogger.system("AI Self-Correction: Repaired script successfully. Re-dispatching build...");
                if (callback != null) {
                    callback.onSuccess(repairedScript);
                }
            }

            @Override
            public void onError(String errorMessage) {
                VynaraLogger.e("AIProductionController: AI Self-Correction repair failed: " + errorMessage);
                if (callback != null) {
                    callback.onError(errorMessage);
                }
            }
        });
    }

    public void visuallyCritiqueAndRefine(String userPrompt,
                                          String currentScript,
                                          File referenceImageFile,
                                          File renderPreviewFile,
                                          final GeminiApiClient.ApiCallback<String> callback) {
        if (aiCorrector == null) {
            if (callback != null) callback.onError("AICorrector subsystem is not initialized.");
            return;
        }
        VynaraLogger.system("AIProductionController: Triggering multimodal visual critique loop...");
        aiCorrector.critiqueAndRefineBlenderScript(userPrompt, currentScript, referenceImageFile, renderPreviewFile, callback);
    }

    public String visuallyCritiqueAndRefineSync(String userPrompt,
                                                String currentScript,
                                                File referenceImageFile,
                                                File renderPreviewFile) {
        if (aiCorrector == null) return null;
        return aiCorrector.critiqueAndRefineBlenderScriptSync(userPrompt, currentScript, referenceImageFile, renderPreviewFile);
    }

    /**
     * Injects the raw user script cleanly into the task graph.
     * Marks execution parameters with is_raw_script=true, isRawUserScript=true, and pipelineMode=OPTION_A,
     * ensuring the cloud worker does not force vehicle road generators or 60-frame animation loops.
     */
    private void injectCustomScriptIntoPlan(ProductionPlan plan, String scriptText) {
        if (plan == null || plan.getTaskGraph() == null || scriptText == null || scriptText.isEmpty()) return;
        for (TaskNode node : plan.getTaskGraph().getAllNodes()) {
            if (node.getOperation() != null && "blender.cloud_generate".equals(node.getOperation().getToolId())) {
                // Configure ToolOperation parameters cleanly
                node.getOperation().setParam("bpyScript", scriptText);
                node.getOperation().setParam("blender_script", scriptText);
                node.getOperation().setParam("is_raw_script", true);
                node.getOperation().setParam("isRawUserScript", true);
                node.getOperation().setParam("pipelineMode", AIPipelineMode.PROCEDURAL_PYTHON.getId());
                node.getOperation().setParam("agenticMode", false);
                node.getOperation().setParam("interactiveCheckpoint", false);

                node.setTitle("Execute Procedural Python Script");
                node.setDescription("Executing standalone procedural Blender script without scene injection");
                VynaraLogger.system("AIProductionController: Injected raw procedural Python script into task [" + node.getId() + "] (is_raw_script=true)");
            }
        }
    }

    private String findCustomScriptPath(List<String> uris) {
        if (uris == null) return null;
        for (String u : uris) {
            if (u != null && (u.toLowerCase().endsWith(".py") || u.contains("custom_user_script.py"))) {
                return u;
            }
        }
        return null;
    }

    private String readScriptContent(String filePath) {
        try {
            File f = new File(filePath);
            if (f.exists() && f.length() > 0) {
                byte[] bytes = new byte[(int) f.length()];
                try (FileInputStream fis = new FileInputStream(f)) {
                    int read = fis.read(bytes);
                    if (read > 0) {
                        return new String(bytes, 0, read, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            VynaraLogger.e("AIProductionController: Error reading script file: " + e.getMessage());
        }
        return "";
    }

    public File getFirstReferenceImageFile(List<String> resolvedUris) {
        if (resolvedUris != null && !resolvedUris.isEmpty()) {
            for (String path : resolvedUris) {
                if (path != null && !path.trim().isEmpty() && !path.toLowerCase().endsWith(".py")) {
                    File f = new File(path);
                    if (f.exists() && f.length() > 0) return f;
                }
            }
        }
        return null;
    }

    public int getCurrentCorrectionAttempt() {
        return currentCorrectionAttempt;
    }

    public void resetCorrectionAttempts() {
        this.currentCorrectionAttempt = 1;
    }

    public List<String> resolveReferenceUris(List<String> uris) {
        List<String> resolved = new ArrayList<>();
        if (uris == null || uris.isEmpty()) return resolved;

        for (String uriStr : uris) {
            if (uriStr == null || uriStr.trim().isEmpty()) continue;
            
            if (uriStr.startsWith("content://")) {
                try {
                    Uri uri = Uri.parse(uriStr);
                    InputStream inputStream = context.getContentResolver().openInputStream(uri);
                    if (inputStream != null) {
                        File cacheDir = new File(context.getCacheDir(), "ref_images");
                        if (!cacheDir.exists()) cacheDir.mkdirs();
                        
                        File destFile = new File(cacheDir, "ref_" + System.currentTimeMillis() + ".jpg");
                        FileOutputStream outputStream = new FileOutputStream(destFile);
                        
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                        outputStream.flush();
                        outputStream.close();
                        inputStream.close();
                        
                        resolved.add(destFile.getAbsolutePath());
                        continue;
                    }
                } catch (Exception e) {
                    VynaraLogger.e("AIProductionController: Failed resolving content URI: " + e.getMessage());
                }
            }
            resolved.add(uriStr);
        }
        return resolved;
    }

    public Context getContext() { return context; }
    public ProjectRuntime getRuntime() { return runtime; }
    public ApiKeyManager getApiKeyManager() { return apiKeyManager; }
    public GeminiApiClient getApiClient() { return apiClient; }
    public GeminiProvider getGeminiProvider() { return geminiProvider; }
    public KnowledgeManager getKnowledgeManager() { return knowledgeManager; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public ThreeDEngine getThreeDEngine() { return threeDEngine; }
    public CharacterManager getCharacterManager() { return characterManager; }
    public ValidationManager getValidationManager() { return validationManager; }
    public ToolExecutor getToolExecutor() { return toolExecutor; }
    public ExecutionEngine getExecutionEngine() { return executionEngine; }
    public AIOrchestrator getOrchestrator() { return orchestrator; }
    public ProductionPlan getCurrentPlan() { return currentPlan; }
    public AICorrector getAiCorrector() { return aiCorrector; }
    public DirectorAgent getDirectorAgent() { return directorAgent; }
}