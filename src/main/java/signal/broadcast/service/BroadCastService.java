package signal.broadcast.service;

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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.bollinger.PercentBIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.ChaikinMoneyFlowIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.num.Num;
import signal.broadcast.ml.MLModel;
import signal.broadcast.ml.MLTrendPredictModel;
import signal.broadcast.model.dto.BroadCastDTO;
import signal.broadcast.model.entity.BroadCastEntity;
import signal.broadcast.model.enums.BROADCAST_STATUS;
import signal.broadcast.model.enums.CONSOLE_COLORS;
import signal.broadcast.model.enums.MODEL_TYPE;
import signal.broadcast.repository.BroadCastRepository;
import signal.common.MemoryUsageMonitor;
import signal.configuration.MyWebSocketClientImpl;
import signal.exception.BroadCastException;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.deeplearning4j.eval.BaseEvaluation.getObjectMapper;
import static signal.broadcast.model.enums.CANDLE_INTERVAL.getIntervalList;
import static signal.broadcast.model.enums.POSITION_SIDE.getPositionSideList;
import static signal.common.캔들유틸.jsonArrayToBar;
import static signal.common.포맷유틸.convertTimestampToDateTime;
import static signal.configuration.JacksonConfig.objectMapper;

@Slf4j
@Service
@Transactional
public class BroadCastService {
    // ****************************************************************************************
    // 상수 세팅
    // ****************************************************************************************
    // JWT
    @Value("${jwt.secret.key}")
    private String JWT_SECRET_KEY;
    // 바이낸스 API KEY
    public String BINANCE_API_KEY;
    // 바이낸스 SECRET KEY
    public String BINANCE_SECRET_KEY;
    public static final String BASE_URL_TEST = "https://testnet.binance.vision";
    public static final String BASE_URL_REAL = "wss://ws-api.binance.com:443/ws-api/v3";
    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();
    boolean DEV_MODE ;
    public BroadCastService(
              @Value("${binance.real.api.key}")       String BINANCE_REAL_API_KEY
            , @Value("${binance.real.secret.key}")    String BINANCE_REAL_SECRET_KEY
            , @Value("${spring.profiles.active}")     String ACTIVE_PROFILE
    ) {
        this.BINANCE_API_KEY    = BINANCE_REAL_API_KEY;
        this.BINANCE_SECRET_KEY = BINANCE_REAL_SECRET_KEY;
        this.BASE_URL           = BASE_URL_REAL;
        this.DEV_MODE           = ACTIVE_PROFILE.equals("local");
        if(DEV_MODE){
            log.info("ACTIVE_PROFILE : " + ACTIVE_PROFILE+ "/ DEV_MODE : " + DEV_MODE + " !!![개발 모드]입니다!!!");
        }
    }

    @Autowired BroadCastRepository          broadCastRepository;
    @Autowired MyWebSocketClientImpl        umWebSocketStreamClient;
    @Autowired RedisTemplate<String, Object> redisTemplate;

    private final WebSocketCallback noopCallback = msg -> {};
    private final WebSocketCallback openCallback      = this::onOpenCallback;
    private final WebSocketCallback onMessageCallback = this::onMessageCallback;
    private final WebSocketCallback closeCallback     = this::onCloseCallback;
    private final WebSocketCallback failureCallback   = this::onFailureCallback;

    public String BASE_URL;
    int failureCount = 0;

