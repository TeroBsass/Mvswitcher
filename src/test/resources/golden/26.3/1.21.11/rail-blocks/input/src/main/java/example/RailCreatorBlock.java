public class RailCreatorBlock extends BaseEntityBlock {
    @Override public void playerDestroy(@NonNull ServerLevel level, @NonNull ServerPlayer player,
        @NonNull BlockPos pos, @NonNull BlockState state, @Nullable BlockEntity blockEntity,
        @NonNull ItemStack destroyedWith) {
        super.playerDestroy(level, player, pos, state, blockEntity, destroyedWith);
        if (blockEntity instanceof RailCreatorEntity entity) {
            entity.dropInputs(level);
        }
    }
}
