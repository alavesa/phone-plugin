package fi.alavesa.phone.phone;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fi.alavesa.phone.PhonePlugin;
import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.storage.Database;
import fi.alavesa.phone.util.Tasks;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /phone and its subcommands (Brigadier). */
public final class PhoneCommand {

    private final PhonePlugin plugin;

    public PhoneCommand(PhonePlugin plugin) {
        this.plugin = plugin;
    }

    private static final SuggestionProvider<CommandSourceStack> ONLINE = (ctx, builder) -> {
        String typed = builder.getRemainingLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(p.getName());
        }
        return builder.buildFuture();
    };

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("phone")
                .requires(s -> s.getSender().hasPermission("phone.use"))
                .executes(ctx -> {
                    if (player(ctx) instanceof Player p) plugin.ui().openHome(p);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("give")
                        .requires(s -> s.getSender().hasPermission("phone.give"))
                        .executes(ctx -> give(ctx, player(ctx), "phone", 1))
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .executes(ctx -> give(ctx, target(ctx), "phone", 1))
                                .then(Commands.literal("phone").executes(ctx -> give(ctx, target(ctx), "phone", 1)))
                                .then(Commands.literal("paper")
                                        .executes(ctx -> give(ctx, target(ctx), "paper", 1))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> give(ctx, target(ctx), "paper", IntegerArgumentType.getInteger(ctx, "amount")))))))
                .then(Commands.literal("open")
                        .requires(s -> s.getSender().hasPermission("phone.admin"))
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .executes(ctx -> open(target(ctx), "home"))
                                .then(Commands.literal("home").executes(ctx -> open(target(ctx), "home")))
                                .then(Commands.literal("messages").executes(ctx -> open(target(ctx), "messages")))
                                .then(Commands.literal("camera").executes(ctx -> open(target(ctx), "camera")))
                                .then(Commands.literal("chat")
                                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ONLINE)
                                                .executes(ctx -> {
                                                    Player target = target(ctx);
                                                    return withKnown(ctx, (sender, known) -> {
                                                        if (target.isOnline()) plugin.ui().openConversation(target, known.id(), 0);
                                                    });
                                                })))))
                .then(Commands.literal("shutter")
                        .requires(s -> s.getSender().hasPermission("phone.admin"))
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .executes(ctx -> shutter(target(ctx), false))
                                .then(Commands.literal("print").executes(ctx -> shutter(target(ctx), true)))))
                .then(Commands.literal("reload")
                        .requires(s -> s.getSender().hasPermission("phone.admin"))
                        .executes(ctx -> {
                            plugin.reload();
                            ctx.getSource().getSender().sendMessage(plugin.lang().get("command.reloaded"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("mute")
                        .requires(s -> s.getSender().hasPermission("phone.admin"))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ONLINE)
                                .executes(ctx -> mute(ctx, 0))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 5_256_000))
                                        .executes(ctx -> mute(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))))
                .then(Commands.literal("unmute")
                        .requires(s -> s.getSender().hasPermission("phone.admin"))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ONLINE)
                                .executes(ctx -> withKnown(ctx, (sender, known) -> {
                                    plugin.messages().setMuted(known.id(), null);
                                    sender.sendMessage(plugin.lang().get("command.unmuted", Lang.ph("player", known.name())));
                                }))))
                .then(Commands.literal("block")
                        .requires(s -> s.getSender().hasPermission("phone.message"))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ONLINE)
                                .executes(ctx -> block(ctx, true))))
                .then(Commands.literal("unblock")
                        .requires(s -> s.getSender().hasPermission("phone.message"))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(ONLINE)
                                .executes(ctx -> block(ctx, false))))
                .then(Commands.literal("fov")
                        .requires(s -> s.getSender().hasPermission("phone.camera"))
                        .then(Commands.literal("reset").executes(ctx -> fov(ctx, null)))
                        .then(Commands.argument("degrees", DoubleArgumentType.doubleArg(30, 110))
                                .executes(ctx -> fov(ctx, DoubleArgumentType.getDouble(ctx, "degrees")))))
                .then(Commands.literal("pack")
                        .executes(ctx -> {
                            if (player(ctx) instanceof Player p && !plugin.pack().send(p)) p.sendMessage(plugin.lang().get("command.no-pack"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    /** The player a command runs as, or null (after telling the console that it needs one). */
    private Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player p) return p;
        ctx.getSource().getSender().sendMessage(plugin.lang().get("command.players-only"));
        return null;
    }

    private static Player target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    }

    private int give(CommandContext<CommandSourceStack> ctx, Player target, String what, int amount) {
        if (target == null) return 0;
        ItemStack item = what.equals("paper") ? plugin.items().createPhotoPaper(amount) : plugin.items().createPhone();
        Map<Integer, ItemStack> left = target.getInventory().addItem(item);
        // a full inventory never eats the item
        for (ItemStack rest : left.values()) target.getWorld().dropItemNaturally(target.getLocation(), rest);
        plugin.phoneListener().sync(target);
        ctx.getSource().getSender().sendMessage(plugin.lang().get(what.equals("paper") ? "command.gave-paper" : "command.gave-phone",
                Lang.ph("player", target.getName()), Lang.ph("amount", amount)));
        return Command.SINGLE_SUCCESS;
    }

    private int open(Player target, String screen) {
        switch (screen) {
            case "messages" -> plugin.ui().openContacts(target, 0);
            case "camera" -> {
                plugin.ui().close(target, true);
                if (plugin.items().holdsPhone(target)) plugin.camera().enter(target);
                else target.sendMessage(plugin.lang().get("camera.hold-phone"));
            }
            default -> plugin.ui().openHome(target);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Presses the shutter for a player whose viewfinder is open (command blocks, NPC scripts, testing). */
    private int shutter(Player target, boolean print) {
        if (!plugin.camera().isActive(target)) return 0;
        plugin.camera().shoot(target, print);
        return Command.SINGLE_SUCCESS;
    }

    /** Resolves the "name" argument to a player who has joined before (online or not), then runs {@code action} on the main thread. */
    private int withKnown(CommandContext<CommandSourceStack> ctx, BiConsumer<CommandSender, Database.KnownPlayer> action) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            action.accept(sender, new Database.KnownPlayer(online.getUniqueId(), online.getName()));
            return Command.SINGLE_SUCCESS;
        }
        Tasks.onMain(plugin, plugin.database().findPlayer(name), known -> {
            if (known == null) sender.sendMessage(plugin.lang().get("command.unknown-player", Lang.ph("player", name)));
            else action.accept(sender, known);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int mute(CommandContext<CommandSourceStack> ctx, int minutes) {
        return withKnown(ctx, (sender, known) -> {
            plugin.messages().setMuted(known.id(), minutes <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L);
            sender.sendMessage(minutes <= 0 ? plugin.lang().get("command.muted", Lang.ph("player", known.name()))
                    : plugin.lang().get("command.muted-for", Lang.ph("player", known.name()), Lang.ph("minutes", minutes)));
        });
    }

    private int block(CommandContext<CommandSourceStack> ctx, boolean blocked) {
        Player self = player(ctx);
        if (self == null) return 0;
        return withKnown(ctx, (sender, known) -> {
            if (known.id().equals(self.getUniqueId()) || !self.isOnline()) return;
            plugin.messages().setBlocked(self, known.id(), blocked);
            self.sendMessage(plugin.lang().get(blocked ? "command.blocked" : "command.unblocked", Lang.ph("player", known.name())));
        });
    }

    private int fov(CommandContext<CommandSourceStack> ctx, Double degrees) {
        Player self = player(ctx);
        if (self == null) return 0;
        plugin.camera().setFov(self, degrees);
        self.sendMessage(plugin.lang().get("command.fov", Lang.ph("fov", Math.round(plugin.camera().fov(self)))));
        return Command.SINGLE_SUCCESS;
    }
}
