package trade.configuration;


import com.binance.connector.futures.client.utils.HttpClientSingleton;
import com.binance.connector.futures.client.utils.WebSocketCallback;
import com.binance.connector.futures.client.utils.WebSocketConnection;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import trade.future.model.entity.TradingEntity;


public class MyWebSocketConnection extends WebSocketConnection {
    private final TradingEntity tradingEntity;
    private final WebSocketCallback onOpenCallback;
    private final WebSocketCallback onMessageCallback;
    private final WebSocketCallback onClosingCallback;
    private final WebSocketCallback onFailureCallback;

    public MyWebSocketConnection(
            TradingEntity tradingEntity
            , WebSocketCallback onOpenCallback
            , WebSocketCallback onMessageCallback
            , WebSocketCallback onClosingCallback
            , WebSocketCallback onFailureCallback
            , Request request) {
        super(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
        tradingEntity.setStreamId(super.getConnectionId());
        this.tradingEntity = tradingEntity;
        this.onOpenCallback = onOpenCallback;
        this.onMessageCallback = onMessageCallback;
        this.onClosingCallback = onClosingCallback;
        this.onFailureCallback = onFailureCallback;
    }

    public TradingEntity getTradingEntity() {
        return tradingEntity;
    }

    @Override
    public void onOpen(WebSocket ws, Response response) {
        onOpenCallback.onReceive(String.valueOf(super.getConnectionId()));
    }

    @Override
    public void onClosing(WebSocket ws, int code, String reason) {
        onClosingCallback.onReceive(String.valueOf(super.getConnectionId()));
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
        onMessageCallback.onReceive(text);
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
        // 에러 로그 기록
        if (response != null) {
            System.err.println("WebSocket failure - Response: " + response.toString());
        } else {
            System.err.println("WebSocket failure - No response available");
        }
        t.printStackTrace();  // 에러 스택 트레이스 출력

        // 자원 정리
        cleanUpResources();
        onFailureCallback.onReceive(String.valueOf(super.getConnectionId()));
    }

    // 자원 정리 메서드
    private void cleanUpResources() {
        // 필요 시 사용한 자원을 정리하는 로직 추가
        System.out.println("Cleaning up resources...");
    }
}
