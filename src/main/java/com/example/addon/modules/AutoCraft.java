package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;
import java.util.Set;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoCraft — Tự động hóa chu kỳ:
 * Mine → Full Inventory → /craft (chừa 1 stack raw) → Đặt khối craft vào Shulker
 * → Phủ 1 stack raw ra full inventory → Lặp lại
 * 
 * Hỗ trợ chọn bất kỳ khối nào trong Minecraft để mine và craft!
 */
public class AutoCraft extends Module {

    // =========================================================
    //  Setting Groups
    // =========================================================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCraft   = settings.createGroup("Craft Settings");
    private final SettingGroup sgShulker = settings.createGroup("Shulker Settings");
    private final SettingGroup sgMine    = settings.createGroup("Mine Settings");

    // ── Mine Settings ────────────────────────────────────────
    private final Setting<Item> mineBlock = sgMine.add(new ItemSetting.Builder()
        .name("mine-block")
        .description("Chọn khối/item để mine từ danh sách Minecraft")
        .defaultValue(Items.DIAMOND_ORE)
        .build());

    private final Setting<Integer> fullInventorySlots = sgGeneral.add(new IntSetting.Builder()
        .name("full-inventory-slots")
        .description("Số slot cần full trước khi craft (36 = full inventory)")
        .defaultValue(35)
        .min(1).max(36)
        .build());

    // ── Craft Settings ───────────────────────────────────────
    private final Setting<Item> craftBlock = sgCraft.add(new ItemSetting.Builder()
        .name("craft-block")
        .description("Chọn khối/item sau khi craft từ danh sách Minecraft")
        .defaultValue(Items.DIAMOND_BLOCK)
        .build());

    private final Setting<String> craftCommand = sgCraft.add(new StringSetting.Builder()
        .name("craft-command")
        .description("Lệnh để craft khối (ví dụ: /craft, /make...)")
        .defaultValue("/craft")
        .build());

    private final Setting<Integer> craftDelay = sgCraft.add(new IntSetting.Builder()
        .name("craft-delay")
        .description("Delay (tick) sau mỗi lệnh /craft")
        .defaultValue(10)
        .min(0).max(200)
        .build());

    // ── Shulker Settings ─────────────────────────────────────
    private final Setting<Boolean> useShulker = sgShulker.add(new BoolSetting.Builder()
        .name("use-shulker")
        .description("Sử dụng Shulker để lưu trữ khối craft")
        .defaultValue(true)
        .build());

    private final Setting<Integer> shulkerSlot1 = sgShulker.add(new IntSetting.Builder()
        .name("shulker-slot-1")
        .description("Slot thứ nhất của Shulker (hotbar 0-8)")
        .defaultValue(7)
        .min(0).max(8)
        .build());

    private final Setting<Integer> shulkerSlot2 = sgShulker.add(new IntSetting.Builder()
        .name("shulker-slot-2")
        .description("Slot thứ hai của Shulker (hotbar 0-8)")
        .defaultValue(8)
        .min(0).max(8)
        .build());

    private final Setting<Integer> shulkerDelay = sgShulker.add(new IntSetting.Builder()
        .name("shulker-delay")
        .description("Delay (tick) giữa các thao tác Shulker")
        .defaultValue(15)
        .min(0).max(200)
        .build());

    // =========================================================
    //  State Management
    // =========================================================
    private enum State {
        MINING,              // Đang mine
        WAIT_FULL,           // Chờ inventory full
        CRAFTING,            // Đang craft
        WAIT_CRAFT,          // Chờ craft xong
        STORE_TO_SHULKER,    // Đặt khối craft vào Shulker
        FILL_WITH_RAW,       // Phủ 1 stack raw ra full inventory
        RESTART              // Lặp lại
    }

    private State currentState = State.MINING;
    private int tickCounter = 0;
    private ItemStack rawStack = null;        // Stack raw (chưa craft) để phủ lại
    private int craftedAmount = 0;            // Số lượng khối vừa craft
    private boolean shulkerOpen = false;      // Track trạng thái Shulker

