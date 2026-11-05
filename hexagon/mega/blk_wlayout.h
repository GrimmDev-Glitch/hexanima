#pragma once
/* byte offsets of the weight matrices / gains inside one blkNN.bin (the "wts" buffer); mod weights follow at W_MODW */
#define W_SQ 0u
#define W_SK 8388608u
#define W_SV 16777216u
#define W_SO 25165824u
#define W_CQ 33554432u
#define W_CK 41943040u
#define W_CV 46137344u
#define W_CO 50331648u
#define W_M1 58720256u
#define W_M2 92274688u
#define W_GAIN 125829120u
#define W_MODW 125833216u
#define W_TOTAL 138416128u
/* q8 weights: each 32x32 tile = 128 B fp16 per-column scale vector + 1024 B int8 (same halfword indexing as the fp16 tile); matrices shrink to 1152/2048 */
#define WQ(x) ((x) / 2048u * 1152u)
#define WQ_GAIN WQ(W_GAIN)
#define WQ_MODW (WQ_GAIN + 4096u)
#define WQ_TOTAL (WQ_MODW + (W_TOTAL - W_MODW))
