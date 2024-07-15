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
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
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
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
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
        getKlines(tradingEntity);
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
        int stockSelectionCount = tradingEntity.getStockSelectionCount();
        int maxPositionCount = tradingEntity.getMaxPositionCount();
        String userCd = tradingEntity.getUserCd();

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
        //closeAllPositions();
        System.out.println("symbolParam : " + targetSymbol);

        int availablePositionCount = maxPositionCount - TRADING_ENTITYS.size();
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
            //
            if (nextFlag) {
                TradingEntity tradingEntity = tradingEntitys.get(0);
                BigDecimal currentROI;
                BigDecimal currentPnl;
                /*if(tradingEntity.getPositionStatus()!=null && tradingEntity.getPositionStatus().equals("OPEN")){
                    tradingEntity.setClosePrice(doulbleToBigDecimal(klineObj.getDouble("c")));
                    currentROI = TechnicalIndicatorCalculator.calculateROI(tradingEntity);
                    currentPnl = TechnicalIndicatorCalculator.calculatePnL(tradingEntity);
                } else {
                    currentROI = new BigDecimal("0");
                    currentPnl = new BigDecimal("0");
                }*/

                /*System.out.println("ROI : " + currentROI);
                System.out.println("PnL : " + currentPnl);*/
                if(isFinal){
                    System.out.println("event : " + event);
                    // klineEvent를 데이터베이스에 저장
                    EventEntity eventEntity = saveKlineEvent(event, tradingEntitys.get(0));

                    TechnicalIndicatorReportEntity technicalIndicatorReportEntity = eventEntity.getKlineEntity().getTechnicalIndicatorReportEntity();
                    Optional<EventEntity> openPositionEntityOpt = eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN");
                    openPositionEntityOpt.ifPresentOrElse(positionEvent -> { // 오픈된 포지션이 있다면
                        TechnicalIndicatorReportEntity positionReport = positionEvent.getKlineEntity().getTechnicalIndicatorReportEntity();
                        if(DEV_FLAG){
                            String remark = "테스트 청산";
                            PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN")){
                                makeCloseOrder(eventEntity, positionEvent, remark);
                            }
                        } else {
                            if(technicalIndicatorReportEntity.getWeakSignal() != 0
                                    ||technicalIndicatorReportEntity.getMidSignal() !=0
                                    ||technicalIndicatorReportEntity.getStrongSignal() !=0){
                                String weakSignal   = technicalIndicatorReportEntity.getWeakSignal() != 0 ? (technicalIndicatorReportEntity.getWeakSignal() == 1 ? "LONG" : "SHORT") : "";
                                String midSignal    = technicalIndicatorReportEntity.getMidSignal() != 0 ? (technicalIndicatorReportEntity.getMidSignal() == 1 ? "LONG" : "SHORT") : "";
                                String strongSignal = technicalIndicatorReportEntity.getStrongSignal() != 0 ? (technicalIndicatorReportEntity.getStrongSignal() == 1 ? "LONG" : "SHORT") : "";
                                PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
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
                                        String remark = "weakSignal(" + weakSignal + ") midSignal(" + midSignal + ") strongSignal(" + strongSignal + ")";
                                        makeCloseOrder(eventEntity, positionEvent, remark);
                                    }
                                }
                            }
                            /*else if (currentROI.compareTo(new BigDecimal("-20")) < 0){
                                String remark = "강제 손절";
                                makeCloseOrder(eventEntity, positionEvent, remark);
                            }*/
                        }
                    },() -> {

                    });
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
                    try {
                        makeOpenOrder(klineEvent, direction, remark);
                    } catch (Exception e) {
                        e.printStackTrace();
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
            }catch (Exception e){
                e.printStackTrace();
            }

            //주문 제출
            Map<String, Object> resultMap = orderSubmit(makeOrder(tradingEntity, "OPEN"));
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

    public LinkedHashMap<String,Object> makeOrder(TradingEntity tradingEntity, String intent){
        LinkedHashMap<String,Object> paramMap = new LinkedHashMap<>();
        String symbol = tradingEntity.getSymbol();
        String positionSide = tradingEntity.getPositionSide();
        String side = ""; //BUY/SELL
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
            }else{
                System.out.println("명목가치(" + notional+ ")가 최소주문가능금액보다 작습니다.");
                //throw new TradingException(tradingEntity);
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
            TradingEntity tempTradingEntity = tradingEntity.clone();
            tempTradingEntity.setSymbol(symbol);
            List<TradingEntity> tradingEntityList = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");
            if (tradingEntityList.isEmpty()) {
                Map<String, Object> klineMap = getKlines(tempTradingEntity);
                Optional<Object> expectationProfitOpt = Optional.ofNullable(klineMap.get("expectationProfit"));
                TechnicalIndicatorReportEntity tempReport = technicalIndicatorCalculate(tempTradingEntity);
                if (expectationProfitOpt.isPresent()){
                    BigDecimal expectationProfit = (BigDecimal) expectationProfitOpt.get();
                    BigDecimal winTradeCount = new BigDecimal(String.valueOf(klineMap.get("winTradeCount")));
                    BigDecimal loseTradeCount = new BigDecimal(String.valueOf(klineMap.get("loseTradeCount")));
                    if (
                            true
                            &&expectationProfit.compareTo(BigDecimal.ZERO) > 0
                            && (winTradeCount.compareTo(loseTradeCount) >= 0)
                    ) {
                        System.out.println("[관심종목추가]symbol : " + symbol + " expectationProfit : " + expectationProfit);
                        overlappingData.add(item);
                        reports.add(tempReport);
                        count++;
                    }
                }

                /*if (
                        true
                        *//*&& ADX_CHECKER && tempReport.getCurrentAdxGrade().equals(ADX_GRADE.횡보)||
                        (tempReport.getCurrentAdxGrade().getGrade() > ADX_GRADE.강한추세.getGrade() && tempReport.getAdxGap()>0)*//*
                        //&& (tempReport.getClosePrice().compareTo(tempReport.getUbb())>0 || tempReport.getClosePrice().compareTo(tempReport.getLbb())<0)
                ) {
                    overlappingData.add(item);
                    reports.add(tempReport);
                    count++;
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
        return getKlines(tradingEntity);
    }


    public Map<String, Object> getKlines(TradingEntity tradingEntity) {

        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        //printPrettyJson(accountInfo);

        System.out.println("사용가능 : " +accountInfo.get("availableBalance"));
        System.out.println("담보금 : " + accountInfo.get("totalWalletBalance"));
        System.out.println("미실현수익 : " + accountInfo.get("totalUnrealizedProfit"));
        System.out.println("현재자산 : " + accountInfo.get("totalMarginBalance"));

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
        log.info("getKline >>>>>");

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
        series.setMaximumBarCount(WINDOW_SIZE);
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

            if(i!=0){
                TechnicalIndicatorReportEntity tempReport = technicalIndicatorCalculate(tradingEntity);
                technicalIndicatorReportEntityArr.add(tempReport);

                if(tradingEntity.getPositionStatus()!=null && tradingEntity.getPositionStatus().equals("OPEN")){
                    BigDecimal currentROI = TechnicalIndicatorCalculator.calculateROI(tradingEntity.getOpenPrice(), tempReport.getClosePrice(), tradingEntity.getLeverage(), tradingEntity.getPositionSide());
                    BigDecimal currentPnl = TechnicalIndicatorCalculator.calculatePnL(tradingEntity.getOpenPrice(), tempReport.getClosePrice(), tradingEntity.getCollateral(), tradingEntity.getLeverage(), tradingEntity.getPositionSide());
                    System.out.println("ROI : " + currentROI);
                    System.out.println("PnL : " + currentPnl);
                    if (currentROI.compareTo(new BigDecimal("-20")) < 0){
                        tradingEntity.setPositionStatus("CLOSE");
                        tradingEntity.setClosePrice(tempReport.getClosePrice());

                        BigDecimal currentProfit = calculateProfit(tradingEntity);
                        System.out.println("강제 손절("+tradingEntity.getSymbol()+"/"+tradingEntity.getOpenPrice()+">>>"+tradingEntity.getClosePrice()+") : "  + currentProfit);
                        expectationProfit = expectationProfit.add(currentProfit);

                    } else if((tempReport.getStrongSignal() < 0
                        || tempReport.getMidSignal() < 0
                        || tempReport.getWeakSignal() < 0)
                        && tradingEntity.getPositionSide().equals("LONG")){
                        tradingEntity.setPositionStatus("CLOSE");
                        tradingEntity.setClosePrice(tempReport.getClosePrice());

                        BigDecimal currentProfit = calculateProfit(tradingEntity);
                        System.out.println("현재 수익("+tradingEntity.getSymbol()+"/"+tradingEntity.getOpenPrice()+">>>"+tradingEntity.getClosePrice()+") : "  + currentProfit);
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
                        System.out.println("현재 수익("+tradingEntity.getSymbol()+"/"+tradingEntity.getOpenPrice()+">>>"+tradingEntity.getClosePrice()+") : "  + currentProfit);
                        expectationProfit = expectationProfit.add(currentProfit);

                        if (currentProfit.compareTo(BigDecimal.ZERO) < 0) {
                            tradingEntity.setLoseTradeCount(tradingEntity.getLoseTradeCount() + 1);
                        }else if (currentProfit.compareTo(BigDecimal.ZERO) > 0) {
                            tradingEntity.setWinTradeCount(tradingEntity.getWinTradeCount() + 1);
                        }
                    }
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
        System.out.println("최종예상 수익("+tradingEntity.getSymbol()+") : " + expectationProfit);
        System.out.println("최종예상 승률("+tradingEntity.getSymbol()+") : " + (tradingEntity.getWinTradeCount()+"/" +tradingEntity.getLoseTradeCount()));
        klines.put(symbol, klineEntities);
        resultMap.put("tempTradingEntity", tradingEntity);
        resultMap.put("result", klineEntities);
        resultMap.put("technicalIndicatorReportEntityArr", technicalIndicatorReportEntityArr);
        resultMap.put("expectationProfit", expectationProfit);
        resultMap.put("winTradeCount", tradingEntity.getWinTradeCount());
        resultMap.put("loseTradeCount", tradingEntity.getLoseTradeCount());

        long endTime = System.currentTimeMillis(); // 종료 시간 기록
        long elapsedTime = endTime - startTime; // 실행 시간 계산
        System.out.println("소요시간 : " + elapsedTime + " milliseconds");
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

        //매매전략 변수 설정 -- tradingEntity에서 필요한 값 추출

        ArrayList<HashMap<String,Object>> technicalIndicatorCheckers = new ArrayList<>();
        int adxChecker = tradingEntity.getAdxChecker();
        int macdHistogramChecker = tradingEntity.getMacdHistogramChecker();
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
        int rsiPeriod = 5;   //RSI 기간
        RSIIndicator rsi = new RSIIndicator(closePrice, rsiPeriod);

        // 단기 이평선 기준으로 방향을 가져온다.
        String directionMA = technicalIndicatorCalculator.determineTrend(series, sma);

        // DI 기준으로 방향을 가져온다.
        String directionDI = technicalIndicatorCalculator.getDirection(series, longMovingPeriod, series.getEndIndex());

        //di
        double plusDi  = technicalIndicatorCalculator.calculatePlusDI(series, longMovingPeriod, series.getEndIndex());
        double minusDi = technicalIndicatorCalculator.calculateMinusDI(series, longMovingPeriod, series.getEndIndex());

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

        if(currentPrice.compareTo(ubb) > 0){
            bollingerBandSignal = -1;
            specialRemark += CONSOLE_COLORS.BRIGHT_RED+"[볼린저밴드 상단 돌파]"+ubb+"["+currentPrice+"]"+CONSOLE_COLORS.RESET;
        } else if(currentPrice.compareTo(lbb) < 0){
            bollingerBandSignal = 1;
            specialRemark += CONSOLE_COLORS.BRIGHT_GREEN+"[볼린저밴드 하단 돌파]"+lbb+"["+currentPrice+"]"+CONSOLE_COLORS.RESET;
        }
        //********************************************* 볼린저밴드 끝 ****************************************************

        //*************************************** 여기서부터 ADX 관련 산식을 정의한다 ***************************************
        int adxSignal = 0;
        int adxDirectionSignal = 0;
        double currentAdx = 0;
        double previousAdx = 0;
        ADX_GRADE currentAdxGrade = ADX_GRADE.횡보;
        ADX_GRADE previousAdxGrade = ADX_GRADE.횡보;
        double adxGap = 0;

        HashMap<String,Object> adxStrategy = technicalIndicatorCalculator.adxStrategy(series, longMovingPeriod, directionDI);
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

        HashMap<String,Object> macdStrategy = technicalIndicatorCalculator.macdStrategy(series, closePrice);
        currentMacd = (double) macdStrategy.get("currentMacd");

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
            System.out.println(commonRemark);
        }
        if (!specialRemark.equals(currentLogPrefix)){
            System.out.println(specialRemark);
        }

        int weakSignal = 0;
        int midSignal = 0;
        int strongSignal = 0;
        int totalSignal = 0;

        //{adxChecker, macdHistogramChecker, stochChecker, stochRsiChecker, rsiChecker, bollingerBandChecker}; -- TODO 동적으로 관리하던 해야될듯...

        double maxSignal = 0;
        for (HashMap<String, Object> technicalIndicatorCheckerMap : technicalIndicatorCheckers) {
            if (technicalIndicatorCheckerMap.get("key").equals("bollingerBandChecker")) {
                maxSignal += Math.abs((Integer) technicalIndicatorCheckerMap.get("value"))*2; //볼린저밴드일 경우 가중치
            } else {
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
            totalSignal += macdReversalSignal;
            if (macdReversalSignal != 0){
                signalLog += "MACD SIGNAL("+ macdReversalSignal+") ";
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

        int totalSignalAbs = Math.abs(totalSignal);
        double signalStandard = maxSignal/2;
        //시그널 계산
        //System.out.println("시그널계산식 : maxSignal/2 < totalSignalAbs : "+(maxSignal/2 +" "+totalSignalAbs));
        if(1 == totalSignalAbs
            //&& totalSignalAbs < signalStandard
        ){
            if(totalSignal < 0){
                weakSignal = -1;
            }else{
                weakSignal = 1;
            }
        }else if(1 < totalSignalAbs && totalSignalAbs < signalStandard) {
            if (totalSignal < 0) {
                midSignal = -1;
            } else {
                midSignal = 1;
            }
        } else if (signalStandard <= totalSignalAbs){
            if (totalSignal < 0){
                strongSignal = -1;
                //strongSignal = 1;
            }else{
                strongSignal = 1;
                //strongSignal = -1;
            }
        }

        if(weakSignal !=0){
            System.out.println("시그널계산식 : maxSignal/2 < totalSignal : "+(maxSignal/2 +" "+totalSignal));
            System.out.println(CONSOLE_COLORS.BRIGHT_BLACK+"약한 매매신호 : "+ "["+formattedEndTime+"/"+closePrice.getValue(series.getEndIndex())+"] " +"/"+ weakSignal+" "+ signalLog + CONSOLE_COLORS.RESET);
        }
        if(midSignal !=0){
            System.out.println("시그널계산식 : maxSignal/2 < totalSignal : "+(maxSignal/2 +" "+totalSignal));
            System.out.println(CONSOLE_COLORS.BACKGROUND_WHITE+""+CONSOLE_COLORS.BRIGHT_BLACK+"중간 매매신호 : "+ "["+formattedEndTime+"/"+closePrice.getValue(series.getEndIndex())+"] " +"/"+ midSignal+" "+ signalLog + CONSOLE_COLORS.RESET);
        }
        if(strongSignal !=0){
            System.out.println("시그널계산식 : maxSignal/2 < totalSignal : "+(maxSignal/2 +" "+totalSignal));
            System.out.println(CONSOLE_COLORS.BRIGHT_BACKGROUND_WHITE+""+CONSOLE_COLORS.BRIGHT_BLACK+"강력한 매매신호 : "+ "["+formattedEndTime+"/"+closePrice.getValue(series.getEndIndex())+"] " +"/"+ strongSignal+" "+signalLog + CONSOLE_COLORS.RESET);
        }
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