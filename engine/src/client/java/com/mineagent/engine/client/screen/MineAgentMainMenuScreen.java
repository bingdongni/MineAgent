package com.mineagent.engine.client.screen;

import com.mineagent.engine.client.MineAgentClientController;
import com.mineagent.engine.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Central, low-depth navigation surface for all companion controls. */
public class MineAgentMainMenuScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int COLUMN_GAP = 6;

    private MineAgentUiComponents.Rect panel;
    private int contentX;
    private int statusY;
    private int buttonsY;
    private Button chatButton;
    private Button removeButton;

    public MineAgentMainMenuScreen() {
        super(Component.literal("MineAgent"));
    }

    @Override
    protected void init() {
        super.init();
        int preferredHeight = MineAgentUiComponents.TITLE_BAND_HEIGHT
                + 10 + 12 + 8 + 4 * BUTTON_HEIGHT + 3 * ROW_GAP
                + 8 + BUTTON_HEIGHT + 12;
        panel = MineAgentUiComponents.centeredPanel(
                this.width, this.height, 400, preferredHeight);
        int innerWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        contentX = panel.x() + MineAgentUiComponents.PANEL_PADDING;
        statusY = panel.y() + MineAgentUiComponents.TITLE_BAND_HEIGHT + 10;
        buttonsY = statusY + 20;

        int columnWidth = (innerWidth - COLUMN_GAP) / 2;
        addMenuButton("screen.mineagent.menu.create", 0, 0, columnWidth,
                ignored -> Minecraft.getInstance().setScreen(new SpawnCompanionScreen()));
        chatButton = addMenuButton("screen.mineagent.menu.chat", 1, 0, columnWidth,
                ignored -> Minecraft.getInstance().setScreen(new CompanionChatScreen(
                        MineAgentClientController.getCompanionId())));
        addMenuButton("screen.mineagent.menu.connection", 0, 1, columnWidth,
                ignored -> Minecraft.getInstance().setScreen(new ModelSelectScreen()));
        addMenuButton("screen.mineagent.menu.runtime", 1, 1, columnWidth,
                ignored -> Minecraft.getInstance().setScreen(new ConfigEditScreen()));
        addMenuButton("screen.mineagent.menu.appearance", 0, 2, columnWidth,
                ignored -> Minecraft.getInstance().setScreen(new SkinSelectScreen()));
        addMenuButton("screen.mineagent.menu.list", 1, 2, columnWidth,
                ignored -> requestCompanionList());
        addMenuButton("screen.mineagent.menu.help", 0, 3, columnWidth,
                ignored -> Minecraft.getInstance().setScreen(new HelpScreen()));
        removeButton = addMenuButton("screen.mineagent.menu.remove", 1, 3,
                columnWidth, ignored -> removeCompanion());

        int closeY = buttonsY + 4 * BUTTON_HEIGHT + 3 * ROW_GAP + 8;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                ignored -> this.onClose())
                .bounds(contentX, closeY, innerWidth, BUTTON_HEIGHT).build());
        refreshAvailability();
    }

    private Button addMenuButton(String translationKey, int column, int row,
                                 int width, Button.OnPress onPress) {
        int x = contentX + column * (width + COLUMN_GAP);
        int y = buttonsY + row * (BUTTON_HEIGHT + ROW_GAP);
        Button button = Button.builder(Component.translatable(translationKey), onPress)
                .bounds(x, y, width, BUTTON_HEIGHT).build();
        this.addRenderableWidget(button);
        return button;
    }

    private void refreshAvailability() {
        boolean online = MineAgentClientController.getCompanionId() != null;
        if (chatButton != null) chatButton.active = online;
        if (removeButton != null) removeButton.active = online;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MineAgentUiComponents.drawPanel(graphics, panel.x(), panel.y(),
                panel.width(), panel.height(), this.title, true,
                this.width, this.height);

        UUID companionId = MineAgentClientController.getCompanionId();
        boolean online = companionId != null;
        Component status = online
                ? Component.translatable("screen.mineagent.status.online",
                        companionId.toString().substring(0, 8))
                : Component.translatable("screen.mineagent.status.offline");
        MineAgentUiComponents.drawStatusIndicator(graphics, contentX, statusY,
                status, online);
        refreshAvailability();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void requestCompanionList() {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent list");
        }
    }

    private void removeCompanion() {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent remove");
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