    // 컬렉션 리스트(리소스 정리 필수)
    private final ConcurrentHashMap <String, BroadCastEntity>      BROADCAST_ENTITYS = new ConcurrentHashMap <String, BroadCastEntity>();
    private final ConcurrentHashMap<String, BaseBarSeries>         SERIES_MAP      = new ConcurrentHashMap <String, BaseBarSeries>();
    private final ConcurrentHashMap <String, MLModel>              ML_MODEL_MAP    = new ConcurrentHashMap <String, MLModel>();
    private final ConcurrentHashMap <String, MLModel>              ML_TREND_PREDICT_MODEL_MAP    = new ConcurrentHashMap <String, MLModel>();
    private final ConcurrentHashMap <String, Object>               PREDICT_MAP      = new ConcurrentHashMap <String, Object>();
    private final ConcurrentHashMap <String, Object>               TREND_PREDICT_MAP      = new ConcurrentHashMap <String, Object>();
    private final ConcurrentHashMap <String, List<Indicator<Num>>> INDICATORS_MAP  = new ConcurrentHashMap <String, List<Indicator<Num>>>();
    boolean BROADCASTING_OPEN_PROCESS_FLAG = false;
    // 날짜 포맷 지정
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * <h3>바이낸스 소켓 스트림이 오픈 되었을 때 호출 되는 콜백</h3>
     */
    public void onOpenCallback(String streamId) {
        BroadCastEntity broadCastEntity = Optional.ofNullable(umWebSocketStreamClient.getBroadCastEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new BroadCastException(streamId + " 번 BROADCAST 존재하지 않습니다."));
        log.info("[OPEN] >>>>> " + streamId + " 번 스트림("+ broadCastEntity.getSymbol()+")을 오픈합니다.");

        // BROADCATING 상태를 ACTIVE로 변경
        broadCastEntity.setBroadCastStatus(BROADCAST_STATUS.ACTIVE.getBroadcastStatus());
        broadCastRepository.save(broadCastEntity);

        getIntervalList().forEach(interval -> {
            String broadCastKey = broadCastEntity.getBroadCastCd()+"_"+interval;
            // 캔들 데이터 스크래핑 및 series 생성
            klineScraping(broadCastEntity, interval, null, 0, 1);
            // 지표 생성
            INDICATORS_MAP.put(broadCastKey, initializeIndicators(broadCastEntity, interval));
            // 머신러닝 모델 생성
            ML_MODEL_MAP.put(broadCastKey, setupMLModel(broadCastEntity, interval, MODEL_TYPE.NORMAL.getModelType(), false));
            // 현재 머신러닝 예상값 세팅
            PREDICT_MAP.put(broadCastKey, setPredictMap(broadCastEntity, interval, MODEL_TYPE.NORMAL.getModelType()));
        });
        log.info("broadCastSaved >>>>> "+ broadCastEntity.getSymbol() + "("+ broadCastEntity.getStreamId()+") : " + broadCastEntity.getBroadCastCd());
    }
    /**
     * <h3>바이낸스 소켓 스트림이 클로즈 되었을 때 호출 되는 콜백</h3>
     */
    public void onCloseCallback(String streamId) {
        BroadCastEntity broadCastEntity = Optional.ofNullable(umWebSocketStreamClient.getBroadCastEntity(Integer.parseInt(streamId)))
                .orElseThrow(() -> new BroadCastException(streamId + "번 BROADCAST 존재하지 않습니다."));
        log.info("[CLOSE] >>>>> " + streamId + " 번 스트림을 클로즈합니다. ");
        broadCastEntity.setBroadCastStatus("CLOSE");
        broadCastRepository.save(broadCastEntity);
        resourceCleanup(broadCastEntity);
    }
    /**
     * <h3>바이낸스 소켓 스트림이 실패 되었을 때 호출 되는 콜백</h3>
     */
    public void onFailureCallback(String streamId) {
        Optional<BroadCastEntity> broadCastEntityOpt = Optional.ofNullable(umWebSocketStreamClient.getBroadCastEntity(Integer.parseInt(streamId)));
        if(broadCastEntityOpt.isPresent()){
            BroadCastEntity broadCastEntity = broadCastEntityOpt.get();
            log.error("tradingSaved >>>>> "+ broadCastEntity.getSymbol() + "("+ broadCastEntity.getStreamId()+") : " + broadCastEntity.getBroadCastCd());
            restartBroadCast(broadCastEntity);
        } else {
            log.error("[RECOVER-ERR] >>>>> "+streamId +" 번 스트림을 복구하지 못했습니다.");
        }
    }
    /**
     * <h3>바이낸스 소켓 스트림이 수신되었을 때 호출 되는 콜백</h3>
     */
    public void onMessageCallback(String event){
        // 최대 재시도 횟수와 초기 재시도 간격 설정
        int maxRetries = 1;
        int initialDelayMillis = 1000;

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                JSONObject eventData = new JSONObject(event);
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
    /**
     * <h3>머신러닝이 추산한 예상치를 MAP에 세팅하는 메서드</h3>
     */
    private double[] setPredictMap(BroadCastEntity broadCastEntity, String interval, String modelType){
        String broadCastKey                  = broadCastEntity.getBroadCastCd()+"_"+interval;

        BaseBarSeries series                 = SERIES_MAP.get(broadCastKey);
        List<Indicator<Num>> indicators      = INDICATORS_MAP.get(broadCastKey);
        MLModel mlModel                      = null;
        if (modelType.equals(MODEL_TYPE.NORMAL.getModelType())){
            mlModel = ML_MODEL_MAP.get(broadCastKey);
        } else if (modelType.equals(MODEL_TYPE.TREND_PREDICT.getModelType())){
            mlModel = ML_TREND_PREDICT_MODEL_MAP.get(broadCastKey);
        } else {
            throw new BroadCastException("해당 모델이 존재하지 않습니다.");
        }

        double[] modelPredict                = mlModel.predictProbabilities(indicators, series.getEndIndex());
        return modelPredict;
    }
    /**
     * <h3>수신 캔들 데이터 처리 메서드</h3>
     */
    private void klineProcess(String event) {
        try {
            JSONObject eventObj      = new JSONObject(event);
            JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
            JSONObject klineObj      = new JSONObject(klineEventObj.get("k").toString());

            // 캔들이 끝났는지 체크 플래그
            boolean isFinal          = klineObj.getBoolean("x");

            // 현재 스트림의 이름(symbol@kline_<interval>)
            String streamNm          = String.valueOf(eventObj.get("stream"));
            String symbol            = streamNm.substring(0, streamNm.indexOf("@"));
            String interval          = streamNm.substring(streamNm.indexOf("_") + 1);

            boolean nextFlag = true;
            List<BroadCastEntity> broadCastEntities = new ArrayList<>();
            try{
                broadCastEntities = getBroadCastEntity(symbol);
                if(broadCastEntities.size() == 0){
                    throw new BroadCastException(symbol+" 해당 broadcast 존재하지 않습니다.");
                }
                if(broadCastEntities.size() > 1){
                    for (BroadCastEntity broadCastEntity : broadCastEntities) {
                        if(broadCastEntity.getSymbol().equals(symbol)){
                            restartBroadCast(broadCastEntity);
                        }
                    }
                }
            } catch (Exception e) {
                Map<Integer, BroadCastEntity> broadCastEntitys = umWebSocketStreamClient.getBroadCastEntities();
                broadCastEntitys.forEach((key, broadCastEntity) -> {
                    if(broadCastEntity.getSymbol().equals(symbol)){
                        streamClose(broadCastEntity.getStreamId());
                    }
                });
                nextFlag = false;
            }
            if (nextFlag) {
                BroadCastEntity broadCastEntity = broadCastEntities.get(0);

                String broadCastCd = broadCastEntity.getBroadCastCd();
                String broadCastKey = broadCastCd+"_"+interval;

                settingBarSeries(broadCastEntity.getBroadCastCd(), event);
                INDICATORS_MAP.put(broadCastKey, initializeIndicators(broadCastEntity, interval));

                if (isFinal) { // 캔들이 끝났을 때
                    log.info("캔들 갱신(" + symbol + " / " + interval + ")");
                    if (interval.equals("1m")){
                        getIntervalList().forEach(intervalKey -> {
                            String targetBroadCastKey   = broadCastCd+"_"+intervalKey;
                            PREDICT_MAP.put(targetBroadCastKey, setPredictMap(broadCastEntity, intervalKey, MODEL_TYPE.NORMAL.getModelType()));
                        });
                        logTradingSignals(broadCastCd);
                    }
                }

                ObjectMapper mapper = objectMapper();
                ObjectNode rootNode = mapper.createObjectNode();
                rootNode.put("symbol", symbol);

                ObjectNode indicatorsNode = rootNode.putObject("indicators");

                for (String intervalKey : getIntervalList()) {
                    String targetBroadCastKey = broadCastCd + "_" + intervalKey;
                    double[] predictValues = (double[]) PREDICT_MAP.getOrDefault(targetBroadCastKey, new double[3]);

                    ObjectNode intervalNode = indicatorsNode.putObject(intervalKey);
                    intervalNode.put("LONG PREDICT", predictValues[2]);
                    intervalNode.put("SHORT PREDICT", predictValues[0]);
                    // 지표 데이터 추가
                    List<Indicator<Num>> indicators = INDICATORS_MAP.get(targetBroadCastKey);
                    for (Indicator<Num> indicator : indicators) {
                        intervalNode.put(indicator.getClass().getSimpleName(), indicator.getValue(indicator.getBarSeries().getEndIndex()).doubleValue());
                    }
                }

                // JSON 데이터를 문자열로 변환하여 Redis로 전송
                String jsonData = mapper.writeValueAsString(rootNode);
                redisTemplate.convertAndSend("signalBroadCast", jsonData);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<Indicator<Num>> initializeIndicators(BroadCastEntity broadCastEntity, String interval) {
        String broadCastKey   = broadCastEntity.getBroadCastCd()+"_"+interval;
        BaseBarSeries series  = SERIES_MAP.get(broadCastKey);

        int shortMovingPeriod = broadCastEntity.getShortMovingPeriod();
        int midMovingPeriod   = broadCastEntity.getMidMovingPeriod();
        int longMovingPeriod  = broadCastEntity.getLongMovingPeriod();

        List<Indicator<Num>> indicators = new ArrayList<>();
        ClosePriceIndicator closePrice  = new ClosePriceIndicator(series);

        // 이평선 관련 지표
        EMAIndicator shortEMA       = new EMAIndicator(closePrice, shortMovingPeriod);
        EMAIndicator longEMA        = new EMAIndicator(closePrice, longMovingPeriod);
        MACDIndicator macdIndicator = new MACDIndicator(closePrice, shortMovingPeriod, longMovingPeriod);

        // 볼린저 밴드 관련 지표
        int bbPeriod = 20;
        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, bbPeriod);
        BollingerBandsMiddleIndicator middleBBand    = new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, bbPeriod));
        BollingerBandsUpperIndicator upperBBand      = new BollingerBandsUpperIndicator(middleBBand, standardDeviation);
        BollingerBandsLowerIndicator lowerBBand      = new BollingerBandsLowerIndicator(middleBBand, standardDeviation);
        PercentBIndicator percentB                   = new PercentBIndicator(closePrice, bbPeriod, 2.0);

        // RSI 지표
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
        //indicators.add(percentB);
        indicators.add(shortEMA);
        indicators.add(longEMA);
        indicators.add(new ATRIndicator(series, shortMovingPeriod));
        //indicators.add(new ADXIndicator(series, longMovingPeriod));
        indicators.add(new ChaikinMoneyFlowIndicator(series, longMovingPeriod));
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
        //indicators.add(new AccumulationDistributionIndicator(series)); // 가격과 거래량의 관계
        //indicators.add(new ParabolicSarIndicator(series)); // 추세 반전 감지

        return indicators;
    }

    /**
     * <h3>시그널을 로깅하기 위한 메서드</h3>
     */
    public void logTradingSignals(String broadCastCd) {
        StringBuilder logBuilder = new StringBuilder("\n");
        String headerFormat = "%-8s | %-14s %-14s\n";
        String dataFormat = "%-8s | %s%-14.4f%s %s%-14.4f%s\n";

        logBuilder.append(String.format(headerFormat, "Interval", "LONG Entry", "LONG Exit"));
        logBuilder.append("-".repeat(41) + "\n");

        for (String intervalKey : getIntervalList()) {
            double longEntryPredict = 0.0, longExitPredict = 0.0;

            String broadCastKey = broadCastCd + "_" + intervalKey;
            double[] predictValues = (double[]) PREDICT_MAP.getOrDefault(broadCastKey, new double[3]);
            longEntryPredict = predictValues[2];
            longExitPredict = predictValues[0];

            logBuilder.append(String.format(dataFormat,
                    intervalKey,
                    getColorForValue(longEntryPredict, true), longEntryPredict, CONSOLE_COLORS.RESET,
                    getColorForValue(longExitPredict, false), longExitPredict, CONSOLE_COLORS.RESET));
        }

        log.info(logBuilder.toString());
    }

    private CONSOLE_COLORS getColorForValue(double value, boolean isEntry) {
        if (isEntry) {
            return value > 0.4 ? CONSOLE_COLORS.GREEN : CONSOLE_COLORS.RESET;
        } else {
            return value > 0.4 ? CONSOLE_COLORS.RED : CONSOLE_COLORS.RESET;
        }
    }

    private void settingBarSeries(String broadCastCd, String event) {
        JSONObject eventObj      = new JSONObject(event);
        JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
        JSONObject klineObj      = new JSONObject(klineEventObj.get("k").toString());

        String streamNm          = String.valueOf(eventObj.get("stream"));
        String symbol            = streamNm.substring(0, streamNm.indexOf("@"));
        String interval          = streamNm.substring(streamNm.indexOf("_") + 1);

        String broadCastKey = broadCastCd+"_"+interval;
        BaseBarSeries series     = SERIES_MAP.get(broadCastKey);

        ZonedDateTime closeTime  = convertTimestampToDateTime(klineObj.getLong("T")).atZone(ZoneOffset.UTC);

        Num open                 = series.numOf(klineObj.getDouble("o"));
        Num high                 = series.numOf(klineObj.getDouble("h"));
        Num low                  = series.numOf(klineObj.getDouble("l"));
        Num close                = series.numOf(klineObj.getDouble("c"));
        Num volume               = series.numOf(klineObj.getDouble("v"));

        Bar newBar = new BaseBar(Duration.ofMinutes(15), closeTime, open, high, low, close, volume ,null);
        Bar lastBar = series.getLastBar();
        if (!newBar.getEndTime().isAfter(lastBar.getEndTime())) {
            series.addBar(newBar, true);
        }else{
            series.addBar(newBar, false);
        }
    }

    /**
     * <h3>사용된 컬렉션 리소스들 정리 메서드</h3>
     */
    public void resourceCleanup(BroadCastEntity broadCastEntity) {
        String symbol = broadCastEntity.getSymbol();
        String broadCastCd = broadCastEntity.getBroadCastCd();

        BROADCAST_ENTITYS.remove(symbol);

        getIntervalList().forEach(interval -> {
            String broadCastKey = broadCastCd+"_"+interval;
            SERIES_MAP.remove(broadCastKey);
            ML_MODEL_MAP.remove(broadCastKey);
            INDICATORS_MAP.remove(broadCastKey);
            PREDICT_MAP.remove(broadCastKey);
            ML_TREND_PREDICT_MODEL_MAP.remove(broadCastKey);
        });

        BROADCASTING_OPEN_PROCESS_FLAG = false;

        // 메모리 사용량 출력
        new MemoryUsageMonitor().logMemoryUsage();
    }
    
    // ****************************************************************************************
    // 스트림 종료 관련
    // ****************************************************************************************
    public void allStreamClose() {
        log.info("모든 스트림 종료");
        List<BroadCastEntity> broadCastEntityList = broadCastRepository.findAll();
        broadCastEntityList.stream().forEach(broadCastEntity -> {
            if(broadCastEntity.getBroadCastStatus().equals(BROADCAST_STATUS.ACTIVE.getBroadcastStatus())){
                broadCastEntity.setBroadCastStatus(BROADCAST_STATUS.CLOSE.getBroadcastStatus());
                broadCastRepository.save(broadCastEntity);
                BROADCAST_ENTITYS.remove(broadCastEntity.getSymbol());
                streamClose(broadCastEntity.getStreamId());
            }
        });
        umWebSocketStreamClient.closeAllConnections();
    }
    public void streamClose(int streamId) {
        log.info("streamClose >>>>> " + streamId);
        umWebSocketStreamClient.closeConnection(streamId);
    }
    private List<BroadCastEntity> getBroadCastEntity(String symbol) {
        return broadCastRepository.findBySymbolAndBroadCastStatus(symbol.toUpperCase(), BROADCAST_STATUS.ACTIVE.getBroadcastStatus());
    }
    //해당 broadCast를 종료하고 다시 오픈하는 코드
    public void restartBroadCast(BroadCastEntity broadCastEntity){
        broadCastEntity.setBroadCastStatus(BROADCAST_STATUS.CLOSE.getBroadcastStatus());
        broadCastEntity = broadCastRepository.save(broadCastEntity);
        log.info("restartBroadCast >>>>> " + broadCastEntity.getSymbol()+ " : " + broadCastEntity.getBroadCastCd());
        streamClose(broadCastEntity.getStreamId());
        resourceCleanup(broadCastEntity);
        broadCastingOpen(broadCastEntity);
    }

    public Map<String, Object> broadCastingOpen(HttpServletRequest request, BroadCastDTO broadCastDTO) {
        BroadCastEntity broadCastEntity = broadCastDTO.toEntity();
        return broadCastingOpen(broadCastEntity);
    }
    
    public Map<String, Object> broadCastingOpen(BroadCastEntity broadCastEntity) {
        if (BROADCASTING_OPEN_PROCESS_FLAG) {
            throw new BroadCastException("이미 실행중입니다.");
        }
        BROADCASTING_OPEN_PROCESS_FLAG = true;
        // *************************************************************************************************
        // 변수선언
        // *************************************************************************************************
        Map<String, Object> resultMap = new LinkedHashMap<>();
        String symbol = broadCastEntity.getSymbol(); // 타겟 심볼

        boolean nextFlag = true;
        try{
            Optional<BroadCastEntity> broadCastEntityOpt = Optional.ofNullable(BROADCAST_ENTITYS.get(symbol));
            if(broadCastEntityOpt.isPresent()){
                throw new BroadCastException(symbol+" 이미 오픈된 broadCast 존재합니다.");
            }
        } catch (Exception e) {
            BROADCASTING_OPEN_PROCESS_FLAG = false;
            broadCastingOpen(broadCastEntity);
            nextFlag = false;
        }

        if(nextFlag){ // 정합성 체크 통과한다면 다음 단계로 진행
            try{
                Optional<BroadCastEntity> broadCastEntityOpt = Optional.ofNullable(BROADCAST_ENTITYS.get(symbol));
                if(broadCastEntityOpt.isPresent()){
                    throw new RuntimeException(broadCastEntityOpt.get().getSymbol() + "이미 오픈된 broadCast 존재합니다.");
                }else{
                    BROADCAST_ENTITYS.put(symbol, autoTradeStreamOpen(broadCastEntity));
                }
            } catch (Exception e) {
                BROADCASTING_OPEN_PROCESS_FLAG = false;
                broadCastingOpen(broadCastEntity);
            }
        }
        BROADCASTING_OPEN_PROCESS_FLAG = false;
        return resultMap;
    }

    public BroadCastEntity autoTradeStreamOpen(BroadCastEntity broadCastEntity) {
        ArrayList<String> streams = new ArrayList<>();

        // 캔들데이터 소켓 스트림
        BroadCastEntity finalBroadCastEntity = broadCastEntity;
        getIntervalList().forEach(interval -> {
            String klineStreamName = finalBroadCastEntity.getSymbol().toLowerCase() + "@kline_" + interval;
            streams.add(klineStreamName);
        });
        //String klineStreamName = broadCastEntity.getSymbol().toLowerCase() + "@kline_" + broadCastEntity.getCandleInterval();
        //streams.add(klineStreamName);

        String forceOrderStreamName = broadCastEntity.getSymbol().toLowerCase() + "@forceOrder";
        //streams.add(forceOrderStreamName);

        String depthStreamName = broadCastEntity.getSymbol().toLowerCase() + "@depth";
        //streams.add(depthStreamName);*/

        String aggTradeStreamName = broadCastEntity.getSymbol().toLowerCase() + "@aggTrade";
        //streams.add(aggTradeStreamName);

        String allMarketForceOrderStreamName = "!forceOrder@arr";
        //streams.add(allMarketForceOrderStreamName);

        broadCastEntity = umWebSocketStreamClient.combineStreams(broadCastEntity, streams, openCallback, onMessageCallback, closeCallback, failureCallback);
        return broadCastEntity;
    }

    public void klineScraping(HttpServletRequest request, BroadCastDTO broadCastDTO){
        BroadCastEntity broadCastEntity = broadCastDTO.toEntity();
        broadCastEntity.setBroadCastCd(UUID.randomUUID().toString());
        getIntervalList().forEach(interval -> {
            klineScraping(broadCastEntity, interval, null, 0, 3);
        });
    }

    public void klineScraping(BroadCastEntity broadCastEntity, String interval, String endTime, int idx, int page) {
        log.info(broadCastEntity.getSymbol() + " klineScraping("+interval+") >>>>>" + idx + "/" + page);
        String broadCastCd = broadCastEntity.getBroadCastCd();

        LinkedHashMap<String, Object> requestParam = new LinkedHashMap<>();
        requestParam.put("timestamp", getServerTime());
        requestParam.put("symbol", broadCastEntity.getSymbol());
        requestParam.put("interval", interval);
        requestParam.put("limit", 1500);
        if (idx != 0) {
            requestParam.put("endTime", endTime);
        }
        UMFuturesClientImpl client = new UMFuturesClientImpl(BINANCE_API_KEY, BINANCE_SECRET_KEY, true);
        String resultStr = client.market().klines(requestParam);

        JSONArray jsonArray = new JSONArray(new JSONObject(resultStr).get("data").toString());
        JSONArray firstIdxArray = jsonArray.getJSONArray(0);
        String firstTime = String.valueOf(firstIdxArray.get(0));

        String broadCastKey = broadCastCd+"_"+interval;
        List<Bar> allBars = new ArrayList<>();

        // 기존 시리즈의 바를 리스트에 추가
        Optional<BaseBarSeries> existingSeriesOpt = Optional.ofNullable(SERIES_MAP.get(broadCastKey));
        if (existingSeriesOpt.isPresent()) {
            BaseBarSeries existingSeries = existingSeriesOpt.get();
            for (int i = 0; i < existingSeries.getBarCount(); i++) {
                allBars.add(existingSeries.getBar(i));
            }
        }

        // 새로운 데이터를 리스트에 추가
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray klineArray = jsonArray.getJSONArray(i);
            Bar newBar = jsonArrayToBar(klineArray);
            allBars.add(newBar);
        }

        // 중복 제거 및 정렬
        List<Bar> uniqueSortedBars = allBars.stream()
                .distinct()
                .sorted(Comparator.comparing(Bar::getEndTime))
                .collect(Collectors.toList());

        // 새로운 시리즈 생성
        BaseBarSeries newSeries = new BaseBarSeries();
        for (Bar bar : uniqueSortedBars) {
            newSeries.addBar(bar);
        }

        // 새로운 시리즈를 맵에 저장
        SERIES_MAP.put(broadCastKey, newSeries);

        String weight = new JSONObject(resultStr).getString("x-mbx-used-weight-1m");
        log.info("*************** [현재 가중치 : " + weight + "] ***************");

        idx++;
        if (idx < page) {
            klineScraping(broadCastEntity, interval, firstTime, idx, page);
        } else {
            log.info(broadCastEntity.getSymbol() + " klineScraping >>>>> END");
        }
    }

