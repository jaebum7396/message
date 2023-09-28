package trade.configuration;


import com.binance.connector.futures.client.utils.WebSocketCallback;
import com.binance.connector.futures.client.utils.WebSocketConnection;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;

public class MyWebSocketConnection extends WebSocketConnection {
    private final WebSocketCallback onOpenCallback;
    private final WebSocketCallback onMessageCallback;
    private final WebSocketCallback onClosingCallback;
    private final WebSocketCallback onFailureCallback;

    public MyWebSocketConnection(
            WebSocketCallback onOpenCallback
            , WebSocketCallback onMessageCallback
            , WebSocketCallback onClosingCallback
            , WebSocketCallback onFailureCallback
            , Request request) {
        super(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
        this.onOpenCallback = onOpenCallback;
        this.onMessageCallback = onMessageCallback;
        this.onClosingCallback = onClosingCallback;
        this.onFailureCallback = onFailureCallback;
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
        //System.out.println("failure : " + super.getConnectionId() + " : " + t.getMessage());
        onFailureCallback.onReceive(String.valueOf(super.getConnectionId()));
    }
}