    // =========================================================
    //  Init & Disable
    // =========================================================
    public AutoCraft() {
        super(AddonTemplate.CATEGORY, "auto-craft", 
            "Tự động: Mine → Full → Craft (chừa 1 stack raw) → Lưu vào Shulker → Phủ raw → Lặp lại");
    }

    @Override
    public void onActivate() {
        currentState = State.MINING;
        tickCounter = 0;
        rawStack = null;
        craftedAmount = 0;
        shulkerOpen = false;
        ChatUtils.info("AutoCraft started!");
        ChatUtils.info("Mine Block: " + mineBlock.get().getName().getString());
        ChatUtils.info("Craft Block: " + craftBlock.get().getName().getString());
    }

    @Override
    public void onDeactivate() {
        ChatUtils.info("AutoCraft stopped!");
    }

    // =========================================================
    //  Main Tick Handler
    // =========================================================
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        tickCounter++;

        switch (currentState) {
            case MINING:
                handleMining();
                break;
            case WAIT_FULL:
                handleWaitFull();
                break;
            case CRAFTING:
                handleCrafting();
                break;
            case WAIT_CRAFT:
                handleWaitCraft();
                break;
            case STORE_TO_SHULKER:
                handleStoreToShulker();
                break;
            case FILL_WITH_RAW:
                handleFillWithRaw();
                break;
            case RESTART:
                handleRestart();
                break;
        }
    }

    // =========================================================
    //  State Handlers
    // =========================================================

    private void handleMining() {
        // Mine cho đến khi inventory gần full
        int filledSlots = countFilledSlots();

        if (filledSlots >= fullInventorySlots.get()) {
            ChatUtils.info("Inventory gần full (" + filledSlots + "/36). Chuyển sang craft.");
            currentState = State.CRAFTING;
            tickCounter = 0;
            return;
        }

        // Mine block
        if (tickCounter % 20 == 0) {
            ChatUtils.info("Mining " + mineBlock.get().getName().getString() + "... (" + filledSlots + "/36)");
        }
    }

    private void handleWaitFull() {
        // Chờ inventory full
        int filledSlots = countFilledSlots();

        if (filledSlots >= 36) {
            currentState = State.CRAFTING;
            tickCounter = 0;
        }
    }

    private void handleCrafting() {
        // Lưu 1 stack raw trước khi craft
        saveOneRawStack();
        
        // Gửi lệnh craft
        ChatUtils.info("Crafting " + craftBlock.get().getName().getString() + "...");
        mc.player.sendChatMessage(craftCommand.get());

        currentState = State.WAIT_CRAFT;
        tickCounter = 0;
    }

    private void handleWaitCraft() {
        // Chờ craft xong + delay
        if (tickCounter >= craftDelay.get()) {
            ChatUtils.info("Craft hoàn thành. Chuyển sang lưu vào Shulker...");
            currentState = State.STORE_TO_SHULKER;
            tickCounter = 0;
        }
    }

    private void handleStoreToShulker() {
        // Đặt khối craft vào Shulker
        if (!useShulker.get()) {
            currentState = State.FILL_WITH_RAW;
            tickCounter = 0;
            return;
        }

        if (tickCounter >= shulkerDelay.get()) {
            int shulkerSlot = findShulkerSlot();
            
            if (shulkerSlot >= 0) {
                // Mở Shulker
                openShulkerBox(shulkerSlot);
                ChatUtils.info("Mở Shulker ở slot " + shulkerSlot);
                
                shulkerOpen = true;
                currentState = State.FILL_WITH_RAW;
                tickCounter = 0;
            } else {
                ChatUtils.error("Không tìm thấy Shulker!");
                currentState = State.FILL_WITH_RAW;
                tickCounter = 0;
            }
        }
    }

    private void handleFillWithRaw() {
        // Phủ 1 stack raw ra full inventory
        if (rawStack != null && rawStack.getCount() > 0) {
            int emptySlots = 36 - countFilledSlots();

            if (emptySlots >= rawStack.getCount()) {
                // Có đủ chỗ, phủ raw stack ra
                int targetSlot = findEmptySlot();
                if (targetSlot >= 0) {
                    // Copy raw stack vào slot trống
                    ItemStack toPlace = rawStack.copyWithCount(Math.min(rawStack.getCount(), 64));
                    mc.player.getInventory().setStack(targetSlot, toPlace);
                    
                    int remaining = rawStack.getCount() - toPlace.getCount();
                    if (remaining > 0) {
                        rawStack.setCount(remaining);
                    } else {
                        rawStack = null;
                    }
                    
                    ChatUtils.info("Phủ raw stack ra. Còn lại: " + (remaining > 0 ? remaining : 0));
                    tickCounter++;
                }
            } else {
                // Inventory full rồi
                ChatUtils.info("Inventory đầy. Chuyển sang lặp lại...");
                closeShulkerIfOpen();
                currentState = State.RESTART;
                tickCounter = 0;
            }
        } else {
            closeShulkerIfOpen();
            currentState = State.RESTART;
            tickCounter = 0;
        }
    }

    private void handleRestart() {
        // Reset và lặp lại
        ChatUtils.info("Chu kỳ hoàn thành. Lặp lại...");
        currentState = State.MINING;
        rawStack = null;
        craftedAmount = 0;
        shulkerOpen = false;
        tickCounter = 0;
    }

    // =========================================================
    //  Helper Methods
    // =========================================================

    private int countFilledSlots() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int findEmptySlot() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private void saveOneRawStack() {
        // Tìm stack raw (mineBlock) đầu tiên và lưu lại
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == mineBlock.get()) {
                // Lưu 1 stack (tối đa 64)
                rawStack = stack.copyWithCount(Math.min(stack.getCount(), 64));
                ChatUtils.info("Lưu 1 stack raw: " + rawStack.getCount() + "x " + rawStack.getName().getString());
                return;
            }
        }
        ChatUtils.warning("Không tìm thấy item raw: " + mineBlock.get().getName().getString());
    }

    private int findShulkerSlot() {
        // Tìm Shulker ở slot được chỉ định
        ItemStack slot1 = mc.player.getInventory().getStack(shulkerSlot1.get());
        ItemStack slot2 = mc.player.getInventory().getStack(shulkerSlot2.get());

        if (isShulkerBox(slot1)) {
            return shulkerSlot1.get();
        }
        if (isShulkerBox(slot2)) {
            return shulkerSlot2.get();
        }
        return -1;
    }

    private boolean isShulkerBox(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item instanceof BlockItem && 
               ((BlockItem) item).getBlock() instanceof ShulkerBoxBlock;
    }

    private void openShulkerBox(int slot) {
        // Click vào Shulker để mở
        if (mc.player != null) {
            mc.player.getInventory().selectedSlot = slot;
            // Gửi use action
            mc.interactionManager.clickCreativeStack(slot, 0);
        }
    }

    private void closeShulkerIfOpen() {
        // Đóng Shulker nếu đang mở
        if (shulkerOpen && mc.currentScreen != null) {
            mc.currentScreen.close();
            shulkerOpen = false;
        }
    }

    private void storeItemsInShulker() {
        // Lưu khối craft vào Shulker (nếu Shulker đang mở)
        if (!(mc.currentScreen instanceof GenericContainerScreen)) return;

        GenericContainerScreen screen = (GenericContainerScreen) mc.currentScreen;
        
        // Tìm và move khối craft vào Shulker
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == craftBlock.get()) {
                // Move vào Shulker slot đầu tiên trống
                InvUtils.move().from(i).to(findEmptySlotInShulker(screen));
                break;
            }
        }
    }

    private int findEmptySlotInShulker(GenericContainerScreen screen) {
        // Tìm slot trống trong Shulker
        // Shulker có 27 slots (0-26)
        for (int i = 0; i < 27; i++) {
            if (screen.getScreenHandler().getSlot(i).getStack().isEmpty()) {
                return i;
            }
        }
        return 0; // Fallback
    }
}
