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
import trade.exception.TradingException;
import trade.future.ml.MLModel;
import trade.future.model.Rule.*;
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

    // 원하는 형식의 날짜 포맷 지정
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${jwt.secret.key}") private String JWT_SECRET_KEY;
    int failureCount = 0;
    private HashMap<String, List<KlineEntity>> klines = new HashMap<String, List<KlineEntity>>();
    private HashMap<String, BaseBarSeries> seriesMap = new HashMap<String, BaseBarSeries>();
    private HashMap<String, Strategy> strategyMap = new HashMap<String, Strategy>();
    private static final int WINDOW_SIZE = 100; // For demonstration purposes
    private final Map<String, TradingEntity> TRADING_ENTITYS = new HashMap<>();


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
        String line = "+%-14s+%22s+%n";

        System.out.printf(line, "-".repeat(14), "-".repeat(22));
        System.out.printf(format, "항목", "금액");
        System.out.printf(line, "-".repeat(14), "-".repeat(22));
        System.out.printf(format, "사용가능", accountInfo.optString("availableBalance", "N/A"));
        System.out.printf(format, "담보금", accountInfo.optString("totalWalletBalance", "N/A"));
        System.out.printf(format, "미실현수익", accountInfo.optString("totalUnrealizedProfit", "N/A"));
        System.out.printf(format, "현재자산", accountInfo.optString("totalMarginBalance", "N/A"));
        System.out.printf(line, "-".repeat(14), "-".repeat(22));
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
                    System.out.println("symbol : " + symbol);

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
                        autoTradingOpen(newTradingEntity);
                    }
                    printTradingEntitys();
                });
            }
        }
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
        JSONArray resultArray = new JSONArray(resultStr);
        //printPrettyJson(resultArray);

        // 거래량(QuoteVolume - 기준 화폐)을 기준으로 내림차순으로 정렬해서 가져옴
        List<Map<String, Object>> sortedByQuoteVolume = getSort(resultArray, "quoteVolume", "DESC", stockSelectionCount);
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
        System.out.println("tradingEntity : " + tradingEntity);

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
        System.out.println("tradingEntity : " + tradingEntity);

        String tradingCd = tradingEntity.getTradingCd();
        String symbol    = tradingEntity.getSymbol();
        String interval  = tradingEntity.getCandleInterval();
        int candleCount  = tradingEntity.getCandleCount();
        int limit = candleCount;

        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);

        int shortMovingPeriod = tradingEntity.getShortMovingPeriod();
        int midPeriod = tradingEntity.getMidMovingPeriod();
        int longMovingPeriod = tradingEntity.getLongMovingPeriod();

        log.info("SYMBOL : " + symbol);

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
        RSIIndicator rsiIndicator = new RSIIndicator(closePrice, rsiPeriod);

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
        Rule rsiBuyingRule            = new CrossedDownIndicatorRule(rsiIndicator, DecimalNum.valueOf(20)); // RSI가 20 이하로 떨어질 때 매수
        Rule rsiSellingRule           = new CrossedUpIndicatorRule(rsiIndicator, DecimalNum.valueOf(70)); // RSI가 70 이상으로 올라갈 때 매도

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
        System.out.println("Train data size: " + trainSize);

        // 훈련 데이터와 테스트 데이터 분리
        BarSeries trainSeries = series.getSubSeries(0, trainSize);
        BarSeries testSeries = series.getSubSeries(trainSize, totalSize);
        // 머신러닝에 쓰일 지표 초기화
        List<Indicator<Num>> indicators = initializeIndicators(series);
        double upThreshold = 0.6;   // 60% 이상의 상승 확률일 때 매수 신호
        double downThreshold = 0.6; // 60% 이상의 하락 확률일 때 매도 신호

        mlModel.train(testSeries, indicators, trainSize);
        //  MLRule 생성
        Rule mlRule = new MLRule(mlModel, indicators, upThreshold, downThreshold);

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
            longEntryRules.add(mlRule);
            shortEntryRules.add(new NotRule(mlRule));

            // 머신러닝 룰은 손익률이 1 이상일 때만 적용
            Rule longExitMLRule = new AndRule(new NotRule(mlRule), longMinimumProfitRule);
            Rule shortExitMLRule = new AndRule(mlRule, shortMinimumProfitRule);

            longExitRules.add(longExitMLRule);
            shortExitRules.add(shortExitMLRule);
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

        if (tradingEntity.getRsiChecker() == 1) {
            longEntryRules.add(rsiBuyingRule);
            shortEntryRules.add(rsiSellingRule);

            // RSI 룰은 손익률이 1 이상일 때만 적용
            Rule longExitRule = new AndRule(rsiSellingRule, longMinimumProfitRule);
            Rule shortExitRule = new AndRule(rsiBuyingRule, shortMinimumProfitRule);

            longExitRules.add(longExitRule);
            shortExitRules.add(shortExitRule);
        }

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
        System.out.println("tradingEntity : " + tradingEntity);
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

        // 시리즈 생성
        seriesMaker(tradingEntity, logFlag);
        BaseBarSeries series = seriesMap.get(tradingCd + "_" + interval);

        // 전략 생성
        strategyMaker(tradingEntity, logFlag);
        Strategy longStrategy = strategyMap.get(tradingCd + "_" + interval + "_long_strategy");
        Strategy shortStrategy = strategyMap.get(tradingCd + "_" + interval + "_short_strategy");

        int totalSize = series.getBarCount();
        int trainSize = (int) (totalSize * 0.5);
        System.out.println("Train data size: " + trainSize);

        // 훈련 데이터와 테스트 데이터 분리
        BarSeries trainSeries = series.getSubSeries(0, trainSize);
        BaseBarSeries testSeries = series.getSubSeries(trainSize, totalSize);

        // 백테스트 실행
        BarSeriesManager seriesManager = new BarSeriesManager(testSeries);
        TradingRecord longTradingRecord = seriesManager.run(longStrategy);
        TradingRecord shortTradingRecord = seriesManager.run(shortStrategy, Trade.TradeType.SELL);
        int leverage = tradingEntity.getLeverage(); // 레버리지

        // 결과 출력
        System.out.println("");
        printBackTestResult(longTradingRecord, testSeries, symbol, leverage, "LONG", maxPositionAmount);
        System.out.println("");
        printBackTestResult(shortTradingRecord, testSeries, symbol, leverage, "SHORT", maxPositionAmount);

        System.out.println("");
        System.out.println("롱 매매횟수 : "+longTradingRecord.getPositionCount());
        System.out.println("숏 매매횟수 : "+shortTradingRecord.getPositionCount());

        return resultMap;
    }

    public void printBackTestResult(TradingRecord tradingRecord, BaseBarSeries series, String symbol, int leverage, String positionSide, BigDecimal collateral) {

        // 트렌드
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSMA = new SMAIndicator(closePrice, 10); // 10일 단기 이동평균선
        SMAIndicator longSMA = new SMAIndicator(closePrice, 20); // 30일 장기 이동평균선

        // 거래 기록 출력
        List<Position> positions = tradingRecord.getPositions();
        BigDecimal totalProfit = BigDecimal.ZERO;
        List<Position> winPositions = new ArrayList<>();
        List<Position> losePositions = new ArrayList<>();
        System.out.println(symbol+"/"+positionSide+" 리포트");
        RelativeATRIndicator relativeATR = new RelativeATRIndicator(series, 14, 100);
        // ADX 지표 설정
        int adxPeriod = 14; // 일반적으로 사용되는 기간
        ADXIndicator adxIndicator = new ADXIndicator(series, adxPeriod);

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
            /* for (Rule entryRule : entryRules) {
                if (entryRule.isSatisfied(entry.getIndex(), tradingRecord) || entryRule.isSatisfied(entry.getIndex())) {
                    entryRuleExpression.append(entryRule.getClass().getSimpleName()).append(" ");
                }
            }

            for (Rule exitRule : stopRules) {
                if (exitRule.isSatisfied(exit.getIndex(), tradingRecord) || exitRule.isSatisfied(exit.getIndex())) {
                    stopRuleExpression.append(exitRule.getClass().getSimpleName()).append(" ");
                }
            }*/
            //System.out.println(" "+entry+" / "+exit);
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