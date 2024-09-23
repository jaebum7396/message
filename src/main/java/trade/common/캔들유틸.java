package trade.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.Num;
import trade.future.model.dto.EventDTO;
import trade.future.model.dto.KlineDTO;
import trade.future.model.entity.KlineEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@Slf4j
public class 캔들유틸 {
    public static BaseBarSeries klineJsonToSeries(String jsonStr){
        String weight        = new JSONObject(jsonStr).getString("x-mbx-used-weight-1m");
        JSONArray jsonArray  = new JSONArray(new JSONObject(jsonStr).get("data").toString());
        BaseBarSeries series = new BaseBarSeries();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray klineArray    = jsonArray.getJSONArray(i);
            KlineEntity klineEntity = jsonArrayToKlineEntity(klineArray);
            Num open                = series.numOf(klineEntity.getOpenPrice());
            Num high                = series.numOf(klineEntity.getHighPrice());
            Num low                 = series.numOf(klineEntity.getLowPrice());
            Num close               = series.numOf(klineEntity.getClosePrice());
            Num volume              = series.numOf(klineEntity.getVolume());
            series.addBar(klineEntity.getEndTime().atZone(ZoneOffset.UTC), open, high, low, close, volume);
        }
        //System.out.println("*************** [현재 가중치 : " + weight + "] ***************");
        return series;
    }
    public static EventDTO convertKlineEventDTO(String event) {
        JSONObject eventDataObj = new JSONObject(event);
        JSONObject eventObj = new JSONObject(eventDataObj.get("data").toString());
        JSONObject klineObj = new JSONObject(eventObj.get("k").toString());
        EventDTO eventDTO = EventDTO.builder()
                .e(eventObj.get("e").toString())
                .E(Long.parseLong(eventObj.get("E").toString()))
                .s(eventObj.get("s").toString())
                .k(KlineDTO.builder()
                        .t(Long.parseLong(klineObj.get("t").toString()))
                        .T(Long.parseLong(klineObj.get("T").toString()))
                        .s(klineObj.get("s").toString())
                        .i(klineObj.get("i").toString())
                        .f(new BigDecimal(klineObj.get("f").toString()))
                        .L(new BigDecimal(klineObj.get("L").toString()))
                        .o(new BigDecimal(klineObj.get("o").toString()))
                        .c(new BigDecimal(klineObj.get("c").toString()))
                        .h(new BigDecimal(klineObj.get("h").toString()))
                        .l(new BigDecimal(klineObj.get("l").toString()))
                        .v(new BigDecimal(klineObj.get("v").toString()))
                        .n(Integer.parseInt(klineObj.get("n").toString()))
                        .x(Boolean.parseBoolean(klineObj.get("x").toString()))
                        .q(new BigDecimal(klineObj.get("q").toString()))
                        .V(new BigDecimal(klineObj.get("V").toString()))
                        .Q(new BigDecimal(klineObj.get("Q").toString()))
                        .B(Integer.parseInt(klineObj.get("B").toString()))
                        .build()
                ).build();
        return eventDTO;
    }
    public static KlineEntity jsonArrayToKlineEntity(JSONArray klineArray) {
        LocalDateTime startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(klineArray.getLong(0)), ZoneOffset.UTC);
        LocalDateTime endTime   = LocalDateTime.ofInstant(Instant.ofEpochMilli(klineArray.getLong(6)), ZoneOffset.UTC);

        return KlineEntity.builder()
            .kLineCd(null) // ID는 자동 생성
            .startTime(startTime)
            .endTime(endTime)
            //.symbol("BTCUSDT") // 심볼은 주어진 데이터에 없으므로 임의로 지정
            .candleInterval("1m") // 인터벌도 임의로 지정
            .firstTradeId(null) // 데이터에 포함되지 않으므로 null로 설정
            .lastTradeId(null) // 데이터에 포함되지 않으므로 null로 설정
            .openPrice(new BigDecimal(klineArray.getString(1)))
            .closePrice(new BigDecimal(klineArray.getString(4)))
            .highPrice(new BigDecimal(klineArray.getString(2)))
            .lowPrice(new BigDecimal(klineArray.getString(3)))
            .volume(new BigDecimal(klineArray.getString(5)))
            .tradeCount(klineArray.getInt(8))
            .isClosed(true) // 데이터에 포함되지 않으므로 임의로 true로 설정
            .quoteAssetVolume(new BigDecimal(klineArray.getString(7)))
            .takerBuyBaseAssetVolume(new BigDecimal(klineArray.getString(9)))
            .takerBuyQuoteAssetVolume(new BigDecimal(klineArray.getString(10)))
            .ignoreField(klineArray.getInt(11))
            .build();
    }
    public static String convertBarToKlineJson(Bar bar, String symbol, String interval) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode rootNode = mapper.createObjectNode();
        ObjectNode dataNode = mapper.createObjectNode();
        ObjectNode kNode = mapper.createObjectNode();

        long endTimeMillis = bar.getEndTime().toInstant().toEpochMilli();
        long startTimeMillis = bar.getBeginTime().toInstant().toEpochMilli();

        dataNode.put("e", "kline");
        dataNode.put("stream", symbol + "@kline_" + interval); // Stream name (symbol@kline_<interval>
        dataNode.put("E", System.currentTimeMillis());
        dataNode.put("s", symbol);

        kNode.put("t", startTimeMillis);
        kNode.put("T", endTimeMillis);
        kNode.put("s", symbol);
        kNode.put("i", interval);
        kNode.put("f", 100); // Placeholder for first trade ID
        kNode.put("L", 200); // Placeholder for last trade ID
        kNode.put("o", formatPrice(bar.getOpenPrice().doubleValue()));
        kNode.put("c", formatPrice(bar.getClosePrice().doubleValue()));
        kNode.put("h", formatPrice(bar.getHighPrice().doubleValue()));
        kNode.put("l", formatPrice(bar.getLowPrice().doubleValue()));
        kNode.put("v", formatVolume(bar.getVolume().doubleValue()));
        kNode.put("n", bar.getTrades()); // Number of trades
        kNode.put("x", true); // Assume the kline is closed
        kNode.put("q", formatVolume(bar.getAmount().doubleValue())); // Quote asset volume
        kNode.put("V", formatVolume(bar.getVolume().doubleValue() / 2)); // Taker buy base asset volume (estimated)
        kNode.put("Q", formatVolume(bar.getAmount().doubleValue() / 2)); // Taker buy quote asset volume (estimated)
        kNode.put("B", "123456"); // Ignore

        dataNode.set("k", kNode);
        rootNode.set("data", dataNode);

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
        } catch (Exception e) {
            e.printStackTrace();
            return "Error converting to JSON";
        }
    }

    private static String formatPrice(double price) {
        return String.format("%.8f", price);
    }

    private static String formatVolume(double volume) {
        return String.format("%.6f", volume);
    }
}
