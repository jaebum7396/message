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
import trade.exception.TradingException;
import trade.future.model.dto.TradingDTO;
import trade.future.model.entity.*;
import trade.future.model.enums.ADX_GRADE;
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
    private static final boolean MACD_CHECKER = false;
    private static final boolean ADX_CHECKER = true;

    private final Map<String, TradingEntity> TRADING_ENTITYS = new HashMap<>();

    public void onOpenCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        log.info("[OPEN] >>>>> " + streamId + " 번 스트림("+tradingEntity.getSymbol()+")을 오픈합니다.");
        tradingEntity.setTradingStatus("OPEN");
        tradingRepository.save(tradingEntity);
        getKlines(tradingEntity, WINDOW_SIZE);
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
        return autoTradingOpen(userCd, tradingDTO);
    }

    public Map<String, Object> autoTradingOpen(String userCd, TradingDTO tradingDTO) {
        log.info("autoTrading >>>>>");
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

        String targetSymbol = tradingDTO.getSymbol();
        String interval = tradingDTO.getInterval();
        int leverage = tradingDTO.getLeverage();
        int stockSelectionCount = tradingDTO.getStockSelectionCount();
        int maxPositionCount = tradingDTO.getMaxPositionCount();
        int trendFollowFlag = tradingDTO.getTrendFollowFlag();
        int adxChecker = tradingDTO.getAdxChecker();
        int macdHistogramChecker = tradingDTO.getMacdHistogramChecker();
        int rsiChecker = tradingDTO.getRsiChecker();
        BigDecimal colleteralRate = tradingDTO.getCollateralRate();

        System.out.println("symbolParam : " + targetSymbol);

        TradingEntity tradingEntityTemplate = tradingDTO.toEntity();
        tradingEntityTemplate.setTradingStatus("OPEN");
        tradingEntityTemplate.setTradingType("REAL");

        List<TradingEntity> openTradingList = tradingRepository.findByTradingStatus("OPEN");
        int availablePositionCount = maxPositionCount - TRADING_ENTITYS.size();
        boolean nextFlag = true;
        try {
            if (availablePositionCount <= 0) {
                throw new RuntimeException("오픈 가능한 트레이딩이 없습니다.");
            }
        } catch (Exception e){
            System.out.println("오픈 가능한 트레이딩이 없습니다.");
            System.out.println("현재 오픈된 트레이딩");
            TRADING_ENTITYS.forEach((symbol, tradingEntity) -> {
                System.out.println("symbol : " + symbol);
            });
            nextFlag = false;
        }
        if(nextFlag){
            if(targetSymbol == null || targetSymbol.isEmpty()) {
                selectedStockList = (List<Map<String, Object>>) getStockFind(tradingEntityTemplate, stockSelectionCount, availablePositionCount).get("overlappingData");
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
                autoTradingOpen(userCd, tradingDTO);
                nextFlag = false;
            }
            if(nextFlag){
                availableBalance = availableBalance.divide(new BigDecimal(tradingTargetSymbols.size()), 0, RoundingMode.DOWN);

                BigDecimal maxPositionAmount = totalWalletBalance
                        .divide(new BigDecimal(maxPositionCount),0, RoundingMode.DOWN)
                        .multiply(new BigDecimal("0.95")).setScale(0, RoundingMode.DOWN);

                BigDecimal finalAvailableBalance = maxPositionAmount;
                log.info("collateral : " + maxPositionAmount);
                System.out.println("tradingTargetSymbols : " + tradingTargetSymbols);
                tradingTargetSymbols.parallelStream().forEach(selectedStock -> {
                    String symbol = String.valueOf(selectedStock.get("symbol"));
                    System.out.println("symbol : " + symbol);
                    // 해당 페어의 평균 거래량을 구합니다.
                    //BigDecimal averageQuoteAssetVolume = getKlinesAverageQuoteAssetVolume( (JSONArray)getKlines(symbol, interval, WINDOW_SIZE).get("result"), interval);

                    TradingEntity tradingEntity = tradingEntityTemplate;
                    tradingEntity.setSymbol(symbol);
                    tradingEntity.setCollateral(finalAvailableBalance);

                    if (targetSymbol != null && !targetSymbol.isEmpty()) {
                        tradingEntity.setTargetSymbol(targetSymbol);
                    }
                    try{
                        Optional<TradingEntity> tradingEntityOpt = Optional.ofNullable(TRADING_ENTITYS.get(symbol));
                        if(tradingEntityOpt.isPresent()){
                            printTradingEntitys();
                            throw new RuntimeException(tradingEntityOpt.get().getSymbol() + "이미 오픈된 트레이딩이 존재합니다.");
                        }else{
                            TRADING_ENTITYS.put(symbol, autoTradeStreamOpen(tradingEntity));
                        }
                    } catch (Exception e) {
                        autoTradingOpen(userCd, tradingDTO);
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
        System.out.println("symbolParam : " + tradingDTO.getSymbol());

        String targetSymbol = tradingDTO.getSymbol();
        String interval = tradingDTO.getInterval();
        int leverage = tradingDTO.getLeverage();
        int stockSelectionCount = tradingDTO.getStockSelectionCount();
        int maxPositionCount = tradingDTO.getMaxPositionCount();
        int trendFollowFlag = tradingDTO.getTrendFollowFlag();
        int adxChecker = tradingDTO.getAdxChecker();
        int macdHistogramChecker = tradingDTO.getMacdHistogramChecker();
        int rsiChecker = tradingDTO.getRsiChecker();
        BigDecimal colleteralRate = tradingDTO.getCollateralRate();

        availableBalance = availableBalance.divide(new BigDecimal(maxPositionCount), 0, RoundingMode.DOWN);

        TradingEntity tradingEntityTemplate = tradingDTO.toEntity();
        tradingEntityTemplate.setTradingStatus("OPEN");
        tradingEntityTemplate.setTradingType("BACKTEST");
        tradingEntityTemplate.setUserCd(userCd);

        int availablePositionCount = maxPositionCount;
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
        String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
        //String resultStr = umFuturesClientImpl.market().tickerSymbol(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);
        //printPrettyJson(resultArray);

        // 거래량(QuoteVolume - 기준 화폐)을 기준으로 내림차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByQuoteVolume = getSort(resultArray, "quoteVolume", "DESC", availablePositionCount);
        List<Map<String, Object>> overlappingData = new ArrayList<>();
        List<TechnicalIndicatorReportEntity> reports = new ArrayList<>();

        int count = 0;
        for (Map<String, Object> item : sortedByQuoteVolume) {
            if (count >= availablePositionCount) {
                break;
            }
            String symbol = String.valueOf(item.get("symbol"));
            TradingEntity tradingEntity = tradingEntityTemplate.clone();
            tradingEntity.setSymbol(symbol);
            getKlines(tradingEntity, WINDOW_SIZE);
        }

        return resultMap;
    }

    private void klineProcess(String event){
        JSONObject eventObj = new JSONObject(event);
        JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
        JSONObject klineObj = new JSONObject(klineEventObj.get("k").toString());
        boolean isFinal = klineObj.getBoolean("x");

        String seriesNm = String.valueOf(eventObj.get("stream"));
        String symbol = seriesNm.substring(0, seriesNm.indexOf("@"));
        String interval = seriesNm.substring(seriesNm.indexOf("_") + 1);

        if (isFinal) {
            System.out.println("event : " + event);
            TradingEntity tradingEntity = getTradingEntity(symbol);
            // klineEvent를 데이터베이스에 저장
            EventEntity eventEntity = saveKlineEvent(event, tradingEntity);

            TechnicalIndicatorReportEntity technicalIndicatorReportEntity = eventEntity.getKlineEntity().getTechnicalIndicatorReportEntity();
            Optional<EventEntity> openPositionEntityOpt = eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN");
            openPositionEntityOpt.ifPresentOrElse(positionEvent -> { // 오픈된 포지션이 있다면
                TechnicalIndicatorReportEntity positionReport = positionEvent.getKlineEntity().getTechnicalIndicatorReportEntity();
                if(DEV_FLAG){
                    String remark = "테스트 청산";
                    PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                    if(closePosition.getPositionStatus().equals("OPEN")){
                        makeCloseOrder(eventEntity, positionEvent, remark, false);
                    }
                } else {
                    if (ADX_CHECKER){
                        if(positionReport.getEndTime().equals(technicalIndicatorReportEntity.getEndTime())){
                            return;
                        }
                        if(positionReport.getAdxSignal()>0&&technicalIndicatorReportEntity.getAdxGap()<1){
                            String remark = "ADX 청산시그널("+technicalIndicatorReportEntity.getAdxGap()+")";
                            PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN")){
                                makeCloseOrder(eventEntity, positionEvent, remark, false);
                            }
                        } else if (positionReport.getAdxSignal()<0&&technicalIndicatorReportEntity.getAdxGap()>-1){
                            String remark = "ADX 청산시그널("+technicalIndicatorReportEntity.getAdxGap()+")";
                            PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN")){
                                makeCloseOrder(eventEntity, positionEvent, remark, false);
                            }
                        }else{
                            //autoTradingRestart(tradingEntity);
                        }
                        /*if(technicalIndicatorReportEntity.getAdxGap()>1 // ADX가 1이상(추세가 강해질때)
                            ||klineEvent.getKlineEntity().getTechnicalIndicatorReportEntity().getCurrentAdx()-technicalIndicatorReportEntity.getCurrentAdx()<-1  // 현재 ADX가 포지션 진입당시 ADX보다 2이상 높아졌을때(추세가 강해질때)

                        ){
                            String remark = "ADX 청산시그널("+ technicalIndicatorReportEntity.getPreviousAdxGrade() +">"+ technicalIndicatorReportEntity.getCurrentAdxGrade() + ")";
                            PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN")){
                                makeCloseOrder(eventEntity, klineEvent, remark);
                            }
                        }*/
                    }
                    if (MACD_CHECKER){
                        if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){ //MACD 크로스가 일어났을때.
                            BigDecimal macd = technicalIndicatorReportEntity.getMacd();
                            int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                            if(macdCrossSignal<0){ //MACD가 양수일때 데드크로스가 일어났을때.
                                String remark = "MACD 데드크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                                PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                                if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("LONG")){ //포지션이 오픈되어있고 롱포지션일때
                                    makeCloseOrder(eventEntity, positionEvent, remark, false);
                                }
                            } else if (macdCrossSignal > 0){ //MACD가 음수일때 골든크로스가 일어났을때.
                                String remark = "MACD 골든크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                                PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                                if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("SHORT")){ //포지션이 오픈되어있고 숏포지션일때
                                    makeCloseOrder(eventEntity, positionEvent, remark, false);
                                }
                            }
                        }
                    }
                }
            },() -> {

            });
            //eventRepository.save(eventEntity);
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
                    makeOpenOrder(klineEvent, "LONG", remark, false);
                } catch (Exception e) {
                    throw new TradingException(tradingEntity);
                }
            } else {
                if(tradingEntity.getAdxChecker() == 1){
                    if (technicalIndicatorReportEntity.getAdxSignal() != 0
                        //&& (technicalIndicatorReportEntity.getAdxGap() > 1 || technicalIndicatorReportEntity.getAdxGap() < -1)
                    ){
                        String remark = "ADX 진입시그널("+technicalIndicatorReportEntity.getAdxGap()+")";
                        try {
                            makeOpenOrder(klineEvent, technicalIndicatorReportEntity.getDirectionDi(), remark, false);
                        } catch (Exception e) {
                            e.printStackTrace();
                            //throw new TradingException(tradingEntity);
                        }
                    }
                }
                if (tradingEntity.getMacdHistogramChecker() == 1){
                    if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){
                        int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                        if(macdCrossSignal<0 && technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("0")) > 0){
                            String remark = "MACD 데드크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                            try {
                                makeOpenOrder(klineEvent, "SHORT", remark, false);
                            } catch (Exception e) {
                                e.printStackTrace();
                                //throw new TradingException(tradingEntity);
                            }
                        } else if (macdCrossSignal > 0 && technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("0")) < 0){
                            String remark = "MACD 골든크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                            try {
                                makeOpenOrder(klineEvent, "LONG", remark, false);
                            } catch (Exception e) {
                                e.printStackTrace();
                                //throw new TradingException(tradingEntity);
                            }
                        }
                    }
                }
            }
        }else{
            System.out.println("포지션 오픈중 : " +  eventEntity.get().getKlineEntity().getPositionEntity());
        }
        return klineEvent;
    }

    public Map<String, Object> getStockFind(String interval, int limit, int availablePositionCount) {
        TradingEntity tradingEntity = TradingEntity.builder()
                .tradingCd(String.valueOf(UUID.randomUUID()))
                .candleInterval(interval)
                .build();
        return getStockFind(tradingEntity, limit, availablePositionCount);
    }

    public Map<String, Object> getStockFind(TradingEntity tradingEntityTemplate, int limit, int availablePositionCount) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
        //String resultStr = umFuturesClientImpl.market().tickerSymbol(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);
        //printPrettyJson(resultArray);

        // 거래량(QuoteVolume - 기준 화폐)을 기준으로 내림차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByQuoteVolume = getSort(resultArray, "quoteVolume", "DESC", limit);
        //System.out.println("sortedByQuoteVolume : " + sortedByQuoteVolume);
        List<Map<String, Object>> overlappingData = new ArrayList<>();
        List<TechnicalIndicatorReportEntity> reports = new ArrayList<>();

        int count = 0;
        for (Map<String, Object> item : sortedByQuoteVolume) {
            if (count >= availablePositionCount) {
                break;
            }

            String tempCd = String.valueOf(UUID.randomUUID());
            String symbol = String.valueOf(item.get("symbol"));
            Optional<TradingEntity> tradingEntityOpt = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");

            if (tradingEntityOpt.isEmpty()) {
                TradingEntity tradingEntity = tradingEntityTemplate.clone();
                tradingEntity.setSymbol(symbol);
                getKlines(tradingEntity, WINDOW_SIZE);
                TechnicalIndicatorReportEntity tempReport = technicalIndicatorCalculate(tradingEntity);

                if (
                        ADX_CHECKER && tempReport.getCurrentAdxGrade().equals(ADX_GRADE.횡보)||
                                (tempReport.getCurrentAdxGrade().getGrade() > ADX_GRADE.추세확정.getGrade() && tempReport.getAdxGap()>0)
                ) {
                    overlappingData.add(item);
                    reports.add(tempReport);
                    count++;
                }

                if (MACD_CHECKER && tempReport.getMacdPreliminarySignal() != 0) {
                    overlappingData.add(item);
                    reports.add(tempReport);
                    count++;
                }
            }
        }

        resultMap.put("reports", reports);
        resultMap.put("overlappingData", overlappingData);
        return resultMap;
    }
    public Map<String, Object> getKlines(String tradingCd, String symbol, String interval, int limit) {
        TradingEntity tradingEntity = TradingEntity.builder()
                .tradingCd(tradingCd)
                .symbol(symbol)
                .candleInterval(interval)
                .build();
        return getKlines(tradingEntity, limit);
    }
    public Map<String, Object> getKlines(TradingEntity tradingEntity, int limit) {
        long startTime = System.currentTimeMillis(); // 시작 시간 기록
        log.info("getKline >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        String tradingCd = tradingEntity.getTradingCd();
        String symbol = tradingEntity.getSymbol();
        String interval = tradingEntity.getCandleInterval();

        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY, true);

        paramMap.put("symbol", symbol);
        paramMap.put("interval", interval);
        paramMap.put("limit", limit);

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
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray klineArray = jsonArray.getJSONArray(i);
            //klineEntity 만들기
            KlineEntity klineEntity = parseKlineEntity(klineArray);
            klineEntity.setSymbol(symbol);

            //eventEntity 만들기
            EventEntity eventEntity = EventEntity.builder()
                    .tradingEntity(tradingEntity)
                    .klineEntity(klineEntity)
                    .eventTime(klineEntity.getEndTime())
                    .build();
            klineEntity.setEvent(eventEntity);

            //positionEntity 만들기
            PositionEntity positionEntity = PositionEntity.builder()
                    .symbol(symbol)
                    .positionStatus("NONE")
                    .klineEntity(klineEntity)
                    .build();
            klineEntity.setPositionEntity(positionEntity);

            Num open = series.numOf(klineEntity.getOpenPrice());
            Num high = series.numOf(klineEntity.getHighPrice());
            Num low = series.numOf(klineEntity.getLowPrice());
            Num close = series.numOf(klineEntity.getClosePrice());
            Num volume = series.numOf(klineEntity.getVolume());

            series.addBar(klineEntity.getEndTime().atZone(ZoneOffset.UTC), open, high, low, close, volume);

            if(i!=0){
                //technicalIndicatorReportEntity 만들기
                TechnicalIndicatorReportEntity technicalIndicatorReportEntity = technicalIndicatorCalculate(tradingEntity);
                klineEntity.setTechnicalIndicatorReportEntity(technicalIndicatorReportEntity);
                if(tradingEntity.getTradingType() != null){
                    if(tradingEntity.getTradingType().equals("BACKTEST")){
                        openOrderProcess(eventEntity, technicalIndicatorReportEntity, true);
                        closeOrderProcess(eventEntity, technicalIndicatorReportEntity, true);
                    }
                }
                technicalIndicatorReportEntityArr.add(technicalIndicatorReportEntity);
            }
            klineEntities.add(klineEntity);
        }
        klines.put(symbol, klineEntities);
        resultMap.put("result", klineEntities);
        resultMap.put("technicalIndicatorReportEntityArr", technicalIndicatorReportEntityArr);

        long endTime = System.currentTimeMillis(); // 종료 시간 기록
        long elapsedTime = endTime - startTime; // 실행 시간 계산
        System.out.println("소요시간 : " + elapsedTime + " milliseconds");
        return resultMap;
    }
    private void openOrderProcess(EventEntity klineEvent, TechnicalIndicatorReportEntity technicalIndicatorReportEntity, boolean mockFlag){
        TradingEntity tradingEntity = klineEvent.getTradingEntity();
        if(tradingEntity.getAdxChecker() == 1){
            if (technicalIndicatorReportEntity.getAdxSignal() != 0
            ){
                String remark = "ADX 진입시그널("+technicalIndicatorReportEntity.getAdxGap()+")";
                try {
                    makeOpenOrder(klineEvent, technicalIndicatorReportEntity.getDirectionDi(), remark, mockFlag);
                } catch (Exception e) {
                    e.printStackTrace();
                    //throw new TradingException(tradingEntity);
                }
            }
        }
        if (tradingEntity.getMacdHistogramChecker() == 1){
            if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){
                int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                if(macdCrossSignal<0 && technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("0")) > 0){
                    String remark = "MACD 데드크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                    try {
                        makeOpenOrder(klineEvent, "SHORT", remark, mockFlag);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else if (macdCrossSignal > 0 && technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("0")) < 0){
                    String remark = "MACD 골든크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                    try {
                        makeOpenOrder(klineEvent, "LONG", remark, mockFlag);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
    private void closeOrderProcess(EventEntity klineEvent, TechnicalIndicatorReportEntity technicalIndicatorReportEntity, boolean mockFlag){
        TradingEntity tradingEntity = klineEvent.getTradingEntity();
        String symbol = klineEvent.getKlineEntity().getSymbol();
        Optional<EventEntity> openPositionEntityOpt = Optional.empty();
        try{
            openPositionEntityOpt = eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN");
        }catch (Exception e){
            e.printStackTrace();
        }
        openPositionEntityOpt.ifPresentOrElse(positionEvent -> { // 오픈된 포지션이 있다면
            TechnicalIndicatorReportEntity positionReport = positionEvent.getKlineEntity().getTechnicalIndicatorReportEntity();
            if(DEV_FLAG){
                String remark = "테스트 청산";
                PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                if(closePosition.getPositionStatus().equals("OPEN")){
                    makeCloseOrder(klineEvent, positionEvent, remark, mockFlag);
                }
            } else {
                if(tradingEntity.getAdxChecker() == 1){
                    if(positionReport.getEndTime().equals(technicalIndicatorReportEntity.getEndTime())){
                        return;
                    }
                    if(positionReport.getAdxSignal()>0&&technicalIndicatorReportEntity.getAdxGap()<1){
                        String remark = "ADX 청산시그널("+technicalIndicatorReportEntity.getAdxGap()+")";
                        PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                        if(closePosition.getPositionStatus().equals("OPEN")){
                            makeCloseOrder(klineEvent, positionEvent, remark, mockFlag);
                        }
                    } else if (positionReport.getAdxSignal()<0&&technicalIndicatorReportEntity.getAdxGap()>-1){
                        String remark = "ADX 청산시그널("+technicalIndicatorReportEntity.getAdxGap()+")";
                        PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                        if(closePosition.getPositionStatus().equals("OPEN")){
                            makeCloseOrder(klineEvent, positionEvent, remark, mockFlag);
                        }
                    }else{
                        //autoTradingRestart(tradingEntity);
                    }
                    /*if(technicalIndicatorReportEntity.getAdxGap()>1 // ADX가 1이상(추세가 강해질때)
                        ||klineEvent.getKlineEntity().getTechnicalIndicatorReportEntity().getCurrentAdx()-technicalIndicatorReportEntity.getCurrentAdx()<-1  // 현재 ADX가 포지션 진입당시 ADX보다 2이상 높아졌을때(추세가 강해질때)

                    ){
                        String remark = "ADX 청산시그널("+ technicalIndicatorReportEntity.getPreviousAdxGrade() +">"+ technicalIndicatorReportEntity.getCurrentAdxGrade() + ")";
                        PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                        if(closePosition.getPositionStatus().equals("OPEN")){
                            makeCloseOrder(eventEntity, klineEvent, remark);
                        }
                    }*/
                }
                if (tradingEntity.getMacdHistogramChecker() == 1){
                    if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){ //MACD 크로스가 일어났을때.
                        BigDecimal macd = technicalIndicatorReportEntity.getMacd();
                        int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                        if(macdCrossSignal<0){ //MACD가 양수일때 데드크로스가 일어났을때.
                            String remark = "MACD 데드크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                            PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("LONG")){ //포지션이 오픈되어있고 롱포지션일때
                                makeCloseOrder(klineEvent, positionEvent, remark, mockFlag);
                            }
                        } else if (macdCrossSignal > 0){ //MACD가 음수일때 골든크로스가 일어났을때.
                            String remark = "MACD 골든크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                            PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("SHORT")){ //포지션이 오픈되어있고 숏포지션일때
                                makeCloseOrder(klineEvent, positionEvent, remark, mockFlag);
                            }
                        }
                    }
                }
            }
        },() -> {

        });
        //eventRepository.save(eventEntity);
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

    public void makeOpenOrder(EventEntity currentEvent, String positionSide, String remark, boolean mockFlag){
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
            if(!mockFlag){
                LinkedHashMap<String,Object> leverageParamMap = new LinkedHashMap<>();
                leverageParamMap.put("symbol", tradingEntity.getSymbol());
                leverageParamMap.put("leverage", tradingEntity.getLeverage());
                leverageChange(leverageParamMap);

                //주문 제출
                Map<String, Object> resultMap = orderSubmit(makeOrder(tradingEntity, "OPEN"));
                //tradingRepository.save(tradingEntity);
            }
        } catch (Exception e) {
            e.printStackTrace();
            //throw new TradingException(tradingEntity);
        } finally {
            eventRepository.save(currentEvent);
            System.out.println("openTradingEntity >>>>> " + tradingEntity);
            tradingRepository.save(tradingEntity);
        }
    }

    public void makeCloseOrder(EventEntity currentEvent, EventEntity positionEvent, String remark, boolean mockFlag){
        System.out.println(remark);
        TradingEntity tradingEntity = positionEvent.getTradingEntity();
        PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
        tradingEntity.setClosePrice(currentEvent.getKlineEntity().getClosePrice());
        try { //마켓가로 클로즈 주문을 제출한다.
            closePosition.setCloseRemark(remark);
            closePosition.setPositionStatus("CLOSE");
            closePosition.setClosePrice(currentEvent.getKlineEntity().getClosePrice());
            if(!mockFlag){
                Map<String, Object> resultMap = orderSubmit(makeOrder(tradingEntity, "CLOSE"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            //throw new TradingException(tradingEntity);
        } finally {
            eventRepository.save(positionEvent);
            tradingEntity.setPositionStatus("CLOSE");
            tradingEntity.setTradingStatus("CLOSE");
            tradingEntity = tradingRepository.save(tradingEntity);
            streamClose(tradingEntity.getStreamId());
            System.out.println("closeTradingEntity >>>>> " + tradingEntity);
            log.info("스트림 종료");
            TRADING_ENTITYS.remove(tradingEntity.getSymbol());
            printTradingEntitys();
            if(!mockFlag) {
                autoTradingOpen(tradingEntity.getUserCd(), tradingEntity.toDTO());
            }
            //autoTradingRestart(tradingEntity);
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

    private TradingEntity getTradingEntity(String symbol) {
        return tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN")
                .orElseThrow(() -> new RuntimeException("트레이딩이 존재하지 않습니다."));
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

    private TechnicalIndicatorReportEntity technicalIndicatorCalculate(TradingEntity tradingEntity) {
        String tradingCd = tradingEntity.getTradingCd();
        String symbol = tradingEntity.getSymbol();
        String interval = tradingEntity.getCandleInterval();

        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);
        // 포맷 적용하여 문자열로 변환
        ZonedDateTime utcEndTime = series.getBar(series.getEndIndex()).getEndTime();
        ZonedDateTime kstEndTime = utcEndTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
        String formattedEndTime = formatter.format(kstEndTime);

        //tickSize
        BigDecimal tickSize = getTickSize(symbol.toUpperCase());
        //System.out.println("[캔들종료시간] : "+symbol+"/"+ formattedEndTime);

        // Define indicators
        OpenPriceIndicator openPrice   = new OpenPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice   = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice     = new LowPriceIndicator(series);

        // Calculate SMA
        SMAIndicator sma = new SMAIndicator(closePrice, 5);

        // Calculate EMA
        EMAIndicator ema = new EMAIndicator(closePrice, 7);

        // Calculate Bollinger Bands
        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, 21);
        BollingerBandsMiddleIndicator middleBBand    = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsUpperIndicator upperBBand      = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand      = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);

        // Calculate RSI
        RSIIndicator rsi = new RSIIndicator(closePrice, 6);

        // Determine current trend
        String currentTrend = technicalIndicatorCalculator.determineTrend(series, sma);

        //direction
        String direction = technicalIndicatorCalculator.getDirection(series, 14, series.getEndIndex(), tradingEntity.getTrendFollowFlag());

        //di
        double plusDi = technicalIndicatorCalculator.calculatePlusDI(series, 14, series.getEndIndex());
        double minusDi = technicalIndicatorCalculator.calculateMinusDI(series, 14, series.getEndIndex());

        //******************************** 여기서부터 ADX 관련 산식을 정의한다 **********************************
        //adx
        double currentAdx     = technicalIndicatorCalculator.calculateADX(series, 14, series.getEndIndex());
        double previousAdx    = technicalIndicatorCalculator.calculateADX(series, 14, series.getEndIndex()-1);
        double prePreviousAdx = technicalIndicatorCalculator.calculateADX(series, 14, series.getEndIndex()-2);

        ADX_GRADE currentAdxGrade     = technicalIndicatorCalculator.calculateADXGrade(currentAdx);
        ADX_GRADE previousAdxGrade    = technicalIndicatorCalculator.calculateADXGrade(previousAdx);
        ADX_GRADE prePreviousAdxGrade = technicalIndicatorCalculator.calculateADXGrade(prePreviousAdx);

        double adxGap = currentAdx - previousAdx;
        double previousAdxGap = previousAdx - prePreviousAdx;

        boolean isAdxGapPositive = adxGap > 0;
        boolean isPreviousAdxGapPositive = previousAdxGap > 0;

        if(ADX_CHECKER){
            System.out.println(" ADX(" + formattedEndTime + " : " + closePrice.getValue(series.getEndIndex()) + ") : " + currentAdx + "[" + adxGap + "]");
        }
        int adxSignal = 0;
        if (isAdxGapPositive == isPreviousAdxGapPositive) {
            //System.out.println("추세유지");
        } else {
            if (adxGap > 0) {
                if (currentAdxGrade.getGrade() == 0) {
                    if(ADX_CHECKER){
                        System.out.println("**********************************************************");
                        System.out.println("추세감소 >>> 추세증가 :" + previousAdx + " >>> " + currentAdx + "(" + previousAdxGap + "/" + adxGap + ")");
                        log.info("!!! " + currentAdxGrade + " !!! " + direction + "/" + currentTrend + " ADX(" + formattedEndTime + " : " + closePrice.getValue(series.getEndIndex()) + ") : " + currentAdx + "[" + adxGap + "]");
                        System.out.println("**********************************************************");
                    }
                    adxSignal = 1;
                }
            } else if (adxGap < 0) {
                if (currentAdxGrade.getGrade() > 2) {
                    if(ADX_CHECKER) {
                        System.out.println("**********************************************************");
                        System.out.println("추세증가 >>> 추세감소 :" + previousAdx + " >>> " + currentAdx + "(" + previousAdxGap + "/" + adxGap + ")");
                        log.info("!!! " + currentAdxGrade + " !!! " + direction + "/" + currentTrend + " ADX(" + formattedEndTime + " : " + closePrice.getValue(series.getEndIndex()) + ") : " + currentAdx + "[" + adxGap + "]");
                        System.out.println("**********************************************************");
                    }
                    adxSignal = -1;
                }
            }
        }

        /*if(adxGap > 0){ // 추세강화
            if (currentAdxGrade.getGrade() > 1) {
                adxSignal = 1;
            }
        } else if(adxGap < 0){ //추세반전
            adxSignal = -1;
        } else {
        }*/

        //****************************************** ADX 끝 **********************************************


        //******************************** 여기서부터 MACD 관련 산식을 정의한다 *******************************
        // Calculate MACD
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);

        double currentMacdHistogram     = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, 12, 26, series.getEndIndex());
        double previousMacdHistogram    = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, 12, 26, series.getEndIndex()-1);
        double prePreviousMacdHistogram = technicalIndicatorCalculator.calculateMACDHistogram(closePrice, 12, 26, series.getEndIndex()-2);

        double macdHistogramGap = currentMacdHistogram - previousMacdHistogram;
        double previousMacdHistogramGap = previousMacdHistogram - prePreviousMacdHistogram;

        boolean MACD_히스토그램_증가 = macdHistogramGap > 0;
        boolean 이전_MACD_히스토그램_증가 = previousMacdHistogramGap > 0;
        //System.out.println(macdGap+" : "+MACD_증가+"_"+이전_MACD_증가);
        if(MACD_CHECKER){
            System.out.println(" MACD 히스토그램(" + formattedEndTime + " : " + closePrice.getValue(series.getEndIndex()) + ") : " + currentMacdHistogram + "[" + macdHistogramGap + "]");
        }
        int macdReversalSignal = 0;
        if (MACD_히스토그램_증가 == 이전_MACD_히스토그램_증가) {
            //System.out.println("추세유지");
        } else {
            //if (currentMacd > 0) {
                if(이전_MACD_히스토그램_증가 && !MACD_히스토그램_증가){
                    if (MACD_CHECKER){
                        System.out.println("MACD히스토그램증가 >>> MACD히스토그램감소 :" + previousMacdHistogram + " >>> " + currentMacdHistogram + "(" + previousMacdHistogramGap + "/" + macdHistogramGap + ")");
                        log.info("!!!MACD 히스토그램 숏시그널(" + formattedEndTime+") : " + closePrice.getValue(series.getEndIndex()));
                    }
                    macdReversalSignal = -1;
                }
            //} else if (currentMacd < 0) {
                if(!이전_MACD_히스토그램_증가 && MACD_히스토그램_증가){
                    if (MACD_CHECKER) {
                        System.out.println("MACD히스토그램감소 >>> MACD히스토그램증가 :" + previousMacdHistogram + " >>> " + currentMacdHistogram + "(" + previousMacdHistogramGap + "/" + macdHistogramGap + ")");
                        log.info("!!!MACD 히스토그램 롱시그널(" + formattedEndTime+") : " + closePrice.getValue(series.getEndIndex()));
                    }
                    macdReversalSignal = 1;
                }
            //}
        }

        EMAIndicator MACD_신호선 = new EMAIndicator(macd, 9);
        // MACD 크로스 신호 계산
        int macdPreliminarySignal = 0;
        boolean isMacdHighAndDeadCrossSoon = macd.getValue(series.getEndIndex() - 1).isGreaterThan(MACD_신호선.getValue(series.getEndIndex() - 1))
                && macd.getValue(series.getEndIndex()).isLessThan(MACD_신호선.getValue(series.getEndIndex()));
        boolean isMacdLowAndGoldenCrossSoon = macd.getValue(series.getEndIndex() - 1).isLessThan(MACD_신호선.getValue(series.getEndIndex() - 1))
                && macd.getValue(series.getEndIndex()).isGreaterThan(MACD_신호선.getValue(series.getEndIndex()));

        if (isMacdHighAndDeadCrossSoon) {
            macdPreliminarySignal = -1;
        } else if (isMacdLowAndGoldenCrossSoon) {
            macdPreliminarySignal = 1;
        }
        int macdCrossSignal = 0 ; // 골든 크로스일시 1, 데드 크로스일시 -1
        if(technicalIndicatorCalculator.isGoldenCross(series, 12, 26, 9)){
            macdCrossSignal = 1;
        }
        if(technicalIndicatorCalculator.isDeadCross(series, 12, 26, 9)){
            macdCrossSignal = -1;
        }

        //****************************************** MACD 끝 ***********************************************

        //log.error(String.valueOf(new BigDecimal(macd.getValue(series.getEndIndex()).doubleValue()).setScale(10, RoundingMode.DOWN)));
        //BigDecimal decimalValue = new BigDecimal(macd.getValue(series.getEndIndex()).doubleValue());
        TechnicalIndicatorReportEntity technicalIndicatorReport = TechnicalIndicatorReportEntity.builder()
                .symbol(symbol)
                .endTime(kstEndTime.toLocalDateTime())
                .openPrice(CommonUtils.truncate(openPrice.getValue(series.getEndIndex()), tickSize))
                .closePrice(CommonUtils.truncate(closePrice.getValue(series.getEndIndex()), tickSize))
                .highPrice(CommonUtils.truncate(highPrice.getValue(series.getEndIndex()), tickSize))
                .lowPrice(CommonUtils.truncate(lowPrice.getValue(series.getEndIndex()), tickSize))
                .currentAdx(currentAdx)
                .currentAdxGrade(currentAdxGrade)
                .previousAdx(previousAdx)
                .previousAdxGrade(previousAdxGrade)
                .adxSignal(adxSignal)
                .adxGap(adxGap)
                .plusDi(plusDi)
                .minusDi(minusDi)
                .directionDi(direction)
                .sma(CommonUtils.truncate(sma.getValue(series.getEndIndex()), tickSize))
                .ema(CommonUtils.truncate(ema.getValue(series.getEndIndex()), tickSize))
                .directionMa(currentTrend)
                .ubb(CommonUtils.truncate(upperBBand.getValue(series.getEndIndex()), tickSize))
                .mbb(CommonUtils.truncate(middleBBand.getValue(series.getEndIndex()), tickSize))
                .lbb(CommonUtils.truncate(lowerBBand.getValue(series.getEndIndex()), tickSize))
                .rsi(CommonUtils.truncate(rsi.getValue(series.getEndIndex()), new BigDecimal(2)))
                .macd(new BigDecimal(macd.getValue(series.getEndIndex()).doubleValue()).setScale(10, RoundingMode.DOWN))
                .macdPreliminarySignal(macdPreliminarySignal)
                .macdCrossSignal(macdCrossSignal)
                //.macdPeakSignal(macdPeakSignal)
                .macdReversalSignal(macdReversalSignal)
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