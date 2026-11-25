class EnderDispenserStorage {
    static final SavedDataType<EnderDispenserStorage> DATA_TYPE =
        new SavedDataType<EnderDispenserStorage>(
            TeroMod.MODID + "_ender_dispenser",
            EnderDispenserStorage::new, CODEC, DataFixTypes.LEVEL);
}
