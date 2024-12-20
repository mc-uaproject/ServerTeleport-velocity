package net.teamfruit.serverteleport;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServerTeleportCommand implements SimpleCommand {
    private final ProxyServer server;

    private final String langPrefix;
    private final String langUsage;
    private final String langNoServer;
    private final String langNoPermission;
    private final String langPlayerNum;
    private final String langPlayerName;
    private final String langSuccess;
    private final String langNotification;

    public ServerTeleportCommand(ProxyServer server, Toml toml) {
        this.server = server;

        // Localization
        Toml lang = toml.getTable("lang");
        this.langPrefix = lang.getString("prefix");
        this.langUsage = lang.getString("usage");
        this.langNoServer = lang.getString("noserver");
        this.langNoPermission = lang.getString("nopermission");
        this.langPlayerNum = lang.getString("player-num");
        this.langPlayerName = lang.getString("player-name");
        this.langSuccess = lang.getString("success");
        this.langNotification = lang.getString("notification");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        // Permission Validation
        if (!source.hasPermission("servertp")) {
            source.sendMessage(Component.text()
                    .content(langPrefix)
                    .append(Component.text(langNoPermission))
                    .build()
            );
            return;
        }

        // Argument Validation
        String srcArg;
        String dstArg;
        if (args.length >= 2) {
            srcArg = args[0];
            dstArg = args[1];
        } else if (args.length == 1 && source instanceof Player player) {
            srcArg = player.getUsername();
            dstArg = args[0];
        } else {
            source.sendMessage(Component.text()
                    .content(langPrefix)
                    .append(Component.text(langUsage))
                    .build()
            );
            return;
        }

        // Destination Validation
        Optional<RegisteredServer> dstOptional;
        Optional<Player> dstPlayer;
        if (dstArg.startsWith("#")) {
            dstPlayer = Optional.empty();
            dstOptional = this.server.getServer(dstArg.substring(1));
        } else {
            dstPlayer = this.server.getPlayer(dstArg);
            dstOptional = dstPlayer.flatMap(Player::getCurrentServer).map(ServerConnection::getServer);
        }
        if (!dstOptional.isPresent()) {
            source.sendMessage(Component.text()
                    .append(Component.text()
                            .content(langPrefix))
                    .append(Component.text()
                            .append(Component.text(langNoServer)))
                    .build()
            );
            return;
        }
        RegisteredServer dst = dstOptional.get();

        // Source Validation
        List<Player> src = (
                srcArg.startsWith("#")
                        ? this.server.getServer(srcArg.substring(1)).map(RegisteredServer::getPlayersConnected).orElseGet(Collections::emptyList)
                        : "@a".equals(srcArg)
                        ? this.server.getAllPlayers()
                        : this.server.getPlayer(srcArg).map(Arrays::asList).orElseGet(Collections::emptyList)
        )
                .stream()
                .collect(Collectors.toList());

        // Send Message
        source.sendMessage(Component.text()
                .append(Component.text()
                        .content(langPrefix))
                .append(Component.text()
                        .append(
                                Component.text(String.format(langSuccess,
                                        src.size() == 1
                                                ? String.format(langPlayerName, src.get(0).getUsername())
                                                : String.format(langPlayerNum, src.size()),
                                        dstArg
                                ))
                        ))
                .build()
        );

        // Run Redirect
        src.forEach(p -> {
            if (!dstOptional.equals(p.getCurrentServer().map(ServerConnection::getServer))) {
                p.sendMessage(Component.text(langPrefix).append(Component.text(String.format(langNotification, dst.getServerInfo().getName()))));
                p.createConnectionRequest(dst).fireAndForget();
            }
        });
        dstPlayer.ifPresent(player -> src.forEach(p -> {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(p.getUsername());
            out.writeUTF(player.getUsername());
            dst.sendPluginMessage(MinecraftChannelIdentifier.create("servertp", "tp"), out.toByteArray());
        }));
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission("servertp");
    }

    private List<String> candidate(String arg, List<String> candidates) {
        if (arg.isEmpty())
            return candidates;
        if (candidates.contains(arg))
            return Arrays.asList(arg);
        return candidates.stream().filter(e -> e.startsWith(arg)).collect(Collectors.toList());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        // Permission Validation
        if (!source.hasPermission("servertp"))
            return Collections.emptyList();

        // Source Suggestion
        if (args.length == 1)
            return candidate(args[0],
                    Stream.of(
                            Stream.of("@a"),
                            this.server.getAllServers().stream().map(RegisteredServer::getServerInfo)
                                    .map(ServerInfo::getName).map(e -> "#" + e),
                            this.server.getAllPlayers().stream().map(Player::getUsername)
                    )
                            .flatMap(Function.identity())
                            .collect(Collectors.toList())
            );

        // Destination Suggestion
        if (args.length == 2)
            return candidate(args[1],
                    Stream.of(
                            this.server.getAllServers().stream().map(RegisteredServer::getServerInfo)
                                    .map(ServerInfo::getName).map(e -> "#" + e),
                            this.server.getAllPlayers().stream().map(Player::getUsername)
                    )
                            .flatMap(Function.identity())
                            .collect(Collectors.toList())
            );

        return Collections.emptyList();
    }
}
