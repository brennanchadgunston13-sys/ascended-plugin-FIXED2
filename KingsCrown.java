package org.thepanther1.kingscrown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class KingsCrown extends JavaPlugin implements Listener, CommandExecutor {

    private final NamespacedKey crownKey = new NamespacedKey(this, "kings_crown");
    private final HashMap<UUID, Long> dashCooldown = new HashMap<>();
    private final HashMap<UUID, Long> lastSneakTime = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("getcrown")).setExecutor(this);

        // Passive effects + action bar (only while wearing the crown)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isWearingCrown(p)) continue;
                applyPassives(p);
                updateActionBar(p);
            }
        }, 0L, 20L);
    }

    // =====================================================================
    //  The crown item  (no crafting recipe anymore)
    // =====================================================================

    public ItemStack getKingsCrown() {
        // A stick is used as the base so Minecraft shows OUR texture on the head.
        // (A real helmet always shows the vanilla armor look instead.)
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("King's Crown", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Shift + Right-Click: Momentum Dash", NamedTextColor.AQUA),
                Component.text("Double-Tap Shift: Instant Teleport", NamedTextColor.LIGHT_PURPLE)));

        meta.addEnchant(Enchantment.PROTECTION, 4, true);
        meta.setUnbreakable(true);
        meta.setMaxStackSize(1);

        // Same defense as the old netherite helmet
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                new NamespacedKey(this, "crown_armor"), 3.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                new NamespacedKey(this, "crown_toughness"), 3.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(
                new NamespacedKey(this, "crown_knockback"), 0.1,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));

        // Makes the item wearable on the head
        EquippableComponent equip = meta.getEquippable();
        equip.setSlot(EquipmentSlot.HEAD);
        meta.setEquippable(equip);

        // Resource pack texture: assets/kingscrown/items/crown.json
        meta.setItemModel(new NamespacedKey("kingscrown", "crown"));

        // Tag so the plugin recognises it (same key as before)
        meta.getPersistentDataContainer().set(crownKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isCrown(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(crownKey, PersistentDataType.BYTE);
    }

    private boolean isWearingCrown(Player p) {
        return isCrown(p.getInventory().getHelmet());
    }

    private boolean hasCrown(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isCrown(item)) return true;
        }
        return false;
    }

    private void removeCrown(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isCrown(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    // Puts the crown straight on the head. The old helmet goes to the inventory.
    private void giveCrown(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack oldHelmet = inv.getHelmet();
        inv.setHelmet(getKingsCrown());

        if (oldHelmet != null && !oldHelmet.getType().isAir()) {
            inv.addItem(oldHelmet).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }

        getConfig().set("holder", p.getUniqueId().toString());
        saveConfig();
    }

    // =====================================================================
    //  Command:  /getcrown [player]
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Usage: /getcrown <player>", NamedTextColor.RED));
            return true;
        }

        // Only ONE crown can exist
        for (Player online : Bukkit.getOnlinePlayers()) removeCrown(online);
        giveCrown(target);
        target.sendMessage(Component.text("You have received the King's Crown!", NamedTextColor.YELLOW));
        return true;
    }

    // =====================================================================
    //  Hot potato: killing the holder moves the crown to the killer
    // =====================================================================

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (!hasCrown(victim)) return;

        // Never let the crown hit the ground
        e.getDrops().removeIf(this::isCrown);

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            removeCrown(victim);   // also covers keepInventory = true
            giveCrown(killer);
            killer.sendMessage(Component.text("You took the King's Crown!", NamedTextColor.GOLD));
        } else if (!e.getKeepInventory()) {
            // Died to a mob, fall damage, etc: the victim keeps the crown
            e.getItemsToKeep().add(getKingsCrown());
        }
    }

    // After respawning, put the crown back on the head
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline() && hasCrown(p) && !isWearingCrown(p)) {
                removeCrown(p);
                giveCrown(p);
            }
        });
    }

    // Remove duplicates if an old holder (or an old crafted crown) shows up
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String holder = getConfig().getString("holder", "");
        if (holder.isEmpty()) return;
        if (hasCrown(p) && !p.getUniqueId().toString().equals(holder)) {
            removeCrown(p);
        }
    }

    // =====================================================================
    //  Protection: no dropping, no chests, no item frames, no damage
    // =====================================================================

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isCrown(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    // Backup: a crown item may never exist in the world
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        if (isCrown(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    // Can't move it into chests, shulkers, anvils, crafting grid, etc.
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        boolean touchesCrown = isCrown(e.getCurrentItem()) || isCrown(e.getCursor());

        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
            touchesCrown |= isCrown(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        }
        if (e.getClick() == ClickType.SWAP_OFFHAND) {
            touchesCrown |= isCrown(e.getWhoClicked().getInventory().getItemInOffHand());
        }
        if (!touchesCrown) return;

        InventoryType top = e.getView().getTopInventory().getType();
        InventoryType.SlotType slot = e.getSlotType();

        boolean ownInventoryOnly = top == InventoryType.CRAFTING
                && slot != InventoryType.SlotType.CRAFTING
                && slot != InventoryType.SlotType.RESULT;

        if (!ownInventoryOnly) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!isCrown(e.getOldCursor())) return;
        int topSize = e.getView().getTopInventory().getSize();
        if (e.getRawSlots().stream().anyMatch(slot -> slot < topSize)) e.setCancelled(true);
    }

    // Can't put it on item frames / armor stands
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        PlayerInventory inv = e.getPlayer().getInventory();
        if (isCrown(inv.getItemInMainHand()) || isCrown(inv.getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    // Unbreakable by anything
    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (isCrown(e.getItem())) e.setCancelled(true);
    }

    // =====================================================================
    //  Abilities (unchanged)
    // =====================================================================

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.isSneaking()
                && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)
                && isWearingCrown(p)
                && getCooldownRemaining(p) <= 0L) {
            performMomentumDash(p);
        }
    }

    @EventHandler
    public void onDoubleSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!e.isSneaking() || !isWearingCrown(p)) return;

        long now = System.currentTimeMillis();
        long last = lastSneakTime.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < 1000L) {
            performTeleport(p);
        }
        lastSneakTime.put(p.getUniqueId(), now);
    }

    private void performMomentumDash(final Player p) {
        Vector velocity = p.getLocation().getDirection().normalize().multiply(2.2).setY(0.4);
        p.setVelocity(velocity);
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.8f);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks >= 10 || !p.isOnline()) {
                    cancel();
                    return;
                }
                p.getWorld().spawnParticle(Particle.DRAGON_BREATH, p.getLocation(), 5, 0.1, 0.1, 0.1, 0.02);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 3, 0.2, 0.2, 0.2, 0.01);
                ticks++;
            }
        }.runTaskTimer(this, 0L, 1L);

        dashCooldown.put(p.getUniqueId(), System.currentTimeMillis());
    }

    private void performTeleport(Player p) {
        RayTraceResult ray = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 100.0);
        if (ray != null && ray.getHitBlock() != null) {
            Location loc = ray.getHitBlock().getLocation().add(0.0, 1.1, 0.0);
            loc.setYaw(p.getLocation().getYaw());
            loc.setPitch(p.getLocation().getPitch());
            p.teleport(loc);
            p.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
            p.getWorld().spawnParticle(Particle.PORTAL, loc, 40, 0.5, 1.0, 0.5);
        }
    }

    private void applyPassives(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 40, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 1, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 40, 0, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 1, false, false, true));
    }

    private void updateActionBar(Player p) {
        long cd = getCooldownRemaining(p);
        p.sendActionBar(cd > 0L
                ? Component.text("Dash Cooldown: " + cd + "s", NamedTextColor.RED)
                : Component.text("Dash: READY", NamedTextColor.GREEN));
    }

    private long getCooldownRemaining(Player p) {
        long diff = (System.currentTimeMillis() - dashCooldown.getOrDefault(p.getUniqueId(), 0L)) / 1000L;
        int dashCooldownSeconds = 7;
        return Math.max(0L, (long) dashCooldownSeconds - diff);
    }
}
