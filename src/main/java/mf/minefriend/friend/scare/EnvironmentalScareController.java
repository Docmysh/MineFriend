package mf.minefriend.friend.scare;

import mf.minefriend.friend.state.FriendData;
import mf.minefriend.friend.state.FriendPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class EnvironmentalScareController {
    private static final int MAX_EVENT_ATTEMPTS = 8;
    private static final Map<UUID, ScareState> STATES = new ConcurrentHashMap<>();
    private static boolean registered;

    public static void init() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new EnvironmentalScareController());
            registered = true;
        }
    }

    public static void recordChat(ServerPlayer player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        ScareState state = STATES.computeIfAbsent(player.getUUID(), uuid -> new ScareState());
        state.recordChat(message.trim());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        event.getServer().getPlayerList().getPlayers().forEach(player ->
                FriendData.get(player).ifPresent(data ->
                        STATES.computeIfAbsent(player.getUUID(), uuid -> new ScareState()).tick(player, data)));
        STATES.keySet().removeIf(uuid -> event.getServer().getPlayerList().getPlayer(uuid) == null);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            STATES.remove(player.getUUID());
        }
    }

    private static class ScareState {
        private static final List<BedBlock> BED_VARIANTS = List.of(
                (BedBlock) Blocks.WHITE_BED,
                (BedBlock) Blocks.ORANGE_BED,
                (BedBlock) Blocks.MAGENTA_BED,
                (BedBlock) Blocks.LIGHT_BLUE_BED,
                (BedBlock) Blocks.YELLOW_BED,
                (BedBlock) Blocks.LIME_BED,
                (BedBlock) Blocks.PINK_BED,
                (BedBlock) Blocks.GRAY_BED,
                (BedBlock) Blocks.LIGHT_GRAY_BED,
                (BedBlock) Blocks.CYAN_BED,
                (BedBlock) Blocks.PURPLE_BED,
                (BedBlock) Blocks.BLUE_BED,
                (BedBlock) Blocks.BROWN_BED,
                (BedBlock) Blocks.GREEN_BED,
                (BedBlock) Blocks.RED_BED,
                (BedBlock) Blocks.BLACK_BED
        );
        private static final List<Block> DOOR_VARIANTS = List.of(
                Blocks.SPRUCE_DOOR,
                Blocks.BIRCH_DOOR,
                Blocks.JUNGLE_DOOR,
                Blocks.ACACIA_DOOR,
                Blocks.DARK_OAK_DOOR,
                Blocks.CRIMSON_DOOR,
                Blocks.WARPED_DOOR,
                Blocks.IRON_DOOR
        );
        private final RandomSource random = RandomSource.create();
        private FriendPhase lastPhase = FriendPhase.NONE;
        private int cooldown = 200;
        private String lastChat = "";

        void recordChat(String message) {
            lastChat = message;
        }

        void tick(ServerPlayer player, FriendData data) {
            if (data.phase() != lastPhase) {
                lastPhase = data.phase();
                cooldown = 100 + random.nextInt(200);
            }
            if (lastPhase == FriendPhase.NONE || lastPhase == FriendPhase.PHASE_ONE) {
                return;
            }
            if (cooldown-- > 0) {
                return;
            }
            boolean triggered = switch (lastPhase) {
                case PHASE_TWO -> triggerPhaseTwo(player);
                case PHASE_THREE -> triggerPhaseThree(player);
                case PHASE_FOUR -> triggerPhaseFour(player);
                default -> false;
            };
            cooldown = (triggered ? 400 : 120) + random.nextInt(triggered ? 400 : 160);
        }

        private boolean triggerPhaseTwo(ServerPlayer player) {
            List<PhaseAction> actions = List.of(
                    this::playChestSound,
                    this::playFootstepSound,
                    this::playPlayerHurtSound,
                    this::playCaveSound,
                    this::extinguishRandomTorch,
                    this::plantUnnaturalFlower,
                    this::disturbFarm,
                    this::placeFoundYouSign,
                    this::buildDirtMarker,
                    this::digWatcherHole
            );
            return executeRandomAction(player, actions);
        }

        private boolean triggerPhaseThree(ServerPlayer player) {
            List<PhaseAction> actions = List.of(
                    this::replaceBed,
                    this::ruinArmorStand,
                    this::tamperDoor,
                    this::spoilChest,
                    this::isolatePet,
                    this::echoChatSign,
                    this::buildMockHouse,
                    this::buildShrine
            );
            return executeRandomAction(player, actions);
        }

        private boolean triggerPhaseFour(ServerPlayer player) {
            List<PhaseAction> actions = List.of(
                    this::turnGolemsHostile,
                    this::upsetPets,
                    this::spookVillagers,
                    this::panicPassiveMobs
            );
            return executeRandomAction(player, actions);
        }

        private boolean executeRandomAction(ServerPlayer player, List<PhaseAction> actions) {
            if (actions.isEmpty()) {
                return false;
            }
            List<PhaseAction> shuffled = new ArrayList<>(actions);
            Collections.shuffle(shuffled, new Random(random.nextLong()));
            int attempts = 0;
            for (PhaseAction action : shuffled) {
                attempts++;
                if (action.perform(player, random)) {
                    return true;
                }
                if (attempts >= MAX_EVENT_ATTEMPTS) {
                    break;
                }
            }
            return false;
        }

        private boolean playChestSound(ServerPlayer player, RandomSource random) {
            BlockPos pos = player.blockPosition().offset(randomOffset(random, 4), 0, randomOffset(random, 4));
            ServerLevel level = player.serverLevel();
            level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6F, 0.9F + random.nextFloat() * 0.2F);
            level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.6F, 0.9F + random.nextFloat() * 0.2F);
            return true;
        }

        private boolean playFootstepSound(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos pos = player.blockPosition().offset(randomOffset(random, 3), 0, randomOffset(random, 3));
            level.playSound(null, pos, SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.PLAYERS, 0.6F, 0.8F + random.nextFloat() * 0.4F);
            return true;
        }

        private boolean playPlayerHurtSound(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos pos = player.blockPosition();
            level.playSound(player, pos, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.8F, 0.9F + random.nextFloat() * 0.2F);
            return true;
        }

        private boolean playCaveSound(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos pos = player.blockPosition();
            level.playSound(null, pos, SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 0.7F, 0.8F + random.nextFloat() * 0.4F);
            return true;
        }

        private boolean extinguishRandomTorch(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            Optional<BlockPos> target = findNearbyBlock(level, player.blockPosition(), 16, state ->
                    state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH));
            if (target.isEmpty()) {
                return false;
            }
            BlockPos pos = target.get();
            BlockState state = level.getBlockState(pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            if (!state.isAir()) {
                Block.popResource(level, pos, new ItemStack(Items.TORCH));
            }
            return true;
        }

        private boolean plantUnnaturalFlower(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos origin = player.blockPosition();
            for (int attempt = 0; attempt < 40; attempt++) {
                BlockPos pos = origin.offset(randomOffset(random, 12), 0, randomOffset(random, 12));
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
                if (!level.getBlockState(ground.below()).isAir()) {
                    ground = ground.below();
                }
                BlockPos target = new BlockPos(ground.getX(), ground.getY(), ground.getZ());
                if (!level.getBlockState(target).isAir()) {
                    continue;
                }
                BlockState below = level.getBlockState(target.below());
                if (!(below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT) || below.is(Blocks.PODZOL))) {
                    continue;
                }
                BlockState flower = BuiltInRegistries.BLOCK
                        .get(ResourceLocation.fromNamespaceAndPath("minecraft", randomFlower(random)))
                        .defaultBlockState();
                if (!(flower.getBlock() instanceof FlowerBlock)) {
                    continue;
                }
                level.setBlock(target, flower, 3);
                return true;
            }
            return false;
        }

        private String randomFlower(RandomSource random) {
            List<String> flowers = List.of("poppy", "dandelion", "blue_orchid", "allium", "azure_bluet", "red_tulip", "white_tulip", "pink_tulip", "oxeye_daisy");
            return flowers.get(random.nextInt(flowers.size()));
        }

        private boolean disturbFarm(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            Optional<BlockPos> farmland = findNearbyBlock(level, player.blockPosition(), 16, state -> state.is(Blocks.FARMLAND));
            if (farmland.isEmpty()) {
                return false;
            }
            BlockPos soil = farmland.get();
            BlockPos cropPos = soil.above();
            BlockState crop = level.getBlockState(cropPos);
            if (!crop.isAir()) {
                level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
            } else {
                return false;
            }
            return true;
        }

        private boolean placeFoundYouSign(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos origin = player.blockPosition();
            for (int attempt = 0; attempt < 20; attempt++) {
                int distance = 12 + random.nextInt(24);
                Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
                BlockPos base = origin.relative(dir, distance);
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, base);
                BlockPos signPos = ground;
                if (!level.getBlockState(signPos).isAir()) {
                    continue;
                }
                BlockState below = level.getBlockState(signPos.below());
                if (!below.isSolidRender(level, signPos.below())) {
                    continue;
                }
                BlockState signState = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, random.nextInt(16));
                level.setBlock(signPos, signState, 3);
                BlockEntity blockEntity = level.getBlockEntity(signPos);
                if (blockEntity instanceof SignBlockEntity sign) {
                    Component message = Component.literal("Found you.");
                    sign.setText(sign.getFrontText().setMessage(0, message), true);
                    sign.setText(sign.getBackText().setMessage(0, message), false);
                    sign.setChanged();
                }
                return true;
            }
            return false;
        }

        private boolean buildDirtMarker(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos origin = player.blockPosition();
            for (int attempt = 0; attempt < 20; attempt++) {
                BlockPos base = origin.offset(randomOffset(random, 16), 0, randomOffset(random, 16));
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, base);
                if (!isAreaLoaded(level, ground, 1)) {
                    continue;
                }
                if (!level.getBlockState(ground).isAir()) {
                    continue;
                }
                BlockState below = level.getBlockState(ground.below());
                if (!below.isSolidRender(level, ground.below())) {
                    continue;
                }
                for (int i = 0; i < 3; i++) {
                    level.setBlock(ground.above(i), Blocks.DIRT.defaultBlockState(), 3);
                }
                return true;
            }
            return false;
        }

        private boolean digWatcherHole(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos origin = player.blockPosition();
            for (int attempt = 0; attempt < 20; attempt++) {
                BlockPos base = origin.offset(randomOffset(random, 16), 0, randomOffset(random, 16));
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, base);
                if (!isAreaLoaded(level, ground, 1)) {
                    continue;
                }
                BlockPos pos = ground;
                if (!level.getBlockState(pos).isSolidRender(level, pos)) {
                    continue;
                }
                pos = pos.above();
                if (!level.getBlockState(pos).isAir()) {
                    continue;
                }
                BlockPos below = pos.below();
                level.removeBlock(below, false);
                level.removeBlock(below.below(), false);
                return true;
            }
            return false;
        }

        private boolean replaceBed(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            Optional<BlockPos> bedPosOpt = findNearbyBlock(level, player.blockPosition(), 12, state -> state.getBlock() instanceof BedBlock);
            if (bedPosOpt.isEmpty()) {
                return false;
            }
            BlockPos bedPos = bedPosOpt.get();
            BlockState current = level.getBlockState(bedPos);
            BedBlock currentBed = (BedBlock) current.getBlock();
            BedPart part = current.getValue(BedBlock.PART);
            Direction facing = current.getValue(BedBlock.FACING);
            BedBlock newBed = BED_VARIANTS.get(random.nextInt(BED_VARIANTS.size()));
            if (newBed == currentBed) {
                newBed = BED_VARIANTS.get((BED_VARIANTS.indexOf(newBed) + 1) % BED_VARIANTS.size());
            }
            BlockPos otherPos = bedPos.relative(part == BedPart.HEAD ? facing.getOpposite() : facing);
            BlockState other = level.getBlockState(otherPos);
            if (!(other.getBlock() instanceof BedBlock)) {
                return false;
            }
            level.setBlock(bedPos, newBed.defaultBlockState().setValue(BedBlock.PART, part).setValue(BedBlock.FACING, facing), 3);
            BedPart otherPart = part == BedPart.HEAD ? BedPart.FOOT : BedPart.HEAD;
            level.setBlock(otherPos, newBed.defaultBlockState().setValue(BedBlock.PART, otherPart).setValue(BedBlock.FACING, facing), 3);
            return true;
        }

        private boolean ruinArmorStand(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class, new AABB(player.blockPosition()).inflate(12.0));
            if (stands.isEmpty()) {
                return false;
            }
            ArmorStand stand = stands.get(random.nextInt(stands.size()));
            List<ItemStack> armor = List.of(createDamagedArmor(Items.LEATHER_HELMET, random), createDamagedArmor(Items.LEATHER_CHESTPLATE, random), createDamagedArmor(Items.LEATHER_LEGGINGS, random), createDamagedArmor(Items.LEATHER_BOOTS, random));
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, armor.get(0));
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, armor.get(1));
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, armor.get(2));
            stand.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, armor.get(3));
            return true;
        }

        private ItemStack createDamagedArmor(net.minecraft.world.item.Item item, RandomSource random) {
            ItemStack stack = new ItemStack(item);
            stack.setDamageValue(random.nextInt(Math.max(1, stack.getMaxDamage() - 1)));
            return stack;
        }

        private boolean tamperDoor(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            Optional<BlockPos> doorPosOpt = findNearbyBlock(level, player.blockPosition(), 12, state -> state.getBlock() instanceof DoorBlock);
            if (doorPosOpt.isEmpty()) {
                return false;
            }
            BlockPos doorPos = doorPosOpt.get();
            BlockState current = level.getBlockState(doorPos);
            DoorBlock door = (DoorBlock) current.getBlock();
            DoubleBlockHalf half = current.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            Direction facing = current.getValue(BlockStateProperties.HORIZONTAL_FACING);
            BlockState topState = level.getBlockState(half == DoubleBlockHalf.LOWER ? doorPos.above() : doorPos.below());
            if (!(topState.getBlock() instanceof DoorBlock)) {
                return false;
            }
            if (random.nextBoolean()) {
                level.removeBlock(doorPos, false);
                if (half == DoubleBlockHalf.LOWER) {
                    level.removeBlock(doorPos.above(), false);
                } else {
                    level.removeBlock(doorPos.below(), false);
                }
            } else {
                Block replacement = DOOR_VARIANTS.get(random.nextInt(DOOR_VARIANTS.size()));
                if (replacement == door) {
                    replacement = DOOR_VARIANTS.get((DOOR_VARIANTS.indexOf(replacement) + 1) % DOOR_VARIANTS.size());
                }
                BlockState lower = replacement.defaultBlockState()
                        .setValue(BlockStateProperties.DOOR_HINGE, current.getValue(BlockStateProperties.DOOR_HINGE))
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                        .setValue(BlockStateProperties.OPEN, current.getValue(BlockStateProperties.OPEN));
                BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                BlockPos lowerPos = half == DoubleBlockHalf.LOWER ? doorPos : doorPos.below();
                level.setBlock(lowerPos, lower, 3);
                level.setBlock(lowerPos.above(), upper, 3);
            }
            return true;
        }

        private boolean spoilChest(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            Optional<BlockPos> chestPosOpt = findNearbyBlock(level, player.blockPosition(), 10, state -> state.getBlock() instanceof ChestBlock);
            if (chestPosOpt.isEmpty()) {
                return false;
            }
            BlockPos pos = chestPosOpt.get();
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof ChestBlockEntity chest)) {
                return false;
            }
            for (int i = 0; i < chest.getContainerSize(); i++) {
                chest.setItem(i, new ItemStack(Items.ROTTEN_FLESH, 1 + random.nextInt(3)));
            }
            chest.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            return true;
        }

        private boolean isolatePet(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            List<TamableAnimal> pets = level.getEntitiesOfClass(TamableAnimal.class, new AABB(player.blockPosition()).inflate(12.0), pet -> pet.isOwnedBy(player) && (pet instanceof Wolf || pet instanceof Cat));
            if (pets.isEmpty()) {
                return false;
            }
            TamableAnimal pet = pets.get(random.nextInt(pets.size()));
            pet.setOrderedToSit(true);
            Vec3 offset = new Vec3(random.nextDouble() - 0.5D, 0.0D, random.nextDouble() - 0.5D).normalize();
            pet.setYRot((float) (Mth.atan2(offset.z, offset.x) * (180F / Math.PI)));
            pet.playSound((pet instanceof Cat ? SoundEvents.CAT_HISS : SoundEvents.WOLF_GROWL), 0.8F, 0.9F + random.nextFloat() * 0.2F);
            return true;
        }

        private boolean echoChatSign(ServerPlayer player, RandomSource random) {
            if (lastChat.isEmpty()) {
                return false;
            }
            ServerLevel level = player.serverLevel();
            BlockPos origin = player.blockPosition();
            for (int attempt = 0; attempt < 20; attempt++) {
                BlockPos base = origin.offset(randomOffset(random, 10), 0, randomOffset(random, 10));
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, base);
                if (!level.getBlockState(ground).isAir()) {
                    continue;
                }
                if (!level.getBlockState(ground.below()).isSolidRender(level, ground.below())) {
                    continue;
                }
                BlockState signState = Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, random.nextInt(16));
                level.setBlock(ground, signState, 3);
                BlockEntity blockEntity = level.getBlockEntity(ground);
                if (blockEntity instanceof SignBlockEntity sign) {
                    Component message = Component.literal(lastChat);
                    sign.setText(sign.getFrontText().setMessage(0, message), true);
                    sign.setChanged();
                }
                return true;
            }
            return false;
        }

        private boolean buildMockHouse(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            BlockPos origin = player.blockPosition().offset(randomOffset(random, 8), 0, randomOffset(random, 8));
            BlockPos base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, origin);
            if (!isAreaLoaded(level, base, 4)) {
                return false;
            }
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos floorPos = base.offset(x, -1, z);
                    level.setBlock(floorPos, Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
            for (int y = 0; y <= 2; y++) {
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos pos = base.offset(x, y, z);
                        boolean wall = Math.abs(x) == 1 || Math.abs(z) == 1 || y == 2;
                        if (!wall) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                            continue;
                        }
                        if (x == 0 && z == 0 && y == 2) {
                            level.setBlock(pos, Blocks.OAK_SLAB.defaultBlockState(), 3);
                        } else if (x == 0 && z == 0 && y == 1) {
                            level.setBlock(pos, Blocks.GLASS_PANE.defaultBlockState(), 3);
                        } else if (x == 0 && z == -1 && y <= 1) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        } else if (x == 1 && z == 1 && y == 0) {
                            level.setBlock(pos, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
                        } else {
                            level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                        }
                    }
                }
            }
            BlockPos doorBase = base.offset(0, 0, -1);
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
            BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
            level.setBlock(doorBase, lower, 3);
            level.setBlock(doorBase.above(), upper, 3);
            return true;
        }

        private boolean buildShrine(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            List<Entity> drops = level.getEntities(player, new AABB(player.blockPosition()).inflate(12.0), e -> e instanceof net.minecraft.world.entity.item.ItemEntity);
            ItemStack stack = ItemStack.EMPTY;
            if (!drops.isEmpty()) {
                net.minecraft.world.entity.item.ItemEntity itemEntity = (net.minecraft.world.entity.item.ItemEntity) drops.get(random.nextInt(drops.size()));
                stack = itemEntity.getItem().copy();
            } else {
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack invStack = player.getInventory().getItem(i);
                    if (!invStack.isEmpty()) {
                        stack = invStack.copy();
                        stack.setCount(1);
                        break;
                    }
                }
            }
            if (stack.isEmpty()) {
                return false;
            }
            BlockPos origin = player.blockPosition().offset(randomOffset(random, 8), 0, randomOffset(random, 8));
            BlockPos base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, origin);
            if (!isAreaLoaded(level, base, 2)) {
                return false;
            }
            level.setBlock(base, Blocks.COBBLESTONE.defaultBlockState(), 3);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos support = base.relative(direction).below();
                level.setBlock(support, Blocks.COBBLESTONE.defaultBlockState(), 3);
                level.setBlock(support.above(), Blocks.TORCH.defaultBlockState(), 3);
            }
            net.minecraft.world.entity.item.ItemEntity display = new net.minecraft.world.entity.item.ItemEntity(level, base.getX() + 0.5, base.getY() + 1.2, base.getZ() + 0.5, stack);
            display.setNoPickUpDelay();
            display.setUnlimitedLifetime();
            level.addFreshEntity(display);
            return true;
        }

        private boolean turnGolemsHostile(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, new AABB(player.blockPosition()).inflate(16.0));
            if (golems.isEmpty()) {
                return false;
            }
            golems.forEach(golem -> golem.setTarget(player));
            return true;
        }

        private boolean upsetPets(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            List<TamableAnimal> pets = level.getEntitiesOfClass(TamableAnimal.class, new AABB(player.blockPosition()).inflate(16.0), pet -> pet.isOwnedBy(player));
            if (pets.isEmpty()) {
                return false;
            }
            pets.forEach(pet -> {
                pet.setOrderedToSit(true);
                pet.playSound((pet instanceof Cat ? SoundEvents.CAT_HISS : SoundEvents.WOLF_GROWL), 0.8F, 0.6F + random.nextFloat() * 0.2F);
            });
            return true;
        }

        private boolean spookVillagers(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            List<Villager> villagers = level.getEntitiesOfClass(Villager.class, new AABB(player.blockPosition()).inflate(16.0));
            if (villagers.isEmpty()) {
                return false;
            }
            villagers.forEach(villager -> {
                Vec3 away = villager.position().vectorTo(player.position()).normalize().scale(-1.5);
                villager.getNavigation().moveTo(villager.getX() + away.x, villager.getY(), villager.getZ() + away.z, 1.2D);
                villager.playSound(SoundEvents.VILLAGER_NO, 1.0F, 0.6F + random.nextFloat() * 0.2F);
            });
            return true;
        }

        private boolean panicPassiveMobs(ServerPlayer player, RandomSource random) {
            ServerLevel level = player.serverLevel();
            List<Entity> mobs = level.getEntities(player, new AABB(player.blockPosition()).inflate(12.0), entity -> entity instanceof net.minecraft.world.entity.animal.Animal && !(entity instanceof TamableAnimal));
            if (mobs.isEmpty()) {
                return false;
            }
            mobs.forEach(entity -> {
                Vec3 away = entity.position().vectorTo(player.position()).normalize().scale(-4.0);
                if (entity instanceof net.minecraft.world.entity.Mob mob) {
                    mob.getNavigation().moveTo(entity.getX() + away.x, entity.getY(), entity.getZ() + away.z, 1.1D);
                }
            });
            return true;
        }

        private Optional<BlockPos> findNearbyBlock(ServerLevel level, BlockPos center, int radius, Predicate<BlockState> predicate) {
            List<BlockPos> positions = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        positions.add(pos);
                    }
                }
            }
            Collections.shuffle(positions, new Random(random.nextLong()));
            for (BlockPos pos : positions) {
                if (!isAreaLoaded(level, pos, 1)) {
                    continue;
                }
                if (predicate.test(level.getBlockState(pos))) {
                    return Optional.of(pos);
                }
            }
            return Optional.empty();
        }

        private int randomOffset(RandomSource random, int distance) {
            return random.nextInt(distance * 2 + 1) - distance;
        }

        private boolean isAreaLoaded(ServerLevel level, BlockPos pos, int radius) {
            return level.hasChunksAt(pos.offset(-radius, -radius, -radius), pos.offset(radius, radius, radius));
        }
    }

    @FunctionalInterface
    private interface PhaseAction {
        boolean perform(ServerPlayer player, RandomSource random);
    }
}
