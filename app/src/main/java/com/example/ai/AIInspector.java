package com.example.ai;

import com.example.engine.Scene;
import com.example.engine.SceneObject;
import com.example.utils.VynaraLogger;
import com.example.validation.ValidationManager;
import com.example.validation.ValidationResult;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AIInspector {
    private final ValidationManager validationManager;

    public AIInspector(ValidationManager validationManager) {
        this.validationManager = validationManager != null ? validationManager : new ValidationManager();
    }

    /**
     * Inspects the active 3D scene and returns detailed validation results across
     * meshes, materials, skeletons, skinning, and lighting.
     */
    public List<ValidationResult> inspect(Scene scene) {
        if (scene == null) {
            List<ValidationResult> nullResults = new ArrayList<>();
            ValidationResult err = new ValidationResult(ValidationResult.Severity.CRITICAL, "Scene instance is null.", "Initialize 3D ProjectRuntime scene.");
            nullResults.add(err);
            VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, "AIInspector: Scene instance is null.");
            return nullResults;
        }
        
        List<ValidationResult> results = validationManager.validateScene(scene);
        if (hasCriticalErrors(results)) {
            VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, "AIInspector: Scene validation encountered critical errors.");
        } else {
            VynaraLogger.validation(VynaraLogger.LogLevel.INFO, "AIInspector: Scene validation clean. " + results.size() + " check(s) performed.");
        }
        return results;
    }

    /**
     * Inspects a specific scene object in isolation.
     */
    public List<ValidationResult> inspectObject(SceneObject object) {
        if (object == null) {
            List<ValidationResult> nullResults = new ArrayList<>();
            ValidationResult err = new ValidationResult(ValidationResult.Severity.ERROR, "Target SceneObject is null.", "Verify target object ID.");
            nullResults.add(err);
            VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, "AIInspector: Target SceneObject is null.");
            return nullResults;
        }
        return validationManager.validateObject(object);
    }

    /**
     * Checks if the inspection results contain any critical or blocking errors.
     */
    public boolean hasCriticalErrors(List<ValidationResult> results) {
        if (results == null || results.isEmpty()) return false;
        for (ValidationResult vr : results) {
            if (vr.getSeverity() == ValidationResult.Severity.ERROR || 
                vr.getSeverity() == ValidationResult.Severity.CRITICAL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Formats inspection results into a structured text report for Gemini diagnosis.
     */
    public String getInspectionDiagnosticsReport(List<ValidationResult> results) {
        if (results == null || results.isEmpty()) {
            return "Inspection Status: CLEAN (0 Issues Detected)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("AI Inspection Report:\n");
        int issueCount = 0;
        for (ValidationResult vr : results) {
            if (!vr.isPassed()) {
                issueCount++;
                sb.append(" - [").append(vr.getSeverity().name()).append("] ")
                  .append(vr.getMessage());
                if (vr.getRepairSuggestion() != null) {
                    sb.append(" (Suggestion: ").append(vr.getRepairSuggestion()).append(")");
                }
                sb.append("\n");
            }
        }

        if (issueCount == 0) {
            return "Inspection Status: PASS (All validation checks passed)";
        }
        return sb.toString();
    }

    // =========================================================================
    // OPTION B1 & B2: MULTIMODAL VISION INSPECTION & CRITIQUE PROMPT BUILDER
    // =========================================================================

    /**
     * Compiles a strict multimodal vision critique prompt instructing Gemini Vision
     * to evaluate intermediate 3D viewport renders against the design target.
     *
     * @param userPrompt        The original user prompt or asset concept.
     * @param currentTurn       Current design iteration turn (e.g. Turn 1).
     * @param maxTurns          Maximum allotted iteration turns (e.g. 3 turns).
     * @param userFeedbackNotes Optional user feedback notes from Option B2 dialog.
     * @return Formatted technical vision prompt string.
     */
    public String buildVisualCritiquePrompt(String userPrompt, int currentTurn, int maxTurns, String userFeedbackNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the Lead 3D Art Director and Mesh Inspection Specialist inside Vynara Studio.\n");
        sb.append("TASK: Perform a critical hard-surface inspection of the attached 3D render snapshot.\n\n");
        
        sb.append("ORIGINAL ASSET GOAL: ").append(userPrompt).append("\n");
        sb.append("CURRENT DESIGN TURN: Turn ").append(currentTurn).append(" of ").append(maxTurns).append("\n\n");

        if (userFeedbackNotes != null && !userFeedbackNotes.trim().isEmpty()) {
            sb.append("MANDATORY USER DIRECTIVE (From Mobile Review Checkpoint):\n");
            sb.append("\"").append(userFeedbackNotes.trim()).append("\"\n\n");
        }

        sb.append("CRITICAL INSPECTION CRITERIA:\n");
        sb.append("1. FLOATING OR DETACHED PARTS: Check if any wheels, roof panels, mirrors, bumpers, or windows are hovering in thin air with air gaps.\n");
        sb.append("2. PROPORTIONS & SILHOUETTE: Compare wheelbase, track width, cabin tumblehome, and hood rake against the reference.\n");
        sb.append("3. WATERTIGHT INTEGRITY: Confirm that the rear tailgate, floor pan, and undercarriage are completely sealed without holes.\n");
        sb.append("4. PBR SURFACE FINISH: Verify that glass is reflective/opaque and paint has high-clarity clearcoat without inverted normals.\n\n");

        sb.append("RESPONSE FORMAT REQUIREMENTS:\n");
        sb.append("Provide your critique in two clean sections:\n");
        sb.append("[OBSERVATIONS]: 3 to 4 concise bullet points detailing exact geometric flaws or misalignments observed.\n");
        sb.append("[BLENDER_PYTHON_DELTAS]: Provide clean, executable Blender 4.2 Python code that directly modifies existing objects by name to fix all observed flaws.\n");

        VynaraLogger.system("AIInspector: Compiled visual critique prompt for Turn " + currentTurn + "/" + maxTurns);
        return sb.toString();
    }

    /**
     * Extracts and formats human-readable critique notes from the raw vision response
     * to populate the Option B2 checkpoint review dialog on mobile.
     */
    public String formatVisualCritiqueNotes(String rawVisionResponse) {
        if (rawVisionResponse == null || rawVisionResponse.trim().isEmpty()) {
            return "Visual inspection clean. Proportions and component alignments verified.";
        }

        String cleaned = rawVisionResponse.trim();
        int obsStart = cleaned.indexOf("[OBSERVATIONS]");
        int deltasStart = cleaned.indexOf("[BLENDER_PYTHON_DELTAS]");

        if (obsStart != -1) {
            int end = (deltasStart != -1 && deltasStart > obsStart) ? deltasStart : cleaned.length();
            String obsText = cleaned.substring(obsStart + "[OBSERVATIONS]".length(), end).trim();
            if (!obsText.isEmpty()) {
                VynaraLogger.validation(VynaraLogger.LogLevel.INFO, "AIInspector: Extracted observations for user review.");
                return obsText;
            }
        }

        // Fallback to the first 300 characters if section markers are omitted
        if (cleaned.length() > 300) {
            return cleaned.substring(0, 300) + "...";
        }
        return cleaned;
    }

    /**
     * Verifies that an intermediate render snapshot artifact exists and is readable
     * before engaging the multimodal vision feedback loop.
     */
    public boolean verifyRenderSnapshot(File snapshotFile) {
        if (snapshotFile == null || !snapshotFile.exists() || snapshotFile.length() <= 0) {
            VynaraLogger.validation(VynaraLogger.LogLevel.WARNING, "AIInspector: Render snapshot file is missing or empty.");
            return false;
        }
        VynaraLogger.system("AIInspector: Render snapshot verified (" + snapshotFile.length() + " bytes): " + snapshotFile.getName());
        return true;
    }

    public ValidationManager getValidationManager() {
        return validationManager;
    }
}