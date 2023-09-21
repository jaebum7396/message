package trade.future.controller;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import trade.common.CommonUtils;
import trade.future.service.FutureService;

@Slf4j
@Api(tags = "FutureController")
@Tag(name = "FutureController", description = "선물컨트롤러")
@RestController
@RequiredArgsConstructor
public class FutureController {
    @Autowired FutureService futureService;
    @Autowired CommonUtils commonUtils;

    @GetMapping(value = "/future/stream/close")
    @Operation(summary="스트림을 클로즈합니다.", description="거래추적 스트림을 클로즈 합니다.")
    public void streamClose(@RequestParam int streamId) {
        futureService.streamClose(streamId);
    }

    @GetMapping(value = "/future/stream/open/kline")
    @Operation(summary="캔들 스트림을 오픈합니다.", description="캔들 스트림을 오픈합니다.")
    public ResponseEntity continuousKlineStreamOpen(@RequestParam String symbol, @RequestParam String interval, @RequestParam int leverage, @RequestParam int goalPricePercent) {
        return commonUtils.okResponsePackaging(futureService.klineStreamOpen(symbol, interval, leverage, goalPricePercent));
    }
    @GetMapping(value = "/future/stock/selection")
    @Operation(summary="거래량과 변동폭 기준으로 종목을 가져옵니다.", description="거래량과 변동폭 기준으로 종목을 가져옵니다.")
    public ResponseEntity getStockSelection(@RequestParam int limit) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getStockSelection(limit));
    }
}