package trade.future.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import trade.common.CommonUtils;
import trade.future.model.entity.KlineEventEntity;
import trade.future.model.entity.PositionEntity;
import trade.future.model.entity.TradingEntity;
import trade.future.repository.KlineEventRepository;
import trade.future.repository.PositionRepository;
import trade.future.repository.TradingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class FutureService {
    @Autowired KlineEventRepository klineEventRepository;
    @Autowired PositionRepository positionRepository;
    @Autowired TradingRepository tradingRepository;
    UMWebsocketClientImpl umWebSocketStreamClient = new UMWebsocketClientImpl();
    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();

    public void streamClose(int streamId) {
        umWebSocketStreamClient.closeConnection(streamId);
    }
    public Map<String, Object> autoTrading(String interval, int leverage, int goalPricePercent, BigDecimal QuoteAssetVolumeStandard) throws Exception {
        log.info("autoTrading >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> selectedStockList = (List<Map<String, Object>>) getStockSelection(10).get("overlappingData");
        for(Map<String, Object> selectedStock : selectedStockList){
            String symbol = String.valueOf(selectedStock.get("symbol"));
            Optional<TradingEntity> tradingEntityOpt = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");

            // 해당 심볼의 트레이딩이 없으면 트레이딩을 시작합니다.
            if(!tradingEntityOpt.isPresent()) {
                int streamId = (int) autoTradeStreamOpen(symbol, interval, leverage, goalPricePercent, QuoteAssetVolumeStandard).get("streamId");
                // 해당 페어의 평균 거래량을 구합니다.
                BigDecimal averageQuoteAssetVolume = getKlinesAverageQuoteAssetVolume((JSONArray)getKlines(symbol, interval, 500).get("result"), interval);
                TradingEntity tradingEntity = TradingEntity.builder()
                        .symbol(symbol)
                        .streamId(streamId)
                        .tradingStatus("OPEN")
                        .candleInterval(interval)
                        .leverage(leverage)
                        .goalPricePercent(goalPricePercent)
                        .quoteAssetVolumeStandard(QuoteAssetVolumeStandard)
                        .averageQuoteAssetVolume(averageQuoteAssetVolume)
                        .build();
                tradingRepository.save(tradingEntity);
            }
        }
        return resultMap;
    }

    public Map<String, Object> autoTradeStreamOpen(String symbol, String interval, int leverage, int goalPricePercent, BigDecimal QuoteAssetVolumeStandard) {
        log.info("klineStreamOpen >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();

        // 500개의 캔들을 가져와서 평균 거래량을 구함
        BigDecimal averageQuoteAssetVolume = getKlinesAverageQuoteAssetVolume((JSONArray)getKlines(symbol, interval, 500).get("result"), interval);
        int streamId = umWebSocketStreamClient.klineStream(symbol, interval, ((event) -> {
            // klineEvent를 역직렬화하여 데이터베이스에 저장
            KlineEventEntity klineEventEntity = saveKlineEvent(event, leverage, goalPricePercent);
            BigDecimal quoteAssetVolume = klineEventEntity.getKline().getQuoteAssetVolume();
            if (quoteAssetVolume.compareTo(averageQuoteAssetVolume.multiply(QuoteAssetVolumeStandard)) > 0) {
                if(positionRepository.getPositionByKlineEndTime(klineEventEntity.getKline().getEndTime(), "OPEN").isEmpty()){
                    Optional<TradingEntity> tradingEntityOpt = tradingRepository.findBySymbolAndTradingStatus(symbol, "OPEN");
                    TradingEntity tradingEntity = tradingEntityOpt.orElseThrow(() -> new RuntimeException("트레이딩이 존재하지 않습니다."));
                    System.out.println("거래량("+quoteAssetVolume+")이 " +
                            "평균 거래량("+averageQuoteAssetVolume+")의 "+
                            quoteAssetVolume.divide(averageQuoteAssetVolume, RoundingMode.FLOOR)+
                            "(기준치 : "+QuoteAssetVolumeStandard+")배 보다 큽니다.");
                    PositionEntity newPosition = PositionEntity.builder()
                            .kline(klineEventEntity.getKline())
                            .positionSide("LONG")
                            .positionStatus("OPEN")
                            .tradingEntity(tradingEntity)
                            .build();
                    klineEventEntity.getKline().setPosition(newPosition);
                    klineEventEntity = klineEventRepository.save(klineEventEntity);

                    System.out.println("포지션을 진입합니다. <<<<< " + klineEventEntity.getKline().getSymbol());
                    System.out.println(
                            "진입가("+klineEventEntity.getKline().getClosePrice()+"), "+
                            "목표가(LONG:"+klineEventEntity.getPlusGoalPrice()+
                            "/SHORT:"+klineEventEntity.getMinusGoalPrice()+")"
                            );
                    System.out.println("포지션 : "+klineEventEntity.getKline().getPosition().toString());
                };
            }
            // 목표가에 도달한 KlineEvent들을 업데이트
            updateGoalAchievedKlineEvent(klineEventEntity);
        }));
        resultMap.put("streamId", streamId);
        return resultMap;
    }

    public KlineEventEntity saveKlineEvent(String event, int leverage, int goalPricePercent) {
        KlineEventEntity klineEventEntity = CommonUtils.convertKlineEventDTO(event).toEntity();
        klineEventEntity.setGoalPricePercent(goalPricePercent);

        klineEventEntity.setPlusGoalPrice(
                CommonUtils.calculateGoalPrice(
                        klineEventEntity.getKline().getClosePrice(), "LONG", leverage, klineEventEntity.getGoalPricePercent()));
        klineEventEntity.setMinusGoalPrice(
                CommonUtils.calculateGoalPrice(
                        klineEventEntity.getKline().getClosePrice(), "SHORT", leverage, klineEventEntity.getGoalPricePercent()));

        return klineEventRepository.save(klineEventEntity);
    }

    public Map<String, Object> klineStreamOpen(String symbol, String interval, int leverage, int goalPricePercent) {
        log.info("klineStreamOpen >>>>>");
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();

        int streamId = umWebSocketStreamClient.klineStream(symbol, interval, ((event) -> {
            // klineEvent를 역직렬화하여 데이터베이스에 저장
            KlineEventEntity klineEventEntity = saveKlineEvent(event, leverage, goalPricePercent);
            // 목표가에 도달한 KlineEvent들을 업데이트
            updateGoalAchievedKlineEvent(klineEventEntity);
        }));
        resultMap.put("streamId", streamId);
        return resultMap;
    }

    public void updateGoalAchievedKlineEvent(KlineEventEntity klineEventEntity) {
        List<KlineEventEntity> goalAchievedPlusList = klineEventRepository.findKlineEventsWithPlusGoalPriceLessThanCurrentPrice(klineEventEntity.getKline().getSymbol(), klineEventEntity.getKline().getClosePrice());
        goalAchievedPlusList.stream().forEach(goalAchievedPlus -> {
            goalAchievedPlus.setGoalPriceCheck(true);
            goalAchievedPlus.getKline().setGoalPricePlus(true);

            Optional<PositionEntity> currentPositionOpt = Optional.ofNullable(goalAchievedPlus.getKline().getPosition());

            if(currentPositionOpt.isPresent()){
                System.out.println("목표가 도달(long) : " + goalAchievedPlus.getKline().getSymbol() + " 현재가 : " + goalAchievedPlus.getKline().getClosePrice() + "/ 목표가 : " + goalAchievedPlus.getPlusGoalPrice());
                PositionEntity currentPosition = currentPositionOpt.get();
                currentPosition.setPositionStatus("CLOSE");
                //System.out.println(currentPosition.toString());
                System.out.println(">>>>> 포지션을 종료합니다. " + klineEventEntity.getKline().getSymbol());

                // 트레이딩을 닫습니다.
                TradingEntity tradingEntity = currentPosition.getTradingEntity();
                tradingEntity.setTradingStatus("CLOSE");
                tradingRepository.save(tradingEntity);

                //소켓 스트림을 닫습니다.
                streamClose(tradingEntity.getStreamId());
                
                //트레이딩을 다시 시작합니다.
                try {
                    autoTrading(tradingEntity.getCandleInterval(), tradingEntity.getLeverage(), tradingEntity.getGoalPricePercent(), tradingEntity.getQuoteAssetVolumeStandard());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            klineEventRepository.save(goalAchievedPlus);
        });
        List<KlineEventEntity> goalAchievedMinusList = klineEventRepository.findKlineEventsWithMinusGoalPriceGreaterThanCurrentPrice(klineEventEntity.getKline().getSymbol(), klineEventEntity.getKline().getClosePrice());
        goalAchievedMinusList.stream().forEach(goalAchievedMinus -> {
            goalAchievedMinus.setGoalPriceCheck(true);
            goalAchievedMinus.getKline().setGoalPriceMinus(true);

            Optional<PositionEntity> currentPositionOpt = Optional.ofNullable(goalAchievedMinus.getKline().getPosition());

            if(currentPositionOpt.isPresent()){
                System.out.println("목표가 도달(short) : " + goalAchievedMinus.getKline().getSymbol() + " 현재가 : " + goalAchievedMinus.getKline().getClosePrice() + "/ 목표가 : " + goalAchievedMinus.getMinusGoalPrice());
                PositionEntity currentPosition = currentPositionOpt.get();
                currentPosition.setPositionStatus("CLOSE");
                System.out.println(">>>>> 포지션을 종료합니다. " + klineEventEntity.getKline().getSymbol());
                System.out.println(currentPosition.toString());

                // 트레이딩을 닫습니다.
                TradingEntity tradingEntity = currentPosition.getTradingEntity();
                tradingEntity.setTradingStatus("CLOSE");
                tradingRepository.save(tradingEntity);

                // 소켓 스트림을 닫습니다.
                streamClose(tradingEntity.getStreamId());
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
}