.p2align 4,,15
.global hmx_acc
.type hmx_acc, @function
hmx_acc:
{
    activation.hf = mxmem(R0, R1):deep;
    weight.hf = mxmem(R2, R3);
}
{
    jumpr R31;
}
.size hmx_acc, .-hmx_acc
.p2align 4,,15
.global hmx_store
.type hmx_store, @function
hmx_store:
#if __HEXAGON_ARCH__ < 73
{
    mxmem(R0, R1):after.hf = acc;
}
#else
/* V73+: FP16 HMX stores pass through the convert unit, which reads a scale/bias block from VTCM. */
{
    R2 = add(PC, ##hmx_scale_blk@PCREL)
    R3 = #2
}
{
    R2 = memw(R2)
}
{
    bias = mxmem2(R2)
}
{
    cvt.hf = acc(R3)
}
{
    mxmem(R0, R1) = cvt;
}
#endif
{
    jumpr R31;
}
.size hmx_store, .-hmx_store
