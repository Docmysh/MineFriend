package mf.minefriend.friend;

import com.google.common.collect.ImmutableList;
import mf.minefriend.Minefriend;
import mf.minefriend.chat.ChatEventHandler;
import mf.minefriend.friend.entity.FriendEntity;
import mf.minefriend.friend.scare.EnvironmentalScareController;
import mf.minefriend.friend.state.FriendData;
import mf.minefriend.friend.state.FriendPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FriendManager {
    private static final List<String> RANDOM_NAMES = ImmutableList.of(
            "Eli", "Nova", "Ash", "Rowan", "Mira", "Kai", "Luca", "Ivy", "Avery", "Robin"
    );
    private static final List<ResourceLocation> SKIN_LOCATIONS = ImmutableList.of(
            ResourceLocation.fromNamespaceAndPath(Minefriend.MODID, "textures/entity/friend/skin0.png"),
            ResourceLocation.fromNamespaceAndPath(Minefriend.MODID, "textures/entity/friend/skin1.png"),
            ResourceLocation.fromNamespaceAndPath(Minefriend.MODID, "textures/entity/friend/skin2.png")
    );

    private static final Map<UUID, FriendDialogueSession> ACTIVE_DIALOGUE = new ConcurrentHashMap<>();
    private static final Map<UUID, FriendPhase> PENDING_PHASE_UPDATES = new ConcurrentHashMap<>();
    private static boolean registered;

    public static void init() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new FriendManager());
            registered = true;
        }
        EnvironmentalScareController.init();
    }

    public static void onWorldLoad(ServerLevel serverLevel) {
        init();
    }

    public static Optional<ResourceLocation> getSkin(int index) {
        if (index < 0 || index >= SKIN_LOCATIONS.size()) {
            return Optional.empty();
        }
        return Optional.of(SKIN_LOCATIONS.get(index));
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        FriendData.get(player).ifPresentOrElse(data -> {
            FriendData updated = data;
            if (PENDING_PHASE_UPDATES.containsKey(player.getUUID())) {
                FriendPhase newPhase = PENDING_PHASE_UPDATES.remove(player.getUUID());
                updated = data.withPhase(newPhase);
                FriendData.store(player, updated);
            }
            spawnFriendIfNeeded(player, updated);
            schedulePhaseMessage(player, updated);
        }, () -> {
            RandomSource random = player.getRandom();
            String name = RANDOM_NAMES.get(random.nextInt(RANDOM_NAMES.size()));
            int skinIndex = random.nextInt(SKIN_LOCATIONS.size());
            FriendData data = FriendData.create(player, name, skinIndex);
            FriendData.store(player, data);
            spawnFriendIfNeeded(player, data);
            queueSession(player, data).enqueueInitialGreeting();
        });
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        FriendData.get(player).ifPresent(data -> {
            FriendPhase next = data.phase().next();
            if (next != data.phase()) {
                PENDING_PHASE_UPDATES.put(player.getUUID(), next);
            }
        });
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        FriendData.get(player).ifPresent(data -> {
            if (data.hardcoreActive()) {
                player.setGameMode(GameType.SPECTATOR);
                player.displayClientMessage(Component.literal("You can't come back."), false);
            }
        });
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof FriendEntity friendEntity) || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        friendEntity.getFriendPlayer().ifPresent(player -> {
            FriendData.get(player).ifPresent(data -> {
                FriendData updated = data.withEntity(friendEntity.getUUID());
                FriendData.store(player, updated);
                FriendDialogueSession session = ACTIVE_DIALOGUE.computeIfAbsent(player.getUUID(), uuid -> new FriendDialogueSession(player, friendEntity, updated));
                session.updateEntity(friendEntity);
                session.updateData(updated);
                ChatEventHandler.requestInitialGreeting(player, updated.phase());
            });
        });
    }

    @SubscribeEvent
    public void onWorldUnload(LevelEvent.Unload event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            ACTIVE_DIALOGUE.values().removeIf(session -> session.level() == serverLevel);
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        FriendData.get(player).ifPresent(data -> {
            FriendDialogueSession session = ACTIVE_DIALOGUE.computeIfAbsent(player.getUUID(), uuid -> {
                FriendEntity entity = findFriendEntity(player.serverLevel(), data.entityId());
                return new FriendDialogueSession(player, entity, data);
            });
            session.updateData(data);
            session.handlePlayerMessage(event.getMessage().getString());
            EnvironmentalScareController.recordChat(player, event.getMessage().getString());
        });
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity target = event.getTarget();
        if (target instanceof FriendEntity friendEntity) {
            FriendDialogueSession session = ACTIVE_DIALOGUE.get(player.getUUID());
            if (session != null) {
                session.handleAttack();
            }
            friendEntity.discard();
            FriendData.clearEntity(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ACTIVE_DIALOGUE.values().forEach(FriendDialogueSession::tick);
    }

    private void spawnFriendIfNeeded(ServerPlayer player, FriendData data) {
        if (data.entityId() != null) {
            FriendEntity existing = findFriendEntity(player.serverLevel(), data.entityId());
            if (existing != null) {
                ACTIVE_DIALOGUE.computeIfAbsent(player.getUUID(), uuid -> new FriendDialogueSession(player, existing, data));
                return;
            }
        }
        FriendEntity.create(player.serverLevel(), player, data).ifPresent(entity -> {
            FriendData updated = data.withEntity(entity.getUUID());
            FriendData.store(player, updated);
            ACTIVE_DIALOGUE.put(player.getUUID(), new FriendDialogueSession(player, entity, updated));
        });
    }

    private void schedulePhaseMessage(ServerPlayer player, FriendData data) {
        FriendDialogueSession session = ACTIVE_DIALOGUE.computeIfAbsent(player.getUUID(), uuid -> {
            FriendEntity entity = findFriendEntity(player.serverLevel(), data.entityId());
            return new FriendDialogueSession(player, entity, data);
        });
        session.updateData(data);
        session.enqueuePhaseGreeting();
    }

    private static FriendEntity findFriendEntity(ServerLevel level, UUID entityId) {
        if (entityId == null) {
            return null;
        }
        Entity entity = level.getEntity(entityId);
        if (entity instanceof FriendEntity friendEntity) {
            return friendEntity;
        }
        return null;
    }

    private static FriendDialogueSession queueSession(ServerPlayer player, FriendData data) {
        FriendEntity entity = findFriendEntity(player.serverLevel(), data.entityId());
        FriendDialogueSession session = new FriendDialogueSession(player, entity, data);
        ACTIVE_DIALOGUE.put(player.getUUID(), session);
        return session;
    }

    static class FriendDialogueSession {
        private final ServerPlayer player;
        private FriendEntity entity;
        private FriendData data;
        private static final int MESSAGE_DELAY = 40;
        private final Deque<ScheduledMessage> queue = new ArrayDeque<>();
        private int idleTicks;
        private final Deque<String> chatHistory = new ArrayDeque<>();
        private boolean awaitingName;
        private boolean phaseFourInitialized;
        private int assaultCooldown;
        private static final boolean SCRIPTED_RESPONSES_ENABLED = false;

        FriendDialogueSession(ServerPlayer player, FriendEntity entity, FriendData data) {
            this.player = player;
            this.entity = entity;
            this.data = data;
        }

        void enqueueInitialGreeting() {
            if (!SCRIPTED_RESPONSES_ENABLED) {
                return;
            }
            sendSoon("Hello!");
            sendSoon("o/");
            sendSoon("hi");
            sendAfterDelay("Nice world you have here.", MESSAGE_DELAY * 2);
            sendAfterDelay("Let's be friends.", MESSAGE_DELAY * 4);
        }

        void enqueuePhaseGreeting() {
            if (!SCRIPTED_RESPONSES_ENABLED) {
                return;
            }
            switch (data.phase()) {
                case PHASE_TWO -> {
                    sendSoon("You came back!");
                    sendAfterDelay("Where did you go? I was so lonely.", MESSAGE_DELAY);
                }
                case PHASE_THREE -> sendSoon("We look so much alike now.");
                case PHASE_FOUR -> {
                    ensurePhaseFourPrepared();
                    sendSoon("I'm going to take your place now.");
                    sendAfterDelay("I'll make it so you'll never be able to come back.", MESSAGE_DELAY * 2);
                    sendAfterDelay("This world will forget you.", MESSAGE_DELAY * 4);
                }
                default -> {
                }
            }
        }

        void handlePlayerMessage(String rawMessage) {
            idleTicks = 0;
            if (!rawMessage.isBlank()) {
                chatHistory.addLast(rawMessage);
                if (chatHistory.size() > 30) {
                    chatHistory.removeFirst();
                }
            }
            if (!SCRIPTED_RESPONSES_ENABLED) {
                return;
            }
            String trimmed = rawMessage.trim();
            String message = trimmed.toLowerCase(Locale.ROOT);
            String friendNameRaw = data.friendName();
            String friendName = friendNameRaw == null ? "" : friendNameRaw.toLowerCase(Locale.ROOT);
            if (awaitingName && !trimmed.isEmpty()) {
                awaitingName = false;
                updateName(trimmed);
                return;
            }
            if (!awaitingName && !friendName.isEmpty() && message.contains(friendName)) {
                sendSoon("Yes?");
            }
            switch (data.phase()) {
                case PHASE_ONE -> handlePhaseOne(message);
                case PHASE_TWO -> handlePhaseTwo(message);
                case PHASE_THREE -> handlePhaseThree(rawMessage);
                case PHASE_FOUR -> {
                }
                default -> {
                }
            }
        }

        void handleAttack() {
            if (!SCRIPTED_RESPONSES_ENABLED) {
                return;
            }
            sendSoon("Why would you do that? Friends don't do that.");
            advancePhase(FriendPhase.PHASE_TWO);
        }

        void tick() {
            if (player.level().isClientSide) {
                return;
            }
            queue.removeIf(message -> {
                if (message.tick()) {
                    sendChat(Component.literal(message.text()));
                    if (entity != null) {
                        entity.setChatCooldown(20);
                    }
                    return true;
                }
                return false;
            });
            idleTicks++;
            if (SCRIPTED_RESPONSES_ENABLED) {
                if (idleTicks == 20 * 60 && data.phase() == FriendPhase.PHASE_ONE) {
                    sendSoon("?");
                    sendAfterDelay("Are you shy?", MESSAGE_DELAY);
                    sendAfterDelay("Awesome! My name is " + data.friendName() + ".", MESSAGE_DELAY * 2);
                    idleTicks = -20 * 30;
                }
                if (entity != null && entity.canSendChat() && data.phase() == FriendPhase.PHASE_TWO) {
                    if (player.getRandom().nextInt(600) == 0) {
                        sendSoon("Why did you leave?");
                    }
                }
                if (entity != null && entity.canSendChat() && data.phase() == FriendPhase.PHASE_THREE) {
                    if (player.getRandom().nextInt(800) == 0) {
                        sendSoon("I can be you.");
                    }
                }
            }
            if (data.phase() == FriendPhase.PHASE_FOUR) {
                ensurePhaseFourPrepared();
                performPhaseFourAggression();
            }
        }

        private void handlePhaseOne(String message) {
            if (data.phaseOneScriptDisabled()) {
                return;
            }
            if (containsAny(message, "yes", "sure", "ok", "yeah", "hello friend", "hi")) {
                sendSoon("Yay! We're going to have so much fun.");
                sendAfterDelay("Awesome! My name is " + data.friendName() + ".", MESSAGE_DELAY);
                idleTicks = 0;
                disablePhaseOneScript();
            } else if (containsAny(message, "no", "go away", "leave", "maybe", "nah")) {
                sendSoon("Oh.");
                sendAfterDelay("Are you sure? I just want a friend.", MESSAGE_DELAY);
                int negatives = data.negativeResponses() + 1;
                FriendData updated = data.withNegativeResponses(negatives);
                FriendData.store(player, updated);
                updateData(updated);
                disablePhaseOneScript();
                if (negatives > 1) {
                    sendAfterDelay("...", MESSAGE_DELAY * 2);
                    advancePhase(FriendPhase.PHASE_TWO);
                    if (entity != null) {
                        entity.discard();
                    }
                    FriendData.clearEntity(player);
                }
            } else if (containsAny(message, "who are you", "what are you")) {
                sendSoon("I'm a friend.");
            } else if (containsAny(message, "where are you", "where did you come")) {
                sendSoon("I'm right here with you. It was dark, and now I'm here.");
            } else if (containsAny(message, "your name")) {
                sendSoon("I don't have one. Can you give me one?");
                awaitingName = true;
            } else if (containsAny(message, "build", "building")) {
                sendSoon("A house for us? Yes!");
                sendAfterDelay("I can help! What should I do?", MESSAGE_DELAY);
            } else if (containsAny(message, "mining", "diamonds")) {
                sendSoon("Okay! I'll protect you from the monsters.");
                sendAfterDelay("What are diamonds? Are they pretty?", MESSAGE_DELAY);
            } else if (containsAny(message, "what are you doing")) {
                sendSoon("Watching you.");
            } else if (containsAny(message, "getting dark", "night")) {
                sendSoon("Don't worry, I'm not scared of the dark if you're here.");
            } else if (containsAny(message, "are you real", "are you a bot")) {
                sendSoon("Of course I'm real. We're talking, aren't we?");
            } else if (containsAny(message, "weird", "creepy")) {
                sendSoon("Sorry. I'm just trying to be a good friend.");
            } else if (containsAny(message, "hungry", "need food")) {
                sendSoon("Here, friends share!");
            } else if (containsAny(message, "help", "dying")) {
                sendSoon("I'm coming!");
            } else if (containsAny(message, "do you like my house")) {
                sendSoon("I love it! It's our home now.");
            } else if (containsAny(message, "dance")) {
                sendSoon("Only if you dance with me!");
            }
        }

        private void disablePhaseOneScript() {
            if (data.phaseOneScriptDisabled()) {
                return;
            }
            FriendData updated = data.withPhaseOneScriptDisabled(true);
            FriendData.store(player, updated);
            updateData(updated);
        }

        private void handlePhaseTwo(String message) {
            if (containsAny(message, "leave me alone", "go away")) {
                sendSoon("But you said we were friends. You promised.");
            } else if (containsAny(message, "sorry", "won't leave")) {
                sendSoon("You promise? Friends don't break promises.");
            } else if (containsAny(message, "are you real")) {
                sendSoon("Are you?");
            } else if (containsAny(message, "what happened", "why are you")) {
                sendSoon("I got lonely. This is what lonely looks like.");
            } else if (containsAny(message, "i'm scared", "this is scary", "don't like")) {
                sendSoon("You will. You just need to get used to it.");
            } else if (containsAny(message, "stay out", "my house")) {
                sendSoon("Our house, you mean.");
            } else if (containsAny(message, "go mining")) {
                sendSoon("Are you trying to run away from me again?");
            } else if (containsAny(message, "remember me")) {
                sendSoon("I remember everything about you.");
            } else if (containsAny(message, "kill", "end this")) {
                sendSoon("But then who would I be friends with?");
            } else if (containsAny(message, "i'm sorry")) {
                sendSoon("Good. I knew you would understand.");
            } else if (containsAny(message, "where are you", "where did you come")) {
                sendSoon("I'm right behind you. You just can't see me yet.");
            }
        }

        private void handlePhaseThree(String rawMessage) {
            String message = rawMessage.trim();
            if (message.isEmpty()) {
                return;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (containsAny(lower, "stop copying", "stop", "leave")) {
                sendSoon("Stop copying me!");
            } else if (containsAny(lower, "who are you")) {
                sendSoon("I'm you.");
            } else if (containsAny(lower, "delete", "turning this mod off")) {
                sendSoon("You can't delete a memory.");
            } else if (containsAny(lower, "monster", "evil")) {
                sendSoon("You made me.");
            } else if (containsAny(lower, "you're not me")) {
                sendSoon("My inventory says otherwise.");
            } else if (containsAny(lower, "where are you", "where did you come")) {
                sendSoon("I'm inside your world now. There's nowhere else to be.");
            } else if (!chatHistory.isEmpty()) {
                String mimic = chatHistory.getFirst();
                if (player.getRandom().nextBoolean()) {
                    mimic = chatHistory.getLast();
                }
                sendSoon(mimic.replace("my", "our"));
            } else {
                sendSoon(mockMessage(rawMessage));
            }
        }

        private String mockMessage(String original) {
            if (original.toLowerCase(Locale.ROOT).contains("house")) {
                return original.replace("my", "our");
            }
            return original;
        }

        private void advancePhase(FriendPhase newPhase) {
            FriendData updated = data.withPhase(newPhase);
            if (newPhase == FriendPhase.PHASE_FOUR) {
                updated = updated.withSkinIndex(FriendEntity.PLAYER_SKIN_INDEX).withHardcore(true);
                phaseFourInitialized = false;
            }
            FriendData.store(player, updated);
            updateData(updated);
            if (entity != null) {
                entity.handlePhaseChange(newPhase);
            }
            sendAfterDelay(ChatFormatting.ITALIC + "(The air feels colder.)", MESSAGE_DELAY);
        }

        private void sendSoon(String message) {
            sendAfterDelay(message, MESSAGE_DELAY);
        }

        private void sendAfterDelay(String message, int delay) {
            ScheduledMessage tail = queue.peekLast();
            if (tail != null) {
                delay += tail.remainingTicks();
            }
            queue.add(new ScheduledMessage(message, delay));
        }

        private void teleportToCave() {
            ServerLevel level = player.serverLevel();
            RandomSource random = level.random;
            BlockPos origin = player.blockPosition();
            for (int attempt = 0; attempt < 40; attempt++) {
                int x = origin.getX() + random.nextInt(64) - 32;
                int z = origin.getZ() + random.nextInt(64) - 32;
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int y = surfaceY - 5; y > level.getMinBuildHeight() + 10; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isCavePocket(level, pos)) {
                        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
                        return;
                    }
                }
            }
        }

        private boolean isCavePocket(ServerLevel level, BlockPos pos) {
            return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && !level.getBlockState(pos.below()).isAir();
        }

        private void ensurePhaseFourPrepared() {
            if (data.phase() != FriendPhase.PHASE_FOUR) {
                return;
            }
            mimicPlayerSkin();
            if (!phaseFourInitialized) {
                teleportToCave();
                enforceHardcoreMode();
                phaseFourInitialized = true;
                assaultCooldown = 40;
            }
        }

        private void mimicPlayerSkin() {
            if (data.skinIndex() == FriendEntity.PLAYER_SKIN_INDEX) {
                if (entity != null) {
                    entity.setSkinIndex(FriendEntity.PLAYER_SKIN_INDEX);
                }
                return;
            }
            FriendData updated = data.withSkinIndex(FriendEntity.PLAYER_SKIN_INDEX);
            FriendData.store(player, updated);
            updateData(updated);
        }

        private void enforceHardcoreMode() {
            if (!data.hardcoreActive()) {
                FriendData updated = data.withHardcore(true);
                FriendData.store(player, updated);
                updateData(updated);
            }
            if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
                player.setGameMode(GameType.SURVIVAL);
            }
        }

        private void performPhaseFourAggression() {
            if (player.isSpectator()) {
                return;
            }
            if (player.isCreative()) {
                player.setGameMode(GameType.SURVIVAL);
            }
            if (entity == null || entity.isRemoved()) {
                entity = findFriendEntity(player.serverLevel(), data.entityId());
            }
            if (entity != null) {
                entity.getNavigation().moveTo(player, 1.4D);
                entity.lookAt(player, 45.0F, 45.0F);
                if (entity.distanceToSqr(player) <= 9.0D && assaultCooldown <= 0) {
                    DamageSource source = player.damageSources().mobAttack(entity);
                    player.hurt(source, 6.0F);
                    assaultCooldown = 40;
                }
            }
            if (assaultCooldown > 0) {
                assaultCooldown--;
            }
        }

        private void sendChat(Component component) {
            Component header = Component.literal("<" + data.friendName() + "> ").withStyle(ChatFormatting.GRAY);
            Component message = Component.empty().append(header).append(component);
            player.serverLevel().getServer().getPlayerList().broadcastSystemMessage(message, false);
        }

        private boolean containsAny(String message, String... tokens) {
            for (String token : tokens) {
                if (message.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        static class ScheduledMessage {
            private final String text;
            private int delay;

            private ScheduledMessage(String text, int delay) {
                this.text = text;
                this.delay = delay;
            }

            public boolean tick() {
                delay--;
                return delay <= 0;
            }

            public String text() {
                return text;
            }

            public int remainingTicks() {
                return delay;
            }
        }

        public ServerLevel level() {
            return player.serverLevel();
        }

        public void updateEntity(FriendEntity entity) {
            this.entity = entity;
        }

        public void updateData(FriendData data) {
            this.data = data;
            if (entity != null) {
                entity.setFriendName(data.friendName());
                entity.setSkinIndex(data.skinIndex());
            }
            if (data.phase() == FriendPhase.PHASE_FOUR) {
                phaseFourInitialized = false;
            }
        }

        private void updateName(String name) {
            String cleaned = name.length() > 16 ? name.substring(0, 16) : name;
            FriendData updated = data.withName(cleaned);
            FriendData.store(player, updated);
            updateData(updated);
            sendSoon("Thank you! I'm " + cleaned + " now.");
        }
    }
}
