package trade.future.controller;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.binance.connector.futures.client.impl.UMWebsocketClientImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import trade.common.CommonUtils;
import trade.future.service.FutureService;
import trade.future.model.dto.AggTradeEventDTO;

import java.util.*;

@Slf4j
@Api(tags = "FutureController")
@Tag(name = "FutureController", description = "선물컨트롤러")
@RestController
@RequiredArgsConstructor
public class FutureController {
    @Autowired FutureService futureService;
    @Autowired CommonUtils commonUtils;

    UMWebsocketClientImpl umWebSocketStreamClient = new UMWebsocketClientImpl();
    UMFuturesClientImpl umFuturesClientImpl = new UMFuturesClientImpl();

    @GetMapping(value = "/future/stream/close")
    @Operation(summary="거래추적 스트림을 클로즈합니다.", description="거래추적 스트림을 클로즈합니다.")
    public void tradeStreamClose(@RequestParam int streamId) {
        umWebSocketStreamClient.closeConnection(streamId);
    }
    @GetMapping(value = "/future/trade/stream/open")
    @Operation(summary="거래추적 스트림을 오픈합니다.", description="거래추적 스트림을 오픈합니다.")
    public ResponseEntity tradeStreamOpen(@RequestParam String symbol) { //btcusdt
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        int streamId = umWebSocketStreamClient.aggTradeStream(symbol, ((event) -> {
            // ObjectMapper를 사용하여 JSON 문자열을 DTO로 변환
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                AggTradeEventDTO aggTradeEventDTO = objectMapper.readValue(event, AggTradeEventDTO.class);
                System.out.println(aggTradeEventDTO.toEntity());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }));
        resultMap.put("streamId", streamId);
        return commonUtils.okResponsePackaging(resultMap);
    }

    @GetMapping(value = "/future/allbookticker/stream/open")
    @Operation(summary="거래추적 스트림을 오픈합니다.", description="거래추적 스트림을 오픈합니다.")
    public ResponseEntity allBookTickerStreamOpen() { //btcusdt
        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        int streamId = umWebSocketStreamClient.allBookTickerStream(((event) -> {
            System.out.println("event : " + event);
        }));
        resultMap.put("streamId", streamId);
        return commonUtils.okResponsePackaging(resultMap);
    }

    @GetMapping(value = "/future/ticker24h")
    @Operation(summary="거래추적 스트림을 오픈합니다.", description="거래추적 스트림을 오픈합니다.")
    public ResponseEntity ticker24h() {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        LinkedHashMap<String, Object> paramMap = new LinkedHashMap<>();
        String resultStr = umFuturesClientImpl.market().ticker24H(paramMap);
        JSONArray resultArray = new JSONArray(resultStr);

        // JSON 데이터를 Java 객체로 파싱하여 리스트에 저장
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (int i = 0; i < resultArray.length(); i++) {
            JSONObject item = resultArray.getJSONObject(i);
            itemList.add(item.toMap());
        }

        // 거래량(volume)을 기준으로 내림차순으로 정렬한 복사본
        List<Map<String, Object>> sortedByVolume = new ArrayList<>(itemList);
        Collections.sort(sortedByVolume, (item1, item2) -> {
            double volume1 = Double.parseDouble(item1.get("volume").toString());
            double volume2 = Double.parseDouble(item2.get("volume").toString());
            return Double.compare(volume2, volume1);
        });

        // 변동폭(priceChange)을 기준으로 내림차순으로 정렬한 복사본
        List<Map<String, Object>> sortedByPriceChange = new ArrayList<>(itemList);
        Collections.sort(sortedByPriceChange, (item1, item2) -> {
            double priceChange1 = Double.parseDouble(item1.get("priceChange").toString());
            double priceChange2 = Double.parseDouble(item2.get("priceChange").toString());
            return Double.compare(priceChange2, priceChange1);
        });

        // 상위 5개 항목 선택
        List<Map<String, Object>> top5VolumeItems = sortedByVolume.subList(0, Math.min(sortedByVolume.size(), 5));
        List<Map<String, Object>> top5PriceChangeItems = sortedByPriceChange.subList(0, Math.min(sortedByPriceChange.size(), 5));

        System.out.println("top5VolumeItems : " + top5VolumeItems);
        System.out.println("top5PriceChangeItems : " + top5PriceChangeItems);

        resultMap.put("result", resultStr);
        return commonUtils.okResponsePackaging(resultMap);
    }
}