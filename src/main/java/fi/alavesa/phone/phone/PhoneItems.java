package fi.alavesa.phone.phone;

import fi.alavesa.phone.config.Lang;
import fi.alavesa.phone.config.PhoneConfig;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The plugin's items: the phone (three looks), photo paper, and the tag on printed photos.
 *
 * <p>A phone is any item carrying the {@code phone:phone} tag in its persistent data, whatever its material. Its look
 * is the {@code item_model} component pointing at an item model definition of the pack (assets/phone/items/):
 * {@code phone:phone}, {@code phone:phone_unread} (red dot) or {@code phone:phone_camera} (hidden in first person
 * while the viewfinder is open). The phone holds no data of its own: messages belong to the player.
 */
public final class PhoneItems {

    public enum State {
        NORMAL("phone"), UNREAD("phone_unread"), CAMERA("phone_camera");

        final NamespacedKey model;

        State(String model) {
            this.model = new NamespacedKey("phone", model);
        }
    }

    private static final NamespacedKey PHONE = new NamespacedKey("phone", "phone");
    private static final NamespacedKey STATE = new NamespacedKey("phone", "state");
    private static final NamespacedKey PAPER = new NamespacedKey("phone", "photo_paper");
    public static final NamespacedKey PHOTO = new NamespacedKey("phone", "photo");
    private static final NamespacedKey PAPER_MODEL = new NamespacedKey("phone", "photo_paper");
    public static final NamespacedKey PHOTO_MODEL = new NamespacedKey("phone", "photo");

    private PhoneConfig cfg;
    private Lang lang;

    public PhoneItems(PhoneConfig cfg, Lang lang) {
        reload(cfg, lang);
    }

    public void reload(PhoneConfig cfg, Lang lang) {
        this.cfg = cfg;
        this.lang = lang;
    }

    public boolean useItemModel() {
        return cfg.useItemModel;
    }

    private static Component plainName(Component name) {
        return name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ---- phone ------------------------------------------------------------------------------------------------------

    public ItemStack createPhone() {
        ItemStack item = new ItemStack(cfg.itemMaterial);
        item.editMeta(meta -> {
            meta.itemName(lang.get("item.phone.name"));
            meta.lore(List.of(plainName(lang.get("item.phone.lore"))));
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(PHONE, PersistentDataType.BYTE, (byte) 1);
        });
        apply(item, State.NORMAL);
        return item;
    }

    public boolean isPhone(ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(PHONE, PersistentDataType.BYTE);
    }

    /** Gives a phone item the look of {@code state}. Returns true when the item changed. */
    public boolean apply(ItemStack item, State state) {
        Byte current = item.getPersistentDataContainer().get(STATE, PersistentDataType.BYTE);
        if (current != null && current == state.ordinal()) return false;
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(STATE, PersistentDataType.BYTE, (byte) state.ordinal());
            if (cfg.useItemModel) {
                meta.setItemModel(state.model);
                meta.setEnchantmentGlintOverride(null);
            } else {
                // no pack look: an enchantment glint is the "unread" signal
                meta.setItemModel(null);
                meta.setEnchantmentGlintOverride(state == State.UNREAD ? Boolean.TRUE : null);
            }
        });
        return true;
    }

    /** Puts every phone the player carries (inventory, armour, off hand) into {@code state}. */
    public void sync(Player player, State state) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (isPhone(item) && apply(item, state)) inv.setItem(slot, item);
        }
    }

    public boolean hasPhone(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isPhone(item)) return true;
        }
        return false;
    }

    public boolean holdsPhone(Player player) {
        PlayerInventory inv = player.getInventory();
        return isPhone(inv.getItemInMainHand()) || isPhone(inv.getItemInOffHand());
    }

    // ---- photo paper ------------------------------------------------------------------------------------------------

    public ItemStack createPhotoPaper(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, amount);
        item.editMeta(meta -> {
            meta.itemName(lang.get("item.photo-paper.name"));
            meta.lore(List.of(plainName(lang.get("item.photo-paper.lore"))));
            if (cfg.useItemModel) meta.setItemModel(PAPER_MODEL);
            meta.getPersistentDataContainer().set(PAPER, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean isPhotoPaper(ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(PAPER, PersistentDataType.BYTE);
    }

    public boolean hasPhotoPaper(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isPhotoPaper(item)) return true;
        }
        return false;
    }

    /** Removes one sheet of photo paper. Returns false when the player has none. */
    public boolean takePhotoPaper(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (!isPhotoPaper(item)) continue;
            item.setAmount(item.getAmount() - 1);
            inv.setItem(slot, item.getAmount() > 0 ? item : null);
            return true;
        }
        return false;
    }

    /** True for the plugin's items that must not be used up as crafting ingredients. */
    public boolean isCustom(ItemStack item) {
        return isPhone(item) || isPhotoPaper(item);
    }
}
