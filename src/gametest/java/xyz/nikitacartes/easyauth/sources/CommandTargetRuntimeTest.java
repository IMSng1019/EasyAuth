package xyz.nikitacartes.easyauth.sources;

//? if >= 1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;

//? if >= 1.20.3 && < 1.20.5 {
/*import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
*///?}

//? if >= 1.21.5 {
public class CommandTargetRuntimeTest {
    @GameTest
//?} else {
/*public class CommandTargetRuntimeTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
*///?}
    public void prefixedAccountsUseVanillaQuotedTeleportTargets(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*try (Fixture fixture = new Fixture(context)) {
            var source = fixture.server.createCommandSourceStack();
            var dispatcher = fixture.server.getCommands().getDispatcher();
            for (ServerPlayer target : List.of(fixture.union, fixture.offline)) {
                String name = target.getGameProfile().getName();
                UUID uuid = target.getUUID();
                String quoted = StringArgumentType.escapeIfRequired(name);
                check(EntityArgument.player().parse(new StringReader(quoted)).findSinglePlayer(source) == target,
                        "Vanilla quoted name selected the wrong prefixed player");
                check(EntityArgument.players().parse(new StringReader("@a[name=" + quoted + "]"))
                                .findPlayers(source).equals(List.of(target)),
                        "Quoted selector name no longer resolves the prefixed player");
                expectInvalid(name);
                fixture.official.setPos(10, 70, 10);
                target.setPos(25, 70, 40);
                check(dispatcher.execute("tp " + fixture.official.getGameProfile().getName() + " " + quoted, source) == 1
                                && fixture.official.position().equals(target.position()),
                        "Vanilla quoted teleport did not reach the prefixed player");
                check(target.getUUID().equals(uuid) && target.getGameProfile().getName().equals(name),
                        "Command handling changed the allocated identity");
            }
            String union = StringArgumentType.escapeIfRequired(fixture.union.getGameProfile().getName());
            String offline = StringArgumentType.escapeIfRequired(fixture.offline.getGameProfile().getName());
            fixture.union.setPos(3, 70, 3);
            fixture.offline.setPos(30, 70, 30);
            check(dispatcher.execute("tp " + union + " " + offline, source) == 1
                            && fixture.union.position().equals(fixture.offline.position()),
                    "Vanilla teleport with both prefixed arguments failed");
            check(EntityArgument.player().parse(new StringReader(fixture.official.getGameProfile().getName()))
                            .findSinglePlayer(source) == fixture.official,
                    "Ordinary player names no longer resolve");
            check(EntityArgument.player().parse(new StringReader("@s"))
                            .findSinglePlayer(source.withEntity(fixture.official)) == fixture.official,
                    "Vanilla self selector changed");
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = EMPTY_STRUCTURE)
    *///?}
    public void commandTreeAndLocalSuggestionsRemainVanilla(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*try (Fixture fixture = new Fixture(context)) {
            var dispatcher = fixture.server.getCommands().getDispatcher();
            var teleport = dispatcher.getRoot().getChild("teleport");
            CommandNode<?> originalDestination = teleport.getChild("destination");
            CommandNode<?> originalTargets = teleport.getChild("targets");
            String customName = "suggestions" + UUID.randomUUID().toString().substring(0, 8);
            dispatcher.register(Commands.literal(customName)
                    .then(Commands.argument("custom", EntityArgument.entity()).suggests(SuggestionProviders.SUMMONABLE_ENTITIES))
                    .then(Commands.argument("word", StringArgumentType.word())));
            fixture.server.getCommands().sendCommands(fixture.official);
            check(fixture.official.transport.commandTree != null, "No command tree packet was sent");
            FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            ClientboundCommandsPacket decoded;
            try {
                fixture.official.transport.commandTree.write(buffer);
                decoded = new ClientboundCommandsPacket(buffer);
                check(!buffer.isReadable(), "Command tree packet left unread bytes");
            } finally {
                buffer.release();
            }
            var clientRoot = decoded.getRoot(CommandBuildContext.simple(fixture.server.registryAccess(),
                    fixture.server.getWorldData().enabledFeatures()));
            assertVanillaTeleportTree(clientRoot.getChild("teleport"));
            assertVanillaTeleportTree(teleport);
            check(teleport.getChild("destination") == originalDestination && teleport.getChild("targets") == originalTargets,
                    "Sending the command tree replaced server argument nodes");
            check(clientRoot.getChild("tp").getRedirect() == clientRoot.getChild("teleport"), "Vanilla /tp redirect changed");
            var custom = (ArgumentCommandNode<SharedSuggestionProvider, ?>) clientRoot.getChild(customName).getChild("custom");
            check(custom.getCustomSuggestions() == SuggestionProviders.getProvider(
                            new net.minecraft.resources.ResourceLocation("minecraft:summonable_entities")),
                    "A third-party suggestion provider was replaced");
            check(((ArgumentCommandNode<?, ?>) clientRoot.getChild(customName).getChild("word")).getCustomSuggestions() == null,
                    "A plain argument gained a server suggestion provider");

            var source = fixture.official.createCommandSourceStack();
            String selectedEntity = UUID.randomUUID().toString();
            SharedSuggestionProvider client = (SharedSuggestionProvider) java.lang.reflect.Proxy.newProxyInstance(
                    SharedSuggestionProvider.class.getClassLoader(), new Class<?>[]{SharedSuggestionProvider.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "customSuggestion" -> throw new AssertionError("Vanilla player completion sent an ASK_SERVER request");
                        case "getOnlinePlayerNames" -> fixture.names();
                        case "getSelectedEntities" -> List.of(selectedEntity);
                        default -> method.invoke(source, arguments);
                    });
            var clientDispatcher = new CommandDispatcher<SharedSuggestionProvider>(clientRoot);
            String ordinary = fixture.official.getGameProfile().getName();
            for (String input : List.of("tp ", "/teleport ", "/tp [M", "tp [OFF]", "tp " + ordinary.substring(0, 3), "tp @a ")) {
                StringReader reader = new StringReader(input);
                if (input.startsWith("/")) reader.skip();
                var pending = clientDispatcher.getCompletionSuggestions(clientDispatcher.parse(reader, client));
                check(pending.isDone() && !pending.isCompletedExceptionally(), "Local vanilla completion did not finish: " + input);
                var suggestions = pending.join().getList();
                for (String name : fixture.names()) {
                    if (!input.endsWith(" ") && !input.endsWith(name.substring(0, 3))
                            && !(name.startsWith("[MUA]") && input.endsWith("[M"))
                            && !(name.startsWith("[OFF]") && input.endsWith("[OFF]"))) continue;
                    check(suggestions.stream().anyMatch(suggestion -> suggestion.getText().equals(name)),
                            "Local vanilla suggestions omitted " + name + " for: " + input);
                    if (!StringArgumentType.escapeIfRequired(name).equals(name)) {
                        check(suggestions.stream().noneMatch(suggestion -> suggestion.getText().equals(StringArgumentType.escapeIfRequired(name))),
                                "Automatic quote completion is still installed");
                    }
                }
                if (input.endsWith(" ")) {
                    check(suggestions.stream().anyMatch(suggestion -> suggestion.getText().equals(selectedEntity)),
                            "Vanilla crosshair entity UUID suggestion was not restored");
                }
            }
            String union = StringArgumentType.escapeIfRequired(fixture.union.getGameProfile().getName());
            String offline = StringArgumentType.escapeIfRequired(fixture.offline.getGameProfile().getName());
            for (String input : List.of("tp " + union, "tp " + union + " " + offline, "teleport " + ordinary + " " + offline,
                    "tp @a ~ ~ ~", "tp @s ~ ~ ~ ~ ~", "tp @s ~ ~ ~ facing entity @e[limit=1] eyes",
                    "tp @s ~ ~ ~ facing ~ ~ ~", "tp " + selectedEntity)) {
                var parsed = clientDispatcher.parse(input, client);
                check(!parsed.getReader().canRead() && parsed.getContext().getLastChild().getCommand() != null,
                        "Valid vanilla command became incomplete: " + input);
            }
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static void assertVanillaTeleportTree(CommandNode<?> teleport) {
        check(teleport.getChildren().size() == 3 && teleport.getChild("location") != null,
                "Vanilla teleport argument branches were changed");
        check(teleport.getChild("destination") != null && teleport.getChild("destination").getCommand() != null,
                "Vanilla single-destination branch is missing");
        check(teleport.getChild("targets") != null && teleport.getChild("targets").getCommand() == null,
                "Vanilla multi-target branch gained single-target execution");
        for (CommandNode<?> node : List.of(teleport.getChild("destination"), teleport.getChild("targets"),
                teleport.getChild("targets").getChild("destination"))) {
            check(node instanceof ArgumentCommandNode<?, ?> argument && argument.getCustomSuggestions() == null,
                    "Entity arguments still force server-side completion");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server;
        final Field ledgerField;
        final Object previousLedger;
        final Path ledgerFile;
        final IdentityStore ledger;
        final CommandTestPlayer official;
        final CommandTestPlayer union;
        final CommandTestPlayer offline;

        Fixture(GameTestHelper context) throws Exception {
            server = context.getLevel().getServer();
            ledgerField = MultiAuth.class.getDeclaredField("identities");
            ledgerField.setAccessible(true);
            previousLedger = ledgerField.get(null);
            ledgerFile = Files.createTempFile("easyauth-vanilla-commands-", ".sqlite");
            ledger = new IdentityStore(ledgerFile);
            ledgerField.set(null, ledger);
            String raw = "Cmd" + UUID.randomUUID().toString().substring(0, 8);
            var external = ledger.resolve(new AuthenticatedIdentity("vanilla-command-test", SourceType.UNION,
                    raw, UUID.randomUUID(), raw, List.of()), "[MUA]", (identity, profile, existing) -> {});
            String offlineRaw = "Off" + UUID.randomUUID().toString().substring(0, 7);
            var local = ledger.resolveOffline(offlineRaw, UUIDUtil.createOfflinePlayerUUID(offlineRaw), "[OFF]", profile -> {}).profile();
            official = new CommandTestPlayer(context, new GameProfile(UUID.randomUUID(), raw));
            union = new CommandTestPlayer(context, new GameProfile(external.uuid(), external.name()));
            offline = new CommandTestPlayer(context, new GameProfile(local.uuid(), local.name()));
            server.getPlayerList().getPlayers().addAll(List.of(official, union, offline));
        }
        List<String> names() { return List.of(official.getGameProfile().getName(), union.getGameProfile().getName(), offline.getGameProfile().getName()); }
        @Override public void close() throws Exception {
            server.getPlayerList().getPlayers().removeAll(List.of(official, union, offline));
            ledgerField.set(null, previousLedger);
            ledger.close();
            Files.deleteIfExists(ledgerFile);
        }
    }

    private static final class CommandTestPlayer extends ServerPlayer {
        final CaptureConnection transport = new CaptureConnection();
        CommandTestPlayer(GameTestHelper context, GameProfile profile) {
            super(context.getLevel().getServer(), context.getLevel(), profile, ClientInformation.createDefault());
            connection = new ServerGamePacketListenerImpl(context.getLevel().getServer(), transport, this, CommonListenerCookie.createInitial(profile));
        }
        @Override protected int getPermissionLevel() { return 4; }
    }

    private static final class CaptureConnection extends Connection {
        ClientboundCommandsPacket commandTree;
        CaptureConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void setListener(PacketListener listener) {}
        @Override public boolean isConnected() { return true; }
        @Override public java.net.SocketAddress getRemoteAddress() { return new java.net.InetSocketAddress("127.0.0.1", 25565); }
        @Override public void send(Packet<?> packet) { if (packet instanceof ClientboundCommandsPacket tree) commandTree = tree; }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { send(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { send(packet); }
    }

    private static void expectInvalid(String target) throws Exception {
        try { EntityArgument.player().parse(new StringReader(target)); }
        catch (CommandSyntaxException expected) { return; }
        throw new AssertionError("Allocated bracket name was accepted without vanilla quotes: " + target);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    *///?}
}
