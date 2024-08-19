package trade.future.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.binance.connector.futures.client.utils.WebSocketCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
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
import trade.future.ml.MLModel;
import trade.future.ml.SignalProximityScanner;
import trade.future.model.Rule.*;
import trade.future.model.dto.TradingDTO;
import trade.future.model.entity.*;
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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static trade.common.CommonUtils.parseKlineEntity;

@Slf4j
@Service
@Transactional
public class FutureMLService {
    public FutureMLService(
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

    @Autowired TechnicalIndicatorCalculator technicalIndicatorCalculator;
    @Autowired EventRepository eventRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired TradingRepository tradingRepository;
    @Autowired TechnicalIndicatorRepository technicalIndicatorRepository;
    @Autowired MyWebSocketClientImpl umWebSocketStreamClient;

    public static final String BASE_URL_TEST = "https://testnet.binance.vision";
    public static final String BASE_URL_REAL = "wss://ws-api.binance.com:443/ws-api/v3";

    @Value("${jwt.secret.key}") private String JWT_SECRET_KEY;
    public String BINANCE_API_KEY;
    public String BINANCE_SECRET_KEY;

    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();
    private final WebSocketCallback noopCallback = msg -> {};
    private final WebSocketCallback openCallback = this::onOpenCallback;
    private final WebSocketCallback onMessageCallback = this::onMessageCallback;
    private final WebSocketCallback closeCallback = this::onCloseCallback;
    private final WebSocketCallback failureCallback = this::onFailureCallback;

    // 원하는 형식의 날짜 포맷 지정
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public JSONObject exchangeInfo;
    public JSONArray symbols;
    public String BASE_URL;
    int failureCount = 0;
    private HashMap<String, BaseBarSeries> seriesMap = new HashMap<String, BaseBarSeries>();
    private HashMap<String, MLModel> mlModelMap = new HashMap<String, MLModel>();
    private HashMap<String, Strategy> strategyMap = new HashMap<String, Strategy>();
    private final Map<String, TradingEntity> TRADING_ENTITYS = new HashMap<>();

    public void onOpenCallback(String streamId) {
        TradingEntity tradingEntity = Optional.ofNullable(umWebSocketStreamClient.getTradingEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new RuntimeException(streamId + "번 트레이딩이 존재하지 않습니다."));
        log.info("[OPEN] >>>>> " + streamId + " 번 스트림("+tradingEntity.getSymbol()+")을 오픈합니다.");
        tradingEntity.setTradingStatus("OPEN");
        tradingRepository.save(tradingEntity);
        // 시리즈 생성
        seriesMaker(tradingEntity, false);
        // 전략 생성
        strategyMaker(tradingEntity, false);
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
        } else {
            System.out.println("[RECOVER-ERR] >>>>> "+streamId +" 번 스트림을 복구하지 못했습니다.");
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

    private List<TradingEntity> getTradingEntity(String symbol) {
        return tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");
    }

    public void streamClose(int streamId) {
        log.info("streamClose >>>>> " + streamId);
        umWebSocketStreamClient.closeConnection(streamId);
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
        } catch (Exception e) {
            nextFlag = false;
            e.printStackTrace();
            for(TradingEntity tradingEntity:tradingEntitys){
                restartTrading(tradingEntity);
            }
        }
        if (nextFlag) {
            TradingEntity tradingEntity = tradingEntitys.get(0);
            String tradingCd = tradingEntity.getTradingCd();
            if(isFinal){
                // klineEvent를 데이터베이스에 저장
                EventEntity eventEntity = saveKlineEvent(event, tradingEntity);
                BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);
                Strategy longStrategy = strategyMap.get(tradingCd + "_" + interval + "_long_strategy");
                Strategy shortStrategy = strategyMap.get(tradingCd + "_" + interval + "_short_strategy");

                boolean longShouldEnter = longStrategy.shouldEnter(series.getEndIndex());
                boolean longShouldExit = longStrategy.shouldExit(series.getEndIndex());
                boolean shortShouldEnter = shortStrategy.shouldEnter(series.getEndIndex());
                boolean shortShouldExit = shortStrategy.shouldExit(series.getEndIndex());

                printTradingSignals(symbol, series.getLastBar(), longShouldEnter, longShouldExit, shortShouldEnter, shortShouldExit);
                MLModel mlModel = mlModelMap.get(tradingCd);
                List<Indicator<Num>> indicators = initializeIndicators(series);

                double threshold = 0.5; // 시그널 임계값
                double proximityThreshold = 0.3; // 근접 임계값
                SignalProximityScanner scanner = new SignalProximityScanner(indicators, series, mlModel, threshold, proximityThreshold);
                scanner.printSignalProximity(symbol);

                Optional<EventEntity> openPositionEntityOpt = eventRepository.findEventBySymbolAndPositionStatus(symbol, "OPEN");
                openPositionEntityOpt.ifPresentOrElse(positionEvent -> { // 오픈된 포지션이 있다면
                    PositionEntity closePosition = positionEvent.getKlineEntity().getPositionEntity();

                    BigDecimal currentROI;
                    BigDecimal currentPnl;
                    if(tradingEntity.getPositionStatus()!=null && tradingEntity.getPositionStatus().equals("OPEN")){
                        currentROI = TechnicalIndicatorCalculator.calculateROI(closePosition.getEntryPrice(), eventEntity.getKlineEntity().getClosePrice(), tradingEntity.getLeverage(), closePosition.getPositionSide());
                        currentPnl = TechnicalIndicatorCalculator.calculatePnL(closePosition.getEntryPrice(), eventEntity.getKlineEntity().getClosePrice(), tradingEntity.getLeverage(), closePosition.getPositionSide(), tradingEntity.getCollateral());
                    } else {
                        currentROI = new BigDecimal("0");
                        currentPnl = new BigDecimal("0");
                    }
                    closePosition.setRoi(currentROI);
                    closePosition.setProfit(currentPnl);
                    System.out.println("ROI : " + currentROI);
                    System.out.println("PNL : " + currentPnl);

                    boolean exitFlag = false;
                    if (closePosition.getPositionStatus().equals("OPEN")) {
                        if (closePosition.getPositionSide().equals("LONG")) {
                            //exitFlag = longStrategy.shouldExit(series.getEndIndex());
                        } else if (closePosition.getPositionSide().equals("SHORT")) {
                            //exitFlag = shortStrategy.shouldExit(series.getEndIndex());
                        }
                    }
                    if (exitFlag) {
                        closePosition.setPositionStatus("CLOSE");
                        positionRepository.save(closePosition);
                        makeCloseOrder(eventEntity, positionEvent, "포지션 청산");
                        System.out.println("포지션 종료");
                    }

                }, () -> { // 없다면 전략에 따른 포지션 오픈 검증
                    scanner.printSignalProximity(symbol);
                    boolean enterFlag = false;
                    enterFlag = shortStrategy.shouldEnter(series.getEndIndex());
                    if (enterFlag) {
                        System.out.println("숏 포지션 오픈");
                        makeOpenOrder(eventEntity, "SHORT", "숏 포지션 오픈");
                    } else{
                        enterFlag = longStrategy.shouldEnter(series.getEndIndex());
                        if (enterFlag) {
                            System.out.println("롱 포지션 오픈");
                            makeOpenOrder(eventEntity, "LONG", "롱 포지션 오픈");
                        }else{
                            if(!scanner.isLikelyToMove()){
                                restartTrading(tradingEntity);
                                System.out.println("closeTradingEntity >>>>> " + tradingEntity);
                                log.info("스트림 종료");
                                printTradingEntitys();
                            }
                        }
                    }
                });
            }
        }
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
                //LinkedHashMap<String, Object> stopLossOrder = makeStopLossOrder(tradingEntity, stopLossPrice, quantity);
                //paramMap.put("stopLossOrder", stopLossOrder);
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

    public EventEntity saveKlineEvent(String event, TradingEntity tradingEntity) {
        EventEntity klineEvent = null;
        klineEvent = CommonUtils.convertKlineEventDTO(event).toEntity();
        klineEvent.setTradingEntity(tradingEntity);

        //System.out.println("tradingEntity.getTradingCd() : " + tradingEntity.getTradingCd());
        // 캔들데이터 분석을 위한 bar 세팅
        settingBar(tradingEntity.getTradingCd(), event);
        //strategyMaker(tradingEntity, false);

        // 포지션 엔티티 생성
        PositionEntity positionEntity = PositionEntity.builder()
                .positionStatus("NONE")
                .klineEntity(klineEvent.getKlineEntity())
                .build();
        klineEvent.getKlineEntity().setPositionEntity(positionEntity);
        klineEvent = eventRepository.save(klineEvent);

        Optional<EventEntity> eventEntity = eventRepository.findEventBySymbolAndPositionStatus(tradingEntity.getSymbol(), "OPEN");
        if(!eventEntity.isEmpty()){
            //System.out.println("포지션 오픈중 : " +  eventEntity.get().getKlineEntity().getPositionEntity());
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

        ZonedDateTime closeTime = CommonUtils.convertTimestampToDateTime(klineObj.getLong("T")).atZone(ZoneOffset.UTC);
        ZonedDateTime kstEndTime = closeTime.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
        String formattedEndTime = formatter.format(kstEndTime);

        Num open   = series.numOf(klineObj.getDouble("o"));
        Num high   = series.numOf(klineObj.getDouble("h"));
        Num low    = series.numOf(klineObj.getDouble("l"));
        Num close  = series.numOf(klineObj.getDouble("c"));
        Num volume = series.numOf(klineObj.getDouble("v"));
        Bar newBar = new BaseBar(Duration.ofMinutes(15), closeTime, open, high, low, close, volume ,null);
        Bar lastBar = series.getLastBar();
        if (!newBar.getEndTime().isAfter(lastBar.getEndTime())) {
            series.addBar(newBar, true);
        }else{
            series.addBar(newBar, false);
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

    boolean autoTradingOpenFlag = false;
    public Map<String, Object> autoTradingOpen(TradingEntity tradingEntity) {
        if (autoTradingOpenFlag) {
            throw new RuntimeException("이미 실행중입니다.");
        }
        autoTradingOpenFlag = true;
        log.info("autoTradingOpen >>>>>");

        // *************************************************************************************************
        // 변수선언
        // *************************************************************************************************
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        String targetSymbol = tradingEntity.getTargetSymbol();            // 타겟 심볼
        String interval = tradingEntity.getCandleInterval();              // 캔들간격
        int leverage = tradingEntity.getLeverage();                       // 레버리지
        int stockSelectionCount = tradingEntity.getStockSelectionCount(); // 최초에 종목 정보를 몇개를 가져올건지
        int maxPositionCount = tradingEntity.getMaxPositionCount();       // 최대 빠따든 놈의 마릿수
        String userCd = tradingEntity.getUserCd();                        // 로그인한 사용자 코드

        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));   // 내 계좌정보를 제이슨으로 리턴하는 API
        printAccountInfo(accountInfo);                                                                         //계좌정보 이쁘게 표현

        BigDecimal availableBalance = new BigDecimal(String.valueOf(accountInfo.get("availableBalance")));     // 사용 가능한 담보금
        BigDecimal totalWalletBalance = new BigDecimal(String.valueOf(accountInfo.get("totalWalletBalance"))); // 총 담보금

        List<Map<String, Object>> selectedStockList;

        // 타게팅된 심볼이 있다면
        if (targetSymbol != null && !targetSymbol.isEmpty()) {
            System.out.println("symbolParam : " + targetSymbol);
        }

        // *************************************************************************************************
        // 트레이딩 개설 정합성 체크
        // *************************************************************************************************
        int availablePositionCount = maxPositionCount - TRADING_ENTITYS.size(); // 현재 가능한 포지션 카운트
        boolean nextFlag = true;
        try {
            if (availablePositionCount <= 0) {
                throw new RuntimeException("오픈 가능한 트레이딩이 없습니다.");
            }
        } catch (Exception e){
            log.error("오픈 가능한 트레이딩이 없습니다.");
            printTradingEntitys();
            nextFlag = false;
        }

        if(nextFlag){ // 정합성 체크 통과한다면 다음 단계로 진행
            // *************************************************************************************************
            // 트레이딩을 개설할 종목 선정
            // *************************************************************************************************
            if(targetSymbol == null || targetSymbol.isEmpty()) { // 타겟 심볼이 없다면 종목을 찾아서 가져와서 selectedStockList에 담기
                selectedStockList = (List<Map<String, Object>>) getStockFind(tradingEntity).get("overlappingData"); // 종목을 찾아서 가져오기.
            } else { // 타겟 심볼이 있다면 종목을 특정해서 selectedStockList에 담기
                LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
                paramMap.put("symbol", targetSymbol);
                String resultStr = client.market().ticker24H(paramMap);
                JSONObject result = new JSONObject(resultStr);
                List<Map<String, Object>> list = new ArrayList<>();
                list.add(result.toMap());
                selectedStockList = list;
            }

            List<Map<String, Object>> tradingTargetSymbols = selectedStockList; // 선정된 종목들

            // *************************************************************************************************
            // 트레이딩 개설 정합성 체크 2
            // *************************************************************************************************
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
                e.printStackTrace();
                autoTradingOpenFlag = false;
                autoTradingOpen(tradingEntity);
                nextFlag = false;
            }

            if(nextFlag){ // 정합성 체크 통과한다면 다음 단계로 진행
                BigDecimal maxPositionAmount = totalWalletBalance
                        .divide(new BigDecimal(maxPositionCount),0, RoundingMode.DOWN)
                        .multiply(tradingEntity.getCollateralRate()).setScale(0, RoundingMode.DOWN);

                BigDecimal finalAvailableBalance = maxPositionAmount;
                log.info("collateral : " + maxPositionAmount);
                tradingTargetSymbols.parallelStream().forEach(selectedStock -> {
                    String symbol = String.valueOf(selectedStock.get("symbol"));
                    // tradingEntity의 template을 만든다.
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
                    newTradingEntity.setAtrChecker(tradingEntity.getAtrChecker());
                    newTradingEntity.setAdxChecker(tradingEntity.getAdxChecker());
                    newTradingEntity.setMacdHistogramChecker(tradingEntity.getMacdHistogramChecker());
                    newTradingEntity.setStochChecker(tradingEntity.getStochChecker());
                    newTradingEntity.setStochRsiChecker(tradingEntity.getStochRsiChecker());
                    newTradingEntity.setRsiChecker(tradingEntity.getRsiChecker());
                    newTradingEntity.setMovingAverageChecker(tradingEntity.getMovingAverageChecker());
                    //머신러닝 체커
                    newTradingEntity.setMlModelChecker(tradingEntity.getMlModelChecker());
                    //손익절 체커
                    newTradingEntity.setStopLossChecker(tradingEntity.getStopLossChecker());
                    newTradingEntity.setStopLossRate(tradingEntity.getStopLossRate());
                    newTradingEntity.setTakeProfitChecker(tradingEntity.getTakeProfitChecker());
                    newTradingEntity.setTakeProfitRate(tradingEntity.getTakeProfitRate());

                    try{
                        Optional<TradingEntity> tradingEntityOpt = Optional.ofNullable(TRADING_ENTITYS.get(symbol));
                        if(tradingEntityOpt.isPresent()){
                            printTradingEntitys();
                            throw new RuntimeException(tradingEntityOpt.get().getSymbol() + "이미 오픈된 트레이딩이 존재합니다.");
                        }else{
                            TRADING_ENTITYS.put(symbol, autoTradeStreamOpen(newTradingEntity));
                        }
                    } catch (Exception e) {
                        autoTradingOpenFlag = false;
                        autoTradingOpen(newTradingEntity);
                    }
                    printTradingEntitys();
                });
            }
        }
        autoTradingOpenFlag = false;
        return resultMap;
    }

    public TradingEntity autoTradeStreamOpen(TradingEntity tradingEntity) {
        //tradingRepository.save(tradingEntity);
        log.info("klineStreamOpen >>>>> ");
        ArrayList<String> streams = new ArrayList<>();

        // 캔들데이터 소켓 스트림
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
        log.info("getStockFind >>>>>");

        // *************************************************************************************************
        // 변수선언
        // *************************************************************************************************
        Map<String, Object> resultMap = new LinkedHashMap<>();
        int maxPositionCount = tradingEntity.getMaxPositionCount();
        int stockSelectionCount = tradingEntity.getStockSelectionCount();
        List<Map<String, Object>> overlappingData = new ArrayList<>();
        List<TechnicalIndicatorReportEntity> reports = new ArrayList<>();

        // *************************************************************************************************
        //  24시간 거래량이 높은 순으로 정렬해서 가져옴
        // *************************************************************************************************
        // 바이낸스 API를 통해 종목을 가져옴
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
        String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);
        //printPrettyJson(resultArray);

        // 거래량이 높은 순으로 정렬
        List<Map<String, Object>> sortedByQuoteVolume = getSort(resultArray, "quoteVolume", "DESC", stockSelectionCount);

        int count = 0;
        for (Map<String, Object> item : sortedByQuoteVolume) {
            System.out.println("현재 가능한 트레이딩 갯수("+maxPositionCount+"-"+TRADING_ENTITYS.size()+") : " + (maxPositionCount - TRADING_ENTITYS.size()));
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
                // Map<String, Object> klineMap = backTestExec(tempTradingEntity, true);
                // Optional<Object> expectationProfitOpt = Optional.ofNullable(klineMap.get("expectationProfit"));

                String tradingCd = tempTradingEntity.getTradingCd();
                String interval = tempTradingEntity.getCandleInterval();

                seriesMaker(tempTradingEntity, false);
                strategyMaker(tempTradingEntity, false);

                BaseBarSeries series = seriesMap.get(tempTradingEntity.getTradingCd() + "_" + tempTradingEntity.getCandleInterval());
                Strategy longStrategy = strategyMap.get(tradingCd + "_" + interval + "_long_strategy");
                Strategy shortStrategy = strategyMap.get(tradingCd + "_" + interval + "_short_strategy");
                MLModel mlModel = mlModelMap.get(tempTradingEntity.getTradingCd());
                boolean longEntrySignal = longStrategy.shouldEnter(series.getEndIndex());
                boolean longExitSignal = longStrategy.shouldExit(series.getEndIndex());
                boolean shortEntrySignal = shortStrategy.shouldEnter(series.getEndIndex());
                boolean shortExitSignal = shortStrategy.shouldExit(series.getEndIndex());
                //printTradingSignals(symbol, series.getLastBar(), longEntrySignal, longExitSignal, shortEntrySignal, shortExitSignal);
                List<Indicator<Num>> indicators = initializeIndicators(series);

                double threshold = 0.5; // 시그널 임계값
                double proximityThreshold = 0.3; // 근접 임계값
                SignalProximityScanner scanner = new SignalProximityScanner(indicators, series, mlModel, threshold, proximityThreshold);
                scanner.printSignalProximity(symbol);

                //if (expectationProfitOpt.isPresent()){
                //    BigDecimal expectationProfit = (BigDecimal) expectationProfitOpt.get();
                //    BigDecimal winTradeCount = new BigDecimal(String.valueOf(klineMap.get("winTradeCount")));
                //    BigDecimal loseTradeCount = new BigDecimal(String.valueOf(klineMap.get("loseTradeCount")));
                    if (
                        true
                        //&& scanner.isNearSignal() // 시그널 근접 여부
                        && scanner.isLikelyToMove() // 움직일 가능성 여부
                        //&& (longEntrySignal || shortEntrySignal)
                        //&&expectationProfit.compareTo(BigDecimal.ONE) > 0
                        //&& (winTradeCount.compareTo(loseTradeCount) > 0)
                    ) {
                        //System.out.println("[관심종목추가]symbol : " + symbol + " expectationProfit : " + expectationProfit);
                        System.out.println("[관심종목추가]symbol : " + symbol);
                        overlappingData.add(item);
                        count++;
                    }
                //}
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

    public Map<String,Object> backTest(HttpServletRequest request, TradingDTO tradingDTO){
        System.out.println("backTest >>>>>" + tradingDTO.toString());
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
        System.out.println("backTest >>>>>" + tradingEntity.toString());
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

            Map<String, Object> klineMap = backTestExec(tempTradingEntity,false);
        }

        for(Map<String, Object> item : overlappingData){
            System.out.println("관심종목 : " + item);
        }

        //System.out.println("overlappingData : " + overlappingData);

        resultMap.put("reports", reports);
        resultMap.put("overlappingData", overlappingData);
        return resultMap;
    }

    public void seriesMaker(TradingEntity tradingEntity, boolean logFlag) {
        //System.out.println("tradingEntity : " + tradingEntity);
        String tradingCd = tradingEntity.getTradingCd();
        String symbol    = tradingEntity.getSymbol();
        String interval  = tradingEntity.getCandleInterval();
        int candleCount  = tradingEntity.getCandleCount();
        int limit = candleCount;

        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
        paramMap.put("symbol", symbol);
        paramMap.put("interval", interval);
        paramMap.put("limit", limit);
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY, true);
        String resultStr = client.market().klines(paramMap);

        String weight = new JSONObject(resultStr).getString("x-mbx-used-weight-1m");
        System.out.println("*************** [현재 가중치 : " + weight + "] ***************");
        JSONArray jsonArray = new JSONArray(new JSONObject(resultStr).get("data").toString());
        BaseBarSeries series = new BaseBarSeries();
        series.setMaximumBarCount(limit);
        seriesMap.put(tradingCd + "_" + interval, series);
        //System.out.println("series : " + seriesMap.get(tradingCd + "_" + interval));

        BigDecimal expectationProfit = BigDecimal.ZERO;
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray klineArray = jsonArray.getJSONArray(i);
            KlineEntity klineEntity = parseKlineEntity(klineArray);

            Num open = series.numOf(klineEntity.getOpenPrice());
            Num high = series.numOf(klineEntity.getHighPrice());
            Num low = series.numOf(klineEntity.getLowPrice());
            Num close = series.numOf(klineEntity.getClosePrice());
            Num volume = series.numOf(klineEntity.getVolume());

            series.addBar(klineEntity.getEndTime().atZone(ZoneOffset.UTC), open, high, low, close, volume);
        }
    }

    public void strategyMaker(TradingEntity tradingEntity, boolean logFlag) {
        //System.out.println("tradingEntity : " + tradingEntity);

        String tradingCd = tradingEntity.getTradingCd();
        String symbol    = tradingEntity.getSymbol();
        String interval  = tradingEntity.getCandleInterval();
        int candleCount  = tradingEntity.getCandleCount();
        int limit = candleCount;

        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);

        int shortMovingPeriod = tradingEntity.getShortMovingPeriod();
        int midPeriod = tradingEntity.getMidMovingPeriod();
        int longMovingPeriod = tradingEntity.getLongMovingPeriod();

        //log.info("SYMBOL : " + symbol);

        // 종가 설정
        OpenPriceIndicator  openPrice   = new OpenPriceIndicator(series); //시가
        ClosePriceIndicator closePrice  = new ClosePriceIndicator(series); //종가
        HighPriceIndicator  highPrice   = new HighPriceIndicator(series); //고가
        LowPriceIndicator   lowPrice    = new LowPriceIndicator(series); //저가

        //**********************************************************************************
        // 지표를 정의한다.
        //**********************************************************************************

        // 이동평균선 설정
        SMAIndicator sma = new SMAIndicator(closePrice, shortMovingPeriod); //단기 이동평균선
        SMAIndicator ema = new SMAIndicator(closePrice, longMovingPeriod);  //장기 이동평균선

        // 트렌드 판단을 위한 이동평균선 설정
        SMAIndicator shortSMA = new SMAIndicator(closePrice, shortMovingPeriod*2); // 10일 단기 이동평균선
        SMAIndicator longSMA = new SMAIndicator(closePrice, longMovingPeriod*2); // 30일 장기 이동평균선

        // 볼린저 밴드 설정
        int barCount = 20;
        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, longMovingPeriod); //장기 이평선 기간 동안의 표준편차
        BollingerBandsMiddleIndicator middleBBand    = new BollingerBandsMiddleIndicator(ema); //장기 이평선으로 중심선
        BollingerBandsUpperIndicator upperBBand      = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand      = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);

        int rsiPeriod = longMovingPeriod;
        //RSIIndicator rsiIndicator = new RSIIndicator(closePrice, rsiPeriod);

        // ATR 상대지수
        RelativeATRIndicator relativeATR = new RelativeATRIndicator(series, 14, 100);

        // ADX 지표 설정
        ADXIndicator adxIndicator = new ADXIndicator(series, 14);

        //**********************************************************************************
        // 지표 룰을 정의한다.
        //**********************************************************************************

        //베이스룰
        Rule baseRule = new BooleanRule(false){};

        // 이동평균선 매수/매도 규칙
        Rule smaBuyingRule            = new OverIndicatorRule(sma, ema); // 20MA > 50MA
        Rule smaSellingRule           = new UnderIndicatorRule(sma, ema); // 20MA < 50MA

        // 볼린저 밴드 매수/매도 규칙
        Rule bollingerBuyingRule      = new CrossedDownIndicatorRule(closePrice, lowerBBand); // 가격이 하한선을 돌파할 때 매수
        Rule bollingerSellingRule     = new CrossedUpIndicatorRule(closePrice, upperBBand); // 가격이 상한선을 돌파할 때 매도

        // RSI 매수/매도 규칙
        //Rule rsiBuyingRule            = new CrossedDownIndicatorRule(rsiIndicator, DecimalNum.valueOf(20)); // RSI가 20 이하로 떨어질 때 매수
        //Rule rsiSellingRule           = new CrossedUpIndicatorRule(rsiIndicator, DecimalNum.valueOf(70)); // RSI가 70 이상으로 올라갈 때 매도

        // MACD 히스토그램 매수/매도 규칙
        Rule macdHistogramPositive    = new MACDHistogramRule(closePrice, shortMovingPeriod, longMovingPeriod, true);
        Rule macdHistogramNegative    = new MACDHistogramRule(closePrice, shortMovingPeriod, longMovingPeriod, false);

        //**********************************************************************************
        // 추가적인 진입 규칙.
        //**********************************************************************************

        // ATR 상대지표 규칙 -- ATR 지표(변동성 지표)를 상대적인 표현으로 바꾼 커스텀 지표
        Rule overAtrRule = new OverIndicatorRule(relativeATR, series.numOf(1));
        Rule underAtrRule = new UnderIndicatorRule(relativeATR, series.numOf(3));

        // ADX 진입 규칙 -- ADX 지표가 임계값-l 이상이고 임계값 -h 미만일 때 진입,
        Rule adxEntryRule = new OverIndicatorRule(adxIndicator, DecimalNum.valueOf(20)); // ADX가 20 이상일 때 매수
        adxEntryRule = adxEntryRule.and(new UnderIndicatorRule(adxIndicator, DecimalNum.valueOf(50))); // ADX가 50 이하일 때 매수

        // 트렌드 팔로우 규칙 -- 현재 감시하고 있는 추세보다 큰 추세에서의 트렌드를 추종한다.
        Rule upTrendRule = new OverIndicatorRule(shortSMA, longSMA);
        Rule downTrendRule = new UnderIndicatorRule(shortSMA, longSMA);

        Rule highVolumeRule = new RelativeVolumeRule(series, 20, 1.5, true);

        // 머신러닝 룰 생성
        MLModel mlModel = new MLModel();
        int totalSize = series.getBarCount();
        int trainSize = (int) (totalSize * 0.5);
        // System.out.println("Train data size: " + trainSize);

        // 훈련 데이터와 테스트 데이터 분리
        BarSeries trainSeries = series.getSubSeries(0, trainSize);
        BarSeries testSeries = series.getSubSeries(trainSize, totalSize);

        // 머신러닝에 쓰일 지표 초기화
        List<Indicator<Num>> indicators = initializeIndicators(series);
        mlModel.train(testSeries, indicators, trainSize);
        mlModelMap.put(tradingCd, mlModel);

        //  MLRule 생성
        double entryThreshold = 0.5;
        double exitThreshold  = 0.5;
        Rule mlLongEntryRule  = new MLLongRule(mlModel, indicators, entryThreshold);
        Rule mlShortEntryRule = new MLShortRule(mlModel, indicators, entryThreshold);
        Rule mlLongExitRule   = new MLShortRule(mlModel, indicators, exitThreshold);
        Rule mlShortExitRule  = new MLLongRule(mlModel, indicators, exitThreshold);

        //Rule mlRule = new MLRule(mlModel, indicators, upThreshold, downThreshold);

        //**********************************************************************************
        // 추가적인 청산 규칙.
        //**********************************************************************************

        // 손/익절 규칙
        int takeProfitRate = tradingEntity.getTakeProfitRate();
        Rule takeProfitRule = new StopGainRule(closePrice, takeProfitRate);

        int stopLossRate = tradingEntity.getStopLossRate();
        Rule stopLossRule = new StopLossRule(closePrice, stopLossRate);

        // 강제 본절 규칙(진입 했을 시에 최소 본절 이상에만 룰이 발동되도록 제한하는 규칙)
        Rule profitableLongRule = new ProfitableRule(closePrice, true);
        Rule profitableShortRule = new ProfitableRule(closePrice, false);

        // 강제 수익 규칙(진입 했을 시에 최소 수익 이상에만 룰이 발동되도록 제한하는 규칙)
        Rule longMinimumProfitRule = new MinimumProfitRule(closePrice, true, takeProfitRate);
        Rule shortMinimumProfitRule = new MinimumProfitRule(closePrice, false, takeProfitRate);

        // 전략 생성을 위한 룰 리스트
        List<Rule> longEntryRules = new ArrayList<>();
        List<Rule> longExitRules = new ArrayList<>();
        List<Rule> shortEntryRules = new ArrayList<>();
        List<Rule> shortExitRules = new ArrayList<>();

        // ML 모델 사용 여부를 확인하는 플래그
        if (tradingEntity.getMlModelChecker() == 1) {

            longEntryRules.add(mlLongEntryRule);
            shortEntryRules.add(mlShortEntryRule);

            longExitRules.add(mlLongExitRule);
            shortExitRules.add(mlShortExitRule);

            // 머신러닝 룰은 손익률이 1 이상일 때만 적용
            //Rule longExitMLRule = new AndRule(new NotRule(mlRule), longMinimumProfitRule);
            //Rule shortExitMLRule = new AndRule(mlRule, shortMinimumProfitRule);

            //longExitRules.add(longExitMLRule);
            //shortExitRules.add(shortExitMLRule);

        }

        if (tradingEntity.getBollingerBandChecker() == 1) {
            longEntryRules.add(bollingerBuyingRule);
            longExitRules.add(bollingerSellingRule);
            shortEntryRules.add(bollingerSellingRule);
            shortExitRules.add(bollingerBuyingRule);
        }

        if (tradingEntity.getMovingAverageChecker() == 1) {
            longEntryRules.add(smaBuyingRule);
            shortEntryRules.add(smaSellingRule);

            // 이동평균선 룰은 손익률이 1 이상일 때만 적용
            Rule longExitMARule = new AndRule(smaSellingRule, longMinimumProfitRule);
            Rule shortExitMARule = new AndRule(smaBuyingRule, shortMinimumProfitRule);

            longExitRules.add(longExitMARule);
            shortExitRules.add(shortExitMARule);
        }

        /*if (tradingEntity.getRsiChecker() == 1) {
            longEntryRules.add(rsiBuyingRule);
            shortEntryRules.add(rsiSellingRule);

            // RSI 룰은 손익률이 1 이상일 때만 적용
            Rule longExitRule = new AndRule(rsiSellingRule, longMinimumProfitRule);
            Rule shortExitRule = new AndRule(rsiBuyingRule, shortMinimumProfitRule);

            longExitRules.add(longExitRule);
            shortExitRules.add(shortExitRule);
        }*/

        if (tradingEntity.getMacdHistogramChecker() == 1) { // macd 히스토그램은 진입 시에 사용하지 않는다.
            //longEntryRules.add(macdHistogramPositive);
            //shortEntryRules.add(macdHistogramNegative);

            // macd 히스토그램 룰은 손익률이 1 이상일 때만 적용
            Rule longExitRule = new AndRule(macdHistogramNegative, longMinimumProfitRule);
            Rule shortExitRule = new AndRule(macdHistogramPositive, shortMinimumProfitRule);

            longExitRules.add(longExitRule);
            shortExitRules.add(shortExitRule);
        }

        if (tradingEntity.getStopLossChecker() == 1) {
            longExitRules.add(stopLossRule);
            shortExitRules.add(stopLossRule);
        }

        if (tradingEntity.getTakeProfitChecker() == 1) {
            longExitRules.add(takeProfitRule);
            shortExitRules.add(takeProfitRule);
        }

        // 복합 규칙 생성
        Rule combinedLongEntryRule = baseRule;
        if (!longEntryRules.isEmpty()) {
            for (int i = 0; i < longEntryRules.size(); i++) {
                combinedLongEntryRule = new OrRule(combinedLongEntryRule, longEntryRules.get(i));
            }
            if (tradingEntity.getAdxChecker() == 1) {
                combinedLongEntryRule = new AndRule(combinedLongEntryRule, adxEntryRule);
            }
            if (tradingEntity.getAtrChecker() == 1) {
                combinedLongEntryRule = new AndRule(combinedLongEntryRule, overAtrRule);
                combinedLongEntryRule = new AndRule(combinedLongEntryRule, underAtrRule);
            }
            if (tradingEntity.getTrendFollowFlag() == 1) {
                combinedLongEntryRule = new AndRule(combinedLongEntryRule, upTrendRule);
            }
        } else {
            combinedLongEntryRule = new BooleanRule(false);  // 항상 참인 규칙
        }

        Rule combinedShortEntryRule = baseRule;
        if (!shortEntryRules.isEmpty()) {
            for (int i = 0; i < shortEntryRules.size(); i++) { // 일반 룰
                combinedShortEntryRule = new OrRule(combinedShortEntryRule, shortEntryRules.get(i));
            }
            if (tradingEntity.getAdxChecker() == 1) {
                combinedShortEntryRule = new AndRule(combinedShortEntryRule, adxEntryRule);
                combinedShortEntryRule = new AndRule(combinedShortEntryRule, underAtrRule);
            }
            if (tradingEntity.getAtrChecker() == 1) {
                combinedShortEntryRule = new AndRule(combinedShortEntryRule, overAtrRule);
            }
            if (tradingEntity.getTrendFollowFlag() == 1) {
                combinedShortEntryRule = new AndRule(combinedShortEntryRule, downTrendRule);
            }
        } else {
            combinedShortEntryRule = new BooleanRule(false);
        }

        Rule combinedLongExitRule = baseRule;
        if (!longExitRules.isEmpty()) {
            for (int i = 0; i < longExitRules.size(); i++) {
                combinedLongExitRule = new OrRule(combinedLongExitRule, longExitRules.get(i));
            }
        } else {
            combinedLongExitRule = new BooleanRule(false);
        }

        Rule combinedShortExitRule = baseRule;
        if (!shortExitRules.isEmpty()) {
            for (int i = 0; i < shortExitRules.size(); i++) {
                combinedShortExitRule = new OrRule(combinedShortExitRule, shortExitRules.get(i));
            }
        } else {
            combinedShortExitRule = new BooleanRule(false);
        }

        Rule finalCombinedLongEntryRule = null;
        Rule finalCombinedShortEntryRule = null;
        int reverseTradeChecker = tradingEntity.getReverseTradeChecker();
        if(reverseTradeChecker == 1) {
            finalCombinedLongEntryRule = combinedShortEntryRule;
            finalCombinedShortEntryRule = combinedLongEntryRule;
        }else{
            finalCombinedLongEntryRule = combinedLongEntryRule;
            finalCombinedShortEntryRule = combinedShortEntryRule;
        }

        // 전략 생성
        Strategy combinedLongStrategy  = new BaseStrategy(finalCombinedLongEntryRule, combinedLongExitRule);
        Strategy combinedShortStrategy = new BaseStrategy(finalCombinedShortEntryRule, combinedShortExitRule);

        // 전략 생성
        strategyMap.put(tradingCd + "_" + interval + "_long_strategy", combinedLongStrategy);
        strategyMap.put(tradingCd + "_" + interval + "_short_strategy", combinedShortStrategy);
    }

    public Map<String, Object> backTestExec(TradingEntity tradingEntity, boolean logFlag) {
        System.out.println("backTestExec >>>>>");
        long startTime = System.currentTimeMillis(); // 시작 시간 기록

        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY);
        JSONObject accountInfo = new JSONObject(client.account().accountInformation(new LinkedHashMap<>()));
        //printPrettyJson(accountInfo);

        BigDecimal availableBalance = new BigDecimal(String.valueOf(accountInfo.get("availableBalance")));
        BigDecimal totalWalletBalance = new BigDecimal(String.valueOf(accountInfo.get("totalWalletBalance")));

        BigDecimal maxPositionAmount = totalWalletBalance
                .divide(new BigDecimal(tradingEntity.getMaxPositionCount()),0, RoundingMode.DOWN)
                .multiply(tradingEntity.getCollateralRate()).setScale(0, RoundingMode.DOWN);
        String tradingCd = tradingEntity.getTradingCd();
        String symbol = tradingEntity.getSymbol();
        String interval = tradingEntity.getCandleInterval();
        int candleCount = tradingEntity.getCandleCount();
        int limit = candleCount;

        if(false){
            System.out.println("사용가능 : " +accountInfo.get("availableBalance"));
            System.out.println("담보금 : " + accountInfo.get("totalWalletBalance"));
            System.out.println("미실현수익 : " + accountInfo.get("totalUnrealizedProfit"));
            System.out.println("현재자산 : " + accountInfo.get("totalMarginBalance"));
        }

        tradingEntity.setCollateral(maxPositionAmount);


        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();

        // 시리즈 생성
        seriesMaker(tradingEntity, logFlag);
        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);

        // 전략 생성
        strategyMaker(tradingEntity, logFlag);
        Strategy longStrategy = strategyMap.get(tradingCd + "_" + interval + "_long_strategy");
        Strategy shortStrategy = strategyMap.get(tradingCd + "_" + interval + "_short_strategy");

        int totalSize = series.getBarCount();
        int trainSize = (int) (totalSize * 0.5);
        //System.out.println("Train data size: " + trainSize);

        // 훈련 데이터와 테스트 데이터 분리
        BarSeries trainSeries = series.getSubSeries(0, trainSize);
        BaseBarSeries testSeries = series.getSubSeries(trainSize, totalSize);

        // 백테스트 실행
        BarSeriesManager seriesManager = new BarSeriesManager(testSeries);
        TradingRecord longTradingRecord = seriesManager.run(longStrategy);
        TradingRecord shortTradingRecord = seriesManager.run(shortStrategy, Trade.TradeType.SELL);
        int leverage = tradingEntity.getLeverage(); // 레버리지

        long endTime = System.currentTimeMillis(); // 종료 시간 기록
        long elapsedTime = endTime - startTime; // 실행 시간 계산
        //System.out.println("");
        HashMap<String, Object> longTradingResult = new HashMap<String, Object>();
        HashMap<String, Object> shortTradingResult = new HashMap<String, Object>();
        longTradingResult  = backTestResult(longTradingRecord, testSeries, symbol, leverage, "LONG", maxPositionAmount, true);
        shortTradingResult = backTestResult(shortTradingRecord, testSeries, symbol, leverage, "SHORT", maxPositionAmount, true);

        int winTradeCount = (int) longTradingResult.get("winTradeCount") + (int) shortTradingResult.get("winTradeCount");
        int loseTradeCount = (int) longTradingResult.get("loseTradeCount") + (int) shortTradingResult.get("loseTradeCount");
        BigDecimal expectationProfit = ((BigDecimal) longTradingResult.get("expectationProfit")).add((BigDecimal) shortTradingResult.get("expectationProfit"));
        resultMap.put("winTradeCount", winTradeCount);
        resultMap.put("loseTradeCount", loseTradeCount);
        resultMap.put("expectationProfit", expectationProfit);

        /* TODO : 룰이 어떻게 작동하는지 */
        if(false){
            for(int i = 0; i < series.getBarCount(); i++){
                TradingRecord longRecord = new BaseTradingRecord();
                TradingRecord shortRecord = new BaseTradingRecord(Trade.TradeType.SELL);
                boolean longShouldEnter = longStrategy.shouldEnter(i, longRecord);
                boolean longShouldExit = longStrategy.shouldExit(i, longRecord);
                boolean shortShouldEnter = shortStrategy.shouldEnter(i, shortRecord);
                boolean shortShouldExit = shortStrategy.shouldExit(i, shortRecord);

                printTradingSignals(symbol, series.getBar(i), longShouldEnter, longShouldExit, shortShouldEnter, shortShouldExit);
            }
        }
        // 결과 출력
        if(false) {
            System.out.println("");
            System.out.println("롱 매매횟수 : "+longTradingRecord.getPositionCount());
            System.out.println("숏 매매횟수 : "+shortTradingRecord.getPositionCount());
            System.out.println("최종예상 수익(" + tradingEntity.getSymbol() + ") : " + expectationProfit);
            System.out.println("최종예상 승률(" + tradingEntity.getSymbol() + ") : " + (tradingEntity.getWinTradeCount() + "/" + tradingEntity.getLoseTradeCount()));
            System.out.println("소요시간 : " + elapsedTime + " milliseconds");
        }
        resultMap.put("expectationProfit", expectationProfit);
        return resultMap;
    }

    public HashMap<String, Object> backTestResult(TradingRecord tradingRecord, BaseBarSeries series, String symbol, int leverage, String positionSide, BigDecimal collateral, boolean logFlag) {
        HashMap<String, Object> resultMap = new HashMap<>();

        // 트렌드
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSMA = new SMAIndicator(closePrice, 10); // 10일 단기 이동평균선
        SMAIndicator longSMA = new SMAIndicator(closePrice, 20); // 30일 장기 이동평균선

        // 거래 기록 출력
        List<Position> positions = tradingRecord.getPositions();
        BigDecimal totalProfit = BigDecimal.ZERO;
        List<Position> winPositions = new ArrayList<>();
        List<Position> losePositions = new ArrayList<>();
        if (logFlag) {
            System.out.println(symbol+"/"+positionSide+" 리포트");
        }
        RelativeATRIndicator relativeATR = new RelativeATRIndicator(series, 14, 100);
        // ADX 지표 설정
        int adxPeriod = 14; // 일반적으로 사용되는 기간
        ADXIndicator adxIndicator = new ADXIndicator(series, adxPeriod);

        for (Position position : positions) {
            //System.out.println(" "+position);
            Trade entry = position.getEntry();
            Trade exit = position.getExit();

            //계약수량=계약금액×레버리지÷진입가격
            BigDecimal 계약수량 = collateral
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

            String trendExpression;
            if (shortSMA.getValue(entry.getIndex()).isGreaterThan(longSMA.getValue(entry.getIndex()))) {
                trendExpression = "Trend: " + CONSOLE_COLORS.BRIGHT_GREEN + "상승" + CONSOLE_COLORS.RESET;
            } else if (shortSMA.getValue(entry.getIndex()).isLessThan(longSMA.getValue(entry.getIndex()))) {
                trendExpression = "Trend: " + CONSOLE_COLORS.BRIGHT_RED + "하락" + CONSOLE_COLORS.RESET;
            } else {
                trendExpression = "Trend: 중립";
            }

            // 특정 인덱스에서의 Relative ATR 값을 얻습니다
            Num atrValue = relativeATR.getValue(entry.getIndex());
            String atrExpression = "ATR: " + atrValue;

            // 특정 인덱스에서의 ADX 값을 얻습니다
            Num adxValue = adxIndicator.getValue(entry.getIndex());
            String adxExpression = "ADX: " + adxValue.doubleValue();

            // 진입 시 거래량 정보 가져오기
            double  entryVolume = getRelativeVolume(series, entry.getIndex(), 20);
            String volumeExpression = "Volume: " + entryVolume;

            String slash = "/";

            StringBuilder entryRuleExpression = new StringBuilder("[진입규칙]");
            StringBuilder stopRuleExpression = new StringBuilder("[청산규칙]");
            int i = 1;
            if (logFlag){
                System.out.println("  "+entryExpression
                        + slash + exitExpression
                        + slash + trendExpression
                        + slash + ROIExpression
                        + slash + PNLExpression
                        + slash + volumeExpression
                        + slash + atrExpression
                        + slash + adxExpression
                        + slash + entryRuleExpression
                        + slash + stopRuleExpression);
            }
            if(PNL.compareTo(BigDecimal.ZERO) > 0){
                winPositions.add(position);
            }else if(PNL.compareTo(BigDecimal.ZERO) < 0){
                losePositions.add(position);
            }
            totalProfit = totalProfit.add(PNL);
        }
        if (logFlag){
            System.out.println(" ");
            System.out.println(symbol+"/"+positionSide+"(승패 : "+winPositions.size()+"/"+losePositions.size()+") : 최종 수익: " + totalProfit);
        }
        resultMap.put("winTradeCount", winPositions.size());
        resultMap.put("loseTradeCount", losePositions.size());
        resultMap.put("expectationProfit", totalProfit);
        return resultMap;
    }

    private List<Indicator<Num>> initializeIndicators(BarSeries series) {
        List<Indicator<Num>> indicators = new ArrayList<>();
        indicators.add(new RSIIndicator(new ClosePriceIndicator(series), 14));
        indicators.add(new RSIIndicator(new ClosePriceIndicator(series), 28));
        indicators.add(new MACDIndicator(new ClosePriceIndicator(series)));
        indicators.add(new EMAIndicator(new ClosePriceIndicator(series), 20));
        indicators.add(new EMAIndicator(new ClosePriceIndicator(series), 50));
        indicators.add(new BollingerBandsMiddleIndicator(new ClosePriceIndicator(series)));
        indicators.add(new StochasticOscillatorKIndicator(series, 14));
        indicators.add(new ATRIndicator(series, 14));
        indicators.add(new ADXIndicator(series, 14));
        indicators.add(new CCIIndicator(series, 20));
        indicators.add(new ROCIndicator(new ClosePriceIndicator(series), 10));
        indicators.add(new WilliamsRIndicator(series, 14));
        indicators.add(new CMOIndicator(new ClosePriceIndicator(series), 14));
        indicators.add(new ParabolicSarIndicator(series));
        return indicators;
    }

    // 거래량 상대화 메서드 (예: 20일 평균 대비)
    private double getRelativeVolume(BarSeries series, int index, int period) {
        double sumVolume = 0;
        for (int i = Math.max(0, index - period + 1); i <= index; i++) {
            sumVolume += series.getBar(i).getVolume().doubleValue();
        }
        double avgVolume = sumVolume / Math.min(period, index + 1);
        return series.getBar(index).getVolume().doubleValue() / avgVolume;
    }


    public String krTimeExpression(Bar bar){
        // 포맷 적용하여 문자열로 변환
        ZonedDateTime utcEndTime = bar.getEndTime(); //캔들이 !!!끝나는 시간!!!
        ZonedDateTime kstEndTime = utcEndTime.withZoneSameInstant(ZoneId.of("Asia/Seoul")); //한국시간 설정
        String formattedEndTime = formatter.format(kstEndTime);
        String krTimeExpression = "["+formattedEndTime+"]";
        return krTimeExpression;
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

    public void printTradingEntitys() {
        System.out.println("현재 오픈된 트레이딩 >>>>>");

        if (TRADING_ENTITYS.isEmpty()) {
            System.out.println("오픈된 트레이딩이 없습니다.");
            return;
        }

        String format = "| %-10s | %-10s | %-10s |%n";
        String line = "+%12s+%12s+%12s+%n";

        System.out.printf(line, "-".repeat(12), "-".repeat(12), "-".repeat(12));
        System.out.printf(format, "Symbol", "Size", "Entry Price");
        System.out.printf(line, "-".repeat(12), "-".repeat(12), "-".repeat(12));

        TRADING_ENTITYS.forEach((symbol, tradingEntity) -> {
            System.out.printf(format,
                    symbol,
                    tradingEntity.getPositionSide(),
                    tradingEntity.getPositionStatus()
            );
        });

        System.out.printf(line, "-".repeat(12), "-".repeat(12), "-".repeat(12));
        System.out.println("총 " + TRADING_ENTITYS.size() + "개의 오픈된 포지션이 있습니다.");
    }

    public void printAccountInfo(JSONObject accountInfo) {
        String format = "| %-12s | %20s |%n";
        String line = "+%-15s+%20s+%n";

        System.out.printf(line, "-".repeat(15), "-".repeat(22));
        System.out.printf(format, "항목", "금액");
        System.out.printf(line, "-".repeat(15), "-".repeat(22));
        System.out.printf(format, "사용가능", accountInfo.optString("availableBalance", "N/A"));
        System.out.printf(format, "담보금", accountInfo.optString("totalWalletBalance", "N/A"));
        System.out.printf(format, "미실현수익", accountInfo.optString("totalUnrealizedProfit", "N/A"));
        System.out.printf(format, "현재자산", accountInfo.optString("totalMarginBalance", "N/A"));
        System.out.printf(line, "-".repeat(15), "-".repeat(22));
    }

    public void printTradingSignals(String symbol, Bar currentBar,
                                    boolean longShouldEnter, boolean longShouldExit,
                                    boolean shortShouldEnter, boolean shortShouldExit) {
        System.out.println("한국시간 : " + krTimeExpression(currentBar));
        String header = String.format("| %-15s | %-22s | %-14s | %-14s | %-15s | %-15s |",
                "Symbol", "Current Bar", "Long Enter", "Long Exit", "Short Enter", "Short Exit");
        String separatorLine = "+-----------------+------------------------+----------------+----------------+-----------------+-----------------+";
        String dataLine = String.format("| %-15s | %-22s | %-14s | %-14s | %-15s | %-15s |",
                symbol,
                krTimeExpression(currentBar),
                longShouldEnter ? "YES" : "NO",
                longShouldExit ? "YES" : "NO",
                shortShouldEnter ? "YES" : "NO",
                shortShouldExit ? "YES" : "NO");

        System.out.println(separatorLine);
        System.out.println(header);
        System.out.println(separatorLine);
        System.out.println(dataLine);
        System.out.println(separatorLine);
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