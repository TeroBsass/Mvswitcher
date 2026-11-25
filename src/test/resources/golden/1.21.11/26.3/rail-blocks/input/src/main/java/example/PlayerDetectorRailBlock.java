public class PlayerDetectorRailBlock extends BaseRailBlock {
    private static final com.mojang.serialization.MapCodec<PlayerDetectorRailBlock> CODEC = simpleCodec(PlayerDetectorRailBlock::new);
    @Override protected com.mojang.serialization.MapCodec<? extends BaseRailBlock> codec() { return CODEC; }
    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return 15; }
}
