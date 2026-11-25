class EnderDispenserStorage {
    static final SavedDataType<EnderDispenserStorage> DATA_TYPE =
        new SavedDataType<EnderDispenserStorage>(
            Identifier.fromNamespaceAndPath(TeroMod.MODID, "ender_dispenser"),
            EnderDispenserStorage::new, CODEC, DataFixTypes.LEVEL);
}
