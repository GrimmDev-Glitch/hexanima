.p2align 4,,15
.global hmx8_tile
.type hmx8_tile, @function
hmx8_tile:
{
    activation.ub = mxmem(R0, R1):deep;
    weight.b = mxmem(R2, R3);
}
{
    mxmem(R4, R5):after.ub = acc;
}
{
    jumpr R31;
}
.size hmx8_tile, .-hmx8_tile
