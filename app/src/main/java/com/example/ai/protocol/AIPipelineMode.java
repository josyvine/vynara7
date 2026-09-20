package com.example.ai.protocol;

import com.example.utils.VynaraLogger;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Production-Grade Pipeline Execution Contract for Vynara.
 * Directly integrates with VynaraLogger to broadcast real-time status,
 * pre-flight validation gates, and error traces to the in-app floating console.
 */
public enum AIPipelineMode implements Serializable {

    PROCEDURAL_PYTHON(
            "pipeline_opt_a_procedural",
            "Option A: Procedural Script (Fast)",
            "blender.cloud_generate",
            "vynara_generate",
            false,  // requiresReferenceImage
            false,  // isInteractive
            false,  // usesVisionFeedback
            180000L, // 3 minutes
            300000L  // 5 minutes
    ),

    AGENTIC_AUTONOMOUS(
            "pipeline_opt_b1_autonomous",
            "Option B1: AI Designer (Autonomous Vision Loop)",
            "blender.agentic_autonomous",
            "vynara_agentic_auto",
            false,
            false,
            true,   // Gemini Vision inspects intermediate renders
            300000L, // 5 minutes
            600000L  // 10 minutes
    ),

    AGENTIC_INTERACTIVE(
            "pipeline_opt_b2_interactive",
            "Option B2: AI Designer (Interactive Checkpoint Review)",
            "blender.agentic_interactive",
            "vynara_agentic_interactive",
            false,
            true,   // Pauses for user review on mobile device
            true,
            360000L, // 6 minutes
            900000L  // 15 minutes
    ),

    NEURAL_IMAGE_TO_3D(
            "pipeline_opt_c_neural",
            "Option C: Neural Image-to-3D (Instant Mesh)",
            "neural.image_to_3d",
            "vynara_neural_reconstruct",
            true,   // MANDATORY: Reference photo required
            false,
            false,
            60000L,  // 1 minute
            180000L  // 3 minutes
    );

    private final String id;
    private final String displayName;
    private final String toolId;
    private final String githubEventType;
    private final boolean requiresReferenceImage;
    private final boolean isInteractive;
    private final boolean usesVisionFeedback;
    private final long expectedTimeoutMs;
    private final long maxTimeoutMs;

    private static final Map<String, AIPipelineMode> ID_LOOKUP;
    private static final Map<String, AIPipelineMode> DISPLAY_LOOKUP;

    static {
        Map<String, AIPipelineMode> idMap = new HashMap<>();
        Map<String, AIPipelineMode> displayMap = new HashMap<>();
        for (AIPipelineMode mode : values()) {
            idMap.put(mode.id.toLowerCase(), mode);
            displayMap.put(mode.displayName.toLowerCase(), mode);
        }
        ID_LOOKUP = Collections.unmodifiableMap(idMap);
        DISPLAY_LOOKUP = Collections.unmodifiableMap(displayMap);
    }

    AIPipelineMode(String id,
                   String displayName,
                   String toolId,
                   String githubEventType,
                   boolean requiresReferenceImage,
                   boolean isInteractive,
                   boolean usesVisionFeedback,
                   long expectedTimeoutMs,
                   long maxTimeoutMs) {
        this.id = id;
        this.displayName = displayName;
        this.toolId = toolId;
        this.githubEventType = githubEventType;
        this.requiresReferenceImage = requiresReferenceImage;
        this.isInteractive = isInteractive;
        this.usesVisionFeedback = usesVisionFeedback;
        this.expectedTimeoutMs = expectedTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getToolId() { return toolId; }
    public String getGithubEventType() { return githubEventType; }
    public boolean requiresReferenceImage() { return requiresReferenceImage; }
    public boolean isInteractive() { return isInteractive; }
    public boolean usesVisionFeedback() { return usesVisionFeedback; }
    public long getExpectedTimeoutMs() { return expectedTimeoutMs; }
    public long getMaxTimeoutMs() { return maxTimeoutMs; }

    // --- PIPELINE CLASSIFICATION HELPERS ---
    public boolean isProcedural() {
        return this == PROCEDURAL_PYTHON;
    }

    public boolean isAgentic() {
        return this == AGENTIC_AUTONOMOUS || this == AGENTIC_INTERACTIVE;
    }

    public boolean isNeural() {
        return this == NEURAL_IMAGE_TO_3D;
    }

    /**
     * Strictly verifies whether this pipeline mode's prerequisites are met.
     * Emits real-time validation logs directly to Vynara's in-app console.
     */
    public ExecutionValidationStatus validateExecutionContract(boolean hasPrompt,
                                                               boolean hasScript,
                                                               int referenceImageCount,
                                                               boolean hasCloudAuth) {
        VynaraLogger.system("AIPipelineMode: Validating execution contract for [" + displayName + "]...");

        if (!hasCloudAuth) {
            String err = "Missing Cloud Credentials: " + displayName + " requires a configured GitHub or Hugging Face token.";
            VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, err);
            return ExecutionValidationStatus.error(err);
        }

        switch (this) {
            case NEURAL_IMAGE_TO_3D:
                if (referenceImageCount <= 0) {
                    String err = "Option C REJECTED: Neural Image-to-3D strictly requires at least one reference photo. Execution halted.";
                    VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, err);
                    return ExecutionValidationStatus.error(err);
                }
                VynaraLogger.validation(VynaraLogger.LogLevel.INFO, "Option C Contract Verified: " + referenceImageCount + " reference image(s) available.");
                break;

            case AGENTIC_INTERACTIVE:
            case AGENTIC_AUTONOMOUS:
                if (!hasPrompt && !hasScript && referenceImageCount <= 0) {
                    String err = displayName + " REJECTED: Requires either a prompt or reference image to initialize the design loop.";
                    VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, err);
                    return ExecutionValidationStatus.error(err);
                }
                VynaraLogger.validation(VynaraLogger.LogLevel.INFO, displayName + " Contract Verified. Vision feedback loop enabled.");
                break;

