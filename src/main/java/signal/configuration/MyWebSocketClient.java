package signal.configuration;

import com.binance.connector.futures.client.WebsocketClient;
import com.binance.connector.futures.client.utils.WebSocketCallback;
import okhttp3.Request;
import signal.broadcast.model.entity.BroadCastEntity;

import java.util.ArrayList;

public interface MyWebSocketClient extends WebsocketClient {
    public BroadCastEntity createConnection(
            BroadCastEntity broadCastEntity,
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request
    );
    BroadCastEntity klineStream(BroadCastEntity broadCastEntity, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback);

    BroadCastEntity combineStreams(BroadCastEntity broadCastEntity, ArrayList<String> streams, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback);
}