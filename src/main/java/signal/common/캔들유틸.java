package signal.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.time.*;

@Component
@Slf4j
public class 캔들유틸 {

    public static BaseBar jsonArrayToBar(JSONArray klineArray) {
        LocalDateTime endTime   = LocalDateTime.ofInstant(Instant.ofEpochMilli(klineArray.getLong(6)), ZoneOffset.UTC);

        ZonedDateTime endTimeZone = endTime.atZone(ZoneOffset.UTC);

        Num open = DecimalNum.valueOf(new BigDecimal(klineArray.getString(1)));
        Num high = DecimalNum.valueOf(new BigDecimal(klineArray.getString(2)));
        Num low = DecimalNum.valueOf(new BigDecimal(klineArray.getString(3)));
        Num close = DecimalNum.valueOf(new BigDecimal(klineArray.getString(4)));
        Num volume = DecimalNum.valueOf(new BigDecimal(klineArray.getString(5)));

        return new BaseBar(Duration.ofMinutes(5), endTimeZone, open, high, low, close, volume, DecimalNum.valueOf(0));
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

    public static int findIndexForTime(BaseBarSeries series, ZonedDateTime time) {
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            Bar bar = series.getBar(i);
            if (bar.getEndTime().equals(time)) {
                return i;
            }
            if (bar.getEndTime().isAfter(time)) {
                return i - 1;  // Return the index of the previous bar
            }
        }
        return series.getEndIndex();  // If not found, return the last index
    }

    private static String formatPrice(double price) {
        return String.format("%.8f", price);
    }

    private static String formatVolume(double volume) {
        return String.format("%.6f", volume);
    }
}
