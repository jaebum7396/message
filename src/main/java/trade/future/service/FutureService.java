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
import trade.exception.TradingException;
import trade.future.model.dto.TradingDTO;
import trade.future.model.entity.*;
import trade.future.model.enums.ADX_GRADE;
import trade.future.repository.EventRepository;
import trade.future.repository.PositionRepository;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int WINDOW_SIZE = 500; // For demonstration purposes

    private static final boolean DEV_FLAG = false;
    private static final boolean MACD_CHECKER = false;
    private static final boolean ADX_CHECKER = true;

    public void onOpenCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        log.info("[OPEN] >>>>> " + streamId + " 번 스트림("+tradingEntity.getSymbol()+")을 오픈합니다.");
        tradingEntity.setTradingStatus("OPEN");
        tradingRepository.save(tradingEntity);
        getKlines(tradingEntity.getTradingCd(), tradingEntity.getSymbol(), tradingEntity.getCandleInterval(), 500);
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

        if (isFinal) {
            System.out.println("event : " + event);
            TradingEntity tradingEntity = getTradingEntity(symbol);
            // klineEvent를 데이터베이스에 저장
            EventEntity eventEntity = saveKlineEvent(event, tradingEntity);

            TechnicalIndicatorReportEntity technicalIndicatorReportEntity = eventEntity.getKlineEntity().getTechnicalIndicatorReportEntity();
            if(DEV_FLAG){
                eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(klineEvent -> {
                    String remark = "테스트 청산";
                    PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                    if(closePosition.getPositionStatus().equals("OPEN")){
                        makeCloseOrder(eventEntity, klineEvent, remark);
                    }
                });
            } else {
                if (ADX_CHECKER){
                    Optional<EventEntity> openPositionEntityOpt = eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN");
                    openPositionEntityOpt.ifPresentOrElse(klineEvent -> { // 오픈된 포지션이 있다면
                        if(technicalIndicatorReportEntity.getAdxSignal() == -1||technicalIndicatorReportEntity.getAdxGap()<-1){
                            String remark = "ADX 청산시그널("+ technicalIndicatorReportEntity.getPreviousAdxGrade() +">"+ technicalIndicatorReportEntity.getCurrentAdxGrade() + ")";
                            PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN")){
                                makeCloseOrder(eventEntity, klineEvent, remark);
                            }
                        }
                    },() -> {
                        if(technicalIndicatorReportEntity.getAdxSignal() == -1
                            ||technicalIndicatorReportEntity.getAdxGap()<-1
                            ||technicalIndicatorReportEntity.getCurrentAdxGrade().getGrade()>ADX_GRADE.추세확정.getGrade()){
                            try {
                                autoTradingRestart(tradingEntity);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                    if(technicalIndicatorReportEntity.getAdxSignal() == -1||technicalIndicatorReportEntity.getAdxGap()<-1){
                        openPositionEntityOpt.ifPresentOrElse(klineEvent -> { // 오픈된 포지션이 있다면
                            String remark = "ADX 청산시그널("+ technicalIndicatorReportEntity.getPreviousAdxGrade() +">"+ technicalIndicatorReportEntity.getCurrentAdxGrade() + ")";
                            PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                            if(closePosition.getPositionStatus().equals("OPEN")){
                                makeCloseOrder(eventEntity, klineEvent, remark);
                            }
                        }, () -> {
                            try {
                                autoTradingRestart(tradingEntity);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                }
                if (MACD_CHECKER){
                    if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){ //MACD 크로스가 일어났을때.
                        BigDecimal macd = technicalIndicatorReportEntity.getMacd();
                        int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                        if(macdCrossSignal<0){ //MACD가 양수일때 데드크로스가 일어났을때.
                            eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(klineEvent -> { //포지션이 오픈되어있는 이벤트를 찾는다.
                                String remark = "MACD 데드크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                                PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                                if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("LONG")){ //포지션이 오픈되어있고 롱포지션일때
                                    makeCloseOrder(eventEntity, klineEvent, remark);
                                }
                            });
                        } else if (macdCrossSignal > 0){ //MACD가 음수일때 골든크로스가 일어났을때.
                            eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(klineEvent -> { //포지션이 오픈되어있는 이벤트를 찾는다.
                                String remark = "MACD 골든크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                                PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                                if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("SHORT")){ //포지션이 오픈되어있고 숏포지션일때
                                    makeCloseOrder(eventEntity, klineEvent, remark);
                                }
                            });
                        }
                    }
                }
            }
            //eventRepository.save(eventEntity);
        }
    }

    public EventEntity saveKlineEvent(String event, TradingEntity tradingEntity) {
        EventEntity klineEvent = null;
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 1;
        int initialDelayMillis = 1000;
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                klineEvent = CommonUtils.convertKlineEventDTO(event).toEntity();
                klineEvent.setTradingEntity(tradingEntity);
                int goalPricePercent = tradingEntity.getGoalPricePercent();
                int leverage = tradingEntity.getLeverage();

                // 캔들데이터 분석을 위한 bar 세팅
                settingBar(tradingEntity.getTradingCd(),event);
                TechnicalIndicatorReportEntity technicalIndicatorReportEntity = technicalIndicatorCalculate(tradingEntity.getTradingCd(), tradingEntity.getSymbol(), tradingEntity.getCandleInterval());
                klineEvent.getKlineEntity().setTechnicalIndicatorReportEntity(technicalIndicatorReportEntity);

                // 포지션 엔티티 생성
                PositionEntity positionEntity = PositionEntity.builder()
                        .positionStatus("NONE")
                        .goalPricePercent(goalPricePercent)
                        .klineEntity(klineEvent.getKlineEntity())
                        .plusGoalPrice(
                                CommonUtils.calculateGoalPrice(
                                        klineEvent.getKlineEntity().getClosePrice(), "LONG", leverage, goalPricePercent))
                        .minusGoalPrice(
                                CommonUtils.calculateGoalPrice(
                                        klineEvent.getKlineEntity().getClosePrice(), "SHORT", leverage, goalPricePercent))
                        .build();
                klineEvent.getKlineEntity().setPositionEntity(positionEntity);
                EventEntity finalKlineEvent = klineEvent;
                eventRepository.findEventBySymbolAndPositionStatus(tradingEntity.getSymbol(), "OPEN").ifPresentOrElse(currentPositionKlineEvent -> {
                    System.out.println("포지션 오픈중 : " + currentPositionKlineEvent.getKlineEntity().getPositionEntity());
                }, () -> {
                    //System.out.println("포지션 없음");
                    if (DEV_FLAG) {
                        String remark = "테스트모드 진입시그널";
                        try {
                            makeOpenOrder(finalKlineEvent, "LONG", remark);
                        } catch (Exception e) {
                            throw new TradingException(tradingEntity);
                        }
                    } else {
                        if(ADX_CHECKER){
                            if (technicalIndicatorReportEntity.getAdxSignal() == 1){
                                String remark = "ADX 진입시그널("+ technicalIndicatorReportEntity.getPreviousAdxGrade() +">"+ technicalIndicatorReportEntity.getCurrentAdxGrade() + ")";
                                try {
                                    makeOpenOrder(finalKlineEvent, technicalIndicatorReportEntity.getDirectionDi(), remark);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    //throw new TradingException(tradingEntity);
                                }
                            }
                        }
                        if (MACD_CHECKER){
                            if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){
                                int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                                if(macdCrossSignal<0 && technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("0")) > 0){
                                    String remark = "MACD 데드크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                                    try {
                                        makeOpenOrder(finalKlineEvent, "SHORT", remark);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        //throw new TradingException(tradingEntity);
                                    }
                                } else if (macdCrossSignal > 0 && technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("0")) < 0){
                                    String remark = "MACD 골든크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                                    try {
                                        makeOpenOrder(finalKlineEvent, "LONG", remark);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        //throw new TradingException(tradingEntity);
                                    }
                                }
                            }
                        }
                    }
                });
                klineEvent = eventRepository.save(klineEvent);
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
            openPosition.setRemark(remark);

            //레버리지 변경
            LinkedHashMap<String,Object> leverageParamMap = new LinkedHashMap<>();
            leverageParamMap.put("symbol", tradingEntity.getSymbol());
            leverageParamMap.put("leverage", tradingEntity.getLeverage());
            leverageChange(leverageParamMap);

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
            closePosition.setRemark(remark);
            closePosition.setPositionStatus("CLOSE");
            closePosition.setClosePrice(currentEvent.getKlineEntity().getClosePrice());
            Map<String, Object> resultMap = orderSubmit(makeOrder(tradingEntity, "CLOSE"));

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
            autoTradingOpen(tradingEntity.getUserCd(), tradingEntity.getTargetSymbol(), tradingEntity.getCandleInterval(), tradingEntity.getLeverage(), tradingEntity.getGoalPricePercent(), tradingEntity.getStockSelectionCount(), tradingEntity.getMaxPositionCount());
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

    public Map<String, Object> orderSubmit(LinkedHashMap<String, Object> requestParam) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        try{
            UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
            requestParam.remove("leverage");
            System.out.println("new Order : " + requestParam);
            String orderResult = client.account().newOrder(requestParam);
            System.out.println("result : " + orderResult);
            resultMap.put("result", orderResult);
        } catch (Exception e) {
            throw e;
        }
        return resultMap;
    }

    public Map<String, Object> orderSubmitCollateral(LinkedHashMap<String, Object> requestParam) throws Exception {
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

    public void autoTradingRestart(TradingEntity tradingEntity){
        tradingEntity = autoTradingClose(tradingEntity);
        autoTradingOpen(tradingEntity.getUserCd(), tradingEntity.getTargetSymbol(), tradingEntity.getCandleInterval(), tradingEntity.getLeverage(), tradingEntity.getGoalPricePercent(), tradingEntity.getStockSelectionCount(), tradingEntity.getMaxPositionCount());
    }

    public Map<String, Object> autoTradingOpen(HttpServletRequest request, TradingDTO tradingDTO) {
        Claims claims = getClaims(request);
        String userCd = String.valueOf(claims.get("userCd"));
        if (userCd == null || userCd.isEmpty()) {
            throw new RuntimeException("사용자 정보가 없습니다.");
        }
        return autoTradingOpen(userCd, tradingDTO.getSymbol(), tradingDTO.getInterval(), tradingDTO.getLeverage(), tradingDTO.getGoalPricePercent(), tradingDTO.getStockSelectionCount(), tradingDTO.getMaxPositionCount());
    }

    public Map<String, Object> autoTradingOpen(String userCd, String targetSymbol, String interval, int leverage, int goalPricePercent, int stockSelectionCount, int maxPositionCount) {
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
        System.out.println("symbolParam : " + targetSymbol);

        List<TradingEntity> openTradingList = tradingRepository.findByTradingStatus("OPEN");
        int availablePositionCount = maxPositionCount - openTradingList.size();

        if(targetSymbol == null || targetSymbol.isEmpty()) {
            selectedStockList = (List<Map<String, Object>>) getStockFind(interval, stockSelectionCount, availablePositionCount).get("overlappingData");
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
            //BigDecimal averageQuoteAssetVolume = getKlinesAverageQuoteAssetVolume( (JSONArray)getKlines(symbol, interval, 500).get("result"), interval);
            TradingEntity tradingEntity = TradingEntity.builder()
                    .symbol(symbol)
                    .tradingStatus("OPEN")
                    .candleInterval(interval)
                    .leverage(leverage)
                    .goalPricePercent(goalPricePercent)
                    .stockSelectionCount(stockSelectionCount)
                    .maxPositionCount(maxPositionCount)
                    .collateral(finalAvailableBalance)
                    .userCd(userCd)
                    .build();
            if (targetSymbol != null && !targetSymbol.isEmpty()) {
                tradingEntity.setTargetSymbol(targetSymbol);
            }
            autoTradeStreamOpen(tradingEntity);
        });
        return resultMap;
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

    public Map<String, Object> getStockFind(String interval, int limit, int availablePositionCount) {
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

        AtomicInteger count = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (Map<String, Object> item : sortedByQuoteVolume) {
            if (count.get() >= availablePositionCount) {
                break;
            }

            executor.submit(() -> {
                String tempCd = String.valueOf(UUID.randomUUID());
                String symbol = String.valueOf(item.get("symbol"));
                Optional<TradingEntity> tradingEntityOpt = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");

                if (tradingEntityOpt.isEmpty()) {
                    getKlines(tempCd, symbol, interval, 500);
                    TechnicalIndicatorReportEntity tempReport = technicalIndicatorCalculate(tempCd, symbol, interval);

                    synchronized (this) {
                        if (ADX_CHECKER && tempReport.getCurrentAdxGrade().equals(ADX_GRADE.약한추세) && tempReport.getAdxGap() > 1) {
                            if (count.get() < availablePositionCount) {
                                overlappingData.add(item);
                                reports.add(tempReport);
                                count.incrementAndGet();
                            }
                        }

                        if (MACD_CHECKER && tempReport.getMacdPreliminarySignal() != 0) {
                            if (count.get() < availablePositionCount) {
                                overlappingData.add(item);
                                reports.add(tempReport);
                                count.incrementAndGet();
                            }
                        }
                    }
                }
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.MINUTES)) { // 60분 내에 모든 작업이 완료되지 않으면 강제 종료
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        resultMap.put("reports", reports);
        resultMap.put("overlappingData", overlappingData);
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

    public Map<String, Object> getKlines(String tradingCd, String symbol, String interval, int limit) {
        long startTime = System.currentTimeMillis(); // 시작 시간 기록
        log.info("getKline >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

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
                technicalIndicatorCalculate(tradingCd, symbol, interval);
            }
        }
        klines.put(symbol, klineEntities);
        resultMap.put("result", klineEntities);

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

    private TechnicalIndicatorReportEntity technicalIndicatorCalculate(String tradingCd, String symbol, String interval) {
        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);
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
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);

        // Determine current trend
        String currentTrend = technicalIndicatorCalculator.determineTrend(series, sma);

        // 포맷 적용하여 문자열로 변환
        ZonedDateTime utcEndTime = series.getBar(series.getEndIndex()).getEndTime();
        ZonedDateTime kstEndTime = utcEndTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
        String formattedEndTime = formatter.format(kstEndTime);
        //System.out.println("[캔들종료시간] : "+symbol+"/"+ formattedEndTime);

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
                adxSignal = 1;
                System.out.println("!!! ADX 포지션 진입 시그널("+kstEndTime+") : "+ closePrice.getValue(series.getEndIndex())+"["+direction+"]");
            }
            //System.out.println("추세등급 증가: " + (previousAdxGrade +" > "+ currentAdxGrade));
            //System.out.println("방향(DI기준): " + direction);
        } else if(currentAdxGrade.getGrade() - previousAdxGrade.getGrade() < 0){
            //System.out.println("추세등급 감소: " + previousAdxGrade +" > "+ currentAdxGrade);
            //System.out.println("방향(DI기준): " + direction);
            adxSignal = -1;
            System.out.println("!!! ADX 포지션 청산 시그널("+kstEndTime+") : "+ closePrice.getValue(series.getEndIndex()));
        } else {
            //System.out.println("추세등급 유지: " + currentAdxGrade);
            //System.out.println("방향(DI기준): " + direction);
        }
        //System.out.println("방향(MA기준): " + currentTrend);

        // MACD 고점 및 저점 검증 로직 추가
        int macdPreliminarySignal = 0;
        boolean isMacdHighAndDeadCrossSoon = macd.getValue(series.getEndIndex() - 1).isGreaterThan(macdSignal.getValue(series.getEndIndex() - 1))
                && macd.getValue(series.getEndIndex()).isLessThan(macdSignal.getValue(series.getEndIndex()));
        boolean isMacdLowAndGoldenCrossSoon = macd.getValue(series.getEndIndex() - 1).isLessThan(macdSignal.getValue(series.getEndIndex() - 1))
                && macd.getValue(series.getEndIndex()).isGreaterThan(macdSignal.getValue(series.getEndIndex()));

        if (isMacdHighAndDeadCrossSoon) {
            macdPreliminarySignal = -1;
        } else if (isMacdLowAndGoldenCrossSoon) {
            macdPreliminarySignal = 1;
        }

        int macdCrossSignal =0 ;
        if(technicalIndicatorCalculator.isGoldenCross(series, 12, 26, 9)){
            macdCrossSignal = 1;
            System.out.println("!!! MACD 시그널("+kstEndTime+") : "+ closePrice.getValue(series.getEndIndex())+"[골든크로스]"+macd.getValue(series.getEndIndex()));
        }
        if(technicalIndicatorCalculator.isDeadCross(series, 12, 26, 9)){
            macdCrossSignal = -1;
            System.out.println("!!! MACD 시그널("+kstEndTime+") : "+ closePrice.getValue(series.getEndIndex())+"[데드크로스]"+macd.getValue(series.getEndIndex()));
        }

        //log.error(String.valueOf(new BigDecimal(macd.getValue(series.getEndIndex()).doubleValue()).setScale(10, RoundingMode.DOWN)));
        //BigDecimal decimalValue = new BigDecimal(macd.getValue(series.getEndIndex()).doubleValue());
        TechnicalIndicatorReportEntity technicalIndicatorReport = TechnicalIndicatorReportEntity.builder()
                .symbol(symbol)
                .endTime(kstEndTime.toLocalDateTime())
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