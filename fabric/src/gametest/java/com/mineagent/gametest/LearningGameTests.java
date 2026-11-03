package com.mineagent.gametest;

import com.google.gson.JsonObject;
import com.mineagent.engine.entity.CompanionEntity;
import com.mineagent.engine.exploration.EnvironmentFingerprint;
import com.mineagent.engine.exploration.MechanismExplorer;
import com.mineagent.engine.skill.SkillLibrary;
import com.mineagent.engine.skill.SkillRuntime;
import com.mineagent.engine.world.SemanticWorldModel;
import com.mineagent.tools.block.InspectBlockTool;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import java.util.HashMap;
import java.util.Map;

/** Test-only mod. Its hidden rule map is never exposed to the production observation tool. */
public final class LearningGameTests implements ModInitializer, FabricGameTest {
    private static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 2);
    private static final Map<BlockPos, Item> RULES = new HashMap<>();
    private static final Item TOKEN = new Item(new Item.Properties().durability(120)) {
        @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,Item.TooltipContext context,
                java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag) {
            for(int i=0;i<15;i++) lines.add(net.minecraft.network.chat.Component.literal("unfamiliar catalyst "+i+" "+"x".repeat(180)));
        }
    };
    private static final Block MACHINE = new Block(BlockBehaviour.Properties.of().strength(1)) {
        { registerDefaultState(stateDefinition.any().setValue(STAGE, 0)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(STAGE); }
        @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                net.minecraft.world.entity.player.Player player, BlockHitResult hit) {
            int result = player.getMainHandItem().is(RULES.getOrDefault(pos, Items.REDSTONE)) ? 1 : 2;
            level.setBlock(pos, state.setValue(STAGE, result), 3);
            return InteractionResult.SUCCESS;
        }
    };

    @Override public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("mineagent_gametest", "unfamiliar_machine"), MACHINE);
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("mineagent_gametest", "unfamiliar_token"), TOKEN);
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void observesPublicActorAndRejectsWrongUuid(GameTestHelper helper) {
        var npc=helper.spawn(net.minecraft.world.entity.EntityType.VILLAGER,new BlockPos(1,1,1));
        npc.setCustomName(net.minecraft.network.chat.Component.literal("Unfamiliar traveller"));
        var player=helper.makeMockServerPlayerInLevel();
        player.setPos(npc.getX()+2,npc.getY(),npc.getZ()+0.5);
        var companion=new CompanionEntity(player,player,"ActorTest");
        JsonObject args=new JsonObject(); args.addProperty("entity_id",npc.getId());
        var result=new java.util.concurrent.atomic.AtomicReference<String>();
        new com.mineagent.tools.perception.InspectEntityTool().onServerCall("inspect",args,companion,result::set);
        var data=com.google.gson.JsonParser.parseString(result.get()).getAsJsonObject();
        require(data.get("entity_type").getAsString().equals("minecraft:villager"),"Registered actor type must be observed");
        require(data.get("fact_subject").getAsString().equals("entity:"+npc.getUUID()),"Actor UUID must be bound");
        require(!data.has("quest_state")&&!data.has("nbt"),"Private actor state must not be exposed");
        args.addProperty("button","use"); args.addProperty("expected_uuid",java.util.UUID.randomUUID().toString());
        new com.mineagent.tools.InteractEntityTool().onServerCall("use",args,companion,result::set);
        require(result.get().contains("UUID changed"),"Wrong UUID must be rejected before task dispatch");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void observesNewItemWithoutDumpingPrivateComponents(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel(); var companion=new CompanionEntity(player,player,"ItemTest");
        var stack=TOKEN.getDefaultInstance(); stack.setDamageValue(5); player.getInventory().setItem(0,stack);
        JsonObject args=new JsonObject(); args.addProperty("inventory_slot",0);
        var result=new java.util.concurrent.atomic.AtomicReference<String>();
        new com.mineagent.tools.perception.InspectItemTool().onServerCall("inspect",args,companion,result::set);
        var data=com.google.gson.JsonParser.parseString(result.get()).getAsJsonObject();
        require(data.get("item_id").getAsString().equals("mineagent_gametest:unfamiliar_token"),"New item identity must be visible");
        require(data.get("durability").getAsInt()==115,"Carried durability must be observed");
        require(data.getAsJsonArray("tooltip").size()<=12 && data.get("tooltip_truncated").getAsBoolean(),"Tooltip output must be bounded");
        require(!data.has("components")&&!data.has("nbt"),"Private stack components must not be dumped");
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 240, batch = "reload")
    public void nativeDataReloadInvalidatesLearnedRules(GameTestHelper helper) {
        var world=new SemanticWorldModel(); var skills=new SkillLibrary();
        var explorer=new MechanismExplorer(world,(a,b,c,d)->{},skills,
                new EnvironmentFingerprint("1.21.1","fabric","reload","reload"),null);
        long tick=helper.getLevel().getGameTime();
        for(int i=0;i<2;i++) {
            String args="{\"x\":"+i+"}"; String target="reload:"+i;
            var proposal=explorer.propose("test:reload","use opens","interact_at",args,target,"state","open",MechanismExplorer.Risk.MEDIUM,tick);
            require(proposal.accepted(),proposal.message()); explorer.onToolDispatched("reload-"+i,"interact_at",args,tick);
            world.observe(target,"state","open",null,1,"server",null,tick,400,false);
            explorer.onToolResult("reload-"+i,"interact_at",true,false,"{\"success\":true}",tick);
        }
        var rule=explorer.knowledgeBase().rules().iterator().next();
        require(rule.reusable(explorer.knowledgeBase().currentFingerprint()),"Fixture rule must be verified");
        var complete=new java.util.concurrent.atomic.AtomicBoolean(); var observed=new java.util.concurrent.atomic.AtomicBoolean();
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server,manager,success)->{
            if(success && server==helper.getLevel().getServer() && observed.compareAndSet(false,true))
                explorer.onRulesReloaded(server.overworld().getGameTime());
        });
        var server=helper.getLevel().getServer();
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((value,failure)->{
            error.set(failure); complete.set(true);
        });
        helper.succeedWhen(()->{
            helper.assertTrue(complete.get(),"Native reload is not finished");
            helper.assertTrue(error.get()==null,"Native reload failed");
            helper.assertTrue(observed.get(),"Reload lifecycle callback did not run");
            helper.assertTrue(skills.get(rule.adapterSkill()).isEmpty(),"Reload retained an old adapter");
            helper.assertTrue(explorer.exportState().learning().trials().isEmpty(),"Reload retained old predictions");
        });
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 100)
    public void runsObservationsViaTheAgentLoopAndReadsPublicStorage(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1); helper.setBlock(relative, net.minecraft.world.level.block.Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(relative);
        var stack = Items.REDSTONE.getDefaultInstance(); chest.setItem(0, stack);
        var player = helper.makeMockServerPlayerInLevel(); BlockPos absolute = helper.absolutePos(relative);
        player.setPos(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 2.5);
        var companion = new CompanionEntity(player, player, "ProbeRunTest");
        // No start/wake: exercise the server dispatcher without creating model requests.
        var loop = new com.mineagent.engine.loop.AgentLoop(companion, "openai-compatible", "", "unconnected-test",
                "http://127.0.0.1:9", 0, 64, null);
        try {
            var args = arguments(absolute); args.addProperty("capabilities", true);
            var raw = new java.util.concurrent.atomic.AtomicReference<String>();
            new InspectBlockTool().onServerCall("baseline", args, companion, raw::set);
            var observed = com.google.gson.JsonParser.parseString(raw.get()).getAsJsonObject();
            var caps = observed.getAsJsonObject("capabilities");
            require(caps.getAsJsonObject("channels").get("items").getAsString().equals("available"), "Public chest adapter must be available");
            require(caps.getAsJsonObject("fields").get("items.0.id").getAsString().equals("minecraft:redstone"), "Public storage must expose the real item");
            require(caps.get("truncated").getAsBoolean(), "A 27-slot chest must respect the 16-view limit");
            long tick = helper.getLevel().getGameTime(); var explorer = loop.mechanismExplorer();
            explorer.onToolResult("baseline", "inspect_block", true, false, raw.get(), tick);
            var proposal = explorer.propose("minecraft:chest", "poll observes updated public count", "inspect_block", args.toString(),
                    subject(helper, relative), "capability.items.0.count", "2", MechanismExplorer.Risk.LOW, tick);
            require(proposal.accepted(), proposal.message());
            chest.setItem(0, new net.minecraft.world.item.ItemStack(Items.REDSTONE, 2));
            require(chest.getItem(0).getCount() == 2, "Fixture must use a legal stackable item");
            var result = loop.runMechanismProbe(proposal.experiment().id());
            require(result.accepted() && !result.asynchronous(), "A successful observation must not require an action postcondition flag");
            require(explorer.get(proposal.experiment().id()).status() == MechanismExplorer.Status.SUPPORTED,
                    "run must publish the fresh observation: " + explorer.get(proposal.experiment().id()).evidence());
            require(chest.getItem(0).getCount() == 2, "Observation must not mutate storage");
            var invalid = explorer.propose("mineagent_gametest:unfamiliar_token", "observe invalid slot", "inspect_item", "{\"inventory_slot\":-1}",
                    "tool:inspect_item", "outcome", "success", MechanismExplorer.Risk.LOW, tick);
            require(invalid.accepted(), invalid.message());
            require(!loop.runMechanismProbe(invalid.experiment().id()).accepted(), "Failed reads must remain failures");
            require(explorer.get(invalid.experiment().id()).status() == MechanismExplorer.Status.INCONCLUSIVE, "Failed reads must finish without invented counterexamples");
            require(loop.metricsSnapshot().llmCalls() == 0, "Native dispatch tests must make no model calls");
        } finally { loop.shutdown(); }
        helper.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE, timeoutTicks = 60)
    public void learnsTransfersAndStopsAfterRuleChange(GameTestHelper helper) {
        var world = new SemanticWorldModel();
        var library = new SkillLibrary();
        var explorer = new MechanismExplorer(world, (a, b, c, d) -> {}, library,
                new EnvironmentFingerprint("1.21.1", "fabric", "gametest", "gametest"), null);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Items.REDSTONE.getDefaultInstance());
        var companion = new CompanionEntity(player, player, "GameTest");
        BlockPos[] positions = {new BlockPos(1, 1, 1), new BlockPos(3, 1, 1), new BlockPos(1, 1, 3)};
        long tick = helper.getLevel().getGameTime();
        for (BlockPos position : positions) {
            helper.setBlock(position, MACHINE);
            RULES.put(helper.absolutePos(position), Items.REDSTONE);
        }
        for (int i = 0; i < 2; i++) {
            BlockPos position = positions[i];
            inspect(helper, position, companion, explorer, tick);
            var args = arguments(helper.absolutePos(position));
            var proposal = propose(explorer, subject(helper, position), args, tick);
            require(proposal.accepted(), proposal.message());
            String action = "source-" + i;
            explorer.onToolDispatched(action, "interact_at", args.toString(), tick);
            helper.useBlock(position, player);
            explorer.onToolResult(action, "interact_at", true, false, "{\"success\":true}", tick);
            inspect(helper, position, companion, explorer, tick);
            explorer.tick(tick);
            require(explorer.get(proposal.experiment().id()).status() == MechanismExplorer.Status.SUPPORTED,
                    "Native block observation must support the declared effect");
        }
        var rule = explorer.knowledgeBase().rules().iterator().next();
        require(rule.reusable(explorer.knowledgeBase().currentFingerprint()), "Two real independent setups must establish reuse");
        BlockPos target = positions[2];
        inspect(helper, target, companion, explorer, tick);
        var transfer = explorer.transfer(rule.id(), subject(helper, target), helper.getLevel().dimension().location().toString(), tick);
        require(transfer.eligible(), transfer.message());
        var runtime = new SkillRuntime(library, world, (id, tool, args) -> {
            BlockPos absolute = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
            BlockPos relative = absolute.subtract(helper.absolutePos(BlockPos.ZERO));
            helper.useBlock(relative, player);
            inspect(helper, relative, companion, explorer, tick);
            return new SkillRuntime.DispatchResult(true, false, true, "Native interaction accepted");
        }, ignored -> {});
        require(runtime.start(transfer.skillName(), transfer.overrides(), tick).accepted(), "Transferred skill must start");
        runtime.tick(tick); runtime.tick(tick + 1);
        require(runtime.snapshot().status() == SkillRuntime.Status.SUCCEEDED, "Transferred native effect must verify");
        helper.assertBlockProperty(target, STAGE, 1);
        helper.setBlock(target, MACHINE);
        RULES.put(helper.absolutePos(target), Items.IRON_INGOT);
        inspect(helper, target, companion, explorer, tick);
        var args = arguments(helper.absolutePos(target));
        var changed = propose(explorer, subject(helper, target), args, tick);
        require(changed.accepted(), changed.message());
        explorer.onToolDispatched("changed", "interact_at", args.toString(), tick);
        helper.useBlock(target, player);
        explorer.onToolResult("changed", "interact_at", true, false, "{\"success\":true}", tick);
        inspect(helper, target, companion, explorer, tick); explorer.tick(tick);
        require(explorer.get(changed.experiment().id()).status() == MechanismExplorer.Status.REFUTED,
                "Changed native rule must produce a counterexample");
        require(library.get(transfer.skillName()).isEmpty(), "Counterexample must remove automatic replay");
        // A changed hidden rule must learn a NEW outcome, using only the bounded recovery allowance.
        for (int i = 0; i < 2; i++) {
            BlockPos position = positions[i]; helper.setBlock(position, MACHINE);
            RULES.put(helper.absolutePos(position), Items.IRON_INGOT);
            inspect(helper, position, companion, explorer, tick);
            var newArgs = arguments(helper.absolutePos(position));
            var replacement = explorer.propose("mineagent_gametest:unfamiliar_machine", "changed rule produces stage two",
                    "interact_at", newArgs.toString(), subject(helper, position), "property.stage", "2",
                    MechanismExplorer.Risk.MEDIUM, "native-relearning", true, "close_gui", "{}", 4, 0.5, tick);
            require(replacement.accepted(), replacement.message());
            String call = "relearn-" + i; explorer.onToolDispatched(call, "interact_at", newArgs.toString(), tick);
            helper.useBlock(position, player);
            explorer.onToolResult(call, "interact_at", true, false, "{\"success\":true}", tick);
            inspect(helper, position, companion, explorer, tick); explorer.tick(tick);
            require(explorer.get(replacement.experiment().id()).status() == MechanismExplorer.Status.SUPPORTED,
                    "Changed rule must obtain fresh physical support");
        }
        var relearned = explorer.knowledgeBase().rules().stream().filter(value -> value.expectedValue().equals("2")).findFirst().orElseThrow();
        require(relearned.reusable(explorer.knowledgeBase().currentFingerprint()), "Two new native supports must restore reuse");
        var prediction = explorer.predict("mineagent_gametest:unfamiliar_machine", "interact_at", args.toString(),
                "property.stage", "0", subject(helper, target), java.util.List.of(), tick);
        require(prediction.independentContexts() == 2 && !prediction.needsRevalidation(), "New predictor must use the fresh rule window");
        require(!prediction.outcomes().containsKey("1"), "Obsolete outcomes must not block recovery");
        require(explorer.exportState().budgets().getFirst().remaining() == 0, "Native recovery must stay within its quota");
        for (BlockPos position : positions) RULES.remove(helper.absolutePos(position));
        helper.succeed();
    }

    private static MechanismExplorer.Proposal propose(MechanismExplorer explorer, String target, JsonObject args, long tick) {
        return explorer.propose("mineagent_gametest:unfamiliar_machine", "use with redstone produces stage one", "interact_at",
                args.toString(), target, "property.stage", "1", MechanismExplorer.Risk.MEDIUM,
                "native-setup", true, "close_gui", "{}", 4, 0.5, tick);
    }

    private static void inspect(GameTestHelper helper, BlockPos relative, CompanionEntity companion,
                                MechanismExplorer explorer, long tick) {
        BlockPos absolute = helper.absolutePos(relative);
        companion.serverPlayer().setPos(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 2.5);
        new InspectBlockTool().onServerCall("inspect", arguments(absolute), companion, raw ->
                explorer.onToolResult("inspect", "inspect_block", true, false, raw, tick));
    }
    private static String subject(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        return "block:" + helper.getLevel().dimension().location() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
    private static JsonObject arguments(BlockPos position) {
        JsonObject args = new JsonObject(); args.addProperty("x", position.getX());
        args.addProperty("y", position.getY()); args.addProperty("z", position.getZ()); return args;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
