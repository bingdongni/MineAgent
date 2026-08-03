package com.mineagent.fabric.client.screen;

import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.fabric.client.ClientKeyBindings;
import com.mineagent.fabric.client.MineAgentClient;
import com.mineagent.fabric.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chat screen for talking to the AI companion.
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for styled
 * chat bubbles (owner=green, AI=blue, system=yellow), panel background
 * with dot grid texture, and boundary-clamped layout. All elements are
 * guaranteed to stay within the visible screen area.
 *
 * <p>Opened with the {@code C} key binding ({@link ClientKeyBindings#OPEN_CHAT}).
 */
public class CompanionChatScreen extends Screen {

    private static final int MAX_VISIBLE_LINES = 18;
    private static final int LINE_HEIGHT = 12;
    private static final int INPUT_WIDTH = 240;
    private static final int INPUT_HEIGHT = 20;
    private static final int REFLEX_BTN_WIDTH = 90;
    private static final int ACTION_BTN_WIDTH = 100;
    private static final int MARGIN = 10;
    private static final int BUBBLE_SPACING = 3;

    private UUID companionId;
    private final List<ChatEntry> history = new ArrayList<>();
    private int scrollOffset = 0;
    private EditBox inputField;
    private Button autoEatBtn;
    private Button fightBackBtn;
    private Button pickupBtn;
    private boolean reflexAutoEat = true;
    private boolean reflexFightBack = true;
    private boolean reflexPickupItems = true;
    private String currentAction = "Idle";
    private boolean companionSpawned = false;

    public CompanionChatScreen(UUID companionId) {
        super(Component.translatable("screen.mineagent.companion_chat"));
        this.companionId = companionId;
        this.companionSpawned = companionId != null;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        // Boundary-safe: clamp bottom Y to visible area
        int bottomY = MineAgentUiComponents.clamp(
                this.height - MARGIN - INPUT_HEIGHT - 4,
                MARGIN,
                this.height - INPUT_HEIGHT - MARGIN);

        // --- Input text field ---
        int inputX = centerX - INPUT_WIDTH / 2;
        inputX = MineAgentUiComponents.clamp(inputX, MARGIN,
                this.width - INPUT_WIDTH - MARGIN);
        this.inputField = new EditBox(
                this.minecraft.font,
                inputX, bottomY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.translatable("screen.mineagent.chat_input")
        );
        this.inputField.setMaxLength(256);
        this.inputField.setHint(Component.literal("Type a message..."));
        this.inputField.setCanLoseFocus(false);
        this.inputField.setResponder(this::onInputChanged);
        this.addRenderableWidget(this.inputField);

        // --- Send button ---
        int sendBtnX = inputX + INPUT_WIDTH + 4;
        sendBtnX = MineAgentUiComponents.clamp(sendBtnX, MARGIN,
                this.width - 40 - MARGIN);
        this.addRenderableWidget(Button.builder(
                Component.literal("Send"),
                btn -> sendMessage()
        ).bounds(sendBtnX, bottomY, 40, INPUT_HEIGHT).build());

        // --- Reflex toggle buttons (left side) ---
        int reflexY = bottomY - 28;
        reflexY = MineAgentUiComponents.clamp(reflexY, MARGIN,
                this.height - INPUT_HEIGHT - MARGIN);
        int reflexX = MARGIN;

        this.addRenderableWidget(Button.builder(
                Component.literal("Auto Eat: " + onOff(reflexAutoEat)),
                btn -> toggleReflex("auto_eat")
        ).bounds(reflexX, reflexY, REFLEX_BTN_WIDTH, INPUT_HEIGHT).build());
        this.autoEatBtn = (Button) this.children().get(this.children().size() - 1);

        this.addRenderableWidget(Button.builder(
                Component.literal("Fight Back: " + onOff(reflexFightBack)),
                btn -> toggleReflex("fight_back")
        ).bounds(reflexX + REFLEX_BTN_WIDTH + 4, reflexY, REFLEX_BTN_WIDTH, INPUT_HEIGHT).build());
        this.fightBackBtn = (Button) this.children().get(this.children().size() - 1);

        this.addRenderableWidget(Button.builder(
                Component.literal("Pickup: " + onOff(reflexPickupItems)),
                btn -> toggleReflex("pickup_items")
        ).bounds(reflexX + (REFLEX_BTN_WIDTH + 4) * 2, reflexY, REFLEX_BTN_WIDTH, INPUT_HEIGHT).build());
        this.pickupBtn = (Button) this.children().get(this.children().size() - 1);

        // --- Spawn / Remove companion buttons (right side) ---
        int actionX = this.width - MARGIN - ACTION_BTN_WIDTH;
        actionX = MineAgentUiComponents.clamp(actionX, MARGIN,
                this.width - ACTION_BTN_WIDTH - MARGIN);

        this.addRenderableWidget(Button.builder(
                Component.literal("Spawn Companion"),
                btn -> spawnCompanion()
        ).bounds(actionX, reflexY, ACTION_BTN_WIDTH, INPUT_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Remove Companion"),
                btn -> removeCompanion()
        ).bounds(actionX, reflexY - INPUT_HEIGHT - 4, ACTION_BTN_WIDTH, INPUT_HEIGHT).build());

        this.setInitialFocus(this.inputField);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // ── Chat history area with styled panel background ──
        int historyTop = MARGIN + LINE_HEIGHT * 3 + 6;
        int historyBottom = this.height - MARGIN - INPUT_HEIGHT - 36;
        // Boundary-safe: clamp history area
        historyTop = MineAgentUiComponents.clamp(historyTop, MARGIN,
                this.height - INPUT_HEIGHT - 40 - MARGIN);
        historyBottom = MineAgentUiComponents.clamp(historyBottom, historyTop + 20,
                this.height - INPUT_HEIGHT - 30 - MARGIN);

        // Draw panel background for chat history area
        int historyWidth = this.width - 2 * MARGIN;
        MineAgentUiComponents.drawPanel(
                graphics,
                MARGIN, historyTop,
                historyWidth, historyBottom - historyTop,
                this.width, this.height
        );

        // --- Title bar ---
        MutableComponent title = Component.literal("Companion Chat");
        if (companionId != null) {
            title = Component.literal("Companion Chat [" + companionId.toString().substring(0, 8) + "...]");
        }
        graphics.drawString(
                this.minecraft.font, title,
                MARGIN, MARGIN, 0xFFFFFF, true
        );

        // --- Current action display ---
        int actionY = MARGIN + LINE_HEIGHT + 2;
        graphics.drawString(
                this.minecraft.font,
                Component.literal("Task: " + currentAction).withStyle(
                        net.minecraft.ChatFormatting.YELLOW),
                MARGIN, actionY, 0xFFFF55, true
        );

        // --- Companion status ---
        String statusText = companionSpawned ? "Companion: Online" : "Companion: Offline";
        int statusColor = companionSpawned ? 0x55FF55 : 0xFF5555;
        graphics.drawString(
                this.minecraft.font, statusText,
                MARGIN, actionY + LINE_HEIGHT, statusColor, true
        );

        // --- Chat history (with bubbles) ---
        renderChatHistoryWithBubbles(graphics, historyTop + 4, historyBottom - 4);

        // Render widgets (buttons, input field)
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Render chat history using styled chat bubbles instead of plain text.
     *
     * <p>Each message gets a colored bubble:
     * <ul>
     *   <li>Owner messages → green bubble (left-aligned)</li>
     *   <li>AI messages → blue bubble (left-aligned, indented)</li>
     *   <li>System messages → yellow bubble</li>
     * </ul>
     */
    private void renderChatHistoryWithBubbles(GuiGraphics graphics, int topY, int bottomY) {
        if (history.isEmpty()) {
            graphics.drawString(
                    this.minecraft.font,
                    "No messages yet. Type below to talk to your companion.",
                    MARGIN + 4, topY + 4, 0x888888, false
            );
            return;
        }

        int visibleHeight = bottomY - topY;
        int bubbleHeight = MineAgentUiComponents.getBubbleHeight();
        int maxBubbles = visibleHeight / (bubbleHeight + BUBBLE_SPACING);

        int totalEntries = history.size();
        int startIdx = Math.max(0, totalEntries - maxBubbles - scrollOffset);
        int endIdx = Math.min(totalEntries, startIdx + maxBubbles);

        // Max bubble width = screen width - 2*margin - padding
        int maxBubbleWidth = this.width - 2 * MARGIN - 8;

        int y = topY;
        for (int i = startIdx; i < endIdx; i++) {
            ChatEntry entry = history.get(i);
            MineAgentUiComponents.BubbleType type = entry.fromOwner
                    ? MineAgentUiComponents.BubbleType.OWNER
                    : MineAgentUiComponents.BubbleType.AI;

            // Draw bubble (boundary-clamped internally)
            int bubbleX = MARGIN + 4;
            MineAgentUiComponents.drawChatBubble(
                    graphics,
                    bubbleX, y,
                    entry.message, type,
                    maxBubbleWidth, this.width
            );
            y += bubbleHeight + BUBBLE_SPACING;

            // Stop if we've run out of visible space
            if (y + bubbleHeight > bottomY) break;
        }

        // Scroll indicator
        if (totalEntries > maxBubbles) {
            String scrollInfo = String.format("[%d/%d]", totalEntries - scrollOffset, totalEntries);
            graphics.drawString(
                    this.minecraft.font, scrollInfo,
                    this.width - MARGIN - 40, bottomY - LINE_HEIGHT,
                    0xAAAAAA, false
            );
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
        int maxScroll = Math.max(0, history.size() - MAX_VISIBLE_LINES);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) delta));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            if (this.inputField.isFocused() && !this.inputField.getValue().isEmpty()) {
                sendMessage();
                return true;
            }
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    private void sendMessage() {
        String text = this.inputField.getValue().trim();
        if (text.isEmpty()) return;
        history.add(new ChatEntry(true, text));
        this.inputField.setValue("");
        // Fallback: adopt the latest known companion if this screen was
        // opened before the spawn push arrived.
        if (companionId == null) {
            companionId = MineAgentClient.getCompanionId();
            companionSpawned = companionId != null;
        }
        if (companionId != null) {
            ClientUiActionPayload payload = new ClientUiActionPayload(
                    companionId, "chat", text
            );
            MineAgentClient.sendUiAction(payload);
        } else {
            history.add(new ChatEntry(false, "[No companion online — spawn one first]"));
        }
    }

    private void toggleReflex(String reflexId) {
        boolean newState;
        switch (reflexId) {
            case "auto_eat" -> { reflexAutoEat = !reflexAutoEat; newState = reflexAutoEat; }
            case "fight_back" -> { reflexFightBack = !reflexFightBack; newState = reflexFightBack; }
            case "pickup_items" -> { reflexPickupItems = !reflexPickupItems; newState = reflexPickupItems; }
            default -> { return; }
        }
        if (companionId != null) {
            ClientUiActionPayload payload = new ClientUiActionPayload(
                    companionId, "toggle_reflex",
                    reflexId + "=" + newState
            );
            MineAgentClient.sendUiAction(payload);
        }
        switch (reflexId) {
            case "auto_eat" -> autoEatBtn.setMessage(Component.literal("Auto Eat: " + onOff(reflexAutoEat)));
            case "fight_back" -> fightBackBtn.setMessage(Component.literal("Fight Back: " + onOff(reflexFightBack)));
            case "pickup_items" -> pickupBtn.setMessage(Component.literal("Pickup: " + onOff(reflexPickupItems)));
        }
    }

    private void spawnCompanion() {
        ClientUiActionPayload payload = new ClientUiActionPayload(
                companionId != null ? companionId : new UUID(0, 0),
                "spawn_companion", ""
        );
        MineAgentClient.sendUiAction(payload);
        history.add(new ChatEntry(true, "[Requested companion spawn]"));
    }

    private void removeCompanion() {
        if (companionId == null) return;
        ClientUiActionPayload payload = new ClientUiActionPayload(
                companionId, "remove_companion", ""
        );
        MineAgentClient.sendUiAction(payload);
        history.add(new ChatEntry(true, "[Requested companion removal]"));
    }

    private void onInputChanged(String newText) {}

    public void receiveCompanionMessage(String message) {
        history.add(new ChatEntry(false, message));
        scrollOffset = 0;
    }

    public void updateCurrentAction(String action) {
        this.currentAction = action;
    }

    public void setCompanionSpawned(boolean spawned) {
        this.companionSpawned = spawned;
    }

    /** Update which companion this chat screen talks to (follows server
     *  spawn/despawn pushes). */
    public void setCompanionId(UUID id) {
        if (!java.util.Objects.equals(this.companionId, id)) {
            // The screen stores one conversation. When the primary companion
            // changes after despawn, retaining entries/task text makes the new
            // companion appear to have authored the old companion's messages.
            history.clear();
            scrollOffset = 0;
            currentAction = "Idle";
        }
        this.companionId = id;
        this.companionSpawned = id != null;
    }

    /** Companion whose conversation this screen currently displays. */
    public UUID getCompanionId() {
        return companionId;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private record ChatEntry(boolean fromOwner, String message) {}
}
