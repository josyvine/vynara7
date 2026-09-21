package com.example.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.MainActivity;
import com.example.R;
import com.example.ai.ApiKeyManager;
import com.example.ai.protocol.AIPipelineMode;
import com.example.asset.Asset;
import com.example.runtime.ProjectRuntime;
import com.example.utils.VynaraLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CreateFragment extends Fragment {

    private EditText etPrompt;
    private TextView tvReferenceCount;
    private TextView tvToggleAdvanced;
    private LinearLayout layoutAdvancedContent;
    private Spinner spinnerStyle, spinnerQuality, spinnerTarget, spinnerAutoMode;

    private final List<Uri> selectedImageUris = new ArrayList<>();
    private Uri selectedScriptUri = null;
    private String selectedScriptFileName = null;

    private ActivityResultLauncher<String> imagePickerLauncher;
    private ActivityResultLauncher<String> scriptPickerLauncher;
    private ActivityResultLauncher<String> permissionLauncher;

    // Bridges the logical gap: binds selected asset from Assets/Studio directly into generation
    private ProjectRuntime runtime;
    private Asset currentActiveAsset = null;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Launcher for reference images
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetMultipleContents(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        selectedImageUris.addAll(uris);
                        updateReferenceUI();
                        VynaraLogger.system("CreateFragment: " + uris.size() + " reference image(s) attached.");
                        Toast.makeText(getContext(), uris.size() + " reference image(s) added successfully!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "No reference image selected", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // Launcher for custom Python script upload (.py)
        scriptPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        selectedScriptUri = uri;
                        selectedScriptFileName = getFileNameFromUri(uri);
                        if (selectedScriptFileName == null || selectedScriptFileName.isEmpty()) {
                            selectedScriptFileName = "custom_script.py";
                        }

                        // Attaching a custom procedural script disassociates any previously selected 3D model
                        // This prevents residual models from being uploaded with standalone procedural scripts
                        currentActiveAsset = null;
                        if (runtime != null) {
                            runtime.setActiveSelectedAsset(null);
                        }

                        // Automatically set pipeline mode to Option A
                        if (spinnerAutoMode != null && spinnerAutoMode.getCount() > 0) {
                            spinnerAutoMode.setSelection(0); // Option A: Procedural Script (Fast)
                        }

                        updateReferenceUI();
                        etPrompt.setHint("Script attached: " + selectedScriptFileName + " (Prompt is optional)");
                        VynaraLogger.system("CreateFragment: Attached Python script: " + selectedScriptFileName + " (Disassociated active 3D models)");
                        Toast.makeText(getContext(), "Attached Python Script: " + selectedScriptFileName + "\nPrompt is now optional.", Toast.LENGTH_LONG).show();
                    }
                }
        );

        // Launcher for runtime permissions
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        openImagePicker();
                    } else {
                        Toast.makeText(getContext(), "Permission denied. Cannot access storage.", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_create, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getActivity() instanceof MainActivity) {
            runtime = ((MainActivity) getActivity()).getProjectRuntime();
        } else {
            runtime = ProjectRuntime.getInstance(requireContext());
        }

        etPrompt = view.findViewById(R.id.et_prompt);
        tvReferenceCount = view.findViewById(R.id.tv_reference_count);
        tvToggleAdvanced = view.findViewById(R.id.tv_toggle_advanced);
        layoutAdvancedContent = view.findViewById(R.id.layout_advanced_content);

        spinnerStyle = view.findViewById(R.id.spinner_style);
        spinnerQuality = view.findViewById(R.id.spinner_quality);
        spinnerTarget = view.findViewById(R.id.spinner_target);
        spinnerAutoMode = view.findViewById(R.id.spinner_auto_mode);

        setupSpinners();

        // Update active model badge
        if (getContext() != null) {
            ApiKeyManager keyMgr = new ApiKeyManager(getContext());
            String activeModel = keyMgr.getSelectedModel();

            TextView tvConnectionStatus = view.findViewById(R.id.tv_model_badge);
            if (tvConnectionStatus == null) {
                tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
            }
            if (tvConnectionStatus == null) {
                int fallbackId = view.getResources().getIdentifier("tv_ai_status", "id", requireContext().getPackageName());
                if (fallbackId != 0) {
                    tvConnectionStatus = view.findViewById(fallbackId);
                }
            }

            if (tvConnectionStatus != null) {
                if (keyMgr.hasApiKey()) {
                    String displayName = (activeModel == null || activeModel.trim().isEmpty()) ? "gemini-1.5-flash" : activeModel;
                    tvConnectionStatus.setText("AI: " + displayName + " • Connected");
                    tvConnectionStatus.setTextColor(0xFF00E676);
                } else {
                    tvConnectionStatus.setText("AI: Disconnected (No API Key)");
                    tvConnectionStatus.setTextColor(0xFFFF5252);
                }
            }
        }

        // Add Reference Image button
        View btnAddRef = view.findViewById(R.id.btn_add_reference);
        if (btnAddRef != null) {
            btnAddRef.setOnClickListener(v -> checkPermissionAndPickImages());
            btnAddRef.setOnLongClickListener(v -> {
                openScriptPicker();
                return true;
            });
        }

        // Dedicated Upload Script button (if present in XML)
        int uploadScriptId = view.getResources().getIdentifier("btn_upload_script", "id", requireContext().getPackageName());
        View btnUploadScript = (uploadScriptId != 0) ? view.findViewById(uploadScriptId) : null;
        if (btnUploadScript != null) {
            btnUploadScript.setOnClickListener(v -> openScriptPicker());
        }

        // Tap reference badge to clear images, script, or active model
        if (tvReferenceCount != null) {
            tvReferenceCount.setOnClickListener(v -> {
                if (!selectedImageUris.isEmpty() || selectedScriptUri != null || currentActiveAsset != null) {
                    selectedImageUris.clear();
                    selectedScriptUri = null;
                    selectedScriptFileName = null;
                    currentActiveAsset = null;
                    if (runtime != null) {
                        runtime.setActiveSelectedAsset(null);
                    }
                    etPrompt.setHint("Describe your 3D vision, structure, or mood...");
                    updateReferenceUI();
                    VynaraLogger.system("CreateFragment: Cleared all attachments and active asset.");
                    Toast.makeText(getContext(), "Attachments & model cleared", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Accordion toggle
        View headerAdv = view.findViewById(R.id.layout_advanced_header);
        if (headerAdv != null) {
            headerAdv.setOnClickListener(v -> {
                if (layoutAdvancedContent.getVisibility() == View.VISIBLE) {
                    layoutAdvancedContent.setVisibility(View.GONE);
                    tvToggleAdvanced.setText("Expand ▼");
                } else {
                    layoutAdvancedContent.setVisibility(View.VISIBLE);
                    tvToggleAdvanced.setText("Collapse ▲");
                }
            });
        }

        // 5 Universal Demo Presets
        setupPresetButton(view, R.id.preset_house, "Create a realistic modern villa with a swimming pool, wooden deck, interior lighting, furniture, and surrounding palm trees.");
        setupPresetButton(view, R.id.preset_human, "Create a stylized rigged superhero character with suit details, heroic posture, and skeletal animation tracks.");
        setupPresetButton(view, R.id.preset_dog, "Create an animated quadruped dog model with skeletal rig, fur material, and a running cycle animation.");
        setupPresetButton(view, R.id.preset_sofa, "Create a modern luxury leather sofa with realistic cushion seams, metallic legs, and wood trim.");
        setupPresetButton(view, R.id.preset_village, "Create a high-detail tropical village environment with wooden huts, sand terrain, palm trees, and ocean shoreline.");

        // Check and sync any active asset selected in AssetsFragment or Studio
        syncActiveAssetFromRuntime();

        // Generate button with Strict Pre-Flight Validation
        Button btnGenerate = view.findViewById(R.id.btn_create_generate);
        if (btnGenerate != null) {
            btnGenerate.setOnClickListener(v -> {
                if (selectedScriptUri == null && currentActiveAsset == null && runtime != null) {
                    currentActiveAsset = runtime.getActiveSelectedAsset();
                }

                String prompt = etPrompt.getText().toString().trim();

                // Clean universal prompt fallback handling
                if (selectedScriptUri != null) {
                    if (prompt.isEmpty()) {
                        prompt = "Custom Script: " + (selectedScriptFileName != null ? selectedScriptFileName : "custom_model.py");
                    }
                } else if (currentActiveAsset != null) {
                    if (prompt.isEmpty()) {
                        prompt = "Cinematic showcase of " + currentActiveAsset.getName() + " with dynamic lighting, professional camera movement, and environment staging.";
                    }
                } else {
                    if (prompt.isEmpty()) {
                        prompt = "Modern Two Story Beach Villa";
                    }
                }

                String style = spinnerStyle.getSelectedItem() != null ? spinnerStyle.getSelectedItem().toString() : "Photorealistic";
                String targetEngine = spinnerTarget.getSelectedItem() != null ? spinnerTarget.getSelectedItem().toString() : "Blender Native";

                // Resolve selected mode strictly
                AIPipelineMode selectedPipelineMode = AIPipelineMode.PROCEDURAL_PYTHON;
                if (spinnerAutoMode.getSelectedItem() != null) {
                    selectedPipelineMode = AIPipelineMode.fromDisplayNameSafe(spinnerAutoMode.getSelectedItem().toString());
                }

                VynaraLogger.system("CreateFragment: User tapped Generate -> Pipeline: " + selectedPipelineMode.getDisplayName());

                // Strict pre-flight execution contract validation
                boolean hasPrompt = !prompt.isEmpty();
                boolean hasScript = (selectedScriptUri != null || currentActiveAsset != null);
                int refImgCount = selectedImageUris.size();
                boolean hasCloudAuth = false;
                if (getContext() != null) {
                    ApiKeyManager keyMgr = new ApiKeyManager(getContext());
                    hasCloudAuth = keyMgr.hasApiKey();
                }

                AIPipelineMode.ExecutionValidationStatus contractStatus =
                        selectedPipelineMode.validateExecutionContract(hasPrompt, hasScript, refImgCount, hasCloudAuth);

                if (!contractStatus.isValid()) {
                    VynaraLogger.validation(VynaraLogger.LogLevel.ERROR, "CreateFragment: Pre-flight check FAILED: " + contractStatus.getErrorMessage());
                    Toast.makeText(getContext(), contractStatus.getErrorMessage(), Toast.LENGTH_LONG).show();
                    return;
                }

                List<String> refUrisStrList = new ArrayList<>();

                // If custom script is attached, cache it locally and append FIRST
                if (selectedScriptUri != null) {
                    String cachedScriptPath = cacheCustomScript(requireContext(), selectedScriptUri);
                    if (cachedScriptPath != null) {
                        refUrisStrList.add(cachedScriptPath);
                    }
                } else if (currentActiveAsset != null && currentActiveAsset.getFilePath() != null) {
                    File assetFile = new File(currentActiveAsset.getFilePath());
                    if (assetFile.exists() && assetFile.length() > 0) {
                        refUrisStrList.add("model:" + currentActiveAsset.getFilePath());
                        VynaraLogger.system("CreateFragment: Bound active asset [" + currentActiveAsset.getName() + "] (" + assetFile.length() + " bytes) to production payload.");
                    } else {
                        VynaraLogger.w("CreateFragment: Active asset file is missing or empty on disk: " + currentActiveAsset.getFilePath());
                    }
                }

                // Cache reference images
                for (Uri uri : selectedImageUris) {
                    if (uri != null) {
                        String localFilePath = cacheReferenceImage(requireContext(), uri);
                        refUrisStrList.add(localFilePath != null ? localFilePath : uri.toString());
                    }
                }

                VynaraLogger.system("CreateFragment: Dispatching verified production plan -> Mode: " + selectedPipelineMode.getId());

                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).startProduction(
                            prompt,
                            style,
                            targetEngine,
                            selectedPipelineMode.getId(),
                            refUrisStrList
                    );
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        syncActiveAssetFromRuntime();
    }

    /**
     * Bridges AssetsFragment -> CreateFragment:
     * When user selects or imports an asset in AssetsFragment, CreateFragment automatically
     * loads it as the active model to direct and animate (unless a custom script is currently attached).
     */
    private void syncActiveAssetFromRuntime() {
        if (runtime == null || selectedScriptUri != null) return;
        Asset active = runtime.getActiveSelectedAsset();
        if (active != null && !active.equals(currentActiveAsset)) {
            currentActiveAsset = active;
            updateReferenceUI();

            String name = active.getName();
            etPrompt.setHint("Direct action for " + name + " (e.g. 'Cinematic turntable orbit, dramatic rim lighting, walking animation')...");

            VynaraLogger.system("CreateFragment: Synchronized active asset: " + active.getName() + " (" + active.getFormat() + ")");
            Toast.makeText(getContext(), "Ready to animate: " + active.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkPermissionAndPickImages() {
        if (getContext() == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                openImagePicker();
            } else {
                permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                openImagePicker();
            } else {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void openImagePicker() {
        if (imagePickerLauncher != null) {
            imagePickerLauncher.launch("image/*");
        }
    }

    private void openScriptPicker() {
        if (scriptPickerLauncher != null) {
            scriptPickerLauncher.launch("*/*");
        }
    }

    private void updateReferenceUI() {
        if (tvReferenceCount != null) {
            int imageCount = selectedImageUris.size();
            StringBuilder sb = new StringBuilder();

            if (selectedScriptFileName != null) {
                sb.append("📜 ").append(selectedScriptFileName).append(" (Attached) ");
            } else if (currentActiveAsset != null) {
                sb.append("📦 Model: ").append(currentActiveAsset.getName())
                  .append(" (").append(currentActiveAsset.getFormat()).append(") ");
            }

            if (imageCount > 0) {
                sb.append("• ").append(imageCount).append(" image(s) ");
            }

            if (currentActiveAsset != null || selectedScriptFileName != null || imageCount > 0) {
                sb.append("(Tap to clear)");
            }

            tvReferenceCount.setText(sb.toString());
        }
    }

    private void setupPresetButton(View root, int resId, String promptText) {
        View btn = root.findViewById(resId);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                selectedScriptUri = null;
                selectedScriptFileName = null;
                updateReferenceUI();
                etPrompt.setHint("Describe your 3D vision, structure, or mood...");
                etPrompt.setText(promptText);
            });
        }
    }

    private void setupSpinners() {
        if (getContext() == null) return;

        String[] styles = new String[]{"Photorealistic", "Stylized / Low-Poly", "Cinematic CGI", "Anime / Toon"};
        ArrayAdapter<String> adapterStyle = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, styles);
        adapterStyle.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerStyle.setAdapter(adapterStyle);

        String[] qualities = new String[]{"High Detail (4K Textures)", "Medium (Optimized)", "Mobile Ultra Lite"};
        ArrayAdapter<String> adapterQuality = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, qualities);
        adapterQuality.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerQuality.setAdapter(adapterQuality);

        String[] targets = new String[]{"Blender Native", "OpenGL ES / GLTF", "Unreal Engine 5", "Unity Universal RP"};
        ArrayAdapter<String> adapterTarget = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, targets);
        adapterTarget.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTarget.setAdapter(adapterTarget);

        AIPipelineMode[] modes = AIPipelineMode.values();
        String[] modeDisplayNames = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeDisplayNames[i] = modes[i].getDisplayName();
        }

        ArrayAdapter<String> adapterMode = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, modeDisplayNames);
        adapterMode.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAutoMode.setAdapter(adapterMode);
    }

    private String cacheCustomScript(Context context, Uri scriptUri) {
        if (scriptUri == null) return null;
        try {
            File cacheFolder = new File(context.getCacheDir(), "scripts");
            if (!cacheFolder.exists()) cacheFolder.mkdirs();

            File destFile = new File(cacheFolder, "custom_user_script.py");

            try (InputStream in = context.getContentResolver().openInputStream(scriptUri);
                 FileOutputStream out = new FileOutputStream(destFile)) {

                if (in == null) return null;

                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            return destFile.getAbsolutePath();
        } catch (Exception e) {
            VynaraLogger.e("CreateFragment: Failed caching custom script: " + e.getMessage());
            return null;
        }
    }

    private String cacheReferenceImage(Context context, Uri contentUri) {
        if (contentUri == null) return null;

        String scheme = contentUri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme)) {
            return contentUri.getPath();
        }

        try {
            File cacheFolder = new File(context.getCacheDir(), "references");
            if (!cacheFolder.exists()) {
                cacheFolder.mkdirs();
            }

            String originalName = getFileNameFromUri(contentUri);
            String ext = ".jpg";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf('.')).toLowerCase(Locale.US);
            }

            File destFile = new File(cacheFolder, "ref_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000) + ext);

            try (InputStream in = context.getContentResolver().openInputStream(contentUri);
                 FileOutputStream out = new FileOutputStream(destFile)) {

                if (in == null) return null;

                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            return destFile.getAbsolutePath();
        } catch (Exception e) {
            VynaraLogger.e("CreateFragment: Failed caching reference image: " + e.getMessage());
            return contentUri.toString();
        }
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return null;
        String fileName = null;
        try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {}

        if (fileName == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                if (cut != -1) {
                    fileName = path.substring(cut + 1);
                }
            }
        }
        return fileName;
    }

    public List<Uri> getSelectedImageUris() {
        return selectedImageUris;
    }
}