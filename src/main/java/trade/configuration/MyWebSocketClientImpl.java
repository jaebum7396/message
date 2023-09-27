package trade.configuration;

import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import com.binance.connector.futures.client.impl.WebsocketClientImpl;
import com.binance.connector.futures.client.utils.WebSocketCallback;
import com.binance.connector.futures.client.utils.WebSocketConnection;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class MyWebSocketClientImpl extends UMWebsocketClientImpl {
    private final Map<Integer, WebSocketConnection> connections = new HashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(MyWebSocketClientImpl.class);
    @Override
    public int createConnection(
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request
    ) {
        WebSocketConnection connection = new MyWebSocketConnection(onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
        connection.connect();
        int connectionId = connection.getConnectionId();
        connections.put(connectionId, connection);
        return connectionId;
    }

    @Override
    public void closeConnection(int connectionId) {
        if (connections.containsKey(connectionId)) {
            connections.get(connectionId).close();
            logger.info("Closing Connection ID {}", connectionId);
            connections.remove(connectionId);
        } else {
            logger.info("Connection ID {} does not exist!", connectionId);
        }
    }
}
