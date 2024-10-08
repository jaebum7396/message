package signal.configuration;

import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import com.binance.connector.futures.client.utils.*;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import signal.broadcast.model.entity.BroadCastEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class MyWebSocketClientImpl extends UMWebsocketClientImpl implements MyWebSocketClient {
    private final Map<Integer, WebSocketConnection> connections = new HashMap<>();
    private final Map<Integer, BroadCastEntity> BroadCastEntitys = new HashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(MyWebSocketClientImpl.class);

    public Map<Integer, WebSocketConnection> getConnections() {
        return connections;
    }
    @Override
    public BroadCastEntity createConnection(
            BroadCastEntity broadCastEntity,
            WebSocketCallback onOpenCallback,
            WebSocketCallback onMessageCallback,
            WebSocketCallback onClosingCallback,
            WebSocketCallback onFailureCallback,
            Request request
    ) {
        WebSocketConnection connection = new MyWebSocketConnection(broadCastEntity, onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
        connection.connect();
        int connectionId = connection.getConnectionId();
        connections.put(connectionId, connection);
        broadCastEntity.setStreamId(connectionId);
        BroadCastEntitys.put(connectionId, broadCastEntity);
        //System.out.println("connections : " + connections);
        //System.out.println("BroadCastEntitys : " + BroadCastEntitys);
        return broadCastEntity;
    }

    public BroadCastEntity getBroadCastEntity(int connectionId) {
        return BroadCastEntitys.get(connectionId);
    }

    @Override
    public void closeConnection(int connectionId) {
        if (connections.containsKey(connectionId)) {
            connections.get(connectionId).close();
            logger.info("Closing Connection ID {}", connectionId);
            BroadCastEntitys.remove(connectionId);
            connections.remove(connectionId);
        } else {
            logger.info("Connection ID {} does not exist!", connectionId);
        }
    }

    @Override
    public void closeAllConnections() {
        if (!this.connections.isEmpty()) {
            logger.info("Closing {} connections(s)", this.connections.size());
            Iterator<Map.Entry<Integer, WebSocketConnection>> iter = this.connections.entrySet().iterator();

            while(iter.hasNext()) {
                WebSocketConnection connection = (WebSocketConnection)((Map.Entry)iter.next()).getValue();
                connection.close();
                iter.remove();
            }
        }

        if (this.connections.isEmpty()) {
            HttpClientSingleton.getHttpClient().dispatcher().executorService().shutdown();
            logger.info("All connections are closed!");

            // Create a new HttpClient
            HttpClientSingleton.getHttpClient();
            logger.info("New HttpClient created!");
        }
    }

    @Override
    public BroadCastEntity klineStream(BroadCastEntity broadCastEntity, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        ParameterChecker.checkParameterType(broadCastEntity.getSymbol(), String.class, "symbol");
        Request request = RequestBuilder.buildWebsocketRequest(String.format("%s/ws/%s@kline_%s", super.getBaseUrl(), broadCastEntity.getSymbol().toLowerCase()));
        return createConnection(broadCastEntity, onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    @Override
    public BroadCastEntity combineStreams(BroadCastEntity broadCastEntity, ArrayList<String> streams, WebSocketCallback onOpenCallback, WebSocketCallback onMessageCallback, WebSocketCallback onClosingCallback, WebSocketCallback onFailureCallback) {
        String url = UrlBuilder.buildStreamUrl(String.format("%s/stream", super.getBaseUrl()), streams);
        Request request = RequestBuilder.buildWebsocketRequest(url);
        return createConnection(broadCastEntity, onOpenCallback, onMessageCallback, onClosingCallback, onFailureCallback, request);
    }

    public Map<Integer, BroadCastEntity> getBroadCastEntitys() {
        return BroadCastEntitys;
    }
}
