package com.mineagent.fabric.client.screen;

import com.mineagent.fabric.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 伴游皮肤设置界面。
 *
 * <p>玩家可以：
 * <ul>
 *   <li>选择默认皮肤 (Steve / Alex / Reset)</li>
 *   <li>输入Minecraft玩家名，使用该玩家的皮肤</li>
 *   <li>兼容皮肤模组 (SkinsRestorer, CustomSkinLoader等)</li>
 * </ul>
 *
 * <p>所有操作通过 /mineagent setskin 和 /mineagent resetskin 命令实现。
 *
 * <p>注意：所有命令发送必须使用 sendCommand 而非 sendChat，
 * 否则会被当作普通聊天消息发送而非命令执行。
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for styled panel
 * background with dot grid texture, gold-accented borders, tilted corner
 * decorations, and section headers. All content is boundary-clamped.
 */
public class SkinSelectScreen extends Screen {

    private static final int BTN_WIDTH = 200;
    private static final int BTN_HEIGHT = 20;
    private static final int INPUT_WIDTH = 200;
    private static final int INPUT_HEIGHT = 18;
    private static final int MARGIN = 10;
    private static final int PANEL_PADDING = 8;

    private EditBox skinNameField;

    /** Computed panel bounds (clamped to screen in init). */
    private int panelX, panelY, panelWidth, panelHeight;
    private int contentStartY;

