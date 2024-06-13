package trade.future.service;

import com.binance.connector.client.WebSocketApiClient;
import com.binance.connector.client.impl.WebSocketApiClientImpl;
import com.binance.connector.client.utils.signaturegenerator.HmacSignatureGenerator;
import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.binance.connector.futures.client.utils.WebSocketCallback;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;
import trade.common.CommonUtils;
import trade.configuration.MyWebSocketClientImpl;
import trade.future.model.entity.*;
import trade.future.model.enums.ADX_GRADE;
import trade.future.repository.EventRepository;
import trade.future.repository.PositionRepository;
import trade.future.repository.TradingRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static trade.common.CommonUtils.parseKlineEntity;

@Slf4j
@Service
public class FutureService {
    public FutureService(
          @Value("${binance.real.api.key}") String BINANCE_REAL_API_KEY
        , @Value("${binance.real.secret.key}") String BINANCE_REAL_SECRET_KEY
        , @Value("${binance.testnet.api.key}") String BINANCE_TEST_API_KEY
        , @Value("${binance.testnet.secret.key}") String BINANCE_TEST_SECRET_KEY) {
        boolean DEV_MODE = false;
        if(DEV_MODE){
            this.BINANCE_API_KEY = BINANCE_TEST_API_KEY;
            this.BINANCE_SECRET_KEY = BINANCE_TEST_SECRET_KEY;
            this.BASE_URL = BASE_URL_TEST;
        }else{
            this.BINANCE_API_KEY = BINANCE_REAL_API_KEY;
            this.BINANCE_SECRET_KEY = BINANCE_REAL_SECRET_KEY;
            this.BASE_URL = BASE_URL_REAL;
        }
        //System.out.println(BINANCE_API_KEY + " " + BINANCE_SECRET_KEY);
        this.exchangeInfo = new JSONObject(umFuturesClientImpl.market().exchangeInfo());
        this.symbols = new JSONArray(String.valueOf(exchangeInfo.get("symbols")));
        //System.out.println("exchangeInfo.get(\"symbols\") : " + symbols);
    }
    public String BINANCE_API_KEY;
    public String BINANCE_SECRET_KEY;

    public JSONObject exchangeInfo;
    public JSONArray symbols;
    public String BASE_URL;
    public static final String BASE_URL_TEST = "https://testnet.binance.vision";
    public static final String BASE_URL_REAL = "wss://ws-api.binance.com:443/ws-api/v3";

    @Autowired TechnicalIndicatorCalculator technicalIndicatorCalculator;

    @Autowired EventRepository eventRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired TradingRepository tradingRepository;
    @Autowired MyWebSocketClientImpl umWebSocketStreamClient;
    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();
    private final WebSocketCallback noopCallback = msg -> {};
    private final WebSocketCallback openCallback = this::onOpenCallback;
    private final WebSocketCallback onMessageCallback = this::onMessageCallback;
    private final WebSocketCallback closeCallback = this::onCloseCallback;
    private final WebSocketCallback failureCallback = this::onFailureCallback;

    // 원하는 형식의 날짜 포맷 지정
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    int failureCount = 0;
    private HashMap<String, List<KlineEntity>> klines = new HashMap<String, List<KlineEntity>>();
    private HashMap<String, BaseBarSeries> seriesMap = new HashMap<String, BaseBarSeries>();
    private static final int WINDOW_SIZE = 500; // For demonstration purposes

