package com.mineagent.fabric.client.screen;

import com.mineagent.fabric.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 配置编辑界面。
 *
 * <p>支持在线修改：
 * <ul>
 *   <li>API Key</li>
 *   <li>Base URL（中转站）</li>
 *   <li>Temperature（温度）</li>
 *   <li>伴游名字</li>
 *   <li>Thinking Effort（思考强度）</li>
 * </ul>
 *
 * <p>每个配置项通过 /mineagent setconfig 命令保存到服务器配置文件。
 * 玩家可在一次打开中保存多个配置项，关闭界面后可查看服务器反馈。
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for styled panel
 * background with dot grid texture, gold-accented borders, tilted corner
 * decorations, and section headers. All content is boundary-clamped.
 */
public class ConfigEditScreen extends Screen {

    private static final int BTN_HEIGHT = 20;
    private static final int INPUT_WIDTH = 200;
    private static final int INPUT_HEIGHT = 20;
    /** Save按钮宽度。 */
    private static final int SAVE_BTN_WIDTH = 80;
    /** 每个配置项块的垂直间距：12(label) + 20(box) + 6(间距)。 */
    private static final int ITEM_SPACING = 38;
    private static final int MARGIN = 10;
    private static final int PANEL_PADDING = 8;

    private EditBox apiKeyField;
    private EditBox baseUrlField;
    private EditBox temperatureField;
    private EditBox nameField;
    private EditBox thinkingEffortField;

    /** Computed panel bounds (clamped to screen in init). */
    private int panelX, panelY, panelWidth, panelHeight;
    private int contentStartY;

