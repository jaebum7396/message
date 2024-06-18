package trade.future.service;

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
import trade.exception.TradingException;
import trade.future.model.entity.*;
import trade.future.model.enums.ADX_GRADE;
import trade.future.repository.EventRepository;
import trade.future.repository.PositionRepository;
import trade.future.repository.TradingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    int failureCount = 0;
    private HashMap<String, List<KlineEntity>> klines = new HashMap<String, List<KlineEntity>>();
    private HashMap<String, BaseBarSeries> seriesMap = new HashMap<String, BaseBarSeries>();
    private static final int WINDOW_SIZE = 500; // For demonstration purposes

    @Transactional
    public void onOpenCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        System.out.println("[OPEN] >>>>> " + streamId + " 번 스트림을 오픈합니다.");
        tradingEntity.setTradingStatus("OPEN");
        tradingRepository.save(tradingEntity);
        /*if (streamId.equals("1")){
            throw new RuntimeException("강제예외 발생");
        }*/
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

        if (isFinal) {
            System.out.println("event : " + event);
            TradingEntity tradingEntity = getTradingEntity(symbol);
            // klineEvent를 데이터베이스에 저장
            EventEntity eventEntity = saveKlineEvent(event, tradingEntity);

            TechnicalIndicatorReportEntity technicalIndicatorReportEntity = eventEntity.getKlineEntity().getTechnicalIndicatorReportEntity();
            if(technicalIndicatorReportEntity.getAdxSignal() == -1){
                eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(klineEvent -> {
                    String remark = "ADX 청산시그널("+ technicalIndicatorReportEntity.getPreviousAdxGrade() +">"+ technicalIndicatorReportEntity.getCurrentAdxGrade() + ")";
                    PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                    if(closePosition.getPositionStatus().equals("OPEN")){
                        makeCloseOrder(eventEntity, klineEvent, remark);
                    }
                });
            }else if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){ //MACD 크로스가 일어났을때.
                int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                if(technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("200")) > 0 && macdCrossSignal<0){ //MACD가 200을 넘어서 데드크로스가 일어났을때.
                    eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(klineEvent -> { //포지션이 오픈되어있는 이벤트를 찾는다.
                        String remark = "MACD 데드크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                        PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                        if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("LONG")){ //포지션이 오픈되어있고 롱포지션일때
                            makeCloseOrder(eventEntity, klineEvent, remark);
                        }
                    });
                } else if (technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("-200")) < 0 && macdCrossSignal > 0){ //MACD가 -200을 넘어서 골든크로스가 일어났을때.
                    eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN").ifPresent(klineEvent -> { //포지션이 오픈되어있는 이벤트를 찾는다.
                        String remark = "MACD 골든크로스 청산시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                        PositionEntity closePosition = klineEvent.getKlineEntity().getPositionEntity();
                        if(closePosition.getPositionStatus().equals("OPEN") && closePosition.getPositionSide().equals("SHORT")){ //포지션이 오픈되어있고 숏포지션일때
                            makeCloseOrder(eventEntity, klineEvent, remark);
                        }
                    });
                }
            }
            eventRepository.save(eventEntity);
        }
    }

    public void allPositionsClose(){
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        /*JSONArray balanceInfo = new JSONArray(client.account().futuresAccountBalance(new LinkedHashMap<>()));
        printPrettyJson(balanceInfo);*/
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        JSONArray positions = accountInfo.getJSONArray("positions");
        positions.forEach(position -> {
            JSONObject symbolObj = new JSONObject(position.toString());
            if(new BigDecimal(String.valueOf(symbolObj.get("positionAmt"))).compareTo(new BigDecimal("0")) != 0){
                LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
                paramMap.put("symbol", symbolObj.get("symbol"));
                paramMap.put("positionSide", symbolObj.get("positionSide"));
                paramMap.put("side", symbolObj.get("positionSide").equals("LONG") ? "SELL" : "BUY");
                paramMap.put("quantity", "100");
                paramMap.put("type", "MARKET");
                try {
                    orderSubmit(paramMap);
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
        autoTradingClose();
    }

    public void makeCloseOrder(EventEntity currentEvent, EventEntity positionEvent, String remark){
        System.out.println(remark);
        TradingEntity tradingEntity = currentEvent.getTradingEntity();
        PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();
        try { //마켓가로 클로즈 주문을 제출한다.
            closePosition.setRemark(remark);
            closePosition.setPositionStatus("CLOSE");
            closePosition.setClosePrice(currentEvent.getKlineEntity().getClosePrice());
            Map<String, Object> returnMap = orderSubmit(makeOrder(tradingEntity, closePosition.getPositionSide(), currentEvent.getKlineEntity().getClosePrice(), "OPEN", "MARKET"));
            eventRepository.save(positionEvent); //포지션을 클로즈한다.
        } catch (Exception e) {
            e.printStackTrace();
            throw new TradingException(tradingEntity);
        }
    }

    public void makeOpenOrder(EventEntity currentEvent, String positionSide, String remark){
        System.out.println(remark);
        PositionEntity openPosition = currentEvent.getKlineEntity().getPositionEntity();
        TradingEntity tradingEntity = currentEvent.getTradingEntity();
        try {
            openPosition.setPositionStatus("OPEN");
            openPosition.setEntryPrice(currentEvent.getKlineEntity().getClosePrice());
            openPosition.setPositionSide(positionSide);
            openPosition.setRemark(remark);
            Map<String, Object> returnMap = orderSubmit(makeOrder(tradingEntity, openPosition.getPositionSide(), currentEvent.getKlineEntity().getClosePrice(), "OPEN", "MARKET"));
            //Map<String, Object> returnMap = orderSubmit(makeOrder(klineEvent, "OPEN", "MARKET"));
            eventRepository.save(currentEvent);
        } catch (Exception e) {
            e.printStackTrace();
            throw new TradingException(tradingEntity);
        }
    }

    public LinkedHashMap<String,Object> makeOrder(TradingEntity tradingEntity, String positionSide, BigDecimal currentPrice, String intent, String type){
        LinkedHashMap<String,Object> paramMap = new LinkedHashMap<>();
        String symbol = tradingEntity.getSymbol();
        String side = ""; //BUY/SELL
        paramMap.put("symbol", symbol);
        if (intent.equals("OPEN")) {
            side = positionSide.equals("LONG") ? "BUY" : "SELL";
            paramMap.put("positionSide", positionSide);
            paramMap.put("side", side);
            BigDecimal notional = tradingEntity.getCollateral().multiply(new BigDecimal("3"));
            if (notional.compareTo(getNotional(symbol)) > 0) {
                BigDecimal quantity = notional.divide(currentPrice, 10, RoundingMode.UP);
                BigDecimal stepSize = getStepSize(symbol);
                quantity = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);

                paramMap.put("quantity", quantity);
            }else{
                System.out.println("명목가치(" + notional+ ")가 최소주문가능금액보다 작습니다.");
                throw new TradingException(tradingEntity);
            }
        } else if (intent.equals("CLOSE")) {
            paramMap.put("positionSide", positionSide);
            paramMap.put("side", positionSide.equals("LONG") ? "SELL" : "BUY");
            paramMap.put("quantity", "100");
        }
        //paramMap.put("positionSide", positionEntity.getPositionStatus());
        paramMap.put("type", type);

        return paramMap;
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

    public Map<String, Object> orderSubmit(LinkedHashMap<String, Object> requestParam) throws Exception {
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        try{
            UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
            LinkedHashMap<String, Object> leverageParam = new LinkedHashMap<>();
            leverageParam.put("symbol", requestParam.get("symbol"));
            leverageParam.put("leverage", 3);
            String leverageChangeResult = client.account().changeInitialLeverage(leverageParam);
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventEntity saveKlineEvent(String event, TradingEntity tradingEntity) {
        EventEntity klineEvent = null;
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 3;
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
                if (technicalIndicatorReportEntity.getAdxSignal() == 1){
                    String remark = "ADX 진입시그널("+ technicalIndicatorReportEntity.getPreviousAdxGrade() +">"+ technicalIndicatorReportEntity.getCurrentAdxGrade() + ")";
                    try {
                        makeOpenOrder(klineEvent, technicalIndicatorReportEntity.getDirectionDi(), remark);
                    } catch (Exception e) {
                        throw new TradingException(tradingEntity);
                    }
                } else if(technicalIndicatorReportEntity.getMacdCrossSignal() != 0){
                    int macdCrossSignal = technicalIndicatorReportEntity.getMacdCrossSignal();
                    if(technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("200")) > 0 && macdCrossSignal<0){
                        String remark = "MACD 데드크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                        try {
                            makeOpenOrder(klineEvent, "SHORT", remark);
                        } catch (Exception e) {
                            throw new TradingException(tradingEntity);
                        }
                    } else if (technicalIndicatorReportEntity.getMacd().compareTo(new BigDecimal("-200")) < 0 && macdCrossSignal > 0){
                        String remark = "MACD 골든크로스 진입시그널(" + technicalIndicatorReportEntity.getMacd() + ")";
                        try {
                            makeOpenOrder(klineEvent, "LONG", remark);
                        } catch (Exception e) {
                            throw new TradingException(tradingEntity);
                        }
                    }
                }
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
        List<Map<String, Object>> selectedStockList;
        //allPositionsClose();

        System.out.println("symbolParam : " + symbolParam);

        if(symbolParam == null || symbolParam.isEmpty()) {
            selectedStockList = (List<Map<String, Object>>) getStockSelection(stockSelectionCount).get("overlappingData");
        } else {
            LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
            paramMap.put("symbol", symbolParam);
            String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
            JSONObject result = new JSONObject(resultStr);
            List<Map<String, Object>> list = new ArrayList<>();
            list.add(result.toMap());
            selectedStockList = list;
        }

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
                        .collateral(new BigDecimal("100"))
                        //.fluctuationRate(new BigDecimal(2))
                        .build();
                autoTradeStreamOpen(tradingEntity);
            }
        });
        return resultMap;
    }

    public TradingEntity autoTradeStreamOpen(TradingEntity tradingEntity) {
        tradingRepository.save(tradingEntity);
        getKlines(tradingEntity.getTradingCd(), tradingEntity.getSymbol(), tradingEntity.getCandleInterval(), 500);
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

        UMFuturesClientImpl client = new UMFuturesClientImpl();

        paramMap.put("symbol", symbol);
        paramMap.put("interval", interval);
        paramMap.put("limit", limit);

        String resultStr = client.market().klines(paramMap);

        JSONArray jsonArray = new JSONArray(resultStr);
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
                System.out.println("!!! 포지션 진입 시그널("+kstEndTime+")");
            }
            //System.out.println("추세등급 증가: " + (previousAdxGrade +" > "+ currentAdxGrade));
            //System.out.println("방향(DI기준): " + direction);
        } else if(currentAdxGrade.getGrade() - previousAdxGrade.getGrade() < 0){
            //System.out.println("추세등급 감소: " + previousAdxGrade +" > "+ currentAdxGrade);
            //System.out.println("방향(DI기준): " + direction);
            adxSignal = -1;
        } else {
            //System.out.println("추세등급 유지: " + currentAdxGrade);
            //System.out.println("방향(DI기준): " + direction);
        }
        //System.out.println("방향(MA기준): " + currentTrend);

        int macdCrossSignal =0 ;
        if(technicalIndicatorCalculator.isGoldenCross(series, 12, 26, 9)){
            macdCrossSignal = 1;
        }
        if(technicalIndicatorCalculator.isDeadCross(series, 12, 26, 9)){
            macdCrossSignal = -1;
        }

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
                .directionMa(currentTrend)
                .ubb(CommonUtils.truncate(upperBBand.getValue(series.getEndIndex()), tickSize))
                .mbb(CommonUtils.truncate(middleBBand.getValue(series.getEndIndex()), tickSize))
                .lbb(CommonUtils.truncate(lowerBBand.getValue(series.getEndIndex()), tickSize))
                .rsi(CommonUtils.truncate(rsi.getValue(series.getEndIndex()), new BigDecimal(2)))
                .macd(CommonUtils.truncate(macd.getValue(series.getEndIndex()), new BigDecimal(0)))
                .macdCrossSignal(macdCrossSignal)
                .build();
        return technicalIndicatorReport;
    }
}