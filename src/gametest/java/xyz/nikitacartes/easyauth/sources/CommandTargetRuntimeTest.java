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
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.nio.file.Files;
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
    public void allocatedExternalNamesAreTeleportTargets(GameTestHelper context) throws Exception {
        //? if >= 1.20.3 && < 1.20.5 {
        /*var server = context.getLevel().getServer();
        var ledgerField = MultiAuth.class.getDeclaredField("identities");
        ledgerField.setAccessible(true);
        Object previousLedger = ledgerField.get(null);
        var ledgerFile = Files.createTempFile("easyauth-command-targets-", ".sqlite");
        ServerPlayer official = null;
        ServerPlayer union = null;
        try (IdentityStore ledger = new IdentityStore(ledgerFile)) {
            ledgerField.set(null, ledger);
            String rawName = "Cmd" + UUID.randomUUID().toString().substring(0, 8);
            var local = ledger.resolve(new AuthenticatedIdentity("command-test", SourceType.UNION,
                    rawName, UUID.randomUUID(), rawName, List.of()), "[MUA]", (identity, profile, existing) -> {});
            official = player(context, new GameProfile(UUID.randomUUID(), rawName));
            union = player(context, new GameProfile(local.uuid(), local.name()));
            server.getPlayerList().getPlayers().add(official);
            server.getPlayerList().getPlayers().add(union);
            var source = server.createCommandSourceStack();

            // Quoted names and vanilla targets already work and must retain their behavior.
            check(EntityArgument.player().parse(new StringReader("\"" + local.name() + "\""))
                    .findSinglePlayer(source) == union, "Quoted external name did not resolve");
            check(EntityArgument.player().parse(new StringReader(rawName)).findSinglePlayer(source) == official,
                    "Ordinary official name did not resolve");
            check(EntityArgument.players().parse(new StringReader("@a[name=\"" + local.name() + "\"]"))
                    .findPlayers(source).equals(List.of(union)), "Quoted selector name did not resolve");
            check(EntityArgument.player().parse(new StringReader("@s"))
                    .findSinglePlayer(source.withEntity(official)) == official, "Vanilla self selector changed");
            expectInvalid("[Unallocated]X");
            expectInvalid(local.name() + ",junk");
            expectInvalid(local.name() + "]");

            StringReader unquoted = new StringReader(local.name() + " trailing");
            try {
                check(EntityArgument.player().parse(unquoted).findSinglePlayer(source) == union,
                        "Unquoted allocated name selected the wrong player");
            } catch (CommandSyntaxException exception) {
                throw new AssertionError("Allocated [MUA] name must be usable as an unquoted command target", exception);
            }
            check(unquoted.getCursor() == local.name().length(), "Name parser consumed the next argument");
            check(EntityArgument.player().parse(new StringReader(local.name().toLowerCase(java.util.Locale.ROOT)))
                    .findSinglePlayer(source) == union, "Allocated name lost vanilla case-insensitive matching");

            official.setPos(10, 70, 10);
            union.setPos(25, 70, 40);
            int result = server.getCommands().getDispatcher().execute("tp " + rawName + " " + local.name(), source);
            check(result == 1 && official.position().equals(union.position()),
                    "Actual unquoted teleport did not move the official player to the external player");
            official.setPos(10, 70, 10);
            result = server.getCommands().getDispatcher().execute("tp " + rawName + " \"" + local.name() + "\"", source);
            check(result == 1 && official.position().equals(union.position()), "Quoted teleport regressed");
            check(union.getUUID().equals(local.uuid()) && union.getGameProfile().getName().equals(local.name()),
                    "Command compatibility changed persistent identity");
            verifySuggestions(context, official, local.name(), rawName, ledgerField, ledger);
        } finally {
            if (official != null) server.getPlayerList().getPlayers().remove(official);
            if (union != null) server.getPlayerList().getPlayers().remove(union);
            ledgerField.set(null, previousLedger);
            Files.deleteIfExists(ledgerFile);
        }
        *///?}
        context.succeed();
    }

    //? if >= 1.20.3 && < 1.20.5 {
    /*private static void verifySuggestions(GameTestHelper context, ServerPlayer official, String localName,
                                          String rawName, java.lang.reflect.Field ledgerField, IdentityStore ledger) throws Exception {
        var server = context.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack source = official.createCommandSourceStack();
        String quoted = StringArgumentType.escapeIfRequired(localName);
        for (String input : List.of("tp ", "tp [M", "tp \"[M")) {
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join();
            check(suggestions.getList().stream().anyMatch(suggestion -> suggestion.getText().equals(quoted)),
                    "External-name completion must include quotes for input: " + input);
            check(suggestions.getList().stream().noneMatch(suggestion -> suggestion.getText().equals(localName)),
                    "Completion still inserts an invalid bare external name");
        }
        check(dispatcher.getCompletionSuggestions(dispatcher.parse("tp " + rawName.substring(0, 3), source)).join()
                .getList().stream().anyMatch(suggestion -> suggestion.getText().equals(rawName)), "Official-name completion changed");
        check(dispatcher.getCompletionSuggestions(dispatcher.parse("tp @", source)).join()
                .getList().stream().anyMatch(suggestion -> suggestion.getText().equals("@s")), "Selector completion changed");

        String fixture = "suggestions" + UUID.randomUUID().toString().substring(0, 8);
        dispatcher.register(Commands.literal(fixture)
                .then(Commands.argument("custom", EntityArgument.entity()).suggests(SuggestionProviders.SUMMONABLE_ENTITIES))
                .then(Commands.argument("word", StringArgumentType.word())));
        var serverDestination = (ArgumentCommandNode<CommandSourceStack, ?>) dispatcher.getRoot()
                .getChild("teleport").getChild("destination");
        check(serverDestination.getCustomSuggestions() == null, "Fixture expected vanilla server-side suggestions");
        server.getCommands().sendCommands(official);
        CaptureConnection transport = ((CommandTestPlayer) official).transport;
        check(transport.commandTree != null, "sendCommands did not send a real command-tree packet");
        FriendlyByteBuf commandBuffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        ClientboundCommandsPacket decoded;
        try {
            transport.commandTree.write(commandBuffer);
            decoded = new ClientboundCommandsPacket(commandBuffer);
            check(!commandBuffer.isReadable(), "Command packet left unread serialized bytes");
        } finally {
            commandBuffer.release();
        }
        var clientRoot = decoded.getRoot(CommandBuildContext.simple(server.registryAccess(), server.getWorldData().enabledFeatures()));
        var destination = (ArgumentCommandNode<SharedSuggestionProvider, ?>) clientRoot.getChild("teleport").getChild("destination");
        // Registration returns a named Wrapper for the server, but the client registry stores
        // and decodes its underlying provider. Compare the decoded registry value, not Wrapper.
        check(destination.getCustomSuggestions() == SuggestionProviders.getProvider(
                        new net.minecraft.resources.ResourceLocation("minecraft:ask_server")),
                "Serialized EntityArgument must ask the server for escaped suggestions");
        check(serverDestination.getCustomSuggestions() == null, "Sending command tree mutated the live server dispatcher");
        var custom = (ArgumentCommandNode<SharedSuggestionProvider, ?>) clientRoot.getChild(fixture).getChild("custom");
        check(custom.getCustomSuggestions() == SuggestionProviders.getProvider(
                        new net.minecraft.resources.ResourceLocation("minecraft:summonable_entities")),
                "Existing registered suggestion provider was overwritten");
        check(((ArgumentCommandNode<SharedSuggestionProvider, ?>) clientRoot.getChild(fixture).getChild("word"))
                .getCustomSuggestions() == null, "Non-entity argument gained server suggestions");

        var clientDispatcher = new CommandDispatcher<SharedSuggestionProvider>(clientRoot);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        SharedSuggestionProvider client = (SharedSuggestionProvider) java.lang.reflect.Proxy.newProxyInstance(
                SharedSuggestionProvider.class.getClassLoader(), new Class<?>[]{SharedSuggestionProvider.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("customSuggestion")) return method.invoke(source, arguments);
                    String input = ((CommandContext<?>) arguments[0]).getInput();
                    int requestId = requests.incrementAndGet();
                    transport.suggestions = null;
                    official.connection.handleCustomCommandSuggestions(new ServerboundCommandSuggestionPacket(requestId, input));
                    check(transport.suggestions != null && transport.suggestions.getId() == requestId,
                            "Server did not answer the actual suggestion packet");
                    FriendlyByteBuf suggestionBuffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    try {
                        transport.suggestions.write(suggestionBuffer);
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                new ClientboundCommandSuggestionsPacket(suggestionBuffer).getSuggestions());
                    } finally {
                        suggestionBuffer.release();
                    }
                });
        var remoteSuggestions = clientDispatcher.getCompletionSuggestions(clientDispatcher.parse("tp [M", client)).join();
        check(requests.get() > 0, "Decoded command tree did not invoke ASK_SERVER");
        var selected = remoteSuggestions.getList().stream().filter(suggestion -> suggestion.getText().equals(quoted)).findFirst().orElseThrow();
        String completed = selected.apply("tp [M");
        // Removing the ledger makes our server parser wrapper fall through to vanilla. This
        // checks what an unmodified client's parser will see, not our bare-name extension.
        ledgerField.set(null, null);
        try {
            check(!clientDispatcher.parse(completed, client).getReader().canRead(), "Completed target is not valid vanilla syntax");
            check(clientDispatcher.parse("tp " + localName, client).getReader().canRead(),
                    "Test must preserve the known vanilla limitation for manually typed bare bracket names");
            check(!clientDispatcher.parse("tp " + UUID.randomUUID(), client).getReader().canRead(), "UUID argument syntax regressed");
        } finally {
            ledgerField.set(null, ledger);
        }
    }

    private static ServerPlayer player(GameTestHelper context, GameProfile profile) {
        return new CommandTestPlayer(context, profile);
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
        ClientboundCommandSuggestionsPacket suggestions;
        CaptureConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void setListener(PacketListener listener) {}
        @Override public boolean isConnected() { return true; }
        @Override public java.net.SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress("127.0.0.1", 25565);
        }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundCommandsPacket tree) commandTree = tree;
            if (packet instanceof ClientboundCommandSuggestionsPacket result) suggestions = result;
        }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { send(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) { send(packet); }
    }

    private static void expectInvalid(String target) throws Exception {
        try {
            EntityArgument.player().parse(new StringReader(target));
        } catch (CommandSyntaxException expected) {
            return;
        }
        throw new AssertionError("Unallocated or malformed token was accepted: " + target);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    *///?}
}
