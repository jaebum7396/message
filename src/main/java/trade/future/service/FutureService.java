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
import trade.common.CommonUtils;
import trade.configuration.MyWebSocketClientImpl;
import trade.future.model.entity.KlineEventEntity;
import trade.future.model.entity.PositionEntity;
import trade.future.model.entity.TradingEntity;
import trade.future.repository.KlineEventRepository;
import trade.future.repository.PositionRepository;
import trade.future.repository.TradingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
        System.out.println(BINANCE_API_KEY + " " + BINANCE_SECRET_KEY);
    }
    public String BINANCE_API_KEY;
    public String BINANCE_SECRET_KEY;

    public String BASE_URL;
    public static final String BASE_URL_TEST = "https://testnet.binance.vision";
    public static final String BASE_URL_REAL = "wss://ws-api.binance.com:443/ws-api/v3";

    @Autowired KlineEventRepository klineEventRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired TradingRepository tradingRepository;
    @Autowired MyWebSocketClientImpl umWebSocketStreamClient;
    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();
    private final WebSocketCallback noopCallback = msg -> {};
    private final WebSocketCallback openCallback = this::onOpenCallback;
    private final WebSocketCallback onMessageCallback = this::onMessageCallback;
    private final WebSocketCallback closeCallback = this::onCloseCallback;
    private final WebSocketCallback failureCallback = this::onFailureCallback;

    int failureCount = 0;

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
        System.out.println("[RECOVER] >>>>> "+tradingEntityOpt.get().toString());
        if(tradingEntityOpt.isPresent()){
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
                JSONObject eventObj = new JSONObject(event);
                JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
                if(String.valueOf(klineEventObj.get("e")).equals("kline")){
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
                } else if(String.valueOf(klineEventObj.get("e")).equals("forceOrder")){
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
    }

    private TradingEntity getTradingEntity(String symbol) {
        return tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN")
                .orElseThrow(() -> new RuntimeException("트레이딩이 존재하지 않습니다."));
    }

    @Transactional
    public void checkAndSavePosition(KlineEventEntity klineEventEntity, TradingEntity tradingEntity, BigDecimal QuoteAssetVolumeStandard, BigDecimal averageQuoteAssetVolume, BigDecimal quoteAssetVolume) {
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 3;
        int initialDelayMillis = 1000;
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                if (quoteAssetVolume.compareTo(averageQuoteAssetVolume.multiply(QuoteAssetVolumeStandard)) > 0) {
                    if (positionRepository.getPositionByKlineEndTime(klineEventEntity.getKlineEntity().getSymbol(), klineEventEntity.getKlineEntity().getEndTime(), "OPEN").isEmpty()) {
                        System.out.println("[" + klineEventEntity.getKlineEntity().getSymbol() + "] 거래량(" + quoteAssetVolume + ")이 평균 거래량(" + averageQuoteAssetVolume + ") 의 " + quoteAssetVolume.divide(averageQuoteAssetVolume, RoundingMode.FLOOR) + "(기준치 : " + QuoteAssetVolumeStandard + ") 배 보다 큽니다.");
                        PositionEntity entryPosition = klineEventEntity.getKlineEntity().getPositionEntity();
                        entryPosition.setPositionStatus("OPEN");
                        klineEventRepository.save(klineEventEntity);
                        System.out.println("[" + klineEventEntity.getKlineEntity().getSymbol() + "] 포지션을 진입합니다. <<<<< ");
                        System.out.println("진입가(" + klineEventEntity.getKlineEntity().getClosePrice() + "), 목표가(LONG:" + entryPosition.getPlusGoalPrice() + "/SHORT:" + entryPosition.getMinusGoalPrice() + ")");
                        System.out.println("포지션 : " + klineEventEntity.getKlineEntity().getPositionEntity().toString());
                    }
                }
            } catch (Exception e) {
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
        umWebSocketStreamClient.closeAllConnections();
    }

    @Transactional
    public Map<String, Object> autoTradingOpen(String symbolParam, String interval, int leverage, int goalPricePercent, int stockSelectionCount, BigDecimal quoteAssetVolumeStandard) throws Exception {
        log.info("autoTrading >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> selectedStockList = (List<Map<String, Object>>) getStockSelection(stockSelectionCount).get("overlappingData");
        for(Map<String, Object> selectedStock : selectedStockList){
            String symbol = String.valueOf(selectedStock.get("symbol"));
            //String symbol = symbolParam;
            Optional<TradingEntity> tradingEntityOpt = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");

            // 해당 심볼의 트레이딩이 없으면 트레이딩을 시작합니다.
            if(tradingEntityOpt.isEmpty()) {
                // 해당 페어의 평균 거래량을 구합니다.
                BigDecimal averageQuoteAssetVolume = getKlinesAverageQuoteAssetVolume( (JSONArray)getKlines(symbol, interval, 500).get("result"), interval);
                TradingEntity tradingEntity = TradingEntity.builder()
                        .symbol(symbol)
                        .tradingStatus("OPEN")
                        .candleInterval(interval)
                        .leverage(leverage)
                        .goalPricePercent(goalPricePercent)
                        .stockSelectionCount(stockSelectionCount)
                        .quoteAssetVolumeStandard(quoteAssetVolumeStandard)
                        .averageQuoteAssetVolume(averageQuoteAssetVolume)
                        .fluctuationRate(new BigDecimal(String.valueOf(selectedStock.get("priceChangePercent"))))
                        //.fluctuationRate(new BigDecimal(2))
                        .build();
                autoTradeStreamOpen(tradingEntity);
            }
        }
        return resultMap;
    }

    public TradingEntity autoTradeStreamOpen(TradingEntity tradingEntity) {
        log.info("klineStreamOpen >>>>> ");
        ArrayList<String> streams = new ArrayList<>();
        //streams.add("btcusdt@trade");
        //streams.add("btcusdt@bookTicker");

        String klineStreamName = tradingEntity.getSymbol().toLowerCase() + "@kline_" + tradingEntity.getCandleInterval();
        System.out.println("klineStreamName : " + klineStreamName);
        streams.add(klineStreamName);
        String forceOrderStreamName = tradingEntity.getSymbol().toLowerCase() + "@forceOrder";
        streams.add(forceOrderStreamName);

        String allMarketForceOrderStreamName = "!forceOrder@arr";
        streams.add(allMarketForceOrderStreamName);

        tradingEntity = umWebSocketStreamClient.combineStreams(tradingEntity, streams, openCallback, onMessageCallback, closeCallback, failureCallback);
        //tradingEntity = umWebSocketStreamClient.klineStream(tradingEntity, openCallback, onMessageCallback, closeCallback, failureCallback);
        return tradingEntity;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public KlineEventEntity saveKlineEvent(String event, TradingEntity tradingEntity) {
        KlineEventEntity klineEventEntity = null;
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 3;
        int initialDelayMillis = 1000;
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                klineEventEntity = CommonUtils.convertKlineEventDTO(event).toEntity();
                int goalPricePercent = tradingEntity.getGoalPricePercent();
                int leverage = tradingEntity.getLeverage();

                PositionEntity positionEntity = PositionEntity.builder()
                        .positionStatus("NONE")
                        .goalPricePercent(goalPricePercent)
                        .klineEntity(klineEventEntity.getKlineEntity())
                        .plusGoalPrice(
                                CommonUtils.calculateGoalPrice(
                                        klineEventEntity.getKlineEntity().getClosePrice(), "LONG", leverage, goalPricePercent))
                        .minusGoalPrice(
                                CommonUtils.calculateGoalPrice(
                                        klineEventEntity.getKlineEntity().getClosePrice(), "SHORT", leverage, goalPricePercent))
                        .build();

                if (tradingEntity.getFluctuationRate().compareTo(BigDecimal.ZERO) > 0) {
                    positionEntity.setPositionSide("LONG");
                    //System.out.println("변동률 :" + tradingEntity.getFluctuationRate()+ " / 포지션 : LONG");
                } else {
                    positionEntity.setPositionSide("SHORT");
                    //System.out.println("변동률 :" + tradingEntity.getFluctuationRate()+ " / 포지션 : SHORT");
                }

                klineEventEntity.setTradingEntity(tradingEntity);
                klineEventEntity.getKlineEntity().setPositionEntity(positionEntity);
                klineEventEntity = klineEventRepository.save(klineEventEntity);
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
        return klineEventEntity;
    }

    @Transactional
    public void updateGoalAchievedKlineEvent(KlineEventEntity klineEventEntity) {
        List<KlineEventEntity> goalAchievedPlusList = klineEventRepository
                .findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(
                        klineEventEntity.getKlineEntity().getSymbol()
                        , klineEventEntity.getKlineEntity().getClosePrice());

        goalAchievedPlusList.stream().forEach(goalAchievedPlus -> {
            goalAchievedPlus.getKlineEntity().getPositionEntity().setGoalPriceCheck(true);
            goalAchievedPlus.getKlineEntity().getPositionEntity().setGoalPricePlus(true);

            Optional<PositionEntity> currentPositionOpt = Optional.ofNullable(goalAchievedPlus.getKlineEntity().getPositionEntity());

            if(currentPositionOpt.isPresent()){
                PositionEntity currentPosition = currentPositionOpt.get();
                if(currentPosition.getPositionStatus().equals("OPEN")) {
                    System.out.println("목표가 도달(long) : " + goalAchievedPlus.getKlineEntity().getSymbol()
                            + " 현재가 : " + klineEventEntity.getKlineEntity().getClosePrice()
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
            klineEventRepository.save(goalAchievedPlus);
        });

        List<KlineEventEntity> goalAchievedMinusList = klineEventRepository
                .findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(
                        klineEventEntity.getKlineEntity().getSymbol()
                        , klineEventEntity.getKlineEntity().getClosePrice());

        goalAchievedMinusList.stream().forEach(goalAchievedMinus -> {
            goalAchievedMinus.getKlineEntity().getPositionEntity().setGoalPriceCheck(true);
            goalAchievedMinus.getKlineEntity().getPositionEntity().setGoalPriceMinus(true);

            Optional<PositionEntity> currentPositionOpt = Optional.ofNullable(goalAchievedMinus.getKlineEntity().getPositionEntity());

            if(currentPositionOpt.isPresent()){
                PositionEntity currentPosition = currentPositionOpt.get();
                if(currentPosition.getPositionStatus().equals("OPEN")){
                    System.out.println("목표가 도달(short) : " + goalAchievedMinus.getKlineEntity().getSymbol()
                            + " 현재가 : " + klineEventEntity.getKlineEntity().getClosePrice()
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
            klineEventRepository.save(goalAchievedMinus);
        });
    }

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

    public Map<String, Object> getKlines(String symbol, String interval, int limit) {
        log.info("getKline >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        UMFuturesClientImpl client = new UMFuturesClientImpl();

        paramMap.put("symbol", symbol);
        paramMap.put("interval", interval);

        String resultStr = client.market().klines(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);
        resultMap.put("result", resultArray);
        return resultMap;
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

    public Map<String, Object> orderBookStream(String symbol) throws Exception {
        log.info("accountInfo >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        ArrayList<String> streams = new ArrayList<>();
        streams.add("btcusdt@trade");
        streams.add("btcusdt@bookTicker");
        umWebSocketStreamClient.combineStreams(streams, ((event) -> {
            System.out.println(event);
        }));
        return resultMap;
    }
}