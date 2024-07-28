package trade.future.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.binance.connector.futures.client.utils.WebSocketCallback;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.*;
import org.ta4j.core.backtest.BarSeriesManager;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.bollinger.PercentBIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.*;
import trade.common.CommonUtils;
import trade.configuration.MyWebSocketClientImpl;
import trade.exception.TradingException;
import trade.future.model.dto.TradingDTO;
import trade.future.model.entity.*;
import trade.future.model.enums.ADX_GRADE;
import trade.future.model.enums.CONSOLE_COLORS;
import trade.future.repository.EventRepository;
import trade.future.repository.PositionRepository;
import trade.future.repository.TechnicalIndicatorRepository;
import trade.future.repository.TradingRepository;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static trade.common.CommonUtils.parseKlineEntity;
import static trade.common.NumberUtils.doulbleToBigDecimal;

@Slf4j
@Service
@Transactional
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
        this.exchangeInfo = new JSONObject(umFuturesClientImpl.market().exchangeInfo());
        this.symbols      = new JSONArray(String.valueOf(exchangeInfo.get("symbols")));
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
    @Autowired TechnicalIndicatorRepository technicalIndicatorRepository;
    @Autowired MyWebSocketClientImpl umWebSocketStreamClient;
    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();
    private final WebSocketCallback noopCallback = msg -> {};
    private final WebSocketCallback openCallback = this::onOpenCallback;
    private final WebSocketCallback onMessageCallback = this::onMessageCallback;
    private final WebSocketCallback closeCallback = this::onCloseCallback;
    private final WebSocketCallback failureCallback = this::onFailureCallback;

    // 원하는 형식의 날짜 포맷 지정
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${jwt.secret.key}") private String JWT_SECRET_KEY;
    int failureCount = 0;
    private HashMap<String, List<KlineEntity>> klines = new HashMap<String, List<KlineEntity>>();
    private HashMap<String, BaseBarSeries> seriesMap = new HashMap<String, BaseBarSeries>();
    private static final int WINDOW_SIZE = 100; // For demonstration purposes
    private static final boolean DEV_FLAG = false;
    private final Map<String, TradingEntity> TRADING_ENTITYS = new HashMap<>();

    public void onOpenCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        log.info("[OPEN] >>>>> " + streamId + " 번 스트림("+tradingEntity.getSymbol()+")을 오픈합니다.");
        tradingEntity.setTradingStatus("OPEN");
        tradingRepository.save(tradingEntity);
        getKlines(tradingEntity, false);
        HashMap<String, Object> trendMap = trendValidate(tradingEntity);
        String trend4h = String.valueOf(trendMap.get("trend4h"));
        String trend1h = String.valueOf(trendMap.get("trend1h"));
        String trend15m = String.valueOf(trendMap.get("trend15m"));
        tradingEntity.setTrend4h(trend4h);
        tradingEntity.setTrend1h(trend1h);
        tradingEntity.setTrend15m(trend15m);
        //getKlines(tradingEntity.getTradingCd(), tradingEntity.getSymbol(), "5m", 50);
        /*if (streamId.equals("1")){
            throw new RuntimeException("강제예외 발생");
        }*/
        log.info("tradingSaved >>>>> "+tradingEntity.getTradingCd() + " : " + tradingEntity.getSymbol() + " / " + tradingEntity.getStreamId());
    }

    public void onCloseCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        System.out.println("[CLOSE] >>>>> " + streamId + " 번 스트림을 클로즈합니다. ");
        tradingEntity.setTradingStatus("CLOSE");
        tradingRepository.save(tradingEntity);
    }

    public void onFailureCallback(String streamId) {
        System.out.println("[FAILURE] >>>>> " + streamId + " 예기치 못하게 스트림이 실패하였습니다. ");
        Optional<TradingEntity> tradingEntityOpt = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)));
        if(tradingEntityOpt.isPresent()){
            TradingEntity tradingEntity = tradingEntityOpt.get();
            log.error("[FAILURE] >>>>> "+tradingEntity.getTradingCd()+ "/" +tradingEntity.getSymbol());
           /* autoTradingRestart(tradingEntity);
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
            }*/
        } else {
            System.out.println("[RECOVER-ERR] >>>>> "+streamId +" 번 스트림을 복구하지 못했습니다.");
            //onFailureCallback(streamId);
        }
    }

    public void onMessageCallback(String event){
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 1;
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

    public Map<String, Object> autoTradingOpen(HttpServletRequest request, TradingDTO tradingDTO) {
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }
        TradingEntity tradingEntity = tradingDTO.toEntity();
        return autoTradingOpen(tradingEntity);
    }

    public Map<String, Object> autoTradingOpen(TradingEntity tradingEntity) {
        log.info("autoTrading >>>>>");
        //변수
        String targetSymbol = tradingEntity.getTargetSymbol();
        String interval = tradingEntity.getCandleInterval();
        int leverage = tradingEntity.getLeverage();
        int stockSelectionCount = tradingEntity.getStockSelectionCount(); // 최초에 종목 정보를 몇개를 가져올건지
        int maxPositionCount = tradingEntity.getMaxPositionCount(); // 최대 빠따든놈의 명 수
        String userCd = tradingEntity.getUserCd();

        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>())); // 내 계좌정보를 제이슨으로 리턴하는 API
        // printPrettyJson(accountInfo);

        System.out.println("사용가능 : " +accountInfo.get("availableBalance"));
        System.out.println("담보금 : " + accountInfo.get("totalWalletBalance"));
        System.out.println("미실현수익 : " + accountInfo.get("totalUnrealizedProfit"));
        System.out.println("현재자산 : " + accountInfo.get("totalMarginBalance"));

        BigDecimal availableBalance = new BigDecimal(String.valueOf(accountInfo.get("availableBalance")));
        BigDecimal totalWalletBalance = new BigDecimal(String.valueOf(accountInfo.get("totalWalletBalance")));

        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> selectedStockList;
        //closeAllPositions();
        System.out.println("symbolParam : " + targetSymbol);

        int availablePositionCount = maxPositionCount - TRADING_ENTITYS.size(); // 현재 가능한 포지션 카운트
        boolean nextFlag = true;
        try {
            if (availablePositionCount <= 0) {
                throw new RuntimeException("오픈 가능한 트레이딩이 없습니다.");
            }
        } catch (Exception e){
            System.out.println("오픈 가능한 트레이딩이 없습니다.");
            System.out.println("현재 오픈된 트레이딩");
            TRADING_ENTITYS.forEach((symbol, currentTradingEntity) -> {
                System.out.println("symbol : " + symbol);
            });
            nextFlag = false;
        }
        if(nextFlag){
            if(targetSymbol == null || targetSymbol.isEmpty()) {
                selectedStockList = (List<Map<String, Object>>) getStockFind(tradingEntity).get("overlappingData");
            } else {
                LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
                paramMap.put("symbol", targetSymbol);
                String resultStr = client.market().ticker24H(paramMap);
                JSONObject result = new JSONObject(resultStr);
                List<Map<String, Object>> list = new ArrayList<>();
                list.add(result.toMap());
                selectedStockList = list;
            }

            List<Map<String, Object>> tradingTargetSymbols = selectedStockList;
            //System.out.println("selectedStockList : " + selectedStockList);
            try{
                if(tradingTargetSymbols.size() == 0){
                    throw new RuntimeException("선택된 종목이 없습니다.");
                }else{
                    for(Map<String,Object> tradingTargetSymbol : tradingTargetSymbols){
                        String symbol = String.valueOf(tradingTargetSymbol.get("symbol"));
                        Optional<TradingEntity> tradingEntityOpt = Optional.ofNullable(TRADING_ENTITYS.get(symbol));
                        if(tradingEntityOpt.isPresent()){
                            printTradingEntitys();
                            throw new RuntimeException("이미 오픈된 트레이딩이 존재합니다.");
                        }
                    }
                }
            } catch (Exception e) {
                autoTradingOpen(tradingEntity);
                nextFlag = false;
            }
            if(nextFlag){
                BigDecimal maxPositionAmount = totalWalletBalance
                        .divide(new BigDecimal(maxPositionCount),0, RoundingMode.DOWN)
                        .multiply(tradingEntity.getCollateralRate()).setScale(0, RoundingMode.DOWN);

                BigDecimal finalAvailableBalance = maxPositionAmount;
                log.info("collateral : " + maxPositionAmount);
                System.out.println("tradingTargetSymbols : " + tradingTargetSymbols);
                tradingTargetSymbols.parallelStream().forEach(selectedStock -> {
                    String symbol = String.valueOf(selectedStock.get("symbol"));
                    System.out.println("symbol : " + symbol);
                    // 해당 페어의 평균 거래량을 구합니다.
                    //BigDecimal averageQuoteAssetVolume = getKlinesAverageQuoteAssetVolume( (JSONArray)getKlines(symbol, interval, WINDOW_SIZE).get("result"), interval);

                    TradingEntity newTradingEntity = new TradingEntity();
                    newTradingEntity.setSymbol(symbol);
                    newTradingEntity.setTradingStatus("OPEN");
                    newTradingEntity.setCandleInterval(interval);
                    newTradingEntity.setLeverage(leverage);
                    newTradingEntity.setStockSelectionCount(stockSelectionCount);
                    newTradingEntity.setMaxPositionCount(maxPositionCount);
                    newTradingEntity.setCandleCount(tradingEntity.getCandleCount());
                    newTradingEntity.setCollateral(finalAvailableBalance);
                    newTradingEntity.setCollateralRate(tradingEntity.getCollateralRate());
                    newTradingEntity.setTrendFollowFlag(tradingEntity.getTrendFollowFlag());
                    newTradingEntity.setUserCd(userCd);
                    //매매전략
                    newTradingEntity.setBollingerBandChecker(tradingEntity.getBollingerBandChecker());
                    newTradingEntity.setAdxChecker(tradingEntity.getAdxChecker());
                    newTradingEntity.setMacdHistogramChecker(tradingEntity.getMacdHistogramChecker());
                    newTradingEntity.setStochChecker(tradingEntity.getStochChecker());
                    newTradingEntity.setStochRsiChecker(tradingEntity.getStochRsiChecker());
                    newTradingEntity.setRsiChecker(tradingEntity.getRsiChecker());
                    newTradingEntity.setMovingAverageChecker(tradingEntity.getMovingAverageChecker());

                    /*TradingEntity tradingEntity = TradingEntity.builder()
                            .symbol(symbol)
                            .tradingStatus("OPEN")
                            .candleInterval(interval)
                            .leverage(leverage)
                            .stockSelectionCount(stockSelectionCount)
                            .maxPositionCount(maxPositionCount)
                            .collateral(finalAvailableBalance)
                            .userCd(userCd)
                            .build();*/
                    try{
                        Optional<TradingEntity> tradingEntityOpt = Optional.ofNullable(TRADING_ENTITYS.get(symbol));
                        if(tradingEntityOpt.isPresent()){
                            printTradingEntitys();
                            throw new RuntimeException(tradingEntityOpt.get().getSymbol() + "이미 오픈된 트레이딩이 존재합니다.");
                        }else{
                            TRADING_ENTITYS.put(symbol, autoTradeStreamOpen(newTradingEntity));
                        }
                    } catch (Exception e) {
                        autoTradingOpen(newTradingEntity);
                    }
                    printTradingEntitys();
                });
            }
        }
        return resultMap;
    }

    public Map<String,Object> backTest(HttpServletRequest request, TradingDTO tradingDTO){
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }
        TradingEntity tradingEntity = tradingDTO.toEntity();
        tradingEntity.setTradingCd(UUID.randomUUID().toString());
        tradingEntity.setUserCd(userCd);
        return backTest(tradingEntity);
    }

    public Map<String, Object> backTest(TradingEntity tradingEntity) {
        //변수 설정
        String interval = tradingEntity.getCandleInterval();
        int maxPositionCount = tradingEntity.getMaxPositionCount();
        int stockSelectionCount = tradingEntity.getStockSelectionCount();

        Map<String, Object> resultMap = new LinkedHashMap<>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);

        // 거래량(QuoteVolume - 기준 화폐)을 기준으로 내림차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByQuoteVolume = getSort(resultArray, "quoteVolume", "DESC", stockSelectionCount);
        //System.out.println("sortedByQuoteVolume : " + sortedByQuoteVolume);
        List<Map<String, Object>> overlappingData = new ArrayList<>();
        List<TechnicalIndicatorReportEntity> reports = new ArrayList<>();

        int count = 0;
        for (Map<String, Object> item : sortedByQuoteVolume) {
            int availablePositionCount = maxPositionCount - TRADING_ENTITYS.size();
            if (availablePositionCount <= 0) {
                break;
            }
            if (count >= availablePositionCount) {
                break;
            }

            String symbol = String.valueOf(item.get("symbol"));

            //트레이딩 데이터 수집의 주가 되는 객체
            TradingEntity tempTradingEntity = tradingEntity.clone();
            tempTradingEntity.setSymbol(symbol);

            List<TradingEntity> tradingEntityList = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");
            if (tradingEntityList.isEmpty()) { //오픈된 트레이딩이 없다면
                Map<String, Object> klineMap = backTestExec(tempTradingEntity,false);
                /*HashMap<String, Object> trendMap = trendValidate(tempTradingEntity);
                String trend4h = String.valueOf(trendMap.get("trend4h"));
                String trend1h = String.valueOf(trendMap.get("trend1h"));
                String trend15m = String.valueOf(trendMap.get("trend15m"));
                tradingEntity.setTrend4h(trend4h);
                tradingEntity.setTrend1h(trend1h);
                tradingEntity.setTrend15m(trend15m);

                boolean resultFlag = trendMap.get("resultFlag").equals(true);

                if (resultFlag) {
                    tempTradingEntity.setTrend4h(trend4h);
                    tempTradingEntity.setTrend1h(trend1h);
                    tempTradingEntity.setTrend15m(trend15m);

                    Map<String, Object> klineMap = backTestExec(tempTradingEntity,true);
                    Optional<Object> expectationProfitOpt = Optional.ofNullable(klineMap.get("expectationProfit"));
                    TechnicalIndicatorReportEntity tempReport = technicalIndicatorCalculate(tempTradingEntity);
                    if (expectationProfitOpt.isPresent()){
                        BigDecimal expectationProfit = (BigDecimal) expectationProfitOpt.get();
                        BigDecimal winTradeCount = new BigDecimal(String.valueOf(klineMap.get("winTradeCount")));
                        BigDecimal loseTradeCount = new BigDecimal(String.valueOf(klineMap.get("loseTradeCount")));
                        if (
                                true
                                        &&expectationProfit.compareTo(BigDecimal.ONE) > 0
                                        && (winTradeCount.compareTo(loseTradeCount) > 0)
                            //&& tempReport.getCurrentAdxGrade().getGrade()>1
                            //&& tempReport.getMarketCondition() == 1
                        ) {
                            System.out.println("[관심종목추가]symbol : " + symbol + " expectationProfit : " + expectationProfit);
                            overlappingData.add(item);
                            reports.add(tempReport);
                            count++;
                        }
                    }
                }*/
            }
        }

        for(Map<String, Object> item : overlappingData){
            System.out.println("관심종목 : " + item);
        }

        //System.out.println("overlappingData : " + overlappingData);

        resultMap.put("reports", reports);
        resultMap.put("overlappingData", overlappingData);
        return resultMap;
    }

    public Map<String, Object> backTestExec(TradingEntity tradingEntity, boolean logFlag) {

        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        //printPrettyJson(accountInfo);

        if(logFlag){
            System.out.println("사용가능 : " +accountInfo.get("availableBalance"));
            System.out.println("담보금 : " + accountInfo.get("totalWalletBalance"));
            System.out.println("미실현수익 : " + accountInfo.get("totalUnrealizedProfit"));
            System.out.println("현재자산 : " + accountInfo.get("totalMarginBalance"));
        }

        BigDecimal availableBalance = new BigDecimal(String.valueOf(accountInfo.get("availableBalance")));
        BigDecimal totalWalletBalance = new BigDecimal(String.valueOf(accountInfo.get("totalWalletBalance")));

        BigDecimal maxPositionAmount = totalWalletBalance
                .divide(new BigDecimal(tradingEntity.getMaxPositionCount()),0, RoundingMode.DOWN)
                .multiply(tradingEntity.getCollateralRate()).setScale(0, RoundingMode.DOWN);

        tradingEntity.setCollateral(maxPositionAmount);

        String tradingCd = tradingEntity.getTradingCd();
        String symbol = tradingEntity.getSymbol();
        String interval = tradingEntity.getCandleInterval();
        int candleCount = tradingEntity.getCandleCount();
        int limit = candleCount;
        long startTime = System.currentTimeMillis(); // 시작 시간 기록

        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        paramMap.put("symbol", symbol);
        paramMap.put("interval", interval);
        paramMap.put("limit", limit);

        client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY, true);
        String resultStr = client.market().klines(paramMap);

        //System.out.println("resultStr : "+ resultStr);
        String weight = new JSONObject(resultStr).getString("x-mbx-used-weight-1m");
        System.out.println("*************** [현재 가중치 : " + weight + "] ***************");
        JSONArray jsonArray = new JSONArray(new JSONObject(resultStr).get("data").toString());
        List<KlineEntity> klineEntities = new ArrayList<>();
        BaseBarSeries series = new BaseBarSeries();
        series.setMaximumBarCount(limit);
        seriesMap.put(tradingCd + "_" + interval, series);

        ArrayList<TechnicalIndicatorReportEntity> technicalIndicatorReportEntityArr = new ArrayList<>();

        BigDecimal expectationProfit = BigDecimal.ZERO;
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray klineArray = jsonArray.getJSONArray(i);
            KlineEntity klineEntity = parseKlineEntity(klineArray);
            //System.out.println(klineArray);
            klineEntities.add(klineEntity);

            Num open = series.numOf(klineEntity.getOpenPrice());
            Num high = series.numOf(klineEntity.getHighPrice());
            Num low = series.numOf(klineEntity.getLowPrice());
            Num close = series.numOf(klineEntity.getClosePrice());
            Num volume = series.numOf(klineEntity.getVolume());

            series.addBar(klineEntity.getEndTime().atZone(ZoneOffset.UTC), open, high, low, close, volume);
        }

        int shortMovingPeriod = tradingEntity.getShortMovingPeriod();
        int midPeriod = tradingEntity.getMidMovingPeriod();
        int longMovingPeriod = tradingEntity.getLongMovingPeriod();

        // 종가 설정
        // Define indicators
        OpenPriceIndicator  openPrice   = new OpenPriceIndicator(series); //시가
        ClosePriceIndicator closePrice  = new ClosePriceIndicator(series); //종가
        HighPriceIndicator  highPrice   = new HighPriceIndicator(series); //고가
        LowPriceIndicator   lowPrice    = new LowPriceIndicator(series); //저가

        log.info("SYMBOL : " + symbol);

        // 이동평균선 설정
        SMAIndicator sma = new SMAIndicator(closePrice, shortMovingPeriod); //단기 이동평균선
        EMAIndicator ema = new EMAIndicator(closePrice, longMovingPeriod);  //장기 이동평균선

        // 볼린저 밴드 설정
        int barCount = 20;
        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, longMovingPeriod); //장기 이평선 기간 동안의 표준편차
        BollingerBandsMiddleIndicator middleBBand    = new BollingerBandsMiddleIndicator(ema); //장기 이평선으로 중심선
        BollingerBandsUpperIndicator upperBBand      = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand      = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);

        //베이스룰
        Rule baseRule = new BooleanRule(false){};

        // 이동평균선 매수/매도 규칙
        Rule smaBuyingRule = new OverIndicatorRule(sma, ema); // 20MA > 50MA
        Rule smaSellingRule = new UnderIndicatorRule(sma, ema); // 20MA < 50MA

        // 볼린저 밴드 매수/매도 규칙
        Rule bollingerBuyingRule = new CrossedDownIndicatorRule(closePrice, lowerBBand); // 가격이 하한선을 돌파할 때 매수
        Rule bollingerSellingRule = new CrossedUpIndicatorRule(closePrice, upperBBand); // 가격이 상한선을 돌파할 때 매도

        // RSI 매수/매도 규칙
        int rsiPeriod = 10;
        RSIIndicator rsiIndicator = new RSIIndicator(closePrice, rsiPeriod);
        Rule rsiBuyingRule = new CrossedDownIndicatorRule(rsiIndicator, DecimalNum.valueOf(20)); // RSI가 20 이하로 떨어질 때 매수
        Rule rsiSellingRule = new CrossedUpIndicatorRule(rsiIndicator, DecimalNum.valueOf(70)); // RSI가 70 이상으로 올라갈 때 매도

        MACDIndicator macd = new MACDIndicator(closePrice, 6, 12);

        Rule macdHistogramPositive = new Rule(){
            @Override
            public boolean isSatisfied(int i, TradingRecord tradingRecord) {
                double currentMacdHistogram     = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, i);
                double previousMacdHistogram    = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, i-1);
                double prePreviousMacdHistogram = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, i-2);

                double macdHistogramGap = currentMacdHistogram - previousMacdHistogram;
                double previousMacdHistogramGap = previousMacdHistogram - prePreviousMacdHistogram;

                boolean MACD_히스토그램_증가 = macdHistogramGap > 0;
                boolean 이전_MACD_히스토그램_증가 = previousMacdHistogramGap > 0;

                return MACD_히스토그램_증가 && !이전_MACD_히스토그램_증가 && previousMacdHistogram < 0;
            }
        };
        Rule macdHistogramNegative = new Rule() {
            @Override
            public boolean isSatisfied(int i, TradingRecord tradingRecord) {
                double currentMacdHistogram     = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, i);
                double previousMacdHistogram    = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, i - 1);
                double prePreviousMacdHistogram = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, shortMovingPeriod, longMovingPeriod, i - 2);

                double macdHistogramGap = currentMacdHistogram - previousMacdHistogram;
                double previousMacdHistogramGap = previousMacdHistogram - prePreviousMacdHistogram;

                boolean MACD_히스토그램_증가 = macdHistogramGap > 0;
                boolean 이전_MACD_히스토그램_증가 = previousMacdHistogramGap > 0;

                return !MACD_히스토그램_증가 && 이전_MACD_히스토그램_증가 && previousMacdHistogram > 0;
            }
        };

        // 손절 규칙 추가: 가격이 진입 가격에서 -0.5% 아래로 떨어졌을 때
        DecimalNum longStopLossPercentage = DecimalNum.valueOf(1); // -0.5%
        Rule longStopLossRule = new StopLossRule(closePrice, longStopLossPercentage){
        };

        DecimalNum shortStopLossPercentage = DecimalNum.valueOf(1); // -0.5%
        Rule shortStopLossRule = new StopLossRule(closePrice, shortStopLossPercentage){
        };

        Rule bollingerBandCheckerRule = new BooleanRule(tradingEntity.getBollingerBandChecker() == 1);
        Rule rsiCheckerRule = new BooleanRule(tradingEntity.getRsiChecker() == 1);
        Rule movingAverageCheckerRule = new BooleanRule(tradingEntity.getMovingAverageChecker() == 1);
        Rule macdHistogramCheckerRule = new BooleanRule(tradingEntity.getMacdHistogramChecker() == 1);
        Rule adxCheckerRule = new BooleanRule(tradingEntity.getAdxChecker() == 1);
        Rule stochCheckerRule = new BooleanRule(tradingEntity.getStochChecker() == 1);
        Rule stochRsiCheckerRule = new BooleanRule(tradingEntity.getStochRsiChecker() == 1);
        Rule stopLossCheckerRule = new BooleanRule(tradingEntity.getStopLossChecker() == 1);

        // 전략 생성을 위한 룰 리스트
        List<Rule> longEntryRules = new ArrayList<>();
        List<Rule> longExitRules = new ArrayList<>();
        List<Rule> shortEntryRules = new ArrayList<>();
        List<Rule> shortExitRules = new ArrayList<>();

        if (tradingEntity.getBollingerBandChecker() == 1) {
            longEntryRules.add(bollingerBuyingRule);
            longExitRules.add(bollingerSellingRule);
            shortEntryRules.add(bollingerSellingRule);
            shortExitRules.add(bollingerBuyingRule);
        }

        if (tradingEntity.getMovingAverageChecker() == 1) {
            longEntryRules.add(smaBuyingRule);
            longExitRules.add(smaSellingRule);
            shortEntryRules.add(smaSellingRule);
            shortExitRules.add(smaBuyingRule);
        }

        if (tradingEntity.getRsiChecker() == 1) {
            longEntryRules.add(rsiBuyingRule);
            longExitRules.add(rsiSellingRule);
            shortEntryRules.add(rsiSellingRule);
            shortExitRules.add(rsiBuyingRule);
        }

        if (tradingEntity.getMacdHistogramChecker() == 1) {
            longEntryRules.add(macdHistogramPositive);
            longExitRules.add(macdHistogramNegative);
            shortEntryRules.add(macdHistogramNegative);
            shortExitRules.add(macdHistogramPositive);
        }

        if (tradingEntity.getStopLossChecker() == 1) {
            longExitRules.add(longStopLossRule);
            shortExitRules.add(shortStopLossRule);
        }

        // 룰 결합
        // 룰 결합
        Rule combinedLongEntryRule = new BooleanRule(false);
        Rule combinedLongExitRule = new BooleanRule(false);
        Rule combinedShortEntryRule = new BooleanRule(false);
        Rule combinedShortExitRule = new BooleanRule(false);

        if (tradingEntity.getBollingerBandChecker() == 1) {
            combinedLongEntryRule = combinedLongEntryRule.or(bollingerBuyingRule);
            combinedLongExitRule = combinedLongExitRule.or(bollingerSellingRule);
            combinedShortEntryRule = combinedShortEntryRule.or(bollingerSellingRule);
            combinedShortExitRule = combinedShortExitRule.or(bollingerBuyingRule);
        }

        if (tradingEntity.getMovingAverageChecker() == 1) {
            combinedLongEntryRule = combinedLongEntryRule.or(smaBuyingRule);
            combinedLongExitRule = combinedLongExitRule.or(smaSellingRule);
            combinedShortEntryRule = combinedShortEntryRule.or(smaSellingRule);
            combinedShortExitRule = combinedShortExitRule.or(smaBuyingRule);
        }

        if (tradingEntity.getRsiChecker() == 1) {
            combinedLongEntryRule = combinedLongEntryRule.or(rsiBuyingRule);
            combinedLongExitRule = combinedLongExitRule.or(rsiSellingRule);
            combinedShortEntryRule = combinedShortEntryRule.or(rsiSellingRule);
            combinedShortExitRule = combinedShortExitRule.or(rsiBuyingRule);
        }

        if (tradingEntity.getMacdHistogramChecker() == 1) {
            combinedLongEntryRule = combinedLongEntryRule.or(macdHistogramPositive);
            combinedLongExitRule = combinedLongExitRule.or(macdHistogramNegative);
            combinedShortEntryRule = combinedShortEntryRule.or(macdHistogramNegative);
            combinedShortExitRule = combinedShortExitRule.or(macdHistogramPositive);
        }

        // 스탑로스 룰 항상 적용
        combinedLongExitRule = combinedLongExitRule.or(longStopLossRule);
        combinedShortExitRule = combinedShortExitRule.or(shortStopLossRule);

        // 기본 룰 설정 (모든 체커가 비활성화된 경우)
        if (combinedLongEntryRule == null) combinedLongEntryRule = new BooleanRule(false);
        if (combinedLongExitRule == null) combinedLongExitRule = new BooleanRule(false);
        if (combinedShortEntryRule == null) combinedShortEntryRule = new BooleanRule(false);
        if (combinedShortExitRule == null) combinedShortExitRule = new BooleanRule(false);

        // 전략 생성
        Strategy combinedLongStrategy = new BaseStrategy(combinedLongEntryRule, combinedLongExitRule);
        Strategy combinedShortStrategy = new BaseStrategy(combinedShortEntryRule, combinedShortExitRule);

        // 백테스트 실행
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        TradingRecord longTradingRecord = seriesManager.run(combinedLongStrategy);
        TradingRecord shortTradingRecord = seriesManager.run(combinedShortStrategy, Trade.TradeType.SELL);

        // 초기 자산 및 레버리지 설정
        Num initialBalance = DecimalNum.valueOf(maxPositionAmount); // 초기 자산 10000달러
        int leverage = 20; // 2배 레버리지

        // 결과 출력
        System.out.println("");
        printBackTestResult(longTradingRecord, longEntryRules, longExitRules, series, symbol, leverage, "LONG", maxPositionAmount);
        System.out.println("");
        printBackTestResult(shortTradingRecord, shortEntryRules, shortExitRules, series, symbol, leverage, "SHORT", maxPositionAmount);

        // 포지션 거래 결과 분석
        /*AnalysisCriterion profitCriterion = new ProfitCriterion();
        Num longTotalProfit = profitCriterion.calculate(series, longTradingRecord);
        Num longLeveragedProfit = longTotalProfit.multipliedBy(DecimalNum.valueOf(leverage));
        Num longFinalBalance = initialBalance.plus(longLeveragedProfit);
        
        Num shortTotalProfit = profitCriterion.calculate(series, shortTradingRecord);
        Num shortLeveragedProfit = shortTotalProfit.multipliedBy(DecimalNum.valueOf(leverage));
        Num shortFinalBalance = initialBalance.plus(shortLeveragedProfit);*/

        System.out.println("");
        System.out.println("롱 매매횟수 : "+longTradingRecord.getPositionCount());
        System.out.println("숏 매매횟수 : "+shortTradingRecord.getPositionCount());

        //System.out.println("최종 롱 수익(레버리지 적용): " + longLeveragedProfit);

        return resultMap;
    }

    public void printBackTestResult(TradingRecord tradingRecord, List<Rule> entryRules, List<Rule> stopRules, BaseBarSeries series, String symbol, int leverage, String positionSide, BigDecimal collateral) {
        // 거래 기록 출력
        List<Position> positions = tradingRecord.getPositions();
        BigDecimal totalProfit = BigDecimal.ZERO;
        List<Position> winPositions = new ArrayList<>();
        List<Position> losePositions = new ArrayList<>();
        System.out.println(symbol+"/"+positionSide+" 리포트");
        for (Position position : positions) {
            //System.out.println(" "+position);
            Trade entry = position.getEntry();
            Trade exit = position.getExit();

            //계약수량=계약금액×레버리지÷진입가격
            BigDecimal 계약수량 =collateral
                    .multiply(new BigDecimal(leverage))
                    .divide(new BigDecimal(entry.getNetPrice().doubleValue()), 4, RoundingMode.UP);
            //총수수료=2×거래수수료×계약수량×진입가격
            BigDecimal 총수수료 = 계약수량.multiply(new BigDecimal("0.0004"))
                    .multiply(new BigDecimal(entry.getNetPrice().doubleValue()))
                    .multiply(new BigDecimal("2"));
            String fee = "수수료 : " + 총수수료;
            총수수료 = 총수수료.setScale(4, RoundingMode.UP);

            BigDecimal PNL = TechnicalIndicatorCalculator.calculatePnL(BigDecimal.valueOf(entry.getNetPrice().doubleValue()), BigDecimal.valueOf(exit.getNetPrice().doubleValue()), leverage, positionSide, collateral).subtract(총수수료);
            BigDecimal ROI = TechnicalIndicatorCalculator.calculateROI(BigDecimal.valueOf(entry.getNetPrice().doubleValue()), BigDecimal.valueOf(exit.getNetPrice().doubleValue()), leverage, positionSide);
            String entryExpression = "진입["+entry.getIndex()+"]"+ krTimeExpression(series.getBar(entry.getIndex())) +":"+entry.getNetPrice();
            String exitExpression = "청산["+exit.getIndex()+"]"+ krTimeExpression(series.getBar(exit.getIndex())) +":"+exit.getNetPrice();
            String ROIExpression = "ROI:" + (PNL.compareTo(BigDecimal.ZERO) > 0 ? CONSOLE_COLORS.BRIGHT_GREEN+String.valueOf(ROI)+CONSOLE_COLORS.RESET : CONSOLE_COLORS.BRIGHT_RED + String.valueOf(ROI)+CONSOLE_COLORS.RESET);
            String PNLExpression = "PNL:" + (PNL.compareTo(BigDecimal.ZERO) > 0 ? CONSOLE_COLORS.BRIGHT_GREEN+String.valueOf(PNL)+CONSOLE_COLORS.RESET : CONSOLE_COLORS.BRIGHT_RED + String.valueOf(PNL)+CONSOLE_COLORS.RESET);

            StringBuilder entryRuleExpression = new StringBuilder("[진입규칙]");
            StringBuilder stopRuleExpression = new StringBuilder("[청산규칙]");
            int i = 1;
            for (Rule entryRule : entryRules) {
                if (entryRule.isSatisfied(entry.getIndex(), tradingRecord)||entryRule.isSatisfied(entry.getIndex())) {
                    entryRuleExpression.append(i).append(" ").append(entryRule.getClass().getSimpleName()).append(" ");
                    i++;
                }
            }
            int j = 1;
            for (Rule stopRule : stopRules) {
                if (stopRule.isSatisfied(exit.getIndex(),tradingRecord)||stopRule.isSatisfied(exit.getIndex())) {
                    stopRuleExpression.append(j).append(" ").append(stopRule.getClass().getSimpleName()).append(" ");
                    j++;
                }
            }
            //System.out.println(" "+entry+" / "+exit);
            System.out.println("  "+entryExpression+" / "+exitExpression+" / "+ROIExpression+" / "+PNLExpression+" / "+entryRuleExpression+" / "+stopRuleExpression);
            if(PNL.compareTo(BigDecimal.ZERO) > 0){
                winPositions.add(position);
            }else if(PNL.compareTo(BigDecimal.ZERO) < 0){
                losePositions.add(position);
            }
            totalProfit = totalProfit.add(PNL);
        }
        System.out.println(" ");
        System.out.println(symbol+"/"+positionSide+"(승패 : "+winPositions.size()+"/"+losePositions.size()+") : 최종 수익: " + totalProfit);
    }


    public String krTimeExpression(Bar bar){
        // 포맷 적용하여 문자열로 변환
        ZonedDateTime utcEndTime = bar.getEndTime(); //캔들이 !!!끝나는 시간!!!
        ZonedDateTime kstEndTime = utcEndTime.withZoneSameInstant(ZoneId.of("Asia/Seoul")); //한국시간 설정
        String formattedEndTime = formatter.format(kstEndTime);
        String krTimeExpression = "["+formattedEndTime+"]";
        return krTimeExpression;
    }

    public Map<String, Object> backTestTradingOpen(HttpServletRequest request, TradingDTO tradingDTO) {
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }
        return backTestTradingOpen(userCd, tradingDTO);
    }

    public Map<String, Object> backTestTradingOpen(String userCd, TradingDTO tradingDTO) {
        log.info("backTestTrading >>>>>");
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        // printPrettyJson(accountInfo);

        System.out.println("사용가능 : " +accountInfo.get("availableBalance"));
        System.out.println("담보금 : " + accountInfo.get("totalWalletBalance"));
        System.out.println("미실현수익 : " + accountInfo.get("totalUnrealizedProfit"));
        System.out.println("현재자산 : " + accountInfo.get("totalMarginBalance"));

        BigDecimal availableBalance = new BigDecimal(String.valueOf(accountInfo.get("availableBalance")));
        BigDecimal totalWalletBalance = new BigDecimal(String.valueOf(accountInfo.get("totalWalletBalance")));

        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> selectedStockList;
        System.out.println("targetSymbol : " + tradingDTO.getTargetSymbol());

        String targetSymbol = tradingDTO.getTargetSymbol();
        String interval = tradingDTO.getInterval();
        int leverage = tradingDTO.getLeverage();
        int stockSelectionCount = tradingDTO.getStockSelectionCount();
        int maxPositionCount = tradingDTO.getMaxPositionCount();
        BigDecimal colleteralRate = tradingDTO.getCollateralRate();

        availableBalance = availableBalance.divide(new BigDecimal(maxPositionCount), 0, RoundingMode.DOWN);

        TradingEntity tradingEntityTemplate = TradingEntity.builder()
                .tradingStatus("OPEN")
                .tradingType("BACKTEST")
                .candleInterval(interval)
                .leverage(leverage)
                .stockSelectionCount(stockSelectionCount)
                .maxPositionCount(maxPositionCount)
                .userCd(userCd)
                .build();

        return resultMap;
    }

    //해당 트레이딩을 종료하고 다시 오픈하는 코드
    public void restartTrading(TradingEntity tradingEntity){
        tradingEntity.setPositionStatus("CLOSE");
        tradingEntity.setTradingStatus("CLOSE");
        tradingEntity = tradingRepository.save(tradingEntity);
        streamClose(tradingEntity.getStreamId());
        TRADING_ENTITYS.remove(tradingEntity.getSymbol());
        autoTradingOpen(tradingEntity);
    }

    private void klineProcess(String event){
        JSONObject eventObj = new JSONObject(event);
        JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
        JSONObject klineObj = new JSONObject(klineEventObj.get("k").toString());
        boolean isFinal = klineObj.getBoolean("x");

        String seriesNm = String.valueOf(eventObj.get("stream"));
        String symbol = seriesNm.substring(0, seriesNm.indexOf("@"));
        String interval = seriesNm.substring(seriesNm.indexOf("_") + 1);

        boolean nextFlag = true;
        List<TradingEntity> tradingEntitys = new ArrayList<>();
        try{
            tradingEntitys = getTradingEntity(symbol);
            if(tradingEntitys.size() != 1){
                throw new RuntimeException("트레이딩이 존재하지 않거나 중복되어있습니다.");
            }
        } catch (Exception e){
            nextFlag = false;
            e.printStackTrace();
            for(TradingEntity tradingEntity:tradingEntitys){
                restartTrading(tradingEntity);
            }
        }
        if (nextFlag) {
            TradingEntity tradingEntity = tradingEntitys.get(0);
            if(isFinal){
                HashMap<String, Object> trendMap = trendValidate(tradingEntity);
                String trend4h = String.valueOf(trendMap.get("trend4h"));
                String trend1h = String.valueOf(trendMap.get("trend1h"));
                String trend15m = String.valueOf(trendMap.get("trend15m"));
                tradingEntity.setTrend4h(trend4h);
                tradingEntity.setTrend1h(trend1h);
                tradingEntity.setTrend15m(trend15m);

                boolean resultFlag = trendMap.get("resultFlag").equals(true);

                //log.info("!!!!!!!!!!!!!!!!!트렌드 검증!!!!!!!!!!!!!!!!!!"+resultFlag+" 4h/"+trend4h+" 1h/"+trend1h+" 15m/"+trend15m);

                //if(resultFlag){ // 트렌드 검증 -- 큰 트렌드들이 모두 일치할때.
                    System.out.println("event : " + event);
                    // klineEvent를 데이터베이스에 저장
                    EventEntity eventEntity = saveKlineEvent(event, tradingEntity);

                    TechnicalIndicatorReportEntity technicalIndicatorReportEntity = eventEntity.getKlineEntity().getTechnicalIndicatorReportEntity();
                    Optional<EventEntity> openPositionEntityOpt = eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN");
                    openPositionEntityOpt.ifPresentOrElse(positionEvent -> { // 오픈된 포지션이 있다면
                        TechnicalIndicatorReportEntity positionReport = positionEvent.getKlineEntity().getTechnicalIndicatorReportEntity();
                        PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();

                        BigDecimal currentROI;
                        BigDecimal currentPnl;
                        if(tradingEntity.getPositionStatus()!=null && tradingEntity.getPositionStatus().equals("OPEN")){
                            currentROI = TechnicalIndicatorCalculator.calculateROI(closePosition.getEntryPrice(), technicalIndicatorReportEntity.getClosePrice(), tradingEntity.getLeverage(), closePosition.getPositionSide());
                            currentPnl = TechnicalIndicatorCalculator.calculatePnL(closePosition.getEntryPrice(), technicalIndicatorReportEntity.getClosePrice(), tradingEntity.getLeverage(), closePosition.getPositionSide(), tradingEntity.getCollateral());
                        } else {
                            currentROI = new BigDecimal("0");
                            currentPnl = new BigDecimal("0");
                        }
                        closePosition.setRoi(currentROI);
                        closePosition.setProfit(currentPnl);
                        System.out.println("ROI : " + currentROI);
                        System.out.println("PNL : " + currentPnl);

                        // 테스트 모드 청산
                        if(DEV_FLAG){
                            String remark = "테스트 청산";
                            closePosition.setRealizatioPnl(currentPnl);
                            positionEvent.getKlineEntity().setPositionEntity(closePosition);
                            makeCloseOrder(eventEntity, positionEvent, remark);
                        }
                        // 진입 당시의 트렌드와 현재 트렌드가 다르다면 청산
                        /*else if (!tradingEntity.getTrend15m().equals(closePosition.getPositionSide())){
                            String remark = "트렌드 역전 청산";
                            closePosition.setRealizatioPnl(currentPnl);
                            positionEvent.getKlineEntity().setPositionEntity(closePosition);
                            makeCloseOrder(eventEntity, positionEvent, remark);
                        }*/
                        // 포지션의 수익률이 -10% 이하라면 청산
                        else if (currentROI.compareTo(new BigDecimal("-20")) < 0){
                            // 레버리지를 반영하여 스탑로스 가격 계산
                            BigDecimal stopLossPrice;
                            if (tradingEntity.getPositionSide().equals("LONG")) {
                                stopLossPrice = tradingEntity.getOpenPrice().multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(0.20).divide(new BigDecimal(tradingEntity.getLeverage()), 10, RoundingMode.HALF_UP)));
                            } else {
                                stopLossPrice = tradingEntity.getOpenPrice().multiply(BigDecimal.ONE.add(BigDecimal.valueOf(0.20).divide(new BigDecimal(tradingEntity.getLeverage()), 10, RoundingMode.HALF_UP)));
                            }

                            //stopLossPrice = stopLossPrice.setScale(getPricePrecision(symbol), RoundingMode.DOWN);

                            String remark = "수익률 하한선 돌파 청산";
                            closePosition.setClosePrice(stopLossPrice);
                            closePosition.setRealizatioPnl(tradingEntity.getCollateral().multiply(new BigDecimal("-0.2")));
                            positionEvent.getKlineEntity().setPositionEntity(closePosition);
                            makeCloseOrder(eventEntity, positionEvent, remark);
                        }
                        // 이하는 시그널에 따른 청산
                        else {
                            if(technicalIndicatorReportEntity.getWeakSignal() != 0 //기타 시그널
                                ||technicalIndicatorReportEntity.getMidSignal() !=0
                                ||technicalIndicatorReportEntity.getStrongSignal() !=0){
                                String weakSignal   = technicalIndicatorReportEntity.getWeakSignal() != 0 ? (technicalIndicatorReportEntity.getWeakSignal() == 1 ? "LONG" : "SHORT") : "";
                                String midSignal    = technicalIndicatorReportEntity.getMidSignal() != 0 ? (technicalIndicatorReportEntity.getMidSignal() == 1 ? "LONG" : "SHORT") : "";
                                String strongSignal = technicalIndicatorReportEntity.getStrongSignal() != 0 ? (technicalIndicatorReportEntity.getStrongSignal() == 1 ? "LONG" : "SHORT") : "";
                                if(closePosition.getPositionStatus().equals("OPEN")){
                                    String positionSide = closePosition.getPositionSide();
                                    boolean isClose = false;
                                    if (!weakSignal.isEmpty() && !weakSignal.equals(positionSide)){
                                        isClose = true;
                                    }
                                    if (!midSignal.isEmpty() && !midSignal.equals(positionSide)){
                                        isClose = true;
                                    }
                                    if (!strongSignal.isEmpty() && !strongSignal.equals(positionSide)){
                                        isClose = true;
                                    }
                                    if(isClose){
                                        closePosition.setRealizatioPnl(currentPnl);
                                        positionEvent.getKlineEntity().setPositionEntity(closePosition);

                                        int adxDirectionSignal = technicalIndicatorReportEntity.getAdxDirectionSignal();
                                        int bollingerBandSignal = technicalIndicatorReportEntity.getBollingerBandSignal();
                                        int macdReversalSignal = technicalIndicatorReportEntity.getMacdReversalSignal();
                                        int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                                        int rsiSignal = technicalIndicatorReportEntity.getRsiSignal();
                                        int stochSignal = technicalIndicatorReportEntity.getStochSignal();
                                        int stochasticRsiSignal = technicalIndicatorReportEntity.getStochRsiSignal();
                                        int movingAverageSignal = technicalIndicatorReportEntity.getMovingAverageSignal();

                                        String remark = "";
                                        if(bollingerBandSignal != 0){
                                            remark += "BOLLINGER("+(bollingerBandSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        if(adxDirectionSignal != 0){
                                            remark += "ADX("+(adxDirectionSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        if(macdReversalSignal != 0){
                                            remark += "MACD("+(macdReversalSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        if(macdCrossSignal != 0){
                                            remark += "MACD CROSS("+(macdCrossSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        if(rsiSignal != 0){
                                            remark += "RSI("+(rsiSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        if(stochSignal != 0){
                                            remark += "STOCH("+(stochSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        if(stochasticRsiSignal != 0){
                                            remark += "STOCHRSI("+(stochasticRsiSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        if(movingAverageSignal != 0){
                                            remark += "MA("+(movingAverageSignal == 1 ? "LONG" : "SHORT") + ") ";
                                        }
                                        makeCloseOrder(eventEntity, positionEvent, remark);
                                    }
                                }
                            }
                            //MACD 히스토그램이 역전됐을 경우
                            /*else if ((technicalIndicatorReportEntity.getMacdHistogramGap()>0&&closePosition.getPositionSide().equals("SHORT")
                                ||technicalIndicatorReportEntity.getMacdHistogramGap()<0&&closePosition.getPositionSide().equals("LONG"))
                                &&!closePosition.getKlineEntity().getKLineCd().equals(technicalIndicatorReportEntity.getKlineEntity().getKLineCd())){
                                String remark = closePosition.getPositionSide()+"청산 MACD HISTOGRAM 역전 시그널";
                                closePosition.setRealizatioPnl(currentPnl);
                                positionEvent.getKlineEntity().setPositionEntity(closePosition);
                                makeCloseOrder(eventEntity, positionEvent, remark);
                            }*/
                        }
                    },() -> {
                        /*if(technicalIndicatorReportEntity.getCurrentAdxGrade().getGrade()<2){
                            restartTrading(tradingEntity);
                            System.out.println("closeTradingEntity >>>>> " + tradingEntity);
                            log.info("스트림 종료");
                            printTradingEntitys();
                        }*/
                        /*if(technicalIndicatorReportEntity.getMarketCondition()!=1){
                            restartTrading(tradingEntity);
                            System.out.println("closeTradingEntity >>>>> " + tradingEntity);
                            log.info("스트림 종료");
                            printTradingEntitys();
                        }*/
                    });
                /*}else{
                    restartTrading(tradingEntity);
                    System.out.println("closeTradingEntity >>>>> " + tradingEntity);
                    log.info("스트림 종료");
                }*/
            }
        }
    }

    public EventEntity saveKlineEvent(String event, TradingEntity tradingEntity) {
        EventEntity klineEvent = null;
        klineEvent = CommonUtils.convertKlineEventDTO(event).toEntity();
        klineEvent.setTradingEntity(tradingEntity);
        int leverage = tradingEntity.getLeverage();

        // 캔들데이터 분석을 위한 bar 세팅
        settingBar(tradingEntity.getTradingCd(), event);

        // 기술지표 계산
        TechnicalIndicatorReportEntity technicalIndicatorReportEntity = technicalIndicatorCalculate(tradingEntity);
        // 트렌드
        tradingEntity.setTrend(technicalIndicatorReportEntity.getDirectionDi());
        technicalIndicatorReportEntity.setKlineEntity(klineEvent.getKlineEntity());
        klineEvent.getKlineEntity().setTechnicalIndicatorReportEntity(technicalIndicatorReportEntity);

        // 포지션 엔티티 생성
        PositionEntity positionEntity = PositionEntity.builder()
                .positionStatus("NONE")
                .klineEntity(klineEvent.getKlineEntity())
                .build();
        klineEvent.getKlineEntity().setPositionEntity(positionEntity);
        klineEvent = eventRepository.save(klineEvent);
        Optional<EventEntity> eventEntity = eventRepository.findEventBySymbolAndPositionStatus(tradingEntity.getSymbol(), "OPEN");
        if(eventEntity.isEmpty()){
            if (DEV_FLAG) {
                String remark = "테스트모드 진입시그널";
                try {
                    makeOpenOrder(klineEvent, "LONG", remark);
                } catch (Exception e) {
                    throw new TradingException(tradingEntity);
                }
            } else {
                if (technicalIndicatorReportEntity.getStrongSignal() != 0
                    ||technicalIndicatorReportEntity.getMidSignal() != 0
                    //&& (technicalIndicatorReportEntity.getAdxGap() > 1 || technicalIndicatorReportEntity.getAdxGap() < -1)
                ){
                    int adxDirectionSignal = technicalIndicatorReportEntity.getAdxDirectionSignal();
                    int bollingerBandSignal = technicalIndicatorReportEntity.getBollingerBandSignal();
                    int macdReversalSignal = technicalIndicatorReportEntity.getMacdReversalSignal();
                    int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                    int rsiSignal = technicalIndicatorReportEntity.getRsiSignal();
                    int stochSignal = technicalIndicatorReportEntity.getStochSignal();
                    int stochasticRsiSignal = technicalIndicatorReportEntity.getStochRsiSignal();
                    int movingAverageSignal = technicalIndicatorReportEntity.getMovingAverageSignal();

                    String remark = "";
                    if(bollingerBandSignal != 0){
                        remark += "BOLLINGER("+(bollingerBandSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }
                    if(adxDirectionSignal != 0){
                        remark += "ADX("+(adxDirectionSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }
                    if(macdReversalSignal != 0){
                        remark += "MACD("+(macdReversalSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }
                    if(macdCrossSignal != 0){
                        remark += "MACD CROSS("+(macdCrossSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }
                    if(rsiSignal != 0){
                        remark += "RSI("+(rsiSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }
                    if(stochSignal != 0){
                        remark += "STOCH("+(stochSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }
                    if(stochasticRsiSignal != 0){
                        remark += "STOCHRSI("+(stochasticRsiSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }
                    if(movingAverageSignal != 0){
                        remark += "MA("+(movingAverageSignal == 1 ? "LONG" : "SHORT") + ") ";
                    }

                    String direction = technicalIndicatorReportEntity.getStrongSignal() != 0
                            ? (technicalIndicatorReportEntity.getStrongSignal() == 1 ? "LONG" : "SHORT") :
                            (technicalIndicatorReportEntity.getMidSignal() == 1 ? "LONG" : "SHORT") ;

                    String bigTrend = tradingEntity.getTrend15m();
                    if(tradingEntity.getTrendFollowFlag() ==1){
                        if(bigTrend.equals(direction)){ // 큰 트렌드와 일치할때 매매 진입
                            try {
                                makeOpenOrder(klineEvent, direction, remark);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    } else {
                        try {
                            makeOpenOrder(klineEvent, direction, remark);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }else{
            System.out.println("포지션 오픈중 : " +  eventEntity.get().getKlineEntity().getPositionEntity());
        }
        return klineEvent;
    }

    public void closeAllPositions(){
        log.info("모든 포지션 종료");
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        /*JSONArray balanceInfo = new JSONArray(client.account().futuresAccountBalance(new LinkedHashMap<>()));
        printPrettyJson(balanceInfo);*/
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        JSONArray positions = accountInfo.getJSONArray("positions");
        positions.forEach(position -> {
            JSONObject symbolObj = new JSONObject(position.toString());
            if(new BigDecimal(String.valueOf(symbolObj.get("positionAmt"))).compareTo(new BigDecimal("0")) != 0){
                LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
                String symbol = String.valueOf(symbolObj.get("symbol"));
                TRADING_ENTITYS.remove(symbol);
                paramMap.put("symbol", symbol);
                paramMap.put("positionSide", symbolObj.get("positionSide"));
                paramMap.put("side", symbolObj.get("positionSide").equals("LONG") ? "SELL" : "BUY");
                BigDecimal positionAmt = new BigDecimal(symbolObj.getString("positionAmt"));
                paramMap.put("quantity", positionAmt.abs()); // 절대값으로 설정
                paramMap.put("type", "MARKET");
                try {
                    Map<String,Object> resultMap = orderSubmit(paramMap);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        List<EventEntity> eventEntities = eventRepository.findEventByPositionStatus("OPEN");
        for(EventEntity eventEntity : eventEntities){
            eventEntity.getKlineEntity().getPositionEntity().setPositionStatus("CLOSE");
            eventRepository.save(eventEntity);
        }
        allStreamClose();
    }

    public void makeOpenOrder(EventEntity currentEvent, String positionSide, String remark){
        System.out.println(remark);
        PositionEntity openPosition = currentEvent.getKlineEntity().getPositionEntity();
        TradingEntity tradingEntity = currentEvent.getTradingEntity();

        tradingEntity.setPositionStatus("OPEN");
        tradingEntity.setPositionSide(positionSide);
        tradingEntity.setOpenPrice(currentEvent.getKlineEntity().getClosePrice());
        try {
            openPosition.setPositionStatus("OPEN");
            openPosition.setEntryPrice(currentEvent.getKlineEntity().getClosePrice());
            openPosition.setPositionSide(positionSide);
            openPosition.setOpenRemark(remark);

            //레버리지 변경
            LinkedHashMap<String,Object> leverageParamMap = new LinkedHashMap<>();
            leverageParamMap.put("symbol", tradingEntity.getSymbol());
            leverageParamMap.put("leverage", tradingEntity.getLeverage());
            leverageChange(leverageParamMap);

            //마진타입 변경(교차)
            LinkedHashMap<String,Object> marginTypeParamMap = new LinkedHashMap<>();
            marginTypeParamMap.put("symbol", tradingEntity.getSymbol());
            marginTypeParamMap.put("marginType", "CROSSED");

            try{
                marginTypeChange(marginTypeParamMap);
            } catch (Exception e){
                e.printStackTrace();
            }

            // 메인 주문과 스탑로스 주문 제출
            LinkedHashMap<String, Object> orderParams = makeOrder(tradingEntity, "OPEN");
            Map<String, Object> resultMap = orderSubmit(orderParams);

            // 스탑로스 주문 제출
            if (orderParams.containsKey("stopLossOrder")) {
                LinkedHashMap<String, Object> stopLossOrderParams = (LinkedHashMap<String, Object>) orderParams.get("stopLossOrder");
                orderSubmit(stopLossOrderParams);
            }

            System.out.println(CONSOLE_COLORS.BRIGHT_BACKGROUND_GREEN+"*********************[진입 - 진입사유]"+remark+"*********************"+CONSOLE_COLORS.RESET);

            //tradingRepository.save(tradingEntity);
        } catch (Exception e) {
            e.printStackTrace();
            //throw new TradingException(tradingEntity);
        } finally {
            eventRepository.save(currentEvent);
            System.out.println("openTradingEntity >>>>> " + tradingEntity);
            tradingRepository.save(tradingEntity);
        }
    }

    public void makeCloseOrder(EventEntity currentEvent, EventEntity positionEvent, String remark){
        System.out.println(remark);
        TradingEntity tradingEntity = positionEvent.getTradingEntity();
        PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
        tradingEntity.setClosePrice(currentEvent.getKlineEntity().getClosePrice());
        try { //마켓가로 클로즈 주문을 제출한다.
            closePosition.setCloseRemark(remark);
            closePosition.setPositionStatus("CLOSE");
            closePosition.setClosePrice(currentEvent.getKlineEntity().getClosePrice());
            Map<String, Object> resultMap = orderSubmit(makeOrder(tradingEntity, "CLOSE"));
            System.out.println(CONSOLE_COLORS.BRIGHT_BACKGROUND_RED+"*********************[청산/"+closePosition.getRealizatioPnl()+" - 청산사유]"+remark+"*********************"+CONSOLE_COLORS.RESET);
        } catch (Exception e) {
            e.printStackTrace();
            //throw new TradingException(tradingEntity);
        } finally {
            eventRepository.save(positionEvent);
            restartTrading(tradingEntity);
            System.out.println("closeTradingEntity >>>>> " + tradingEntity);
            log.info("스트림 종료");
            printTradingEntitys();
        }
    }

    public LinkedHashMap<String, Object> makeOrder(TradingEntity tradingEntity, String intent) {
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
        String symbol = tradingEntity.getSymbol();
        String positionSide = tradingEntity.getPositionSide();
        String side = ""; // BUY/SELL
        paramMap.put("symbol", symbol);

        if (intent.equals("OPEN")) {
            BigDecimal openPrice = tradingEntity.getOpenPrice();
            side = positionSide.equals("LONG") ? "BUY" : "SELL";
            paramMap.put("positionSide", positionSide);
            paramMap.put("side", side);
            BigDecimal notional = tradingEntity.getCollateral().multiply(new BigDecimal(tradingEntity.getLeverage()));
            if (notional.compareTo(getNotional(symbol)) > 0) {
                BigDecimal quantity = notional.divide(openPrice, 10, RoundingMode.UP);
                BigDecimal stepSize = getStepSize(symbol);
                quantity = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
                paramMap.put("quantity", quantity);

                // 레버리지를 반영하여 스탑로스 가격 계산
                BigDecimal stopLossPrice;
                if (positionSide.equals("LONG")) {
                    stopLossPrice = openPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(0.20).divide(new BigDecimal(tradingEntity.getLeverage()), 10, RoundingMode.HALF_UP)));
                } else {
                    stopLossPrice = openPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(0.20).divide(new BigDecimal(tradingEntity.getLeverage()), 10, RoundingMode.HALF_UP)));
                }
                stopLossPrice = stopLossPrice.setScale(getPricePrecision(symbol), RoundingMode.DOWN);
                LinkedHashMap<String, Object> stopLossOrder = makeStopLossOrder(tradingEntity, stopLossPrice, quantity);
                paramMap.put("stopLossOrder", stopLossOrder);
            } else {
                System.out.println("명목가치(" + notional + ")가 최소주문가능금액보다 작습니다.");
                // throw new TradingException(tradingEntity);
            }
        } else if (intent.equals("CLOSE")) {
            BigDecimal closePrice = tradingEntity.getClosePrice();
            paramMap.put("positionSide", positionSide);
            paramMap.put("side", positionSide.equals("LONG") ? "SELL" : "BUY");
            JSONObject currentPosition = getPosition(symbol, positionSide).orElseThrow(() -> new RuntimeException("포지션을 찾을 수 없습니다."));
            BigDecimal positionAmt = new BigDecimal(currentPosition.getString("positionAmt"));
            paramMap.put("quantity", positionAmt.abs()); // 절대값으로 설정
        }
        paramMap.put("type", "MARKET");
        return paramMap;
    }


    public LinkedHashMap<String, Object> makeStopLossOrder(TradingEntity tradingEntity, BigDecimal stopLossPrice, BigDecimal quantity) {
        LinkedHashMap<String, Object> stopLossParamMap = new LinkedHashMap<>();
        String symbol = tradingEntity.getSymbol();
        String positionSide = tradingEntity.getPositionSide();
        stopLossParamMap.put("symbol", symbol);
        stopLossParamMap.put("positionSide", positionSide);
        stopLossParamMap.put("side", positionSide.equals("LONG") ? "SELL" : "BUY");
        stopLossParamMap.put("type", "STOP_MARKET");
        stopLossParamMap.put("quantity", quantity);
        stopLossParamMap.put("stopPrice", stopLossPrice);
        return stopLossParamMap;
    }

    public Optional<JSONObject> getPosition(String symbol, String positionSide){
        AtomicReference<Optional<JSONObject>> positionOpt = new AtomicReference<>(Optional.empty());
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        JSONArray positions = accountInfo.getJSONArray("positions");
        positions.forEach(position -> { //포지션을 찾는다.
            JSONObject positionObj = new JSONObject(position.toString());
            if(positionObj.get("symbol").equals(symbol) && positionObj.get("positionSide").equals(positionSide)){
                System.out.println("포지션 : " + positionObj);
                positionOpt.set(Optional.of(positionObj));
            }
        });
        return positionOpt.get();
    }

    public Map<String, Object> leverageChange(LinkedHashMap<String, Object> leverageParam) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        try{
            UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
            String leverageChangeResult = client.account().changeInitialLeverage(leverageParam);
            resultMap.put("result", leverageChangeResult);
        } catch (Exception e) {
            throw e;
        }
        return resultMap;
    }

    public Map<String, Object> marginTypeChange(LinkedHashMap<String, Object> marginTypeParam) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        try{
            UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
            String leverageChangeResult = client.account().changeMarginType(marginTypeParam);
            resultMap.put("result", leverageChangeResult);
        } catch (Exception e) {
            throw e;
        }
        return resultMap;
    }

    public Map<String, Object> orderSubmit(HttpServletRequest request, LinkedHashMap<String, Object> requestParam) throws Exception {
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }
        return orderSubmit(requestParam);
    }

    public Map<String, Object> orderSubmit(LinkedHashMap<String, Object> requestParam) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        try{
            UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
            requestParam.remove("leverage");
            log.info("!!!new Order : " + requestParam);
            String orderResult = client.account().newOrder(requestParam);
            log.info("!!!result : " + orderResult);
            resultMap.put("result", orderResult);
        } catch (Exception e) {
            throw e;
        }
        return resultMap;
    }

    public Map<String, Object> orderSubmitCollateral(HttpServletRequest request, LinkedHashMap<String, Object> requestParam) throws Exception {
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }

        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        try{
            UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
            LinkedHashMap<String, Object> leverageParam = new LinkedHashMap<>();
            String symbol = String.valueOf(requestParam.get("symbol")).toUpperCase();
            leverageParam.put("symbol", requestParam.get("symbol"));
            leverageParam.put("leverage", 3);
            String leverageChangeResult = client.account().changeInitialLeverage(leverageParam);

            exchangeInfo = getExchangeInfo();

            LinkedHashMap<String, Object> klineMap = new LinkedHashMap<>();
            klineMap.put("symbol", symbol);
            klineMap.put("interval", "1m");
            String resultStr = client.market().klines(klineMap);
            JSONArray jsonArray = new JSONArray(resultStr);
            JSONArray klineArray = jsonArray.getJSONArray(jsonArray.length()-1);
            KlineEntity klineEntity = parseKlineEntity(klineArray);
            BigDecimal notional = new BigDecimal(String.valueOf(requestParam.get("collateral"))).multiply(new BigDecimal("3"));
            if (notional.compareTo(getNotional(symbol)) > 0) {
                BigDecimal quantity = notional.divide(klineEntity.getClosePrice(), 10, RoundingMode.UP);
                BigDecimal stepSize = getStepSize(symbol);
                quantity = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
                requestParam.put("quantity", quantity);
            }
            System.out.println("new Order : " + requestParam);
            String result = client.account().newOrder(requestParam);
            System.out.println("result : " + result);
            resultMap.put("result", result);
        } catch (Exception e) {
            throw e;
        }
        return resultMap;
    }

    public void reconnectStream(TradingEntity tradingEntity) {
        tradingEntity.setTradingStatus("CLOSE");
        tradingRepository.save(tradingEntity);
        autoTradeStreamOpen(tradingEntity);
    }

    public static BigDecimal roundQuantity(BigDecimal quantity, BigDecimal tickSize) {
        return quantity.divide(tickSize, 0, RoundingMode.UP).multiply(tickSize);
    }

    private void settingBar(String tradingCd, String event) {
        JSONObject eventObj = new JSONObject(event);
        JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
        JSONObject klineObj = new JSONObject(klineEventObj.get("k").toString());

        String seriesNm = String.valueOf(eventObj.get("stream"));
        String symbol = seriesNm.substring(0, seriesNm.indexOf("@"));
        String interval = seriesNm.substring(seriesNm.indexOf("_") + 1);
        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);
        //캔들데이터 분석
        ZonedDateTime closeTime = CommonUtils.convertTimestampToDateTime(klineObj.getLong("T")).atZone(ZoneOffset.UTC);
        ZonedDateTime kstEndTime = closeTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
        String formattedEndTime = formatter.format(kstEndTime);
        System.out.println("한국시간 : " + formattedEndTime);
        Num open   = series.numOf(klineObj.getDouble("o"));
        Num high   = series.numOf(klineObj.getDouble("h"));
        Num low    = series.numOf(klineObj.getDouble("l"));
        Num close  = series.numOf(klineObj.getDouble("c"));
        Num volume = series.numOf(klineObj.getDouble("v"));
        //series.addBar(closeTime, open, high, low, close, volume);
        Bar newBar = new BaseBar(Duration.ofMinutes(15), closeTime, open, high, low, close, volume ,null);
        Bar lastBar = series.getLastBar();
        if (!newBar.getEndTime().isAfter(lastBar.getEndTime())) {
            series.addBar(newBar, true);
        }else{
            series.addBar(newBar, false);
        }
    }

    private List<TradingEntity> getTradingEntity(String symbol) {
        return tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");
    }

    public void streamClose(int streamId) {
        log.info("streamClose >>>>> " + streamId);
        umWebSocketStreamClient.closeConnection(streamId);
    }

    public void allStreamClose() {
        log.info("모든 스트림 종료");
        List<TradingEntity> tradingEntityList = tradingRepository.findAll();
        tradingEntityList.stream().forEach(tradingEntity -> {
            if(tradingEntity.getTradingStatus().equals("OPEN")){
                tradingEntity.setTradingStatus("CLOSE");
                tradingRepository.save(tradingEntity);
                TRADING_ENTITYS.remove(tradingEntity.getSymbol());
                printTradingEntitys();
                streamClose(tradingEntity.getStreamId());
            }
        });
        //umWebSocketStreamClient.closeAllConnections();
    }

    public TradingEntity autoTradingClose(TradingEntity tradingEntity) { //특정 심볼의 트레이딩 종료
        log.info("스트림 종료");
        if(tradingEntity.getTradingStatus().equals("OPEN")){
            tradingEntity.setTradingStatus("CLOSE");
            tradingEntity = tradingRepository.save(tradingEntity);
            streamClose(tradingEntity.getStreamId());
        }
        return tradingEntity;
    }

    public boolean tradingValidate(List<Map<String, Object>> tradingTargetSymbols, int maxPositionCount){
        boolean nextFlag = true;
        try{
            int availablePositionCount = maxPositionCount - TRADING_ENTITYS.size();
            if (availablePositionCount <= 0) {
                throw new RuntimeException("오픈 가능한 트레이딩이 없습니다.");
            }
            if(tradingTargetSymbols.size() == 0){
                throw new RuntimeException("선택된 종목이 없습니다.");
            }else{
                for(Map<String,Object> tradingTargetSymbol : tradingTargetSymbols){
                    String symbol = String.valueOf(tradingTargetSymbol.get("symbol"));
                    Optional<TradingEntity> tradingEntityOpt = Optional.ofNullable(TRADING_ENTITYS.get(symbol));
                    if(tradingEntityOpt.isPresent()){
                        printTradingEntitys();
                        throw new RuntimeException(tradingEntityOpt.get().getSymbol() + "이미 오픈된 트레이딩이 존재합니다.");
                    }
                }
            }
        } catch (Exception e) {
            nextFlag = false;
        }
        return nextFlag;
    }

    public void printTradingEntitys(){
        System.out.println("현재 오픈된 포지션 >>>>>");
        TRADING_ENTITYS.forEach((symbol, tradingEntity) -> {
            System.out.println("symbol : " + symbol);
        });
    }

    public TradingEntity autoTradeStreamOpen(TradingEntity tradingEntity) {
        //tradingRepository.save(tradingEntity);
        log.info("klineStreamOpen >>>>> ");
        ArrayList<String> streams = new ArrayList<>();

        String klineStreamName = tradingEntity.getSymbol().toLowerCase() + "@kline_" + tradingEntity.getCandleInterval();
        streams.add(klineStreamName);

        String forceOrderStreamName = tradingEntity.getSymbol().toLowerCase() + "@forceOrder";
        //streams.add(forceOrderStreamName);

        String depthStreamName = tradingEntity.getSymbol().toLowerCase() + "@depth";
        //streams.add(depthStreamName);*/

        String aggTradeStreamName = tradingEntity.getSymbol().toLowerCase() + "@aggTrade";
        //streams.add(aggTradeStreamName);

        String allMarketForceOrderStreamName = "!forceOrder@arr";
        //streams.add(allMarketForceOrderStreamName);

        tradingEntity = umWebSocketStreamClient.combineStreams(tradingEntity, streams, openCallback, onMessageCallback, closeCallback, failureCallback);
        return tradingEntity;
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

    public Map<String,Object> getStockFind(HttpServletRequest request, TradingDTO tradingDTO){
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }
        TradingEntity tradingEntity = tradingDTO.toEntity();
        tradingEntity.setTradingCd(UUID.randomUUID().toString());
        tradingEntity.setUserCd(userCd);
        return getStockFind(tradingEntity);
    }

    public Map<String, Object> getStockFind(TradingEntity tradingEntity) {
        //변수 설정
        String interval = tradingEntity.getCandleInterval();
        int maxPositionCount = tradingEntity.getMaxPositionCount();
        int stockSelectionCount = tradingEntity.getStockSelectionCount();

        Map<String, Object> resultMap = new LinkedHashMap<>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
        //String resultStr = umFuturesClientImpl.market().tickerSymbol(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);
        //printPrettyJson(resultArray);

        // 거래량(QuoteVolume - 기준 화폐)을 기준으로 내림차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByQuoteVolume = getSort(resultArray, "quoteVolume", "DESC", stockSelectionCount);
        //System.out.println("sortedByQuoteVolume : " + sortedByQuoteVolume);
        List<Map<String, Object>> overlappingData = new ArrayList<>();
        List<TechnicalIndicatorReportEntity> reports = new ArrayList<>();

        int count = 0;
        for (Map<String, Object> item : sortedByQuoteVolume) {
            int availablePositionCount = maxPositionCount - TRADING_ENTITYS.size();
            if (availablePositionCount <= 0) {
                break;
            }
            if (count >= availablePositionCount) {
                break;
            }

            String symbol = String.valueOf(item.get("symbol"));

            //트레이딩 데이터 수집의 주가 되는 객체
            TradingEntity tempTradingEntity = tradingEntity.clone();
            tempTradingEntity.setSymbol(symbol);

            List<TradingEntity> tradingEntityList = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");
            if (tradingEntityList.isEmpty()) { //오픈된 트레이딩이 없다면

                HashMap<String, Object> trendMap = trendValidate(tempTradingEntity);
                String trend4h = String.valueOf(trendMap.get("trend4h"));
                String trend1h = String.valueOf(trendMap.get("trend1h"));
                String trend15m = String.valueOf(trendMap.get("trend15m"));
                tradingEntity.setTrend4h(trend4h);
                tradingEntity.setTrend1h(trend1h);
                tradingEntity.setTrend15m(trend15m);

                boolean resultFlag = trendMap.get("resultFlag").equals(true);

                if (resultFlag) {
                    tempTradingEntity.setTrend4h(trend4h);
                    tempTradingEntity.setTrend1h(trend1h);
                    tempTradingEntity.setTrend15m(trend15m);

                    /* TODO : 현재 제일 작업이 필요한 부분 - 필요한 트레이딩 데이터를 수집하고 종목을 선정함 */
                    Map<String, Object> klineMap = getKlines(tempTradingEntity,true); //klines - candle 데이터를 말한다.
                    Optional<Object> expectationProfitOpt = Optional.ofNullable(klineMap.get("expectationProfit"));
                    TechnicalIndicatorReportEntity tempReport = technicalIndicatorCalculate(tempTradingEntity);
                    if (expectationProfitOpt.isPresent()){
                        BigDecimal expectationProfit = (BigDecimal) expectationProfitOpt.get();
                        BigDecimal winTradeCount = new BigDecimal(String.valueOf(klineMap.get("winTradeCount")));
                        BigDecimal loseTradeCount = new BigDecimal(String.valueOf(klineMap.get("loseTradeCount")));
                        if (
                                true
                                &&expectationProfit.compareTo(BigDecimal.ONE) > 0
                                && (winTradeCount.compareTo(loseTradeCount) > 0)
                                //&& tempReport.getCurrentAdxGrade().getGrade()>1
                                //&& tempReport.getMarketCondition() == 1
                        ) {
                            System.out.println("[관심종목추가]symbol : " + symbol + " expectationProfit : " + expectationProfit);
                            overlappingData.add(item);
                            reports.add(tempReport);
                            count++;
                        }
                    }
                }
            }
        }

        for(Map<String, Object> item : overlappingData){
            System.out.println("관심종목 : " + item);
        }

        //System.out.println("overlappingData : " + overlappingData);

        resultMap.put("reports", reports);
        resultMap.put("overlappingData", overlappingData);
        return resultMap;
    }

    public HashMap<String,Object> trendValidate(TradingEntity tradingEntity){
        HashMap<String, Object> returnMap = new HashMap<>();
        //트레이딩 데이터 4h
        TradingEntity tempTradingEntity4h = tradingEntity.clone();
        tempTradingEntity4h.setCandleInterval("4h");
        tempTradingEntity4h.setCandleCount(10);
        //트레이딩 데이터 1h
        TradingEntity tempTradingEntity1h = tradingEntity.clone();
        tempTradingEntity1h.setCandleInterval("1h");
        tempTradingEntity1h.setCandleCount(10);
        //트레이딩 데이터 15m
        TradingEntity tempTradingEntity15m = tradingEntity.clone();
        tempTradingEntity15m.setCandleInterval("15m");
        tempTradingEntity15m.setCandleCount(10);

        String trend4h = "";
        String trend1h = "";
        String trend15m = "";

        // 4시간봉 데이터 수집
        Map<String, Object> kline4hMap =getKlines(tempTradingEntity4h, false);
        trend4h = String.valueOf(kline4hMap.get("currentTrendDi"));
        //String.valueOf(kline4hMap.get("currentTrendMa"));

        // 1시간봉 데이터 수집
        Map<String, Object> kline1hMap = getKlines(tempTradingEntity1h, false);
        trend1h = String.valueOf(kline1hMap.get("currentTrendDi"));
        //String.valueOf(kline1hMap.get("currentTrendMa"));

        // 15분봉 데이터 수집
        Map<String, Object> kline15mMap = getKlines(tempTradingEntity15m, false);
        trend15m = String.valueOf(kline15mMap.get("currentTrendDi"));
        //String.valueOf(kline15mMap.get("currentTrendMa"));

        System.out.println("symbol trend : " + tradingEntity.getSymbol() + " / trend4h(" + trend4h + ") trend1h (" + trend1h + " ) / trend15m (" + trend15m+")");

        boolean resultFlag = true
                //&& trend4h.equals(trend1h)
                //&& trend1h.equals(trend15m)
                ;

        returnMap.put("trend4h", trend4h);
        returnMap.put("trend1h", trend1h);
        returnMap.put("trend15m", trend15m);
        returnMap.put("resultFlag", resultFlag);

        return returnMap;
    }

    public BigDecimal calculateProfit(TradingEntity tradingEntity){
        BigDecimal 차익 = BigDecimal.ZERO;
        //계약수량=계약금액×레버리지÷진입가격
        BigDecimal 계약수량 =tradingEntity.getCollateral()
                .multiply(new BigDecimal(tradingEntity.getLeverage()))
                .divide(tradingEntity.getOpenPrice(), 10, RoundingMode.UP);
        //총수수료=2×거래수수료×계약수량×진입가격
        BigDecimal 총수수료 = 계약수량.multiply(new BigDecimal("0.0004"))
                .multiply(tradingEntity.getOpenPrice())
                .multiply(new BigDecimal("2"));
        if (tradingEntity.getPositionSide().equals("LONG")) {
            //롱차익=[(청산가격−진입가격)×계약수량]−총수수료
            차익 = (tradingEntity.getClosePrice().subtract(tradingEntity.getOpenPrice()))
                    .multiply(계약수량).subtract(총수수료);
        } else {
            //숏차익=[(진입가격−청산가격)×계약수량]−총수수료
            차익 = (tradingEntity.getOpenPrice().subtract(tradingEntity.getClosePrice()))
                    .multiply(계약수량).subtract(총수수료);
        }
        return 차익;
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

    public List<Map<String, Object>> getSort(JSONArray resultArray, String sortBy, String orderBy, int limit) {
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
                .filter(item -> !item.get("symbol").toString().toLowerCase().contains("usdc"))
                //제외 종목
               /* .filter(item -> !item.get("symbol").toString().toLowerCase().contains("btc"))
                .filter(item -> !item.get("symbol").toString().toLowerCase().contains("eth"))
                .filter(item -> !item.get("symbol").toString().toLowerCase().contains("xrp"))
                .filter(item -> !item.get("symbol").toString().toLowerCase().contains("sol"))
                .filter(item -> !item.get("symbol").toString().toLowerCase().contains("ada"))*/
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

    public Map<String, Object> autoTradingInfo(HttpServletRequest request) throws Exception {
        log.info("autoTradingInfo >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        //List<TradingEntity> tradingEntityList = tradingRepository.findAll();
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        System.out.println("userCd : " + userCd);
        List<TradingEntity> tradingEntityList = tradingRepository.findByUserCd(userCd);
        resultMap.put("tradingEntityList", tradingEntityList);
        return resultMap;
    }

    public Map<String, Object> getEvent(String symbol) throws Exception {
        log.info("getEvents >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        Optional<EventEntity> eventEntityOpt = eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN");
        eventEntityOpt.ifPresent(eventEntity -> {
            System.out.println("eventEntity : " + eventEntity);
            System.out.println("getKlineEntity : " + eventEntity.getKlineEntity().getPositionEntity());
            System.out.println("getTechnicalIndicatorReportEntity : " + eventEntity.getKlineEntity().getTechnicalIndicatorReportEntity());
            resultMap.put("eventEntity", eventEntity);
        });
        return resultMap;
    }

    public Map<String, Object> getReports(String tradingCd) throws Exception {
        log.info("getReports >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        List<EventEntity> eventEntityList = eventRepository.findEventsByTradingCd(tradingCd);
        resultMap.put("eventEntityList", eventEntityList);
        return resultMap;
    }

    public Map<String, Object> accountInfo() throws Exception {
        log.info("accountInfo >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        //WebSocketApiClient client = new WebSocketApiClientImpl(BINANCE_API_KEY, signatureGenerator, BASE_URL);
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);

        JSONArray balanceInfo = new JSONArray(client.account().futuresAccountBalance(new LinkedHashMap<>()));
        printPrettyJson(balanceInfo);

        /*JSONArray positionInfo = new JSONArray(client.account().getPositionMarginChangeHistory(new LinkedHashMap<>(Map.of("symbol", "BTCUSDT"))));
        printPrettyJson(positionInfo);*/

        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        printPrettyJson(accountInfo);
        JSONArray positionArr = findObjectByValue(accountInfo.getJSONArray("positions"), "symbol", "BTCUSDT");
        System.out.println("[BTCUSDT 포지션 정보]");
        printPrettyJson(positionArr);
        //findObjectByValue(accountInfo.getJSONArray("positions"), "symbol", "BTCUSDT").ifPresent(System.out::println);

        System.out.println("사용가능 : " +accountInfo.get("availableBalance"));
        System.out.println("담보금 : " + accountInfo.get("totalWalletBalance"));
        System.out.println("미실현수익 : " + accountInfo.get("totalUnrealizedProfit"));
        System.out.println("현재자산 : " + accountInfo.get("totalMarginBalance"));
        return resultMap;
    }

    public static JSONArray findObjectByValue(JSONArray jsonArray, String key, String value) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);
            if (jsonObject.getString(key).equals(value)) {
                result.put(jsonObject);
            }
        }
        return result;
    }

    public void printPrettyJson(Object jsonObj) {
        if (jsonObj instanceof JSONObject) {
            // JSONObject인 경우
            JSONObject jsonObject = (JSONObject) jsonObj;
            System.out.println(jsonObject.toString(2));  // 들여쓰기 2칸
        } else if (jsonObj instanceof JSONArray) {
            // JSONArray인 경우
            JSONArray jsonArray = (JSONArray) jsonObj;
            System.out.println(jsonArray.toString(2));  // 들여쓰기 2칸
        } else {
            System.out.println("Invalid JSON object.");
        }
    }


    public void printPrettyJsonString(String jsonStr) {
        // Trim the string to handle any leading or trailing spaces
        String trimmedJsonStr = jsonStr.trim();

        // Determine if the JSON string is an object or array
        if (trimmedJsonStr.startsWith("{")) {
            // It's a JSONObject
            JSONObject jsonObject = new JSONObject(trimmedJsonStr);
            System.out.println(jsonObject.toString(2));
        } else if (trimmedJsonStr.startsWith("[")) {
            // It's a JSONArray
            JSONArray jsonArray = new JSONArray(trimmedJsonStr);
            System.out.println(jsonArray.toString(2));
        } else {
            System.out.println("Invalid JSON string.");
        }
    }

    public Map<String, Object> getKlines(HttpServletRequest request, TradingDTO tradingDTO) {
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }
        TradingEntity tradingEntity = tradingDTO.toEntity();
        tradingEntity.setTradingCd(UUID.randomUUID().toString());
        tradingEntity.setUserCd(userCd);
        return getKlines(tradingEntity, true);
    }


    // 가상 트레이딩으로 사용되고 있다.
    public Map<String, Object> getKlines(TradingEntity tradingEntity, boolean logFlag) {
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        //printPrettyJson(accountInfo);

        if(logFlag){
            System.out.println("사용가능 : " +accountInfo.get("availableBalance"));
            System.out.println("담보금 : " + accountInfo.get("totalWalletBalance"));
            System.out.println("미실현수익 : " + accountInfo.get("totalUnrealizedProfit"));
            System.out.println("현재자산 : " + accountInfo.get("totalMarginBalance"));
        }

        BigDecimal availableBalance = new BigDecimal(String.valueOf(accountInfo.get("availableBalance")));
        BigDecimal totalWalletBalance = new BigDecimal(String.valueOf(accountInfo.get("totalWalletBalance")));

        BigDecimal maxPositionAmount = totalWalletBalance
                .divide(new BigDecimal(tradingEntity.getMaxPositionCount()),0, RoundingMode.DOWN)
                .multiply(tradingEntity.getCollateralRate()).setScale(0, RoundingMode.DOWN);

        tradingEntity.setCollateral(maxPositionAmount); // 해당 빠따든 놈에게 할당된 담보금을 세팅 해준다

        String tradingCd = tradingEntity.getTradingCd();
        String symbol = tradingEntity.getSymbol();
        String interval = tradingEntity.getCandleInterval();
        int candleCount = tradingEntity.getCandleCount();
        int limit = candleCount;
        long startTime = System.currentTimeMillis(); // 시작 시간 기록

        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        paramMap.put("symbol", symbol); // BTCUSDT
        paramMap.put("interval", interval); // 1m, 5m, 15m, 1h, 4h, 1d
        paramMap.put("limit", limit); // 최대 1500개까지 가져올수 있음.

        client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY, true);
        String resultStr = client.market().klines(paramMap); // 바이낸스에서 캔들데이터를 제이슨으로 반환함.

        //System.out.println("resultStr : "+ resultStr);
        String weight = new JSONObject(resultStr).getString("x-mbx-used-weight-1m");
        System.out.println("*************** [현재 가중치 : " + weight + "] ***************");
        JSONArray jsonArray = new JSONArray(new JSONObject(resultStr).get("data").toString());
        List<KlineEntity> klineEntities = new ArrayList<>();

        // TA4J 라이브러리 사용 -- 0.16 버전인데 이전버전의 정보가 너무 많다. !주의
        BaseBarSeries series = new BaseBarSeries();
        series.setMaximumBarCount(limit);
        seriesMap.put(tradingCd + "_" + interval, series);

        ArrayList<TechnicalIndicatorReportEntity> technicalIndicatorReportEntityArr = new ArrayList<>();

        BigDecimal expectationProfit = BigDecimal.ZERO;
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray klineArray = jsonArray.getJSONArray(i);
            KlineEntity klineEntity = parseKlineEntity(klineArray);
            //System.out.println(klineArray);
            klineEntities.add(klineEntity);

            Num open = series.numOf(klineEntity.getOpenPrice());
            Num high = series.numOf(klineEntity.getHighPrice());
            Num low = series.numOf(klineEntity.getLowPrice());
            Num close = series.numOf(klineEntity.getClosePrice());
            Num volume = series.numOf(klineEntity.getVolume());

            // 바이낸스에서 가져온 캔들데이터를 시리즈(TA4J의 컬렉션 객체)에다가 세팅하는거
            series.addBar(klineEntity.getEndTime().atZone(ZoneOffset.UTC), open, high, low, close, volume);

            //기술적 지표를 세팅하는 부분
            if(i!=0){
                TechnicalIndicatorReportEntity tempReport = technicalIndicatorCalculate(tradingEntity);
                technicalIndicatorReportEntityArr.add(tempReport);

                if(tradingEntity.getPositionStatus()!=null && tradingEntity.getPositionStatus().equals("OPEN")){
                    BigDecimal currentROI = TechnicalIndicatorCalculator.calculateROI(tradingEntity.getOpenPrice(), tempReport.getClosePrice(), tradingEntity.getLeverage(), tradingEntity.getPositionSide());
                    BigDecimal currentPnl = TechnicalIndicatorCalculator.calculatePnL(tradingEntity.getOpenPrice(), tempReport.getClosePrice(), tradingEntity.getLeverage(), tradingEntity.getPositionSide(), tradingEntity.getCollateral());
                    if(logFlag){
                        System.out.println("ROI : " + currentROI+"/"+"PnL : " + currentPnl);
                    }
                    if((tempReport.getStrongSignal() < 0
                        || tempReport.getMidSignal() < 0
                        || tempReport.getWeakSignal() < 0)
                        && tradingEntity.getPositionSide().equals("LONG")){
                        tradingEntity.setPositionStatus("CLOSE");
                        tradingEntity.setClosePrice(tempReport.getClosePrice());

                        BigDecimal currentProfit = calculateProfit(tradingEntity);
                        if(logFlag) {
                            System.out.println("현재 수익(" + tradingEntity.getSymbol() + "/" + tradingEntity.getOpenPrice() + ">>>" + tradingEntity.getClosePrice() + ") : " + currentProfit);
                        }
                        expectationProfit = expectationProfit.add(currentProfit);

                        if (currentProfit.compareTo(BigDecimal.ZERO) < 0) {
                            tradingEntity.setLoseTradeCount(tradingEntity.getLoseTradeCount() + 1);
                        }else if (currentProfit.compareTo(BigDecimal.ZERO) > 0) {
                            tradingEntity.setWinTradeCount(tradingEntity.getWinTradeCount() + 1);
                        }
                    } else if ((tempReport.getStrongSignal() > 0
                            || tempReport.getMidSignal() > 0
                            ||tempReport.getWeakSignal() > 0)
                            && tradingEntity.getPositionSide().equals("SHORT")){
                        tradingEntity.setPositionStatus("CLOSE");
                        tradingEntity.setClosePrice(tempReport.getClosePrice());

                        BigDecimal currentProfit = calculateProfit(tradingEntity);
                        if(logFlag) {
                            System.out.println("현재 수익(" + tradingEntity.getSymbol() + "/" + tradingEntity.getOpenPrice() + ">>>" + tradingEntity.getClosePrice() + ") : " + currentProfit);
                        }
                        expectationProfit = expectationProfit.add(currentProfit);

                        if (currentProfit.compareTo(BigDecimal.ZERO) < 0) {
                            tradingEntity.setLoseTradeCount(tradingEntity.getLoseTradeCount() + 1);
                        }else if (currentProfit.compareTo(BigDecimal.ZERO) > 0) {
                            tradingEntity.setWinTradeCount(tradingEntity.getWinTradeCount() + 1);
                        }
                    } else if (currentROI.compareTo(new BigDecimal("-20")) < 0){
                        tradingEntity.setPositionStatus("CLOSE");
                        BigDecimal stopLossPrice = BigDecimal.ZERO;
                        if (tradingEntity.getPositionSide().equals("LONG")) {
                            stopLossPrice = tradingEntity.getOpenPrice().multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(0.20).divide(new BigDecimal(tradingEntity.getLeverage()), 10, RoundingMode.HALF_UP)));
                        } else {
                            stopLossPrice = tradingEntity.getOpenPrice().multiply(BigDecimal.ONE.add(BigDecimal.valueOf(0.20).divide(new BigDecimal(tradingEntity.getLeverage()), 10, RoundingMode.HALF_UP)));
                        }
                        stopLossPrice = stopLossPrice.setScale(getPricePrecision(symbol), RoundingMode.DOWN);
                        tradingEntity.setClosePrice(stopLossPrice);

                        BigDecimal currentProfit = calculateProfit(tradingEntity);
                        if(logFlag) {
                            System.out.println("강제 손절(" + tradingEntity.getSymbol() + "/" + tradingEntity.getOpenPrice() + ">>>" + tradingEntity.getClosePrice() + ") : " + currentProfit);
                        }
                        if (currentProfit.compareTo(BigDecimal.ZERO) < 0) {
                            tradingEntity.setLoseTradeCount(tradingEntity.getLoseTradeCount() + 1);
                        }else if (currentProfit.compareTo(BigDecimal.ZERO) > 0) {
                            tradingEntity.setWinTradeCount(tradingEntity.getWinTradeCount() + 1);
                        }
                    }
                    /*else if (tempReport.getMacdHistogramGap() < 0 && tradingEntity.getPositionSide().equals("LONG")
                            || tempReport.getMacdHistogramGap() > 0 && tradingEntity.getPositionSide().equals("SHORT")){
                        tradingEntity.setPositionStatus("CLOSE");
                        tradingEntity.setClosePrice(tempReport.getClosePrice());

                        BigDecimal currentProfit = calculateProfit(tradingEntity);
                        System.out.println("청산 MACD HISTOGRAM 역전 시그널");
                        if (currentProfit.compareTo(BigDecimal.ZERO) < 0) {
                            tradingEntity.setLoseTradeCount(tradingEntity.getLoseTradeCount() + 1);
                        }else if (currentProfit.compareTo(BigDecimal.ZERO) > 0) {
                            tradingEntity.setWinTradeCount(tradingEntity.getWinTradeCount() + 1);
                        }
                    }*/
                } else if(tradingEntity.getPositionStatus() == null || tradingEntity.getPositionStatus().equals("CLOSE")){
                    if (tempReport.getStrongSignal() != 0 || tempReport.getMidSignal() != 0) {
                        if (tempReport.getStrongSignal() < 0 || tempReport.getMidSignal() < 0) {
                            tradingEntity.setPositionSide("SHORT");
                            tradingEntity.setPositionStatus("OPEN");
                            tradingEntity.setOpenPrice(tempReport.getClosePrice());
                        } else {
                            tradingEntity.setPositionSide("LONG");
                            tradingEntity.setPositionStatus("OPEN");
                            tradingEntity.setOpenPrice(tempReport.getClosePrice());
                        }
                        tradingEntity.setTotalTradeCount(tradingEntity.getTotalTradeCount() + 1);
                    }
                }
            }
        }
        if(logFlag) {
            System.out.println("최종예상 수익(" + tradingEntity.getSymbol() + ") : " + expectationProfit);
            System.out.println("최종예상 승률(" + tradingEntity.getSymbol() + ") : " + (tradingEntity.getWinTradeCount() + "/" + tradingEntity.getLoseTradeCount()));
        }
        klines.put(symbol, klineEntities);
        resultMap.put("tempTradingEntity", tradingEntity);
        resultMap.put("result", klineEntities);
        resultMap.put("technicalIndicatorReportEntityArr", technicalIndicatorReportEntityArr);
        resultMap.put("expectationProfit", expectationProfit);
        resultMap.put("winTradeCount" , tradingEntity.getWinTradeCount());
        resultMap.put("loseTradeCount", tradingEntity.getLoseTradeCount());
        resultMap.put("currentTrendDi", technicalIndicatorReportEntityArr.get(technicalIndicatorReportEntityArr.size()-1).getDirectionDi());
        resultMap.put("currentTrendMa", technicalIndicatorReportEntityArr.get(technicalIndicatorReportEntityArr.size()-1).getDirectionMa());

        long endTime = System.currentTimeMillis(); // 종료 시간 기록
        long elapsedTime = endTime - startTime; // 실행 시간 계산
        if(logFlag) {
            System.out.println("소요시간 : " + elapsedTime + " milliseconds");
        }
        return resultMap;
    }

    private BigDecimal getNotional(String symbol) {
        JSONArray symbols = getSymbols(exchangeInfo);
        JSONObject symbolInfo = getSymbolInfo(symbols, symbol);
        JSONObject priceFilter = getFilterInfo(symbolInfo, "MIN_NOTIONAL");
        return new BigDecimal(priceFilter.getString("notional"));
    }

    private BigDecimal getTickSize(String symbol) { //최소 주문가능 금액
        JSONArray symbols = getSymbols(exchangeInfo);
        JSONObject symbolInfo = getSymbolInfo(symbols, symbol);
        JSONObject priceFilter = getFilterInfo(symbolInfo, "PRICE_FILTER");
        return new BigDecimal(priceFilter.getString("tickSize"));
    }

    private BigDecimal getMinQty(String symbol) { //최소 주문가능 금액
        JSONArray symbols = getSymbols(exchangeInfo);
        JSONObject symbolInfo = getSymbolInfo(symbols, symbol);
        JSONObject lotSizeFilter = getFilterInfo(symbolInfo, "LOT_SIZE");
        return new BigDecimal(lotSizeFilter.getString("minQty"));
    }

    private BigDecimal getMaxQty(String symbol) { //최대 주문가능 금액
        JSONArray symbols = getSymbols(exchangeInfo);
        JSONObject symbolInfo = getSymbolInfo(symbols, symbol);
        JSONObject lotSizeFilter = getFilterInfo(symbolInfo, "LOT_SIZE");
        return new BigDecimal(lotSizeFilter.getString("maxQty")).subtract(getStepSize(symbol));
    }

    private BigDecimal getStepSize(String symbol) { //최소 주문가능 금액
        JSONArray symbols = getSymbols(exchangeInfo);
        JSONObject symbolInfo = getSymbolInfo(symbols, symbol);
        JSONObject lotSizeFilter = getFilterInfo(symbolInfo, "LOT_SIZE");
        return new BigDecimal(lotSizeFilter.getString("stepSize"));
    }

    private int getPricePrecision(String symbol) { //최소 주문가능 금액
        JSONArray symbols = getSymbols(exchangeInfo);
        JSONObject symbolInfo = getSymbolInfo(symbols, symbol);
        return symbolInfo.getInt("pricePrecision");
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

    private TechnicalIndicatorReportEntity technicalIndicatorCalculate(String tradingCd, String symbol, String interval){
        TradingEntity tradingEntity = TradingEntity.builder()
                .tradingCd(tradingCd)
                .symbol(symbol)
                .candleInterval(interval)
                .build();
        return technicalIndicatorCalculate(tradingEntity);
    }

    private TechnicalIndicatorReportEntity technicalIndicatorCalculate(TradingEntity tradingEntity) {
        // 변수 설정 -- tradingEntity에서 필요한 값 추출
        String tradingCd = tradingEntity.getTradingCd();
        String symbol = tradingEntity.getSymbol();
        String interval = tradingEntity.getCandleInterval();

        String trend4h = tradingEntity.getTrend4h();
        String trend1h = tradingEntity.getTrend1h();
        String trend15m = tradingEntity.getTrend15m();

        //매매전략 변수 설정 -- tradingEntity에서 필요한 값 추출

        ArrayList<HashMap<String,Object>> technicalIndicatorCheckers = new ArrayList<>();

        int trendFollowFlag = tradingEntity.getTrendFollowFlag();

        int adxChecker = tradingEntity.getAdxChecker();
        int macdHistogramChecker = tradingEntity.getMacdHistogramChecker();
        int macdCrossChecker = tradingEntity.getMacdCrossChecker();
        int stochChecker = tradingEntity.getStochChecker();
        int stochRsiChecker = tradingEntity.getStochRsiChecker();
        int rsiChecker = tradingEntity.getRsiChecker();
        int bollingerBandChecker = tradingEntity.getBollingerBandChecker();
        int movingAverageChecker = tradingEntity.getMovingAverageChecker();

        if (adxChecker == 1) {
            HashMap<String, Object> adxCheckerMap = new HashMap<>();
            adxCheckerMap.put("key", "adxChecker");
            adxCheckerMap.put("value", adxChecker);
            technicalIndicatorCheckers.add(adxCheckerMap);
        }
        if (macdHistogramChecker == 1) {
            HashMap<String, Object> macdHistogramCheckerMap = new HashMap<>();
            macdHistogramCheckerMap.put("key", "macdHistogramChecker");
            macdHistogramCheckerMap.put("value", macdHistogramChecker);
            technicalIndicatorCheckers.add(macdHistogramCheckerMap);
        }
        if (stochChecker == 1) {
            HashMap<String, Object> stochCheckerMap = new HashMap<>();
            stochCheckerMap.put("key", "stochChecker");
            stochCheckerMap.put("value", stochChecker);
            technicalIndicatorCheckers.add(stochCheckerMap);
        }
        if (stochRsiChecker == 1) {
            HashMap<String, Object> stochRsiCheckerMap = new HashMap<>();
            stochRsiCheckerMap.put("key", "stochRsiChecker");
            stochRsiCheckerMap.put("value", stochRsiChecker);
            technicalIndicatorCheckers.add(stochRsiCheckerMap);
        }
        if (rsiChecker == 1) {
            HashMap<String, Object> rsiCheckerMap = new HashMap<>();
            rsiCheckerMap.put("key", "rsiChecker");
            rsiCheckerMap.put("value", rsiChecker);
            technicalIndicatorCheckers.add(rsiCheckerMap);
        }
        if (bollingerBandChecker == 1) {
            HashMap<String, Object> bollingerBandCheckerMap = new HashMap<>();
            bollingerBandCheckerMap.put("key", "bollingerBandChecker");
            bollingerBandCheckerMap.put("value", bollingerBandChecker);
            technicalIndicatorCheckers.add(bollingerBandCheckerMap);
        }
        if (movingAverageChecker == 1) {
            HashMap<String, Object> movingAverageCheckerMap = new HashMap<>();
            movingAverageCheckerMap.put("key", "movingAverageChecker");
            movingAverageCheckerMap.put("value", movingAverageChecker);
            technicalIndicatorCheckers.add(movingAverageCheckerMap);
        }

        //int[] technicalIndicatorCheckers = {adxChecker, macdHistogramChecker, stochChecker, stochRsiChecker, rsiChecker, bollingerBandChecker};

        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval); //{tradingCd}_{interval} 로 특정한다
        // 포맷 적용하여 문자열로 변환
        ZonedDateTime utcEndTime = series.getBar(series.getEndIndex()).getEndTime(); //캔들이 !!!끝나는 시간!!!
        ZonedDateTime kstEndTime = utcEndTime.withZoneSameInstant(ZoneId.of("Asia/Seoul")); //한국시간 설정
        String formattedEndTime = formatter.format(kstEndTime);

        //tickSize
        BigDecimal tickSize = getTickSize(symbol.toUpperCase()); //해당 심볼의 틱사이즈(해당 페어의 최소로 보여지는 금액단위를 말한다)

        // Define indicators
        OpenPriceIndicator  openPrice   = new OpenPriceIndicator(series); //시가
        ClosePriceIndicator closePrice  = new ClosePriceIndicator(series); //종가
        HighPriceIndicator  highPrice   = new HighPriceIndicator(series); //고가
        LowPriceIndicator   lowPrice    = new LowPriceIndicator(series); //저가

        String krTimeAndPriceExpressiont = "["+formattedEndTime+CONSOLE_COLORS.CYAN+"/"+symbol+"/"+closePrice.getValue(series.getEndIndex())+"] ";
        String currentLogPrefix = krTimeAndPriceExpressiont;
        String commonRemark  = currentLogPrefix;
        String specialRemark = currentLogPrefix;

        //******************************** 이동평균선 관련 산식을 정의한다 **********************************
        int shortMovingPeriod = 3;   //단기이동평균 기간
        int middleMovingPeriod = 5;  //중기이동평균 기간
        int longMovingPeriod  = 10;  //장기이동평균 기간

        SMAIndicator sma = new SMAIndicator(closePrice, shortMovingPeriod); //단기 이동평균선
        EMAIndicator ema = new EMAIndicator(closePrice, longMovingPeriod);  //장기 이동평균선
        CrossedUpIndicatorRule crossUp = new CrossedUpIndicatorRule(sma, ema); //단기 이평선이 장기 이평선을 상향 돌파
        CrossedDownIndicatorRule crossDown = new CrossedDownIndicatorRule(sma, ema); //단기 이평선이 장기 이평선을 하향 돌파

        // 이동평균선 교차 신호 설정
        int movingAverageSignal = 0;
        int endIndex = series.getEndIndex();

        if (endIndex >= 1) {
            Num previousShortSma = sma.getValue(endIndex - 1);
            Num previousLongEma = ema.getValue(endIndex - 1);
            Num currentShortSma = sma.getValue(endIndex);
            Num currentLongEma = ema.getValue(endIndex);

            if (currentShortSma.isGreaterThan(currentLongEma) && previousShortSma.isLessThanOrEqual(previousLongEma)) {
                movingAverageSignal = -1; // 매수 신호
                specialRemark += CONSOLE_COLORS.BRIGHT_GREEN+"[이동평균선 상향돌파]"+CONSOLE_COLORS.RESET;
            } else if (currentShortSma.isLessThan(currentLongEma) && previousShortSma.isGreaterThanOrEqual(previousLongEma)) {
                movingAverageSignal = 1; // 매도 신호
                specialRemark += CONSOLE_COLORS.BRIGHT_RED+"[이동평균선 하향돌파]"+CONSOLE_COLORS.RESET;
            }
        }

        //**************************************** 이동평균선 끝 ******************************************

        // Calculate RSI
        int rsiPeriod = middleMovingPeriod;   //RSI 기간
        RSIIndicator rsi = new RSIIndicator(closePrice, rsiPeriod);

        // 단기 이평선 기준으로 방향을 가져온다.
        String directionMA = technicalIndicatorCalculator.determineTrend(series, sma);

        // DI 기준으로 방향을 가져온다.
        String directionDI = technicalIndicatorCalculator.getDirection(series, shortMovingPeriod, series.getEndIndex());

        //di
        double plusDi  = technicalIndicatorCalculator.calculatePlusDI(series, longMovingPeriod, series.getEndIndex());
        double minusDi = technicalIndicatorCalculator.calculateMinusDI(series, longMovingPeriod, series.getEndIndex());

        //************************************ 여기서부터 ATR 관련 산식을 정의한다 ************************************
        AverageTrueRangeIndicator atr = new AverageTrueRangeIndicator(series, longMovingPeriod);
        // ATR 평균 계산
        List<Num> atrValues = new ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            atrValues.add(atr.getValue(i));
        }
        Num averageATR = atrValues.stream().reduce(series.numOf(0), Num::plus).dividedBy(series.numOf(atrValues.size()));
        // 임계값 설정 (예: 평균 ATR의 1.5배)
        Num atrThreshold = averageATR.multipliedBy(series.numOf(1.5));

        Num currentATR = atr.getValue(series.getEndIndex());
        int atrSignal = 0;
        if (currentATR.isGreaterThan(atrThreshold)) {
            atrSignal = 1;  // 신호 발생 (임계값 초과)
        } else {
            atrSignal = 0;  // 신호 없음 (임계값 이하)
        }

        //************************************ 여기서부터 볼린저밴드 관련 산식을 정의한다 ************************************
        int bollingerBandSignal = 0;

        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, longMovingPeriod); //장기 이평선 기간 동안의 표준편차
        BollingerBandsMiddleIndicator middleBBand    = new BollingerBandsMiddleIndicator(ema); //장기 이평선으로 중심선
        BollingerBandsUpperIndicator upperBBand      = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand      = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);

        BigDecimal ubb = CommonUtils.truncate(upperBBand.getValue(series.getEndIndex()), tickSize);
        BigDecimal mbb = CommonUtils.truncate(middleBBand.getValue(series.getEndIndex()), tickSize);
        BigDecimal lbb = CommonUtils.truncate(lowerBBand.getValue(series.getEndIndex()), tickSize);
        BigDecimal currentPrice = CommonUtils.truncate(closePrice.getValue(series.getEndIndex()), tickSize);

        //if(atrSignal == 1){
            if(currentPrice.compareTo(ubb) > 0){
                bollingerBandSignal = -1;
                specialRemark += CONSOLE_COLORS.BRIGHT_RED+"[볼린저밴드 상단 돌파]"+ubb+"["+currentPrice+"]"+CONSOLE_COLORS.RESET;
            } else if(currentPrice.compareTo(lbb) < 0){
                bollingerBandSignal = 1;
                specialRemark += CONSOLE_COLORS.BRIGHT_GREEN+"[볼린저밴드 하단 돌파]"+lbb+"["+currentPrice+"]"+CONSOLE_COLORS.RESET;
            }
        //}

        //********************************************* 볼린저밴드 끝 ****************************************************

        //*************************************** 여기서부터 ADX 관련 산식을 정의한다 ***************************************
        int adxSignal = 0;
        int adxDirectionSignal = 0;
        double currentAdx = 0;
        double previousAdx = 0;
        ADX_GRADE currentAdxGrade = ADX_GRADE.횡보;
        ADX_GRADE previousAdxGrade = ADX_GRADE.횡보;
        double adxGap = 0;

        HashMap<String,Object> adxStrategy = technicalIndicatorCalculator.adxStrategy(series, shortMovingPeriod, directionDI);
        currentAdx = (double) adxStrategy.get("currentAdx");
        previousAdx = (double) adxStrategy.get("previousAdx");
        currentAdxGrade = (ADX_GRADE) adxStrategy.get("currentAdxGrade");
        previousAdxGrade = (ADX_GRADE) adxStrategy.get("previousAdxGrade");
        adxGap = (double) adxStrategy.get("adxGap");

        if(adxChecker == 1) {
            adxSignal = (int) adxStrategy.get("adxSignal");
            adxDirectionSignal = (int) adxStrategy.get("adxDirectionSignal");
            commonRemark += adxStrategy.get("commonRemark");
            specialRemark += adxStrategy.get("specialRemark");
        }
        //*************************************************** ADX 끝 ***************************************************

        //************************************** 여기서부터 MACD 관련 산식을 정의한다 ***************************************
        int macdCrossSignal = 0;
        int macdReversalSignal = 0;
        double currentMacd = 0;
        double macdHistogramGap = 0;

        HashMap<String,Object> macdStrategy = technicalIndicatorCalculator.macdStrategy(series, closePrice);
        currentMacd = (double) macdStrategy.get("currentMacd");
        macdHistogramGap = (double) macdStrategy.get("macdHistogramGap");

        if (macdHistogramChecker == 1){
            macdCrossSignal = (int) macdStrategy.get("macdCrossSignal");
            macdReversalSignal = (int) macdStrategy.get("macdReversalSignal");
            commonRemark += macdStrategy.get("commonRemark");
            specialRemark += macdStrategy.get("specialRemark");
        }
        //************************************************ MACD 끝 *****************************************************

        //************************************* 여기서부터 RSI 관련 산식을 정의한다 *****************************************
        int rsiSignal = 0;
        double currentRsi = 0;
        HashMap<String,Object> rsiStrategy = technicalIndicatorCalculator.rsiStrategy(series, closePrice, rsiPeriod);
        currentRsi = (double) rsiStrategy.get("currentRsi");

        if (rsiChecker == 1){
            rsiSignal = (int) rsiStrategy.get("rsiSignal");
            commonRemark += rsiStrategy.get("commonRemark");
            specialRemark += rsiStrategy.get("specialRemark");
        }
        //************************************************ RSI 끝 ******************************************************

        //******************************** 여기서부터 스토캐스틱 오실레이터 관련 산식을 정의한다 *******************************
        int stochSignal = 0;
        double stochD = 0;
        double stochK = 0;
        HashMap<String,Object> stochStrategy = technicalIndicatorCalculator.stochStrategy(series, closePrice, longMovingPeriod);
        stochD = (double) stochStrategy.get("stochD");
        stochK = (double) stochStrategy.get("stochK");

        if (stochChecker == 1){
            stochSignal = (int) stochStrategy.get("stochSignal");
            commonRemark += stochStrategy.get("commonRemark");
            specialRemark += stochStrategy.get("specialRemark");
        }
        //****************************************** 스토캐스틱 오실레이터 끝 **********************************************

        //************************************ 여기서부터 스토캐스틱RSI 관련 산식을 정의한다 *********************************
        int stochRsiSignal = 0;
        double stochRsi = 0;
        HashMap<String,Object> stochRsiStrategy = technicalIndicatorCalculator.stochRsiStrategy(series, closePrice, longMovingPeriod);
        stochRsi = (double) stochRsiStrategy.get("stochRsi");

        if (stochRsiChecker == 1){
            stochRsiSignal = (int) stochRsiStrategy.get("stochRsiSignal");
            commonRemark += stochRsiStrategy.get("commonRemark");
            specialRemark += stochRsiStrategy.get("specialRemark");
        }
        //********************************************* 스토캐스틱RSI 끝 *************************************************

        if(!commonRemark.equals(currentLogPrefix)){
            //System.out.println(commonRemark);
        }
        if (!specialRemark.equals(currentLogPrefix)){
            //System.out.println(specialRemark);
        }

        int weakSignal = 0;
        int midSignal = 0;
        int strongSignal = 0;
        int totalSignal = 0;

        //{adxChecker, macdHistogramChecker, stochChecker, stochRsiChecker, rsiChecker, bollingerBandChecker}; -- TODO 동적으로 관리하던 해야될듯...

        double maxSignal = 0;
        for (HashMap<String, Object> technicalIndicatorCheckerMap : technicalIndicatorCheckers) {
            if (technicalIndicatorCheckerMap.get("key").equals("bollingerBandChecker")) {
                maxSignal += Math.abs((Integer) technicalIndicatorCheckerMap.get("value")); //볼린저밴드일 경우 가중치
            } else if(technicalIndicatorCheckerMap.get("key").equals("macdHistogramChecker")){
                maxSignal += Math.abs((Integer) technicalIndicatorCheckerMap.get("value"))*0; //macd 경우 가중치
            }
            else {
                maxSignal += Math.abs((Integer) technicalIndicatorCheckerMap.get("value"));
            }
        }
        String signalLog = " ";
        if(adxChecker == 1){
            totalSignal += adxDirectionSignal;
            if (adxDirectionSignal != 0){
                signalLog += "ADX DIRECTION SIGNAL("+ adxDirectionSignal+") ";
            }
        }
        if(macdHistogramChecker == 1){
            //totalSignal += macdReversalSignal;
            if (macdReversalSignal != 0){
                signalLog += "MACD SIGNAL("+ macdReversalSignal+") ";
            }
        }
        if(macdCrossChecker == 1){
            totalSignal += macdCrossSignal;
            if (macdCrossSignal != 0){
                signalLog += "MACD CROSS SIGNAL("+ macdCrossSignal+") ";
            }
        }
        if(stochChecker == 1){
            totalSignal += stochSignal;
            if (stochSignal != 0){
                signalLog += "STOCH SIGNAL("+ stochSignal+") ";
            }
        }
        if(stochRsiChecker == 1){
            totalSignal += stochRsiSignal;
            if (stochRsiSignal != 0){
                signalLog += "STOCHRSI SIGNAL("+ stochRsiSignal+") ";
            }
        }
        if(rsiChecker == 1){
            totalSignal += rsiSignal;
            if (rsiSignal != 0){
                signalLog += "RSI SIGNAL("+ rsiSignal+") ";
            }
        }
        if(bollingerBandChecker == 1){
            totalSignal += bollingerBandSignal*2;
            if (bollingerBandSignal != 0){
                signalLog += "BOLLINGERBAND SIGNAL("+ bollingerBandSignal+") ";
            }
        }
        if(movingAverageChecker == 1){
            totalSignal += movingAverageSignal;
            if (movingAverageSignal != 0){
                signalLog += "MA SIGNAL("+ movingAverageSignal+") ";
            }
        }

        boolean signalHide = false;
        if(trendFollowFlag == 1){
            signalHide = true;
        }else{
            signalHide = false;
        }
        // 모든 트렌드가 일치하고 시그널 또한 같은 방향일때만 시그널을 노출한다.
        if(trend4h !=null && trend1h !=null && trend15m !=null){
            if((trend15m.equals("LONG")
                //&& trend1h.equals("LONG")
                //&& trend4h.equals("LONG")
                && totalSignal > 0)
                ||(trend15m.equals("SHORT")
                //&& trend1h.equals("SHORT")
                //&& trend4h.equals("SHORT")
                && totalSignal < 0)){
                //totalSignal = 0;
                signalHide = false;
            }
        }

        int totalSignalAbs = Math.abs(totalSignal);
        double signalStandard = maxSignal/2;
        // 시그널 계산
        //System.out.println("시그널계산식 : maxSignal/2 < totalSignalAbs : "+(maxSignal/2 +" "+totalSignalAbs));

        // 약한 시그널은 언제나 감지한다.
        if(1 == totalSignalAbs
            //&& totalSignalAbs < signalStandard
        ){
            if(totalSignal < 0){
                weakSignal = -1;
            }else{
                weakSignal = 1;
            }
        }else if(
            true
            //&&currentAdxGrade.getGrade()>ADX_GRADE.약한추세.getGrade()
        ){
            if(1 < totalSignalAbs
                    && totalSignalAbs <= signalStandard
                    && !signalHide
            ){
                if (totalSignal < 0) {
                    midSignal = -1;
                } else {
                    midSignal = 1;
                }
            } else if (
                signalStandard < totalSignalAbs
                && !signalHide
            ){
                if (totalSignal < 0){
                    strongSignal = -1;
                    //strongSignal = 1;
                }else{
                    strongSignal = 1;
                    //strongSignal = -1;
                }
            }
        }
        /*VolatilityAndTrendChecker volatilityAndTrendChecker = new VolatilityAndTrendChecker(series, 10, 1.5, 3,5);
        int marketCondition = volatilityAndTrendChecker.checkMarketCondition(series.getEndIndex());
        //System.out.println("현재변동성 : " + marketCondition);
        if (marketCondition != 1){
            midSignal = 0;
            strongSignal = 0;
        }*/

        //추세가 확정이고 강해지고 있을때, 반대 포지션은 잡지 않는다.
        /*if (adxGap > 0){
            if (directionDI.equals("LONG")) { //상승추세일때
                if (midSignal < 0){
                    midSignal = 0;
                }
                if (strongSignal < 0){
                    strongSignal = 0;
                }
            } else if(directionDI.equals("SHORT")){ //하락추세일때
                if (midSignal > 0){
                    midSignal = 0;
                }
                if (strongSignal > 0){
                    strongSignal = 0;
                }
            }
        }*/

        if(weakSignal !=0){
           // System.out.println("시그널계산식 : maxSignal/2 < totalSignal : "+(maxSignal/2 +" "+totalSignal));
            System.out.println(CONSOLE_COLORS.BRIGHT_BLACK+"약한 매매신호 : "+ "["+formattedEndTime+"/"+closePrice.getValue(series.getEndIndex())+"] " +"/"+ weakSignal+" "+ signalLog + CONSOLE_COLORS.RESET);
        }
        if(midSignal !=0){
            //System.out.println("시그널계산식 : maxSignal/2 < totalSignal : "+(maxSignal/2 +" "+totalSignal));
            System.out.println(CONSOLE_COLORS.BACKGROUND_WHITE+""+CONSOLE_COLORS.BRIGHT_BLACK+"중간 매매신호 : "+ "["+formattedEndTime+"/"+closePrice.getValue(series.getEndIndex())+"] " +"/"+ midSignal+" "+ signalLog + CONSOLE_COLORS.RESET);
        }
        if(strongSignal !=0){
            //System.out.println("시그널계산식 : maxSignal/2 < totalSignal : "+(maxSignal/2 +" "+totalSignal));
            System.out.println(CONSOLE_COLORS.BRIGHT_BACKGROUND_WHITE+""+CONSOLE_COLORS.BRIGHT_BLACK+"강력한 매매신호 : "+ "["+formattedEndTime+"/"+closePrice.getValue(series.getEndIndex())+"] " +"/"+ strongSignal+" "+signalLog + CONSOLE_COLORS.RESET);
        }


        // 매수 및 매도 규칙 정의
        /*Rule entryRule = new CrossedDownIndicatorRule(closePrice, lbb) // 가격이 볼린저 밴드 하단을 하향 돌파할 때
                .and(new OverIndicatorRule(atr, atrThreshold)); // ATR이 임계값 이상일 때

        Rule exitRule = new CrossedUpIndicatorRule(closePrice, ubb) // 가격이 볼린저 밴드 상단을 상향 돌파할 때
                .and(new OverIndicatorRule(atr, atrThreshold)); // ATR이 임계값 이상일 때

        // 전략 생성
        Strategy strategy = new BaseStrategy(entryRule, exitRule);

        // 백테스트 실행
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        TradingRecord tradingRecord = seriesManager.run(strategy);

        // 결과 출력
        System.out.println("Number of trades: " + tradingRecord.getPositionCount());
        for (Position trade : tradingRecord.getPositions()) {
            System.out.println("Trade entered at: " + trade.getEntry().getIndex()
                    + ", exited at: " + trade.getExit().getIndex());
        }*/

        TechnicalIndicatorReportEntity technicalIndicatorReport = TechnicalIndicatorReportEntity.builder()
                //기본정보
                .symbol(symbol)
                .endTime(kstEndTime.toLocalDateTime())
                .openPrice(CommonUtils.truncate(openPrice.getValue(series.getEndIndex()), tickSize))
                .closePrice(CommonUtils.truncate(closePrice.getValue(series.getEndIndex()), tickSize))
                .highPrice(CommonUtils.truncate(highPrice.getValue(series.getEndIndex()), tickSize))
                .lowPrice(CommonUtils.truncate(lowPrice.getValue(series.getEndIndex()), tickSize))
                //MA관련
                .sma(CommonUtils.truncate(sma.getValue(series.getEndIndex()), tickSize))
                .ema(CommonUtils.truncate(ema.getValue(series.getEndIndex()), tickSize))
                .directionMa(directionMA)
                .movingAverageSignal(movingAverageSignal) //!!! 시그널
                //DI관련
                .plusDi(plusDi)
                .minusDi(minusDi)
                .directionDi(directionDI)
                //볼린저밴드관련
                .ubb(CommonUtils.truncate(upperBBand.getValue(series.getEndIndex()), tickSize))
                .mbb(CommonUtils.truncate(middleBBand.getValue(series.getEndIndex()), tickSize))
                .lbb(CommonUtils.truncate(lowerBBand.getValue(series.getEndIndex()), tickSize))
                .bollingerBandSignal(bollingerBandSignal) //!!! 시그널
                //ADX관련
                .currentAdx(currentAdx)
                .currentAdxGrade(currentAdxGrade)
                .previousAdx(previousAdx)
                .previousAdxGrade(previousAdxGrade)
                .adxGap(adxGap)
                .adxSignal(adxSignal) //!!! 시그널
                .adxDirectionSignal(adxDirectionSignal) //!!! 시그널
                //macd관련
                .macd(doulbleToBigDecimal(currentMacd).setScale(10, RoundingMode.DOWN))
                .macdReversalSignal(macdReversalSignal)
                .macdCrossSignal(macdCrossSignal)
                .macdHistogramGap(macdHistogramGap)
                //rsi관련
                .rsi(BigDecimal.valueOf(currentRsi))
                .rsiSignal(rsiSignal)
                //stoch관련
                .stochK(doulbleToBigDecimal(stochK))
                .stochD(doulbleToBigDecimal(stochD))
                .stochSignal(stochSignal)
                //stochRsi관련
                .stochRsi(doulbleToBigDecimal(stochRsi))
                .stochRsiSignal(stochRsiSignal)
                //매매신호
                .weakSignal(weakSignal)
                .midSignal(midSignal)
                .strongSignal(strongSignal)
                //.marketCondition(marketCondition)
                .build();
        return technicalIndicatorReport;
    }

    public Claims getClaims(HttpServletRequest request){
        try{
            Key secretKey = Keys.hmacShaKeyFor(JWT_SECRET_KEY.getBytes(StandardCharsets.UTF_8));
            System.out.println("JWT_SECRET_KEY : " + JWT_SECRET_KEY);
            System.out.println("authorization : " + request.getHeader("authorization"));
            Claims claim = Jwts.parserBuilder().setSigningKey(secretKey).build()
                    .parseClaimsJws(request.getHeader("authorization")).getBody();
            return claim;
        } catch (ExpiredJwtException e) {
            throw new ExpiredJwtException(null, null, "로그인 시간이 만료되었습니다.");
        } catch (Exception e) {
            throw new BadCredentialsException("인증 정보에 문제가 있습니다.");
        }
    }
}