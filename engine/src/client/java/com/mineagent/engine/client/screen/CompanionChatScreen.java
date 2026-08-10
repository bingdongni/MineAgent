package com.mineagent.engine.client.screen;

import com.mineagent.api.entity.CompanionGameMode;
import com.mineagent.api.network.payload.ClientUiActionPayload;
import com.mineagent.engine.client.MineAgentClientController;
import com.mineagent.engine.client.MineAgentKeyMappings;
import com.mineagent.engine.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chat and reflex control surface opened with {@link MineAgentKeyMappings#OPEN_CHAT}.
 */
public class CompanionChatScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int INPUT_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int BUBBLE_GAP = 4;

    private UUID companionId;
    private final List<ChatEntry> history = new ArrayList<>();
    private int scrollOffset;
    private EditBox inputField;
    private Button autoEatButton;
    private Button fightBackButton;
    private Button pickupButton;
    private Button removeButton;
    private CycleButton<UUID> companionSelector;
    private CycleButton<CompanionGameMode> gameModeButton;
    private boolean reflexAutoEat = true;
    private boolean reflexFightBack = true;
    private boolean reflexPickupItems = true;
    private String currentAction = "Idle";
    private boolean companionSpawned;

    private MineAgentUiComponents.Rect panel;
    private int contentX;
    private int contentWidth;
    private int statusY;
    private int historyTop;
    private int historyBottom;

    public CompanionChatScreen(UUID companionId) {
        super(Component.translatable("screen.mineagent.companion_chat"));
        this.companionId = companionId;
        this.companionSpawned = companionId != null;
    }

    @Override
    protected void init() {
        super.init();
        panel = MineAgentUiComponents.centeredPanel(
                this.width, this.height, 620, 420);
        contentX = panel.x() + MineAgentUiComponents.PANEL_PADDING;
        contentWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        statusY = panel.y() + MineAgentUiComponents.TITLE_BAND_HEIGHT + 8;

        int inputY = panel.y() + panel.height()
                - MineAgentUiComponents.PANEL_PADDING - INPUT_HEIGHT;
        int actionY = inputY - BUTTON_HEIGHT - ROW_GAP;
        int reflexY = actionY - BUTTON_HEIGHT - ROW_GAP;
        int selectorY = statusY;
        historyTop = selectorY + BUTTON_HEIGHT + 8;
        historyBottom = reflexY - 8;

        List<UUID> ids = MineAgentClientController.getCompanionIds();
        if (ids.isEmpty() && companionId != null) ids = List.of(companionId);
        if (ids.isEmpty()) ids = List.of(new UUID(0L, 0L));
        companionSelector = CycleButton.<UUID>builder(this::companionLabel)
                .withValues(ids)
                .withInitialValue(ids.contains(companionId) ? companionId : ids.get(0))
                .create(contentX, selectorY, Math.max(80, contentWidth / 2 - ROW_GAP),
                        BUTTON_HEIGHT,
                        Component.translatable("screen.mineagent.chat.companion"),
                        (button, id) -> selectCompanion(id));
        this.addRenderableWidget(companionSelector);

        gameModeButton = CycleButton.<CompanionGameMode>builder(this::gameModeLabel)
                .withValues(List.of(CompanionGameMode.values()))
                .withInitialValue(MineAgentClientController.getCompanionGameMode(companionId))
                .create(contentX, actionY, contentWidth, BUTTON_HEIGHT,
                        Component.translatable("screen.mineagent.field.game_mode"),
                        (button, mode) -> setGameMode(mode));

        int sendWidth = Math.min(72, Math.max(52, contentWidth / 5));
        int inputWidth = Math.max(60, contentWidth - sendWidth - ROW_GAP);
        inputField = new EditBox(this.minecraft.font, contentX, inputY,
                inputWidth, INPUT_HEIGHT,
                Component.translatable("screen.mineagent.chat_input"));
        inputField.setMaxLength(512);
        inputField.setHint(Component.translatable("screen.mineagent.chat_hint"));
        inputField.setCanLoseFocus(false);
        this.addRenderableWidget(inputField);
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.chat.send"),
                ignored -> sendMessage())
                .bounds(contentX + inputWidth + ROW_GAP, inputY,
                        contentWidth - inputWidth - ROW_GAP, BUTTON_HEIGHT).build());

        int reflexWidth = (contentWidth - ROW_GAP * 2) / 3;
        autoEatButton = this.addRenderableWidget(Button.builder(Component.empty(),
                ignored -> toggleReflex("auto_eat"))
                .bounds(contentX, reflexY, reflexWidth, BUTTON_HEIGHT).build());
        fightBackButton = this.addRenderableWidget(Button.builder(Component.empty(),
                ignored -> toggleReflex("fight_back"))
                .bounds(contentX + reflexWidth + ROW_GAP, reflexY,
                        reflexWidth, BUTTON_HEIGHT).build());
        pickupButton = this.addRenderableWidget(Button.builder(Component.empty(),
                ignored -> toggleReflex("pickup_items"))
                .bounds(contentX + (reflexWidth + ROW_GAP) * 2, reflexY,
                        contentWidth - (reflexWidth + ROW_GAP) * 2,
                        BUTTON_HEIGHT).build());
        this.addRenderableWidget(gameModeButton);

        int commandWidth = Math.max(52, (contentWidth - ROW_GAP * 2) / 4);
        int modeWidth = contentWidth - commandWidth * 2 - ROW_GAP * 2;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.menu.create"),
                ignored -> Minecraft.getInstance().setScreen(new SpawnCompanionScreen()))
                .bounds(contentX, actionY, commandWidth, BUTTON_HEIGHT).build());
        gameModeButton.setX(contentX + commandWidth + ROW_GAP);
        gameModeButton.setWidth(modeWidth);
        removeButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.mineagent.menu.remove"),
                ignored -> removeCompanion())
                .bounds(contentX + commandWidth + ROW_GAP + modeWidth + ROW_GAP,
                        actionY, commandWidth,
                        BUTTON_HEIGHT).build());

        refreshControlState();
        this.setInitialFocus(inputField);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MineAgentUiComponents.drawPanel(graphics, panel.x(), panel.y(),
                panel.width(), panel.height(), this.title, true,
                this.width, this.height);

        int taskX = contentX + contentWidth / 2;
        int taskWidth = Math.max(40, contentX + contentWidth - taskX);
        String taskText = Component.translatable("screen.mineagent.chat.task",
                currentAction).getString();
        taskText = this.minecraft.font.plainSubstrByWidth(taskText, taskWidth);
        graphics.drawString(this.minecraft.font, taskText, taskX, statusY,
                MineAgentUiComponents.COLOR_SUBTITLE, false);

        // The transcript is a functional reading area within the one dialog,
        // so it uses a quiet fill and divider rather than another framed card.
        graphics.fill(contentX, historyTop, contentX + contentWidth,
                historyBottom, 0xB0121719);
        graphics.fill(contentX, historyTop, contentX + contentWidth,
                historyTop + 1, MineAgentUiComponents.COLOR_DIVIDER);
        graphics.fill(contentX, historyBottom - 1, contentX + contentWidth,
                historyBottom, MineAgentUiComponents.COLOR_DIVIDER);
        renderChatHistory(graphics);

        refreshControlState();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderChatHistory(GuiGraphics graphics) {
        int availableHeight = Math.max(1, historyBottom - historyTop - 8);
        int maxBubbleWidth = Math.max(80, contentWidth * 3 / 4);
        int endIndex = Math.max(0, history.size() - scrollOffset);
        int startIndex = endIndex;
        int usedHeight = 0;

        while (startIndex > 0) {
            ChatEntry candidate = history.get(startIndex - 1);
            int height = MineAgentUiComponents.measureChatBubble(
                    candidate.message(), candidate.type(), maxBubbleWidth).height();
            int needed = height + (usedHeight == 0 ? 0 : BUBBLE_GAP);
            if (usedHeight > 0 && usedHeight + needed > availableHeight) break;
            usedHeight += needed;
            startIndex--;
        }

        if (history.isEmpty()) {
            graphics.drawString(this.minecraft.font,
                    Component.translatable("screen.mineagent.chat.empty"),
                    contentX + 6, historyTop + 6,
                    MineAgentUiComponents.COLOR_MUTED, false);
            return;
        }

        int y = historyTop + 4;
        for (int i = startIndex; i < endIndex; i++) {
            ChatEntry entry = history.get(i);
            MineAgentUiComponents.BubbleMetrics metrics =
                    MineAgentUiComponents.measureChatBubble(
                            entry.message(), entry.type(), maxBubbleWidth);
            int x = entry.type() == MineAgentUiComponents.BubbleType.OWNER
                    ? contentX + contentWidth - metrics.width() - 4
                    : contentX + 4;
            MineAgentUiComponents.drawChatBubble(graphics, x, y,
                    entry.message(), entry.type(), maxBubbleWidth, this.width);
            y += metrics.height() + BUBBLE_GAP;
        }

        if (startIndex > 0 || endIndex < history.size()) {
            String indicator = (endIndex) + "/" + history.size();
            graphics.drawString(this.minecraft.font, indicator,
                    contentX + contentWidth - this.minecraft.font.width(indicator) - 4,
                    historyTop + 4, MineAgentUiComponents.COLOR_MUTED, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        if (scrollY != 0.0 && mouseX >= contentX
                && mouseX <= contentX + contentWidth
                && mouseY >= historyTop && mouseY <= historyBottom) {
            int maxScroll = Math.max(0, history.size() - 1);
            int direction = scrollY > 0 ? 1 : -1;
            scrollOffset = MineAgentUiComponents.clamp(
                    scrollOffset + direction, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && inputField.isFocused()
                && !inputField.getValue().isBlank()) {
            sendMessage();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sendMessage() {
        String text = ClientCommandInput.greedy(inputField.getValue(), 512);
        if (text.isEmpty()) return;
        history.add(new ChatEntry(MineAgentUiComponents.BubbleType.OWNER, text));
        inputField.setValue("");
        scrollOffset = 0;

        if (companionId == null) {
            companionId = MineAgentClientController.getCompanionId();
            companionSpawned = companionId != null;
        }
        if (companionId != null) {
            MineAgentClientController.sendUiAction(new ClientUiActionPayload(
                    companionId, "chat", text));
        } else {
            history.add(new ChatEntry(MineAgentUiComponents.BubbleType.SYSTEM,
                    Component.translatable("screen.mineagent.chat.no_companion").getString()));
        }
        refreshControlState();
    }

    private void toggleReflex(String reflexId) {
        if (companionId == null || !companionSpawned) return;
        boolean enabled;
        switch (reflexId) {
            case "auto_eat" -> enabled = reflexAutoEat = !reflexAutoEat;
            case "fight_back" -> enabled = reflexFightBack = !reflexFightBack;
            case "pickup_items" -> enabled = reflexPickupItems = !reflexPickupItems;
            default -> { return; }
        }
        MineAgentClientController.sendUiAction(new ClientUiActionPayload(
                companionId, "toggle_reflex", reflexId + "=" + enabled));
        refreshControlState();
    }

    private void selectCompanion(UUID id) {
        if (id == null || id.getMostSignificantBits() == 0L
                && id.getLeastSignificantBits() == 0L) return;
        companionId = id;
        companionSpawned = true;
        MineAgentClientController.selectCompanion(id);
        if (gameModeButton != null) {
            gameModeButton.setValue(MineAgentClientController.getCompanionGameMode(id));
        }
        refreshControlState();
    }

    private void setGameMode(CompanionGameMode mode) {
        if (companionId == null || !companionSpawned || mode == null) return;
        MineAgentClientController.sendUiAction(new ClientUiActionPayload(
                companionId, "set_game_mode", mode.wireName()));
    }

    private Component companionLabel(UUID id) {
        if (id == null || (id.getMostSignificantBits() == 0L
                && id.getLeastSignificantBits() == 0L)) {
            return Component.translatable("screen.mineagent.chat.offline");
        }
        return Component.literal(MineAgentClientController.getCompanionName(id));
    }

    private Component gameModeLabel(CompanionGameMode mode) {
        return Component.translatable("screen.mineagent.game_mode."
                + (mode == null ? CompanionGameMode.SURVIVAL : mode).wireName());
    }

    private void removeCompanion() {
        if (companionId == null) return;
        MineAgentClientController.sendUiAction(new ClientUiActionPayload(
                companionId, "remove_companion", ""));
        history.add(new ChatEntry(MineAgentUiComponents.BubbleType.SYSTEM,
                Component.translatable("screen.mineagent.chat.remove_requested").getString()));
        scrollOffset = 0;
    }

    private void refreshControlState() {
        boolean available = companionSpawned && companionId != null;
        if (autoEatButton != null) {
            autoEatButton.active = available;
            autoEatButton.setMessage(toggleLabel("screen.mineagent.reflex.auto_eat",
                    reflexAutoEat));
        }
        if (fightBackButton != null) {
            fightBackButton.active = available;
            fightBackButton.setMessage(toggleLabel("screen.mineagent.reflex.fight_back",
                    reflexFightBack));
        }
        if (pickupButton != null) {
            pickupButton.active = available;
            pickupButton.setMessage(toggleLabel("screen.mineagent.reflex.pickup",
                    reflexPickupItems));
        }
        if (companionSelector != null) companionSelector.active = !MineAgentClientController
                .getCompanionIds().isEmpty();
        if (gameModeButton != null) gameModeButton.active = available;
        if (removeButton != null) removeButton.active = available;
    }

    private Component toggleLabel(String key, boolean enabled) {
        return Component.translatable(key, Component.translatable(enabled
                ? "screen.mineagent.on" : "screen.mineagent.off"));
    }

    public void receiveCompanionMessage(String message) {
        history.add(new ChatEntry(MineAgentUiComponents.BubbleType.AI, message));
        scrollOffset = 0;
    }

    public void updateCurrentAction(String action) {
        currentAction = action == null || action.isBlank() ? "Idle" : action;
    }

    public void setCompanionSpawned(boolean spawned) {
        companionSpawned = spawned;
        refreshControlState();
    }

    public void setCompanionId(UUID id) {
        companionId = id;
        companionSpawned = id != null;
        if (companionSelector != null && id != null) {
            try { companionSelector.setValue(id); } catch (IllegalArgumentException ignored) { }
        }
        if (gameModeButton != null) {
            gameModeButton.setValue(MineAgentClientController.getCompanionGameMode(id));
        }
        refreshControlState();
    }

    public void setCompanionGameMode(UUID id, CompanionGameMode mode) {
        if (id != null && id.equals(companionId) && gameModeButton != null && mode != null) {
            gameModeButton.setValue(mode);
        }
    }

    /** Refresh the selected companion's display label after a server update. */
    public void refreshCompanionSelector() {
        if (companionSelector != null && companionId != null) {
            // setValue rebuilds CycleButton's normal "label: value" message;
            // setting the raw message would discard the localized field label.
            companionSelector.setValue(companionId);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private record ChatEntry(MineAgentUiComponents.BubbleType type, String message) {}
}
