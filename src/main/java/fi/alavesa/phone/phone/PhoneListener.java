package fi.alavesa.phone.phone;

import fi.alavesa.phone.camera.CameraService;
import fi.alavesa.phone.messaging.MessageService;
import fi.alavesa.phone.ui.UiController;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Using the phone item and keeping its look in step with the holder's unread messages.
 *
 * <p>The red dot belongs to the player, not the item: every phone a player carries shows that player's state. A
 * phone that leaves the inventory (dropped, put in a chest) is reset to the normal look; whoever picks one up gets
 * their own state on it. Besides the events below a slow pass over all players catches the ways an item can arrive
 * that have no simple event (creative inventory, other plugins, /give).
 */
public final class PhoneListener implements Listener {

    private final Plugin plugin;
    private final PhoneItems items;
    private final MessageService messages;
    private final UiController ui;
    private final CameraService camera;
    /** Tick of the last accepted right-click per player: one click fires for both hands and sometimes twice. */
    private final Map<UUID, Integer> lastUse = new HashMap<>();

    public PhoneListener(Plugin plugin, PhoneItems items, MessageService messages, UiController ui, CameraService camera) {
        this.plugin = plugin;
        this.items = items;
        this.messages = messages;
        this.ui = ui;
        this.camera = camera;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::sync), 40L, 40L);
    }

    /** Gives the player's phones the look that matches their state. */
    public void sync(Player player) {
        PhoneItems.State state = camera.isActive(player) ? PhoneItems.State.CAMERA
                : messages.unread(player.getUniqueId()) > 0 ? PhoneItems.State.UNREAD : PhoneItems.State.NORMAL;
        items.sync(player, state);
    }

    private void syncNextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) sync(player);
        });
    }

    private void use(Player player) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastUse.put(player.getUniqueId(), now);
        if (last != null && now - last < 4) return;
        if (camera.isActive(player)) camera.shoot(player);
        else ui.openHome(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (!items.isPhone(e.getItem())) return;
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) {
            // left-click in the viewfinder flips between the rear and the front camera
            if (camera.isActive(e.getPlayer())) {
                e.setCancelled(true);
                camera.toggleSelfie(e.getPlayer());
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // the phone never does what its base item would do, and never uses the block behind it
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setCancelled(true);
        use(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!items.isPhone(e.getPlayer().getInventory().getItem(e.getHand()))) return;
        e.setCancelled(true);
        use(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack stack = e.getItemDrop().getItemStack();
        if (!items.isPhone(stack)) return;
        if (items.apply(stack, PhoneItems.State.NORMAL)) e.getItemDrop().setItemStack(stack);
        Player player = e.getPlayer();
        // the last phone left: close what it was showing
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (camera.isActive(player) && !items.holdsPhone(player)) camera.exit(player);
            if (!items.hasPhone(player)) ui.close(player, true);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player player && items.isPhone(e.getItem().getItemStack())) syncNextTick(player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player player) syncNextTick(player);
    }

    /** Phones and photo paper are not iron ingots or paper: they cannot be crafted into anything. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack item : e.getInventory().getMatrix()) {
            if (items.isCustom(item)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        messages.join(e.getPlayer());
        // until the unread count is loaded the phone shows no dot; a leftover camera look is cleared at once
        sync(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        messages.quit(e.getPlayer());
        lastUse.remove(e.getPlayer().getUniqueId());
    }
}
