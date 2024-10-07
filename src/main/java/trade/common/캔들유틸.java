package trade.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.bollinger.PercentBIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.AccumulationDistributionIndicator;
import org.ta4j.core.indicators.volume.ChaikinMoneyFlowIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.num.Num;
import trade.future.model.dto.EventDTO;
import trade.future.model.dto.KlineDTO;
import trade.future.model.entity.KlineEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

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

    public static List<Indicator<Num>> initializeLongIndicators(BaseBarSeries series, int shortMovingPeriod, int longMovingPeriod) {
        List<Indicator<Num>> indicators = new ArrayList<>();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // EMA를 사용 (SMA 대신)
        EMAIndicator shortEMA = new EMAIndicator(closePrice, shortMovingPeriod);
        EMAIndicator longEMA = new EMAIndicator(closePrice, longMovingPeriod);

        int timeFrame = 20; // 볼린저 밴드의 기간
        double k = 2.0; // 표준편차의 배수

        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, shortMovingPeriod);
        BollingerBandsMiddleIndicator middleBBand = new BollingerBandsMiddleIndicator(shortEMA);
        BollingerBandsUpperIndicator upperBBand = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);
        PercentBIndicator percentB = new PercentBIndicator(closePrice, timeFrame, 2.0);

        MACDIndicator macdIndicator = new MACDIndicator(closePrice, shortMovingPeriod, longMovingPeriod);

        indicators.add(macdIndicator);
        indicators.add(lowerBBand);
        indicators.add(middleBBand);
        indicators.add(upperBBand);
        indicators.add(percentB);
        indicators.add(shortEMA);
        indicators.add(longEMA);
        //indicators.add(new RSIIndicator(closePrice, shortMovingPeriod));
        //indicators.add(new StochasticOscillatorKIndicator(series, shortMovingPeriod));
        //indicators.add(new CCIIndicator(series, shortMovingPeriod));
        //indicators.add(new ROCIndicator(closePrice, shortMovingPeriod));

        // Volume 관련 지표 추가
        //indicators.add(new OnBalanceVolumeIndicator(series));
        //indicators.add(new AccumulationDistributionIndicator(series));
        indicators.add(new ChaikinMoneyFlowIndicator(series, longMovingPeriod));

        // 추가적인 단기 모멘텀 지표
        //indicators.add(new WilliamsRIndicator(series, shortMovingPeriod));

        // 주석 처리된 지표들 (필요시 주석 해제)
        //indicators.add(new RelativeATRIndicator(series, shortMovingPeriod, longMovingPeriod));
        indicators.add(new ATRIndicator(series, shortMovingPeriod));  // ATR 추가
        indicators.add(new ADXIndicator(series, longMovingPeriod));
        indicators.add(new PlusDIIndicator(series, longMovingPeriod));
        indicators.add(new MinusDIIndicator(series, longMovingPeriod));
        // indicators.add(new RSIIndicator(closePrice, longMovingPeriod));
        // indicators.add(new CMOIndicator(closePrice, longMovingPeriod));
        // indicators.add(new ParabolicSarIndicator(series));

        return indicators;
    }

    public static List<Indicator<Num>> initializeShortIndicators(BaseBarSeries series, int shortMovingPeriod, int longMovingPeriod) {
        List<Indicator<Num>> indicators = new ArrayList<>();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 기존 지표 유지
        EMAIndicator shortEMA = new EMAIndicator(closePrice, shortMovingPeriod);
        EMAIndicator longEMA = new EMAIndicator(closePrice, longMovingPeriod);
        MACDIndicator macdIndicator = new MACDIndicator(closePrice, shortMovingPeriod, longMovingPeriod);

        // 볼린저 밴드 관련 지표 (숏 전략에 중요)
        int bbPeriod = 20;
        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, bbPeriod);
        BollingerBandsMiddleIndicator middleBBand = new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, bbPeriod));
        BollingerBandsUpperIndicator upperBBand = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);
        PercentBIndicator percentB = new PercentBIndicator(closePrice, bbPeriod, 2.0);

        // RSI (과매수 상태 감지에 유용)
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        // 스토캐스틱 오실레이터 (과매수 상태 및 반전 신호 감지)
        StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, 14);
        StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);

        // 추가 지표
        CCIIndicator cci = new CCIIndicator(series, 20); // 과매수/과매도 상태 감지
        ROCIndicator roc = new ROCIndicator(closePrice, shortMovingPeriod); // 모멘텀 측정
        WilliamsRIndicator williamsR = new WilliamsRIndicator(series, 14); // 과매수/과매도 및 반전 감지

        // 기존 지표 추가
        indicators.add(macdIndicator);
        indicators.add(percentB);
        indicators.add(shortEMA);
        indicators.add(longEMA);
        indicators.add(new ChaikinMoneyFlowIndicator(series, longMovingPeriod));
        indicators.add(new ATRIndicator(series, shortMovingPeriod));
        indicators.add(new ADXIndicator(series, longMovingPeriod));
        indicators.add(new PlusDIIndicator(series, longMovingPeriod));
        indicators.add(new MinusDIIndicator(series, longMovingPeriod));

        // 새로운 지표 추가
        indicators.add(middleBBand);
        indicators.add(upperBBand);
        indicators.add(lowerBBand);
        indicators.add(rsi);
        indicators.add(stochK);
        indicators.add(stochD);
        indicators.add(cci);
        indicators.add(roc);
        indicators.add(williamsR);

        // 추가적인 반전 관련 지표
        indicators.add(new OnBalanceVolumeIndicator(series)); // 거래량 동향 파악
        indicators.add(new AccumulationDistributionIndicator(series)); // 가격과 거래량의 관계
        indicators.add(new ParabolicSarIndicator(series)); // 추세 반전 감지

        return indicators;
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
