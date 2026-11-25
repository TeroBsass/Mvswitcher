public class RailCreatorBlock extends BaseEntityBlock {
    private static final com.mojang.serialization.MapCodec<RailCreatorBlock> CODEC = simpleCodec(RailCreatorBlock::new);
    @Override protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public void playerDestroy(@NonNull Level level, @NonNull Player player,
        @NonNull BlockPos pos, @NonNull BlockState state, @Nullable BlockEntity blockEntity,
        @NonNull ItemStack destroyedWith) {
        super.playerDestroy(level, player, pos, state, blockEntity, destroyedWith);
        if (blockEntity instanceof RailCreatorEntity entity) {
            if (level instanceof ServerLevel serverLevel) {
                entity.dropInputs(serverLevel);
            }
        }
    }
}
