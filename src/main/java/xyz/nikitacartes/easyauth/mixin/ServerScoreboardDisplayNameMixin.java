package xyz.nikitacartes.easyauth.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.nikitacartes.easyauth.integrations.SourceNameDisplay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keep the decorated TAB name current without changing any scoreboard team or membership. */
@Mixin(ServerScoreboard.class)
public abstract class ServerScoreboardDisplayNameMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = {
            "onTeamChanged(Lnet/minecraft/world/scores/PlayerTeam;)V",
            "onTeamRemoved(Lnet/minecraft/world/scores/PlayerTeam;)V"
    }, at = @At("RETURN"))
    private void easyAuth$refreshTeamDisplayNames(PlayerTeam team, CallbackInfo ci) {
        // Vanilla removes the scoreboard's player->team mappings before onTeamRemoved, while
        // retaining the removed team's member names. Their new display names no longer use it.
        easyAuth$refreshNames(team.getPlayers());
    }

    @Inject(method = "addPlayerToTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)Z",
            at = @At("RETURN"))
    private void easyAuth$refreshJoinedTeam(String name, PlayerTeam team, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) easyAuth$refreshNames(List.of(name));
    }

    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void easyAuth$refreshLeftTeam(String name, PlayerTeam team, CallbackInfo ci) {
        // The removed name is no longer in team.getPlayers(), so refresh it directly.
        easyAuth$refreshNames(List.of(name));
    }

    @Unique
    private void easyAuth$refreshNames(Collection<String> names) {
        if (server.getPlayerList() == null || names.isEmpty()) return;
        List<ServerPlayer> affected = new ArrayList<>();
        for (String name : names) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player != null && !SourceNameDisplay.prefix(player).isEmpty()) affected.add(player);
        }
        SourceNameDisplay.refresh(server, affected);
    }
}
