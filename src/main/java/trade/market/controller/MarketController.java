package trade.market.controller;

import com.binance.connector.client.WebSocketStreamClient;
import com.binance.connector.client.impl.WebSocketStreamClientImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import trade.common.CommonUtils;
import trade.market.model.dto.TradeEventDTO;
import trade.market.service.MarketService;

@Slf4j
@Api(tags = "MarketController")
@Tag(name = "MarketController", description = "시장컨트롤러")
@RestController
@RequiredArgsConstructor
public class MarketController {
    @Autowired MarketService marketService;
    @Autowired CommonUtils commonUtils;
    private static WebSocketStreamClient client = new WebSocketStreamClientImpl();

    @GetMapping(value = "/market/stream/open/trade/")
    @Operation(summary="거래추적 스트림을 오픈합니다.", description="거래추적 스트림을 오픈합니다.")
    public void openTradeStream() {
        client.tradeStream("btcusdt", ((event) -> {
            // ObjectMapper를 사용하여 JSON 문자열을 DTO로 변환
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                TradeEventDTO tradeEventDTO = objectMapper.readValue(event, TradeEventDTO.class);
                System.out.println(tradeEventDTO.toEntity());
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @GetMapping(value = "/market/stream/close/trade/")
    @Operation(summary="거래추적 스트림을 클로즈합니다.", description="거래추적 스트림을 클로즈합니다.")
    public void closeTradeStream() {
        client.closeAllConnections();
    }
}