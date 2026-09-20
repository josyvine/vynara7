package com.example.validation.validators;

import com.example.engine.Mesh;
import com.example.validation.ValidationResult;

import java.util.ArrayList;
import java.util.List;

public class MeshValidator {

    /**
     * Phase 11 Alignment: Specialized Mesh Validator. Inspects 3D geometry 
     * vertex counts, triangle indices alignment, normal array dimensions, and index boundaries.
     */
    public List<ValidationResult> validate(Mesh mesh, String objectName) {
        List<ValidationResult> results = new ArrayList<>();
        String name = objectName != null ? objectName : "Unknown Mesh";

        if (mesh == null) {
            results.add(new ValidationResult(
                    ValidationResult.Severity.ERROR,
                    ValidationResult.Category.MESH,
                    "Object " + name + " has missing or uninitialized 3D mesh.",
                    "Call geometry generator to rebuild mesh.",
                    null));
            return results;
        }

        int vertexCount = mesh.getVertexCount();
        if (vertexCount == 0) {
            results.add(new ValidationResult(
                    ValidationResult.Severity.ERROR,
                    ValidationResult.Category.MESH,
                    "Object " + name + " contains 0 vertices.",
                    "Re-generate primitive or procedural geometry.",
                    null));
            return results;
        }

        if (vertexCount < 3) {
            results.add(new ValidationResult(
                    ValidationResult.Severity.ERROR,
                    ValidationResult.Category.MESH,
                    "Object " + name + " contains insufficient vertices (" + vertexCount + " vertices). Minimum required is 3.",
                    "Rebuild primitive geometry.",
                    null));
        }

        // Normal array size alignment validation
        if (mesh.getNormals() == null || mesh.getNormals().length == 0) {
            results.add(new ValidationResult(
                    ValidationResult.Severity.WARNING,
                    ValidationResult.Category.MESH,
                    "Object " + name + " is missing normal vectors. Flat normals will be generated.",
                    "Invoke Mesh.recalculateNormals() to generate normals.",
                    null));
        } else if (mesh.getVertices() != null && mesh.getNormals().length != mesh.getVertices().length) {
            results.add(new ValidationResult(
                    ValidationResult.Severity.ERROR,
                    ValidationResult.Category.MESH,
                    "Object " + name + " has mismatched normal array length relative to vertex count.",
                    "Recompute aligned normal vectors.",
                    null));
        }

        // Triangle index alignment validation
        short[] indices = mesh.getIndices();
        if (indices != null && indices.length > 0) {
            if (indices.length % 3 != 0) {
                results.add(new ValidationResult(
                        ValidationResult.Severity.ERROR,
                        ValidationResult.Category.MESH,
                        "Object " + name + " has invalid index count (" + indices.length + "). Indices must be a multiple of 3 to form triangles.",
                        "Verify triangle index generation.",
                        null));
            }

            // Verify index out-of-bounds references using unsigned 16-bit evaluation (& 0xFFFF).
            // In OpenGL ES/glTF, indices are GL_UNSIGNED_SHORT (0 to 65535).
            // Java's signed short produces negative values for indices between 32768 and 65535.
            for (int i = 0; i < indices.length; i++) {
                int indexVal = indices[i] & 0xFFFF;
                if (indexVal >= vertexCount) {
                    results.add(new ValidationResult(
                            ValidationResult.Severity.CRITICAL,
                            ValidationResult.Category.MESH,
                            "Object " + name + " contains out-of-bounds vertex index reference (" + indexVal + "). Max allowed is " + (vertexCount - 1),
                            "Re-index vertices to resolve buffer overflow crashes.",
                            null));
                    break;
                }
            }
        }

        return results;
    }
}