    public ConfigEditScreen() {
        super(Component.literal("Configuration"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // ── Compute panel dimensions ──
        // 5 input rows + button row + title band
        int contentHeight = ITEM_SPACING * 5 + BTN_HEIGHT + 4;
        panelWidth = Math.min(INPUT_WIDTH + SAVE_BTN_WIDTH + 4 + PANEL_PADDING * 2 + 4,
                this.width - 2 * MARGIN);
        panelHeight = Math.min(contentHeight + MineAgentUiComponents.TITLE_BAND_HEIGHT
                        + PANEL_PADDING * 2 + 16,
                this.height - 2 * MARGIN);

        panelX = MineAgentUiComponents.clamp(centerX - panelWidth / 2, MARGIN,
                Math.max(MARGIN, this.width - panelWidth - MARGIN));
        panelY = MineAgentUiComponents.clamp((this.height - panelHeight) / 2, MARGIN,
                Math.max(MARGIN, this.height - panelHeight - MARGIN));

        contentStartY = panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT + PANEL_PADDING + 8;

        int startY = contentStartY;
        int pairWidth = INPUT_WIDTH + 4 + SAVE_BTN_WIDTH;
        int inputX = centerX - pairWidth / 2;
        // Clamp inputX to panel bounds
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - pairWidth - PANEL_PADDING);
        int saveX = inputX + INPUT_WIDTH + 4;

        // --- API Key ---
        this.apiKeyField = new EditBox(
                this.minecraft.font, inputX, startY + 12,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("API Key")
        );
        this.apiKeyField.setMaxLength(256);
        this.apiKeyField.setHint(Component.literal("§7输入API Key"));
        this.addRenderableWidget(this.apiKeyField);
        this.addRenderableWidget(Button.builder(
                Component.literal("§aSave"),
                btn -> saveConfig("apikey", apiKeyField.getValue().trim())
        ).bounds(saveX, startY + 12, SAVE_BTN_WIDTH, BTN_HEIGHT).build());

        // --- Base URL ---
        int baseUrlY = startY + ITEM_SPACING;
        this.baseUrlField = new EditBox(
                this.minecraft.font, inputX, baseUrlY + 12,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Base URL")
        );
        this.baseUrlField.setMaxLength(256);
        this.baseUrlField.setHint(Component.literal("§7中转站URL，留空用官方URL"));
        this.addRenderableWidget(this.baseUrlField);
        this.addRenderableWidget(Button.builder(
                Component.literal("§aSave"),
                btn -> saveConfig("baseurl", baseUrlField.getValue().trim())
        ).bounds(saveX, baseUrlY + 12, SAVE_BTN_WIDTH, BTN_HEIGHT).build());

        // --- Temperature ---
        int tempY = startY + ITEM_SPACING * 2;
        this.temperatureField = new EditBox(
                this.minecraft.font, inputX, tempY + 12,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Temperature")
        );
        this.temperatureField.setMaxLength(8);
        this.temperatureField.setHint(Component.literal("§70.0-2.0，默认0.7"));
        this.addRenderableWidget(this.temperatureField);
        this.addRenderableWidget(Button.builder(
                Component.literal("§aSave"),
                btn -> saveConfig("temperature", temperatureField.getValue().trim())
        ).bounds(saveX, tempY + 12, SAVE_BTN_WIDTH, BTN_HEIGHT).build());

        // --- Companion Name ---
        int nameY = startY + ITEM_SPACING * 3;
        this.nameField = new EditBox(
                this.minecraft.font, inputX, nameY + 12,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Name")
        );
        this.nameField.setMaxLength(32);
        this.nameField.setHint(Component.literal("§7伴游默认名字（留空用模型名）"));
        this.addRenderableWidget(this.nameField);
        this.addRenderableWidget(Button.builder(
                Component.literal("§aSave"),
                btn -> saveConfig("name", nameField.getValue().trim())
        ).bounds(saveX, nameY + 12, SAVE_BTN_WIDTH, BTN_HEIGHT).build());

        // --- Thinking Effort (留空=使用API默认值，支持等级因模型而异) ---
        int effortY = startY + ITEM_SPACING * 4;
        this.thinkingEffortField = new EditBox(
                this.minecraft.font, inputX, effortY + 12,
                INPUT_WIDTH, INPUT_HEIGHT, Component.literal("Thinking Effort")
        );
        this.thinkingEffortField.setMaxLength(8);
        this.thinkingEffortField.setValue("");
        this.thinkingEffortField.setHint(Component.literal("§7留空=默认 (off/low/medium/high/xhigh/max，因模型而异)"));
        this.addRenderableWidget(this.thinkingEffortField);
        this.addRenderableWidget(Button.builder(
                Component.literal("§aSave"),
                btn -> saveEffort(thinkingEffortField.getValue().trim())
        ).bounds(saveX, effortY + 12, SAVE_BTN_WIDTH, BTN_HEIGHT).build());

        // --- 按钮区域（3个按钮并排） ---
        int buttonY = startY + ITEM_SPACING * 5 + 4;
        // Boundary-safe: clamp buttonY
        buttonY = MineAgentUiComponents.clamp(buttonY, MARGIN,
                this.height - BTN_HEIGHT - MARGIN);
        int thirdBtnWidth = (INPUT_WIDTH - 8) / 3;

        this.addRenderableWidget(Button.builder(
                Component.literal("§7 Show"),
                btn -> {
                    this.onClose();
                    if (this.minecraft.player != null) {
                        this.minecraft.player.connection.sendCommand("mineagent config");
                    }
                }
        ).bounds(inputX, buttonY, thirdBtnWidth, BTN_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§b🔄 Reload"),
                btn -> {
                    this.onClose();
                    if (this.minecraft.player != null) {
                        this.minecraft.player.connection.sendCommand("mineagent reload");
                    }
                }
        ).bounds(inputX + thirdBtnWidth + 4, buttonY, thirdBtnWidth, BTN_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§7Back"),
                btn -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen())
        ).bounds(inputX + (thirdBtnWidth + 4) * 2, buttonY, thirdBtnWidth, BTN_HEIGHT).build());

        this.setInitialFocus(this.apiKeyField);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // ── Styled panel background ──
        MineAgentUiComponents.drawPanel(
                graphics,
                panelX, panelY, panelWidth, panelHeight,
                Component.literal("§6§lConfiguration"),
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

        // ── Section header ──
        int startY = contentStartY;
        int pairWidth = INPUT_WIDTH + 4 + SAVE_BTN_WIDTH;
        int inputX = centerX - pairWidth / 2;
        inputX = Math.max(inputX, panelX + PANEL_PADDING);
        inputX = Math.min(inputX, panelX + panelWidth - pairWidth - PANEL_PADDING);

        // 各配置项标签 (using section header style)
        drawConfigLabel(graphics, "§eAPI Key:", inputX, startY);
        drawConfigLabel(graphics, "§eBase URL:", inputX, startY + ITEM_SPACING);
        drawConfigLabel(graphics, "§eTemperature:", inputX, startY + ITEM_SPACING * 2);
        drawConfigLabel(graphics, "§eName:", inputX, startY + ITEM_SPACING * 3);
        drawConfigLabel(graphics, "§eEffort:", inputX, startY + ITEM_SPACING * 4);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Draw a config item label, clamped to panel bounds. */
    private void drawConfigLabel(GuiGraphics graphics, String text, int x, int y) {
        // Boundary-safe: clamp Y to visible area
        y = MineAgentUiComponents.clamp(y, MARGIN,
                this.height - 10 - MARGIN);
        graphics.drawString(this.minecraft.font, text, x, y, 0xAAAAAA, false);
    }

    /** 发送setconfig命令保存配置项（不关闭界面，便于连续保存多个项）。 */
    private void saveConfig(String key, String value) {
        if (value.isEmpty()) {
            return;
        }
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent setconfig " + key + " " + value);
        }
    }

    /** 发送seteffort命令保存思考强度（不关闭界面，便于连续操作）。 */
    private void saveEffort(String effort) {
        if (effort.isEmpty()) {
            return;
        }
        if (this.minecraft.player != null) {
            this.minecraft.player.connection.sendCommand("mineagent seteffort " + effort);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