    private MLModel setupMLModel(BroadCastEntity broadCastEntity, String interval, String modelType, boolean testFlag) {
        String broadCastCd  = broadCastEntity.getBroadCastCd();
        String broadCastKey = broadCastCd+"_"+interval;

        BaseBarSeries series             = SERIES_MAP.get(broadCastKey);
        List <Indicator<Num>> indicators = INDICATORS_MAP.get(broadCastKey);
        MLModel mlModel = null;
        switch (MODEL_TYPE.valueOf(modelType)) {
            case TREND_PREDICT:
                mlModel = new MLTrendPredictModel(indicators);
                break;
            case NORMAL:
            default:
                mlModel = new MLModel(indicators);
                break;
        }
        int totalSize = series.getBarCount();
        int trainSize = (int) (totalSize * 0.75);
        BarSeries trainSeries = series.getSubSeries(0, trainSize);
        BarSeries testSeries = series.getSubSeries(trainSize, totalSize);
        if (testFlag) {
            mlModel.train(trainSeries, trainSize);
        } else {
            mlModel.train(series, totalSize);
        }
        return mlModel;
    }

    public String getServerTime() {
        return umFuturesClientImpl.market().time();
    }

    // ****************************************************************************************
    // JSON 데이터 파싱 관련 메서드
    // ****************************************************************************************
    public Claims getClaims(HttpServletRequest request){
        try{
            Key secretKey = Keys.hmacShaKeyFor(JWT_SECRET_KEY.getBytes(StandardCharsets.UTF_8));
            log.info("JWT_SECRET_KEY : " + JWT_SECRET_KEY);
            log.info("authorization : " + request.getHeader("authorization"));
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