            case PROCEDURAL_PYTHON:
            default:
                if (!hasPrompt && !hasScript) {
                    String err = "Option A REJECTED: Requires either a text prompt or a custom Python script.";
                    VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, err);
                    return ExecutionValidationStatus.error(err);
                }
                VynaraLogger.validation(VynaraLogger.LogLevel.INFO, "Option A Contract Verified: Procedural Python pipeline initialized.");
                break;
        }

        return ExecutionValidationStatus.valid();
    }

    /**
     * Resolves a raw string into a pipeline mode strictly.
     * Emits an explicit log entry when resolved or rejected.
     */
    public static AIPipelineMode parseStrict(String raw) throws IllegalArgumentException {
        if (raw == null || raw.trim().isEmpty()) {
            VynaraLogger.e("AIPipelineMode: parseStrict received null or empty string.");
            throw new IllegalArgumentException("Pipeline mode string cannot be null or empty.");
        }

        String cleaned = raw.trim();
        String lower = cleaned.toLowerCase();

        // 1. Exact ID Match
        if (ID_LOOKUP.containsKey(lower)) {
            AIPipelineMode m = ID_LOOKUP.get(lower);
            VynaraLogger.system("AIPipelineMode: Mapped by ID -> " + m.displayName);
            return m;
        }

        // 2. Exact Display Name Match
        if (DISPLAY_LOOKUP.containsKey(lower)) {
            AIPipelineMode m = DISPLAY_LOOKUP.get(lower);
            VynaraLogger.system("AIPipelineMode: Mapped by Display Name -> " + m.displayName);
            return m;
        }

        // 3. Enum Identifier Match
        try {
            AIPipelineMode m = AIPipelineMode.valueOf(cleaned.toUpperCase());
            VynaraLogger.system("AIPipelineMode: Mapped by Enum Value -> " + m.displayName);
            return m;
        } catch (IllegalArgumentException ignored) {}

        // 4. Deterministic Prefix Match
        AIPipelineMode resolved = null;
        if (lower.startsWith("option a") || lower.contains("procedural")) {
            resolved = PROCEDURAL_PYTHON;
        } else if (lower.startsWith("option b2") || (lower.contains("interactive") && lower.contains("checkpoint"))) {
            resolved = AGENTIC_INTERACTIVE;
        } else if (lower.startsWith("option b1") || (lower.contains("autonomous") && lower.contains("vision"))) {
            resolved = AGENTIC_AUTONOMOUS;
        } else if (lower.startsWith("option c") || lower.contains("neural") || lower.contains("image-to-3d")) {
            resolved = NEURAL_IMAGE_TO_3D;
        }

        if (resolved != null) {
            VynaraLogger.system("AIPipelineMode: Deterministically resolved [" + raw + "] -> " + resolved.displayName);
            return resolved;
        }

        String fatalMsg = "CRITICAL: Unrecognized Vynara pipeline mode [" + raw + "]. Execution halted to prevent fallback degradation.";
        VynaraLogger.e(fatalMsg);
        throw new IllegalArgumentException(fatalMsg);
    }

    /**
     * Safe lookup for UI initialization.
     */
    public static AIPipelineMode fromDisplayNameSafe(String name) {
        try {
            return parseStrict(name);
        } catch (Exception e) {
            VynaraLogger.w("AIPipelineMode: Initializing default to PROCEDURAL_PYTHON due to: " + e.getMessage());
            return PROCEDURAL_PYTHON;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static class ExecutionValidationStatus implements Serializable {
        private final boolean valid;
        private final String errorMessage;

        private ExecutionValidationStatus(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ExecutionValidationStatus valid() {
            return new ExecutionValidationStatus(true, null);
        }

        public static ExecutionValidationStatus error(String message) {
            return new ExecutionValidationStatus(false, message);
        }

        public boolean isValid() { return valid; }
        public String getErrorMessage() { return errorMessage != null ? errorMessage : "Unknown validation failure."; }
    }
}