    public SkinSelectScreen() {
        super(Component.literal("Skin Settings"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // ── Compute panel dimensions ──
        // 3 default buttons + divider + input + apply button + back button + info area
        int contentHeight = 3 * (BTN_HEIGHT + 4) + 4 + INPUT_HEIGHT + 4 + BTN_HEIGHT + 4 + BTN_HEIGHT + 40;
        panelWidth = Math.min(INPUT_WIDTH + PANEL_PADDING * 2 + 4,
                this.width - 2 * MARGIN);
        panelHeight = Math.min(contentHeight + MineAgentUiComponents.TITLE_BAND_HEIGHT
                        + PANEL_PADDING * 2 + 16,
                this.height - 2 * MARGIN);

        panelX = MineAgentUiComponents.clamp(centerX - panelWidth / 2, MARGIN,
                Math.max(MARGIN, this.width - panelWidth - MARGIN));
        panelY = MineAgentUiComponents.clamp((this.height - panelHeight) / 2, MARGIN,
                Math.max(MARGIN, this.height - panelHeight - MARGIN));

        contentStartY = panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT + PANEL_PADDING + 8;

        int inputX = centerX - INPUT_WIDTH / 2;
        // Clamp inputX to panel bounds
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - INPUT_WIDTH - PANEL_PADDING);

        int startY = contentStartY;

        // --- 默认 Steve 皮肤 ---
        this.addRenderableWidget(Button.builder(
                Component.literal("§aDefault Steve"),
                btn -> setSkin("steve")
        ).bounds(inputX, startY, BTN_WIDTH, BTN_HEIGHT).build());

        // --- 默认 Alex 皮肤 ---
        this.addRenderableWidget(Button.builder(
                Component.literal("§aDefault Alex"),
                btn -> setSkin("alex")
        ).bounds(inputX, startY + (BTN_HEIGHT + 4), BTN_WIDTH, BTN_HEIGHT).build());

        // --- 重置皮肤 (Auto Steve/Alex) ---
        this.addRenderableWidget(Button.builder(
                Component.literal("§7Reset (Auto Steve/Alex)"),
                btn -> resetSkin()
        ).bounds(inputX, startY + (BTN_HEIGHT + 4) * 2, BTN_WIDTH, BTN_HEIGHT).build());

        // --- 玩家名输入框（分隔线下方） ---
        int inputY = startY + (BTN_HEIGHT + 4) * 2 + BTN_HEIGHT + 16;
        // Boundary-safe: clamp inputY
        inputY = MineAgentUiComponents.clamp(inputY, MARGIN,
                this.height - INPUT_HEIGHT - BTN_HEIGHT - 8 - BTN_HEIGHT - MARGIN);
        this.skinNameField = new EditBox(
                this.minecraft.font,
                inputX, inputY,
                INPUT_WIDTH, INPUT_HEIGHT,
                Component.literal("Skin Player Name")
        );
        this.skinNameField.setMaxLength(16);
        this.skinNameField.setHint(Component.literal("§7输入Minecraft玩家名 (支持皮肤模组)"));
        this.addRenderableWidget(this.skinNameField);

        // --- Apply Skin 按钮 ---
        int applyY = inputY + INPUT_HEIGHT + 4;
        applyY = MineAgentUiComponents.clamp(applyY, MARGIN,
                this.height - BTN_HEIGHT - MARGIN);
        this.addRenderableWidget(Button.builder(
                Component.literal("§a✔ Apply Skin"),
                btn -> applySkin()
        ).bounds(inputX, applyY, BTN_WIDTH, BTN_HEIGHT).build());

        // --- Back 按钮 ---
        int backY = applyY + BTN_HEIGHT + 4;
        backY = MineAgentUiComponents.clamp(backY, MARGIN,
                this.height - BTN_HEIGHT - MARGIN);
        this.addRenderableWidget(Button.builder(
                Component.literal("§7Back"),
                btn -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen())
        ).bounds(inputX, backY, BTN_WIDTH, BTN_HEIGHT).build());

        this.setInitialFocus(this.skinNameField);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // ── Styled panel background ──
        MineAgentUiComponents.drawPanel(
                graphics,
                panelX, panelY, panelWidth, panelHeight,
                Component.literal("§6§lSkin Settings"),
                true,
                this.width, this.height
        );

        // ── Tilted decorations at title corners ──
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4, MineAgentUiComponents.COLOR_CORNER, -2.0f);
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + panelWidth - 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4, MineAgentUiComponents.COLOR_CORNER, 2.0f);

        // ── Content ──
        int inputX = centerX - INPUT_WIDTH / 2;
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - INPUT_WIDTH - PANEL_PADDING);

        // 分隔线说明文字
        int dividerY = contentStartY + (BTN_HEIGHT + 4) * 2 + BTN_HEIGHT + 4;
        dividerY = MineAgentUiComponents.clamp(dividerY, MARGIN,
                this.height - 10 - MARGIN);
        MineAgentUiComponents.drawSectionHeader(
                graphics, "§e--- 或使用玩家名/皮肤模组皮肤 ---",
                inputX, dividerY,
                INPUT_WIDTH, 0xFFAA00);

        // 底部说明文字
        int infoY = this.height - 40;
        infoY = MineAgentUiComponents.clamp(infoY, MARGIN,
                this.height - 30 - MARGIN);
        graphics.drawCenteredString(
                this.minecraft.font,
                Component.literal("§8默认皮肤: Steve(偶数UUID)或Alex(奇数UUID)"),
                centerX, infoY,
                0x666666
        );
        graphics.drawCenteredString(
                this.minecraft.font,
                Component.literal("§8玩家名皮肤: 从Mojang API加载，需联网"),
                centerX, infoY + 10,
                0x666666
        );
        graphics.drawCenteredString(
                this.minecraft.font,
                Component.literal("§8兼容皮肤模组: SkinsRestorer, CustomSkinLoader等"),
                centerX, infoY + 20,
                0x666666
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 设置皮肤 (Steve / Alex / 玩家名) */
    private void setSkin(String skin) {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent setskin " + skin);
        }
    }

    /** 重置皮肤为默认 Steve/Alex */
    private void resetSkin() {
        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent resetskin");
        }
    }

    /** 应用输入框中的玩家名作为皮肤 */
    private void applySkin() {
        String skinName = skinNameField.getValue().trim();
        if (skinName.isEmpty()) return;

        this.onClose();
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent setskin " + skinName);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
