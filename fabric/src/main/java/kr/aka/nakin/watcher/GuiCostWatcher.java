package kr.aka.nakin.watcher;

import kr.aka.nakin.core.DongleCategory;
import kr.aka.nakin.core.GuiCostLore;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * 열린 컨테이너 창(대장간 등)의 슬롯 아이템 설명에서 결제 비용을 읽는다 — <b>읽기만</b>.
 * 클릭·슬롯 조작·패킷 전송은 하지 않는다. 창 제목은 리소스팩 때문에 믿을 수 없어 쓰지 않는다.
 */
public final class GuiCostWatcher {
    private GuiCostWatcher() {}

    /**
     * 마지막으로 툴팁이 뜬 아이템 = 마우스가 올라간 슬롯. 같은 가격 진열품이 여럿이면 비용은 금액으로만
     * 기억되므로(나중 것이 이김) 이걸 맨 뒤에 넣어 실제로 클릭한 품목 이름이 남게 한다.
     */
    private static volatile ItemStack hovered = ItemStack.EMPTY;

    /**
     * 수량 선택 창의 "클릭하여 구매 xN" 버튼에는 품목 이름이 없다(2026-09-30 사용자 스크린샷).
     * 그 창으로 가기 전 진열대에서 마우스를 올린 품목(이름·단가)을 기억해 두었다가 버튼 비용에 붙인다.
     */
    private static String product;
    private static long productUnit, productAt;

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> hovered = stack);
    }

    /** @return 지금 열린 창에서 읽은 비용들(창이 없으면 빈 목록) */
    public static List<GuiCostLore.GuiCost> scan(Minecraft client) {
        List<GuiCostLore.GuiCost> out = new ArrayList<>();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return out;
        try {
            ItemStack last = null;
            for (Slot slot : screen.getMenu().slots) {
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                if (stack == hovered) { last = stack; continue; }
                out.addAll(parse(stack));
            }
            if (last != null) {
                List<GuiCostLore.GuiCost> hov = parse(last);
                for (GuiCostLore.GuiCost c : hov) {
                    if (DongleCategory.NPC_SHOP.equals(c.category()) && !GuiCostLore.BUY_BUTTON_LABEL.equals(c.label())) {
                        product = c.label();
                        productUnit = c.amount();
                        productAt = System.currentTimeMillis();
                    }
                }
                out.addAll(hov);
            }
            if (product != null && System.currentTimeMillis() - productAt < 120_000) {
                out.replaceAll(c -> GuiCostLore.withProduct(c, product, productUnit));
            }
        } catch (Throwable t) {
            // 창 구조가 예상과 달라도 크래시 금지 — 이 기능만 조용히 빠진다
        }
        return out;
    }

    private static List<GuiCostLore.GuiCost> parse(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component c : lore.lines()) lines.add(c.getString());
        return GuiCostLore.parse(stack.getHoverName().getString(), lines);
    }
}
