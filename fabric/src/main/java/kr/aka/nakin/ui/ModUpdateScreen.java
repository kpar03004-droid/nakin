package kr.aka.nakin.ui;

import java.util.ArrayList;
import java.util.List;
import kr.aka.nakin.update.ModUpdater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 업데이트 동의 화면 — 칼띵 {@code ModUpdateScreen} 과 같은 구성.
 *
 * <p>"동의 후 다운로드"만 실제 다운로드를 시작하고, 그때 넘기는 offer 는
 * <b>이 화면에 표시된 바로 그 파일</b>이다. 표시 후 릴리즈가 바뀌면 ModUpdater 가 거부한다.
 * 닫기는 이미 동의한 교체 예약을 취소하지 않는다.
 */
public final class ModUpdateScreen extends Screen {
    private final ModUpdater updater;
    private final String currentVersion;
    private final String error;
    private ModUpdater.Offer displayedOffer;
    private boolean awaitingOffer = true;
    private Button checkButton, downloadButton;
    private double scroll;
    private int maxScroll;

    public ModUpdateScreen(ModUpdater updater, String currentVersion, String error) {
        super(Component.literal("낙인 업데이트"));
        this.updater = updater;
        this.currentVersion = currentVersion;
        this.error = error;
        // 열자마자 대상 버전이 보이도록 조회를 건다. 1시간 쿨다운이 내장돼 있어
        // 알림에서 이미 조회했거나 여러 번 열어도 GitHub 에 추가 요청은 가지 않는다.
        if (updater != null) updater.check();
    }

    private int panelWidth() { return Math.max(1, Math.min(460, width - 20)); }

    @Override protected void init() {
        int panel = panelWidth(), left = (width - panel) / 2;
        int buttonWidth = Math.max(1, (panel - 12) / 3);
        checkButton = addRenderableWidget(Button.builder(Component.literal("업데이트 확인"), b -> {
            displayedOffer = null;
            awaitingOffer = true;
            scroll = 0;
            updater.check();
        }).bounds(left, height - 30, buttonWidth, 20).build());
        downloadButton = addRenderableWidget(Button.builder(Component.literal("동의 후 다운로드"), b -> {
            if (canDownload()) updater.download(displayedOffer);
        }).bounds(left + buttonWidth + 6, height - 30, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
                .bounds(left + (buttonWidth + 6) * 2, height - 30, buttonWidth, 20).build());
        updateButtons();
    }

    private boolean canDownload() {
        return updater != null && !updater.isBusy() && !updater.isQueued()
                && displayedOffer != null && displayedOffer.equals(updater.offer());
    }

    private void updateButtons() {
        if (updater != null && awaitingOffer && updater.offer() != null) {
            displayedOffer = updater.offer();
            awaitingOffer = false;
        }
        checkButton.active = updater != null && !updater.isBusy() && !updater.isQueued();
        downloadButton.active = canDownload();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        updateButtons();
        super.extractRenderState(context, mouseX, mouseY, delta);
        int panel = panelWidth(), left = (width - panel) / 2;
        context.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        List<FormattedCharSequence> lines = new ArrayList<>();
        add(lines, "배포 출처: GitHub / " + (updater == null ? "확인 불가" : updater.repository()), panel);
        add(lines, "현재 버전: " + currentVersion, panel);
        add(lines, "대상 버전: " + (displayedOffer == null ? "미확인" : displayedOffer.version()), panel);
        if (displayedOffer != null) {
            add(lines, "파일: " + displayedOffer.fileName(), panel);
            add(lines, "크기: " + displayedOffer.size() + " bytes", panel);
            add(lines, "SHA-256: " + displayedOffer.sha256(), panel);
        }
        add(lines, "동의하면 위 파일을 다운로드하고 크기·해시·모드 정보를 검증합니다.", panel);
        add(lines, "게임을 완전히 종료한 뒤 현재 JAR를 백업하고 교체합니다. 종료 후 약 5초 기다린 다음 직접 다시 실행하세요.", panel);
        add(lines, "백업·결과 기록: mods/.mod-updates/ 아래 작업 폴더", panel);
        add(lines, "다운로드 동의 뒤 이 화면을 닫아도 교체 예약은 유지됩니다.", panel);
        if (updater != null && displayedOffer != null && !displayedOffer.equals(updater.offer()))
            add(lines, "대상 정보가 변경되었습니다. 업데이트 확인을 다시 눌러 주세요.", panel);
        add(lines, "상태: " + (updater == null ? error : updater.status()), panel);
        int top = 36, bottom = Math.max(top, height - 44);
        maxScroll = Math.max(0, lines.size() * 12 - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        context.enableScissor(left, top, left + panel, bottom);
        try {
            int y = top - (int) scroll;
            for (FormattedCharSequence line : lines) {
                context.text(font, line, left, y, 0xFFE8EEF2);
                y += 12;
            }
        } finally { context.disableScissor(); }
        if (maxScroll > 0)
            context.centeredText(font, Component.literal("휠로 안내 스크롤"), width / 2, height - 42, 0xFFAAAAAA);
    }

    private void add(List<FormattedCharSequence> lines, String text, int width) {
        lines.addAll(font.split(Component.literal(text), width));
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - vertical * 24));
        return true;
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
}
