#pragma once
/* MOSS-Image block: byte offsets (bytes) into the shared activation blob, passed as int32 meta[] */
enum { M_X = 0, M_COS, M_SIN, M_H, M_Q, M_K, M_V, M_O, M_T, M_MID, M_LQ, M_LO, M_LU, M_LD,
       M_NTOK, M_NVALID, M_STOP, M_NW, M_REL, M_FIRST, M_MODE, M_NCB, M_FMT, M_ROWS, M_CS, M_WOFF, M_NMETA };   /* M_WOFF: byte offset of this pack inside the weights buffer */   /* MODE 20: TE delta-net layer, 21: TE full-attention layer (pack = header table + matrices + f32 vectors), M_ROWS: fp32 row scratch, M_CS: rope cos/sin rows [T][64] */   /* M_NCB: hidden/32 (0 = 96); MODE 10: TE ffn test (rms+gate/up q4a swiglu + down q8 + residual) */   /* M_MODE: 0 DiT block, 1 text-refiner block (L buffers pre-filled with the bias tile by the host, LoRA-A GEMMs skipped), 2 text projection (x32 += gate*(text16 * Wp + bias)) */
/* byte offsets inside one blkNN.bin (see anima/moss/export_moss.py): q8 tile records 1152 B per 32x32 tile, [nb][kb'] with LoRA-B rows appended (kb' = kb + 4) */
#define W_Q 0u
#define W_K 11059200u
#define W_V 22118400u
#define W_O 33177600u
#define W_UG 44236800u
#define W_DN 103219200u
#define W_AQKV 131973120u
#define W_AO 133300224u
#define W_AUG 133742592u
#define W_ADN 134184960u
#define W_GAIN 135364608u      /* f32: q gain/sqrt(128) [128], k gain [128], norm1 w [3072], norm2 w [3072], pad to 8192 floats */
#define W_TOTAL 135397376u

/* Qwen3.5 layer pack header (first 4096 bytes): int32 off[8], fmt[8], nb[8], kb[8], voff; matrices in order. delta: qkv, z, ab, out, gu, dn; full: q, kv, o, gu, dn */
typedef struct { int off[8], fmt[8], nb[8], kb[8], voff; } te_hdr_t;
