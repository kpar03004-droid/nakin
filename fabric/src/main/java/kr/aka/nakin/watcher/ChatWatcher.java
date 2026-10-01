package kr.aka.nakin.watcher;

import kr.aka.nakin.core.CurrencyParser;
import kr.aka.nakin.core.TradeSignal;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/**
 * 채팅/시스템 메시지를 CurrencyParser 로 파싱해 TradeSignal 발행.
 * 액션바(overlay=true)는 잔고 캡처용이므로 파싱 대상에서 제외.
 */
public final class ChatWatcher {
    private final CurrencyParser parser;
    private final Consumer<TradeSignal> signalSink;
    private final Consumer<String> actionBarSink;

    public ChatWatcher(CurrencyParser parser, Consumer<TradeSignal> signalSink,
                       Consumer<String> actionBarSink) {
        this.parser = parser;
        this.signalSink = signalSink;
        this.actionBarSink = actionBarSink;
    }

    public void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                actionBarSink.accept(message.getString());
            } else {
                handle(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                handle(message));
    }

    private void handle(Component message) {
        String s = message.getString();
        for (TradeSignal sig : parser.parse(s)) {
            signalSink.accept(sig);
        }
    }
}
