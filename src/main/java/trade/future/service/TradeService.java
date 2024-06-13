package trade.future.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import trade.future.repository.EventRepository;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TradeService {
    @Autowired
    EventRepository eventRepository;
    UMWebsocketClientImpl umWebSocketStreamClient = new UMWebsocketClientImpl();
    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();

    public Map<String, Object> getKlines(String symbol, String interval, int limit) {
        log.info("getKline >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        UMFuturesClientImpl client = new UMFuturesClientImpl();

        paramMap.put("symbol", symbol);
        paramMap.put("interval", interval);

        String resultStr = client.market().klines(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);
        System.out.println(resultArray);
        resultMap.put("result", resultArray);
        return resultMap;
    }
}