    @Transactional
    public void onOpenCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        System.out.println("[OPEN] >>>>> " + streamId + " 번 스트림을 오픈합니다.");
        tradingRepository.save(tradingEntity);
        log.info("tradingSaved >>>>> "+tradingEntity.getTradingCd() + " : " + tradingEntity.getSymbol() + " / " + tradingEntity.getStreamId());
    }

    @Transactional
    public void onCloseCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        System.out.println("[CLOSE] >>>>> " + streamId + " 번 스트림을 클로즈합니다. ");
        tradingEntity.setTradingStatus("CLOSE");
        tradingRepository.save(tradingEntity);
    }

    @Transactional
    public void onFailureCallback(String streamId) {
        System.out.println("[FAILURE] >>>>> " + streamId + " 예기치 못하게 스트림이 실패하였습니다. ");
        Optional<TradingEntity> tradingEntityOpt = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)));
        if(tradingEntityOpt.isPresent()){
            System.out.println("[RECOVER] >>>>> "+tradingEntityOpt.get().toString());
            TradingEntity tradingEntity = tradingEntityOpt.get();
            tradingEntity.setTradingStatus("CLOSE");
            tradingRepository.save(tradingEntity);
            //System.out.println("[CLOSE] >>>>> " + streamId + " 번 스트림을 클로즈합니다. ");
            failureCount++;
            if(failureCount>4){
                System.out.println("[RECOVER-ERR] >>>>> "+streamId +" 번 스트림을 복구하지 못했습니다.");
                //onFailureCallback(streamId);
            }else{
                ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
                Runnable task = () -> {
                    TradingEntity currentTrading = autoTradeStreamOpen(tradingEntity);
                    System.out.println("[RECOVER] >>>>> "+streamId +" 번 스트림을 "+currentTrading.getStreamId() + " 번으로 복구 합니다.");
                };
                // 5초 후에 task 실행
                scheduler.schedule(task, 5, TimeUnit.SECONDS);
            }
        } else {
            System.out.println("[RECOVER-ERR] >>>>> "+streamId +" 번 스트림을 복구하지 못했습니다.");
            //onFailureCallback(streamId);
        }
    }

    @Transactional
    public void onMessageCallback(String event){
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 3;
        int initialDelayMillis = 1000;

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                //System.out.println("event : " + event);
                JSONObject eventData = new JSONObject(event);
                //System.out.println("eventData : " + eventData);
                JSONObject eventObj = new JSONObject(eventData.get("data").toString());
                switch (String.valueOf(eventObj.get("e"))) {
                    case "kline":
                        klineProcess(event);
                        break;
                    case "forceOrder":
                        //System.out.println("forceOrder 이벤트 발생");
                        System.out.println("event : " + event);
                        break;
                    case "depthUpdate":
                        //System.out.println("depthUpdate 이벤트 발생");
                        System.out.println("event : " + event);
                        break;
                    case "aggTrade":
                        //System.out.println("aggTrade 이벤트 발생");
                        System.out.println("event : " + event);
                        break;
                    default:
                        //System.out.println("기타 이벤트 발생");
                        System.out.println("event : " + event);
                        break;
                }
                break;
            } catch (Exception e) {
                e.printStackTrace();
                if (retry >= maxRetries - 1) {
                    // 최대 재시도 횟수에 도달한 경우 예외를 던짐
                    throw e;
                }

                // 재시도 간격을 설정한 시간만큼 대기
                try {
                    Thread.sleep(initialDelayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

                // 재시도 간격을 증가시킴 (예: 백오프 전략)
                initialDelayMillis *= 2;
            }
        }
    }

    private void klineProcess(String event){
        JSONObject eventObj = new JSONObject(event);
        JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
        JSONObject klineObj = new JSONObject(klineEventObj.get("k").toString());
        boolean isFinal = klineObj.getBoolean("x");

        String seriesNm = String.valueOf(eventObj.get("stream"));
        String symbol = seriesNm.substring(0, seriesNm.indexOf("@"));
        String interval = seriesNm.substring(seriesNm.indexOf("_") + 1);

        BaseBarSeries series = seriesMap.get(seriesNm);
        if (isFinal) {
            System.out.println("event : " + event);
            //캔들데이터 to EventEntity
            //EventEntity eventEntity = CommonUtils.convertKlineEventDTO(event).toEntity();
            // 트레이딩 엔티티 조회
            TradingEntity tradingEntity = getTradingEntity(symbol);
            // klineEvent를 데이터베이스에 저장
            EventEntity klineEventEntity = saveKlineEvent(event, tradingEntity);

            ZonedDateTime closeTime = CommonUtils.convertTimestampToDateTime(klineObj.getLong("T")).atZone(ZoneOffset.UTC);
            ZonedDateTime kstEndTime = closeTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
            String formattedEndTime = formatter.format(kstEndTime);

            Num open = series.numOf(klineObj.getDouble("o"));
            Num high = series.numOf(klineObj.getDouble("h"));
            Num low = series.numOf(klineObj.getDouble("l"));
            Num close = series.numOf(klineObj.getDouble("c"));
            Num volume = series.numOf(klineObj.getDouble("v"));
            //series.addBar(closeTime, open, high, low, close, volume);
            Bar newBar = new BaseBar(Duration.ofMinutes(15), closeTime, open, high, low, close, volume ,null);
            series.addBar(newBar, true);

            TechnicalIndicatorReportEntity technicalIndicatorReportEntity = technicalIndicatorCalculate(symbol, interval);
            if (technicalIndicatorReportEntity.getAdxSignal() == 1){
                System.out.println("진입시그널");
                klineEventEntity.getKlineEntity().setTechnicalIndicatorReportEntity(technicalIndicatorReportEntity);
                /*PositionEntity entryPosition = klineEventEntity.getKlineEntity().getPositionEntity();
                entryPosition.setPositionStatus("OPEN");*/
            }
            eventRepository.save(klineEventEntity);

        }
    }

     /*@Transactional
    public void onMessageCallback(String event){
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 3;
        int initialDelayMillis = 1000;

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                //System.out.println("event : " + event);
                JSONObject eventObj = new JSONObject(event);
                JSONObject eventObj = new JSONObject(eventObj.get("data").toString());
                if(String.valueOf(eventObj.get("e")).equals("kline")){
                    // 트랜잭션 시작
                    // 트랜잭션 내에서 수행할 작업들
                    KlineEventEntity klineEvent = CommonUtils.convertKlineEventDTO(event).toEntity();
                    String symbol = klineEvent.getKlineEntity().getSymbol();

                    // 트레이딩 엔티티 조회
                    TradingEntity tradingEntity = getTradingEntity(symbol);
                    BigDecimal QuoteAssetVolumeStandard = tradingEntity.getQuoteAssetVolumeStandard();
                    BigDecimal averageQuoteAssetVolume = tradingEntity.getAverageQuoteAssetVolume();

                    // klineEvent를 역직렬화하여 데이터베이스에 저장
                    KlineEventEntity klineEventEntity = saveKlineEvent(event, tradingEntity);
                    BigDecimal quoteAssetVolume = klineEventEntity.getKlineEntity().getQuoteAssetVolume();

                    // 포지션 확인 및 저장
                    checkAndSavePosition(klineEventEntity, tradingEntity, QuoteAssetVolumeStandard, averageQuoteAssetVolume, quoteAssetVolume);

                    // 목표가에 도달한 KlineEvent들을 업데이트
                    updateGoalAchievedKlineEvent(klineEventEntity);

                    // 트랜잭션 성공 시 반복문 종료
                } else if(String.valueOf(eventObj.get("e")).equals("forceOrder")){
                    System.out.println("강제 청산 이벤트 발생");
                    System.out.println("event : " + event);
                } else {
                    System.out.println("event : " + event);
                }
                break;
            } catch (Exception e) {
                e.printStackTrace();
                if (retry >= maxRetries - 1) {
                    // 최대 재시도 횟수에 도달한 경우 예외를 던짐
                    throw e;
                }

                // 재시도 간격을 설정한 시간만큼 대기
                try {
                    Thread.sleep(initialDelayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

                // 재시도 간격을 증가시킴 (예: 백오프 전략)
                initialDelayMillis *= 2;
            }
        }
    }*/

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventEntity saveKlineEvent(String event, TradingEntity tradingEntity) {
        EventEntity eventEntity = null;
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 3;
        int initialDelayMillis = 1000;
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                eventEntity = CommonUtils.convertKlineEventDTO(event).toEntity();
                int goalPricePercent = tradingEntity.getGoalPricePercent();
                int leverage = tradingEntity.getLeverage();

                PositionEntity positionEntity = PositionEntity.builder()
                        .positionStatus("NONE")
                        .goalPricePercent(goalPricePercent)
                        .klineEntity(eventEntity.getKlineEntity())
                        .plusGoalPrice(
                                CommonUtils.calculateGoalPrice(
                                        eventEntity.getKlineEntity().getClosePrice(), "LONG", leverage, goalPricePercent))
                        .minusGoalPrice(
                                CommonUtils.calculateGoalPrice(
                                        eventEntity.getKlineEntity().getClosePrice(), "SHORT", leverage, goalPricePercent))
                        .build();

                if (tradingEntity.getFluctuationRate().compareTo(BigDecimal.ZERO) > 0) {
                    positionEntity.setPositionSide("LONG");
                    //System.out.println("변동률 :" + tradingEntity.getFluctuationRate()+ " / 포지션 : LONG");
                } else {
                    positionEntity.setPositionSide("SHORT");
                    //System.out.println("변동률 :" + tradingEntity.getFluctuationRate()+ " / 포지션 : SHORT");
                }

                eventEntity.setTradingEntity(tradingEntity);
                eventEntity.getKlineEntity().setPositionEntity(positionEntity);
                eventEntity = eventRepository.save(eventEntity);
                break;
            } catch (Exception e) {
                e.printStackTrace();
                if (retry >= maxRetries - 1) {
                    // 최대 재시도 횟수에 도달한 경우 예외를 던짐
                    throw e;
                }
                // 재시도 간격을 설정한 시간만큼 대기
                try {
                    Thread.sleep(initialDelayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

                // 재시도 간격을 증가시킴 (예: 백오프 전략)
                initialDelayMillis *= 2;
            }
        }
        return eventEntity;
    }

    private TradingEntity getTradingEntity(String symbol) {
        return tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN")
                .orElseThrow(() -> new RuntimeException("트레이딩이 존재하지 않습니다."));
    }

    public void streamClose(int streamId) {
        umWebSocketStreamClient.closeConnection(streamId);
    }

    @Transactional
    public void autoTradingClose() {
        List<TradingEntity> tradingEntityList = tradingRepository.findAll();
        tradingEntityList.stream().forEach(tradingEntity -> {
            if(tradingEntity.getTradingStatus().equals("OPEN")){
                tradingEntity.setTradingStatus("CLOSE");
                tradingRepository.save(tradingEntity);
                streamClose(tradingEntity.getStreamId());
            }
        });
        //umWebSocketStreamClient.closeAllConnections();
    }

    @Transactional
    public Map<String, Object> autoTradingOpen(String symbolParam, String interval, int leverage, int goalPricePercent, int stockSelectionCount, BigDecimal quoteAssetVolumeStandard) throws Exception {
        log.info("autoTrading >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> selectedStockList = (List<Map<String, Object>>) getStockSelection(stockSelectionCount).get("overlappingData");

        selectedStockList.parallelStream().forEach(selectedStock -> {
            String symbol = String.valueOf(selectedStock.get("symbol"));
            Optional<TradingEntity> tradingEntityOpt = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");

            // 해당 심볼의 트레이딩이 없으면 트레이딩을 시작합니다.
            if(tradingEntityOpt.isEmpty()) {
                // 해당 페어의 평균 거래량을 구합니다.
                //BigDecimal averageQuoteAssetVolume = getKlinesAverageQuoteAssetVolume( (JSONArray)getKlines(symbol, interval, 500).get("result"), interval);
                TradingEntity tradingEntity = TradingEntity.builder()
                        .symbol(symbol)
                        .tradingStatus("OPEN")
                        .candleInterval(interval)
                        .leverage(leverage)
                        .goalPricePercent(goalPricePercent)
                        .stockSelectionCount(stockSelectionCount)
                        .quoteAssetVolumeStandard(quoteAssetVolumeStandard)
                        //.averageQuoteAssetVolume(averageQuoteAssetVolume)
                        .fluctuationRate(new BigDecimal(String.valueOf(selectedStock.get("priceChangePercent"))))
                        //.fluctuationRate(new BigDecimal(2))
                        .build();
                autoTradeStreamOpen(tradingEntity);
            }
        });
        return resultMap;
    }

    public TradingEntity autoTradeStreamOpen(TradingEntity tradingEntity) {
        getKlines(tradingEntity.getSymbol(), tradingEntity.getCandleInterval(), 500);
        log.info("klineStreamOpen >>>>> ");
        ArrayList<String> streams = new ArrayList<>();

        String klineStreamName = tradingEntity.getSymbol().toLowerCase() + "@kline_" + tradingEntity.getCandleInterval();
        streams.add(klineStreamName);

        String forceOrderStreamName = tradingEntity.getSymbol().toLowerCase() + "@forceOrder";
        streams.add(forceOrderStreamName);

        /*String depthStreamName = tradingEntity.getSymbol().toLowerCase() + "@depth";
        streams.add(depthStreamName);*/

        /*String aggTradeStreamName = tradingEntity.getSymbol().toLowerCase() + "@aggTrade";
        streams.add(aggTradeStreamName);*/

        String allMarketForceOrderStreamName = "!forceOrder@arr";
        //streams.add(allMarketForceOrderStreamName);

        tradingEntity = umWebSocketStreamClient.combineStreams(tradingEntity, streams, openCallback, onMessageCallback, closeCallback, failureCallback);
        return tradingEntity;
    }

    /*@Transactional
    public void updateGoalAchievedKlineEvent(EventEntity eventEntity) {
        List<EventEntity> goalAchievedPlusList = eventRepository
                .findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(
                        eventEntity.getKlineEntity().getSymbol()
                        , eventEntity.getKlineEntity().getClosePrice());

        goalAchievedPlusList.stream().forEach(goalAchievedPlus -> {
            goalAchievedPlus.getKlineEntity().getPositionEntity().setGoalPriceCheck(true);
            goalAchievedPlus.getKlineEntity().getPositionEntity().setGoalPricePlus(true);

            Optional<PositionEntity> currentPositionOpt = Optional.ofNullable(goalAchievedPlus.getKlineEntity().getPositionEntity());

            if(currentPositionOpt.isPresent()){
                PositionEntity currentPosition = currentPositionOpt.get();
                if(currentPosition.getPositionStatus().equals("OPEN")) {
                    System.out.println("목표가 도달(long) : " + goalAchievedPlus.getKlineEntity().getSymbol()
                            + " 현재가 : " + eventEntity.getKlineEntity().getClosePrice()
                            + "/ 목표가 : " + goalAchievedPlus.getKlineEntity().getPositionEntity().getPlusGoalPrice());
                    currentPosition.setPositionStatus("CLOSE");
                    //System.out.println(currentPosition.toString());
                    System.out.println(">>>>> 포지션을 종료합니다. " + goalAchievedPlus.getKlineEntity().getSymbol());

                    // 트레이딩을 닫습니다.
                    TradingEntity tradingEntity = goalAchievedPlus.getTradingEntity();
                    streamClose(tradingEntity.getStreamId());
                    //tradingEntity.setTradingStatus("CLOSE");
                    //tradingRepository.save(tradingEntity);

                    //트레이딩을 다시 시작합니다.
                    try {
                        autoTradingOpen(tradingEntity.getSymbol(),tradingEntity.getCandleInterval(), tradingEntity.getLeverage(), tradingEntity.getGoalPricePercent(), tradingEntity.getStockSelectionCount(), tradingEntity.getQuoteAssetVolumeStandard());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            eventRepository.save(goalAchievedPlus);
        });

        List<EventEntity> goalAchievedMinusList = eventRepository
                .findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(
                        eventEntity.getKlineEntity().getSymbol()
                        , eventEntity.getKlineEntity().getClosePrice());

        goalAchievedMinusList.stream().forEach(goalAchievedMinus -> {
            goalAchievedMinus.getKlineEntity().getPositionEntity().setGoalPriceCheck(true);
            goalAchievedMinus.getKlineEntity().getPositionEntity().setGoalPriceMinus(true);

            Optional<PositionEntity> currentPositionOpt = Optional.ofNullable(goalAchievedMinus.getKlineEntity().getPositionEntity());

            if(currentPositionOpt.isPresent()){
                PositionEntity currentPosition = currentPositionOpt.get();
                if(currentPosition.getPositionStatus().equals("OPEN")){
                    System.out.println("목표가 도달(short) : " + goalAchievedMinus.getKlineEntity().getSymbol()
                            + " 현재가 : " + eventEntity.getKlineEntity().getClosePrice()
                            + "/ 목표가 : " + goalAchievedMinus.getKlineEntity().getPositionEntity().getMinusGoalPrice());
                    currentPosition.setPositionStatus("CLOSE");
                    System.out.println(">>>>> 포지션을 종료합니다. " + goalAchievedMinus.getKlineEntity().getSymbol());
                    System.out.println(currentPosition.toString());

                    // 트레이딩을 닫습니다.
                    TradingEntity tradingEntity = goalAchievedMinus.getTradingEntity();
                    tradingEntity.setTradingStatus("CLOSE");
                    tradingRepository.save(tradingEntity);

                    // 소켓 스트림을 닫습니다.
                    streamClose(tradingEntity.getStreamId());

                    //트레이딩을 다시 시작합니다.
                    try {
                        autoTradingOpen(tradingEntity.getSymbol(), tradingEntity.getCandleInterval(), tradingEntity.getLeverage(), tradingEntity.getGoalPricePercent(), tradingEntity.getStockSelectionCount(), tradingEntity.getQuoteAssetVolumeStandard());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            eventRepository.save(goalAchievedMinus);
        });
    }*/

    public BigDecimal getKlinesAverageQuoteAssetVolume(JSONArray klineArray, String interval) {
        log.info("getKlinesAverageQuoteAssetVolume >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        // BigDecimal을 사용하여 8번째 데이터인 "quoteAssetVolume"의 합계를 저장할 변수
        BigDecimal totalQuoteAssetVolume = BigDecimal.ZERO;

        // JSONArray를 순회하며 객체를 추출
        for (int i = 0; i < klineArray.length(); i++) {
            JSONArray row = klineArray.getJSONArray(i);
            //0: Open time, 1: Open, 2: High, 3: Low, 4: Close, 5: Volume, 6: Close time, 7: Quote asset volume, 8: Number of trades, 9: Taker buy base asset volume, 10: Taker buy quote asset volume, 11: Ignore
            BigDecimal quoteAssetVolume = new BigDecimal(row.getString(7));
            totalQuoteAssetVolume = totalQuoteAssetVolume.add(quoteAssetVolume);
        }

        // 평균 계산 (totalQuoteAssetVolume / arrayLength)
        BigDecimal averageQuoteAssetVolume = totalQuoteAssetVolume.divide(new BigDecimal(klineArray.length()));
        System.out.println(interval+"("+klineArray.length()+") 평균 거래량 : " + averageQuoteAssetVolume);
        return averageQuoteAssetVolume;
    }

    public Map<String, Object> getStockSelection(int limit) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);

        // 거래량(QuoteVolume - 기준 화폐)을 기준으로 내림차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByQuoteVolume = getSort(resultArray, "quoteVolume", "DESC", limit);
        // 변동폭(priceChangePercent)을 기준으로 내림차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByPriceChangePercentDESC = getSort(resultArray, "priceChangePercent", "DESC", limit);
        // 변동폭(priceChangePercent)을 기준으로 오름차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByPriceChangePercentASC = getSort(resultArray, "priceChangePercent", "ASC", limit);

        // 겹치는 데이터 찾기
        List<Map<String, Object>> overlappingData = findOverlappingData(sortedByQuoteVolume, sortedByPriceChangePercentDESC, sortedByPriceChangePercentASC);

        resultMap.put("sortedByQuoteVolume", sortedByQuoteVolume);
        resultMap.put("sortedByPriceChangePercentDESC", sortedByPriceChangePercentDESC);
        resultMap.put("sortedByPriceChangePercentASC", sortedByPriceChangePercentASC);
        resultMap.put("overlappingData", overlappingData);

        return resultMap;
    }

    public List<Map<String, Object>> getSort(JSONArray resultArray, String sortBy, String orderBy, int limit) throws Exception {
        log.info("getSort >>>>> sortBy : {}, orderBy : {}, limit : {}", sortBy, orderBy, limit);
        // JSON 데이터를 Java 객체로 파싱하여 리스트에 저장
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (int i = 0; i < resultArray.length(); i++) {
            JSONObject item = resultArray.getJSONObject(i);
            itemList.add(item.toMap());
        }

        // sortBy 기준으로 orderBy(DESC/ASC)으로 정렬한 복사본
        List<Map<String, Object>> sortedBy = new ArrayList<>(itemList);
        Collections.sort(sortedBy, (item1, item2) -> {
            double volume1 = Double.parseDouble(item1.get(sortBy).toString());
            double volume2 = Double.parseDouble(item2.get(sortBy).toString());
            if (orderBy.equals("DESC")) return Double.compare(volume2, volume1);
            else return Double.compare(volume1, volume2);
        });

        // 상위 5개 항목 선택
        List<Map<String, Object>> topLimitItems = sortedBy.stream()
                .filter(item -> !item.get("symbol").toString().toLowerCase().contains("busd"))
                .limit(limit)
                .collect(Collectors.toList());

        topLimitItems.forEach(item -> {
            System.out.println(item.get("symbol") + " : " + item.get(sortBy));
        });
        return topLimitItems;
    }

    // 2개 이상의 리스트에 포함되는 데이터 찾기
    private List<Map<String, Object>> findOverlappingData(List<Map<String, Object>> list1, List<Map<String, Object>> list2, List<Map<String, Object>> list3) {
        log.info("findOverlappingData >>>>>");
        List<Map<String, Object>> overlappingData = new ArrayList<>();

        // 각 리스트에서 겹치는 데이터를 찾아서 overlappingData 리스트에 추가
        for (Map<String, Object> item1 : list1) {
            boolean isItemInList2 = list2.stream().anyMatch(item2 -> item2.get("symbol").equals(item1.get("symbol")));
            boolean isItemInList3 = list3.stream().anyMatch(item3 -> item3.get("symbol").equals(item1.get("symbol")));

            if (isItemInList2 || isItemInList3) {
                overlappingData.add(item1); // 2개 이상의 리스트에 포함된 경우 추가
                System.out.println(
                    item1.get("symbol")+ " : " +
                        "거래량(" + CommonUtils.getDollarFormat(String.valueOf(item1.get("quoteVolume")))+ ")" +
                        ", 변동폭(" + item1.get("priceChangePercent")+"%)"
                );
            }
        }
        return overlappingData;
    }

    public Map<String, Object> autoTradingInfo() throws Exception {
        log.info("autoTradingInfo >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        List<TradingEntity> tradingEntityList = tradingRepository.findAll();
        tradingEntityList = tradingEntityList.stream().filter(tradingEntity -> tradingEntity.getTradingStatus().equals("OPEN")).collect(Collectors.toList());
        resultMap.put("tradingEntityList", tradingEntityList);
        return resultMap;
    }

    public Map<String, Object> accountInfo() throws Exception {
        log.info("accountInfo >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        //System.out.println(umFuturesClientImpl.account().accountInformation());
        System.out.println(BINANCE_API_KEY + " " + BINANCE_SECRET_KEY);
        HmacSignatureGenerator signatureGenerator = new HmacSignatureGenerator(BINANCE_SECRET_KEY);
        WebSocketApiClient client = new WebSocketApiClientImpl(BINANCE_API_KEY, signatureGenerator, BASE_URL);
        client.connect(((event) -> {
            System.out.println(event + "\n");
        }));
        return resultMap;
    }

    public Map<String, Object> getKlines(String symbol, String interval, int limit) {
        long startTime = System.currentTimeMillis(); // 시작 시간 기록
        log.info("getKline >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        UMFuturesClientImpl client = new UMFuturesClientImpl();

        paramMap.put("symbol", symbol);
        paramMap.put("interval", interval);
        paramMap.put("limit", limit);

        String resultStr = client.market().klines(paramMap);

        JSONArray jsonArray = new JSONArray(resultStr);
        List<KlineEntity> klineEntities = new ArrayList<>();
        BaseBarSeries series = new BaseBarSeries();
        series.setMaximumBarCount(WINDOW_SIZE);
        seriesMap.put(symbol.toLowerCase() + "@kline_" + interval, series);

        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray klineArray = jsonArray.getJSONArray(i);
            KlineEntity klineEntity = parseKlineEntity(klineArray);
            System.out.println(klineArray);
            klineEntities.add(klineEntity);

            Num open = series.numOf(klineEntity.getOpenPrice());
            Num high = series.numOf(klineEntity.getHighPrice());
            Num low = series.numOf(klineEntity.getLowPrice());
            Num close = series.numOf(klineEntity.getClosePrice());
            Num volume = series.numOf(klineEntity.getVolume());

            series.addBar(klineEntity.getEndTime().atZone(ZoneOffset.UTC), open, high, low, close, volume);

            if(i!=0){
                technicalIndicatorCalculate(symbol, interval);
            }
        }
        klines.put(symbol, klineEntities);
        resultMap.put("result", klineEntities);

        long endTime = System.currentTimeMillis(); // 종료 시간 기록
        long elapsedTime = endTime - startTime; // 실행 시간 계산
        System.out.println("소요시간 : " + elapsedTime + " milliseconds");
        return resultMap;
    }

    private BigDecimal getTickSize(String symbol) {
        JSONArray symbols = getSymbols(exchangeInfo);
        JSONObject symbolInfo = getSymbolInfo(symbols, symbol);
        JSONObject priceFilter = getFilterInfo(symbolInfo, "PRICE_FILTER");
        return new BigDecimal(priceFilter.getString("tickSize"));
    }

    private JSONObject getExchangeInfo() {
        return new JSONObject(umFuturesClientImpl.market().exchangeInfo());
    }
    private JSONArray getSymbols(JSONObject exchangeInfo) {
        return new JSONArray(String.valueOf(exchangeInfo.get("symbols")));
    }

    private JSONObject getSymbolInfo(JSONArray symbols, String symbol) {
        for (int i = 0; i < symbols.length(); i++) {
            JSONObject symbolObject = symbols.getJSONObject(i);
            String findSymbol = symbolObject.getString("symbol");
            if(findSymbol.equals(symbol)){
                return symbolObject;
            }
        }
        return null;
    }

    private JSONObject getFilterInfo(JSONObject symbolInfo, String filterType) {
        JSONArray filters = symbolInfo.getJSONArray("filters");
        for (int i = 0; i < filters.length(); i++) {
            JSONObject filter = filters.getJSONObject(i);
            String filterTypeValue = filter.getString("filterType");
            if(filterTypeValue.equals(filterType)){
                return filter;
            }
        }
        return null;
    }

    private TechnicalIndicatorReportEntity technicalIndicatorCalculate(String symbol, String interval) {
        BaseBarSeries series = seriesMap.get(symbol.toLowerCase() + "@kline_" + interval);
        // Define indicators
        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // Calculate SMA
        SMAIndicator sma = new SMAIndicator(closePrice, 7);
        // Calculate EMA
        EMAIndicator ema = new EMAIndicator(closePrice, 7);
        // Calculate Bollinger Bands
        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, 21);
        BollingerBandsMiddleIndicator middleBBand = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsUpperIndicator upperBBand = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);
        // Calculate RSI
        RSIIndicator rsi = new RSIIndicator(closePrice, 6);
        // Calculate MACD
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);

        // Determine current trend
        String currentTrend = technicalIndicatorCalculator.determineTrend(series, sma);

        // 포맷 적용하여 문자열로 변환
        ZonedDateTime utcEndTime = series.getBar(series.getEndIndex()).getEndTime();
        ZonedDateTime kstEndTime = utcEndTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
        String formattedEndTime = formatter.format(kstEndTime);
        System.out.println("[캔들종료시간] : "+symbol+"/"+ formattedEndTime);

        BigDecimal tickSize = getTickSize(symbol.toUpperCase());
        //adx
        double currentAdx  = technicalIndicatorCalculator.calculateADX(series, 14, series.getEndIndex());
        double previousAdx = technicalIndicatorCalculator.calculateADX(series, 14, series.getEndIndex()-1);
        //di
        double plusDi = technicalIndicatorCalculator.calculatePlusDI(series, 14, series.getEndIndex());
        double minusDi = technicalIndicatorCalculator.calculateMinusDI(series, 14, series.getEndIndex());
        //direction
        String direction = technicalIndicatorCalculator.getDirection(series, 14, series.getEndIndex());

        double adxGap = currentAdx - previousAdx;
        /*if (adxGap > 0) {
            System.out.println("추세증가 : " + adxGap);
        } else if (adxGap < 0) {
            System.out.println("추세감소 : " + adxGap);
        }*/
        ADX_GRADE currentAdxGrade  = technicalIndicatorCalculator.calculateADXGrade(currentAdx);
        ADX_GRADE previousAdxGrade = technicalIndicatorCalculator.calculateADXGrade(previousAdx);

        int adxSignal = 0;
        if(currentAdxGrade.getGrade() - previousAdxGrade.getGrade() > 0){
            if (previousAdxGrade.getGrade() == 1 && currentAdxGrade.getGrade() == 2) {
                System.out.println("!!! 포지션 진입 시그널");
                adxSignal = 1;
            }
            System.out.println("추세등급 증가: " + (previousAdxGrade +" > "+ currentAdxGrade));
            System.out.println("방향(DI기준): " + direction);
        } else if(currentAdxGrade.getGrade() - previousAdxGrade.getGrade() < 0){
            System.out.println("추세등급 감소: " + previousAdxGrade +" > "+ currentAdxGrade);
            System.out.println("방향(DI기준): " + direction);
        } else {
            System.out.println("추세등급 유지: " + currentAdxGrade);
            System.out.println("방향(DI기준): " + direction);
        }
        System.out.println("방향(MA기준): " + currentTrend);

        /*if(currentAdx <= 20){
            System.out.println("횡보 : adx(" + currentAdx+ ")");
        }
        if(currentAdx > 20 && currentAdx <= 30){
            System.out.println("약한추세 : adx(" + currentAdx+ ")");
        }
        if(currentAdx > 30 && currentAdx <= 40){
            System.out.println("추세확정 : adx(" + currentAdx+ ")");
        }
        if(currentAdx > 40 && currentAdx <= 50){
            System.out.println("강한추세 : adx(" + currentAdx+ ")");
        }
        if(currentAdx > 50){
            System.out.println("추세말미 : adx(" + currentAdx+ ")");
        }*/

        TechnicalIndicatorReportEntity technicalIndicatorReport = TechnicalIndicatorReportEntity.builder()
                .symbol(symbol)
                .endTime(kstEndTime.toLocalDateTime())
                .currentAdx(currentAdx)
                .currentAdxGrade(currentAdxGrade)
                .previousAdx(previousAdx)
                .previousAdxGrade(previousAdxGrade)
                .adxSignal(adxSignal)
                .plusDi(plusDi)
                .minusDi(minusDi)
                .directionDi(direction)
                .sma(CommonUtils.truncate(sma.getValue(series.getEndIndex()), tickSize))
                .ema(CommonUtils.truncate(ema.getValue(series.getEndIndex()), tickSize))
                .maDi(currentTrend)
                .ubb(CommonUtils.truncate(upperBBand.getValue(series.getEndIndex()), tickSize))
                .mbb(CommonUtils.truncate(middleBBand.getValue(series.getEndIndex()), tickSize))
                .lbb(CommonUtils.truncate(lowerBBand.getValue(series.getEndIndex()), tickSize))
                .rsi(CommonUtils.truncate(rsi.getValue(series.getEndIndex()), new BigDecimal(2)))
                .macd(CommonUtils.truncate(macd.getValue(series.getEndIndex()), new BigDecimal(0)))
                .macdGoldenCross(technicalIndicatorCalculator.isGoldenCross(series, 12, 26, 9))
                .macdDeadCross(technicalIndicatorCalculator.isDeadCross(series, 12, 26, 9))
                .build();
        return technicalIndicatorReport;
    }
}