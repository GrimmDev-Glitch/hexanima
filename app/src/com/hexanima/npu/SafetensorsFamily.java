package com.hexanima.npu;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONObject;

/** Lightweight Safetensors header inspection used to route imports before copying multi-GB files. */
final class SafetensorsFamily {
    enum Kind { ANIMA, MOSS, QWEN, MAGE, ANIMA_LORA, MOSS_LORA, QWEN_LORA, LORA, UNKNOWN }
    private static final long MAX_HEADER = 32L << 20;

    private SafetensorsFamily() { }

    static Kind inspect(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(source);
        byte[] lengthBytes = new byte[8];
        in.readFully(lengthBytes);
        long length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (length < 2 || length > MAX_HEADER) throw new IOException("invalid Safetensors header length: " + length);
        byte[] data = new byte[(int) length];
        in.readFully(data);
        final JSONObject header;
        try { header = new JSONObject(new String(data, StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IOException("invalid Safetensors JSON header", e); }

        JSONObject metadata = header.optJSONObject("__metadata__");
        String meta = metadata == null ? "" : metadata.toString().toLowerCase(java.util.Locale.ROOT);
        boolean anima = false, moss = false, qwen = false, mage = meta.matches("(?s).*\\bmage\\b.*"), lora = false;
        boolean animaAdapter = false, mossAdapter = false, qwenAdapter = false;
        Iterator<String> it = header.keys();
        while (it.hasNext()) {
            String key = it.next().toLowerCase(java.util.Locale.ROOT);
            if (key.equals("__metadata__")) continue;
            boolean adapterKey = key.contains("lora_") || key.contains(".lora_a.") || key.contains(".lora_b.");
            if (key.endsWith("blocks.0.self_attn.q_proj.weight")) anima = true;
            if (key.endsWith("layers.0.proj.qkv.weight") || key.endsWith("layers.0.proj.q.weight")
                || key.endsWith("layers.0.proj.k.weight") || key.endsWith("layers.0.proj.v.weight")) moss = true;
            if (key.contains("transformer_blocks.0.") || key.contains("double_blocks.0.")) qwen = true;
            if (adapterKey) {
                lora = true;
                if (key.contains("layers.0.proj.")) mossAdapter = true;
                if (key.contains("blocks.0.self_attn.") || key.contains("lora_unet_blocks_0_self_attn_") || key.contains("lycoris_blocks_0_self_attn_")) animaAdapter = true;
                if (key.contains("transformer_blocks.0.") || key.contains("double_blocks.0.")) qwenAdapter = true;
            }
        }
        if (lora) {
            if (mossAdapter) return Kind.MOSS_LORA;
            if (qwenAdapter) return Kind.QWEN_LORA;
            if (animaAdapter) return Kind.ANIMA_LORA;
            return Kind.LORA;
        }
        // Family-specific diffusion weights take precedence over adapter names.
        if (anima) return Kind.ANIMA;
        if (moss) return Kind.MOSS;
        if (qwen) return Kind.QWEN;
        if (mage) return Kind.MAGE;
        return Kind.UNKNOWN;
    }

    static String checkpointMessage(Kind kind) {
        switch (kind) {
            case MOSS: return L.s("Este archivo parece un checkpoint DiT de MOSS-Image (incluido Kromoss). HexAnima todavía no convierte checkpoints MOSS a su pack V73; no se copió el archivo.",
                "This looks like a MOSS-Image DiT checkpoint (including Kromoss). HexAnima cannot convert MOSS checkpoints into its V73 pack yet; the file was not copied.");
            case QWEN: return L.s("Este archivo parece un checkpoint Qwen. El importador local todavía solo convierte checkpoints Anima; no se copió el archivo.",
                "This looks like a Qwen checkpoint. The local importer only converts Anima checkpoints yet; the file was not copied.");
            case MAGE: return L.s("Este archivo parece un checkpoint Mage. Mage se instala como pack del catálogo; la importación local de checkpoints Mage aún no está implementada.",
                "This looks like a Mage checkpoint. Mage is installed as a catalog pack; local Mage checkpoint import is not implemented yet.");
            case ANIMA_LORA: case MOSS_LORA: case QWEN_LORA: case LORA:
                return L.s("El archivo parece una LoRA, no un checkpoint completo. Impórtalo desde la sección LoRA del modelo compatible.",
                "This looks like a LoRA, not a full checkpoint. Import it from the compatible model's LoRA section.");
            case UNKNOWN: return L.s("No se reconoció la arquitectura del checkpoint. Solo se pueden convertir aquí checkpoints Anima compatibles.",
                "The checkpoint architecture was not recognized. Only compatible Anima checkpoints can be converted here.");
            default: return null;
        }
    }

    static String loraMismatch(Kind kind, Kind expected) {
        if (kind == Kind.UNKNOWN || kind == Kind.LORA || kind == expected) return null; // let the native shape validator decide ambiguous files
        if (kind == Kind.ANIMA || kind == Kind.MOSS || kind == Kind.QWEN || kind == Kind.MAGE) {
            String family = kind == Kind.ANIMA ? "Anima" : kind == Kind.MOSS ? "MOSS-Image" : kind == Kind.QWEN ? "Qwen-Image 2.1" : "Mage";
            return L.f("El archivo parece un checkpoint completo de %s, no una LoRA. No se copió.",
                "This looks like a full %s checkpoint, not a LoRA. The file was not copied.", family);
        }
        String family = kind == Kind.ANIMA_LORA ? "Anima" : kind == Kind.MOSS_LORA ? "MOSS-Image" : kind == Kind.QWEN_LORA ? "Qwen-Image 2.1" : "otro modelo";
        String wanted = expected == Kind.ANIMA_LORA ? "Anima" : expected == Kind.MOSS_LORA ? "MOSS-Image" : "Qwen-Image 2.1";
        return L.f("Esta LoRA parece ser para %s, pero el modelo activo usa %s. No se copió el archivo.",
            "This LoRA appears to be for %s, but the active model uses %s. The file was not copied.", family, wanted);
    }
}
