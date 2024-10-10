package signal.configuration;


import com.binance.connector.futures.client.utils.WebSocketCallback;
import com.binance.connector.futures.client.utils.WebSocketConnection;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import signal.broadcast.model.entity.BroadCastEntity;


public class MyWebSocketConnection extends WebSocketConnection {
    private final BroadCastEntity broadCastEntity;
    private final WebSocketCallback onOpenCallback;
    private final WebSocketCallback onMessageCallback;
    private final WebSocketCallback onClosingCallback;
    private final WebSocketCallback onFailureCallback;

    public MyWebSocketConnection(
            BroadCastEntity broadCastEntity
            , WebSocketCallback onOpenCallback
            , WebSocketCallback onMessageCallback
            , WebSocketCallback onClosingCallback
            , WebSocketCallback onFailureCallback
            , Request request) {
        super(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
        broadCastEntity.setStreamId(super.getConnectionId());
        this.broadCastEntity = broadCastEntity;
        this.onOpenCallback = onOpenCallback;
        this.onMessageCallback = onMessageCallback;
        this.onClosingCallback = onClosingCallback;
        this.onFailureCallback = onFailureCallback;
    }

    public BroadCastEntity getBroadCastEntity() {
        return broadCastEntity;
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
        //t.printStackTrace();  // 에러 스택 트레이스 출력
        // 자원 정리
        onFailureCallback.onReceive(String.valueOf(super.getConnectionId()));
    }
}
