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

import java.math.BigDecimal;

@Slf4j
@Api(tags = "FutureController")
@Tag(name = "FutureController", description = "선물컨트롤러")
@RestController
@RequiredArgsConstructor
public class FutureController {
    @Autowired FutureService futureService;
    @Autowired CommonUtils commonUtils;

    @GetMapping(value = "/future/auto/close")
    @Operation(summary="자동매매를 종료합니다.", description="자동매매를 종료합니다.")
    public void autoTradingClose() {
        futureService.autoTradingClose();
    }
    @GetMapping(value = "/future/auto/open")
    @Operation(summary="자동매매를 시작합니다.", description="자동매매를 시작합니다.")
    public ResponseEntity autoTradingOpen(
              @RequestParam(required = false) String symbol
            , @RequestParam String interval
            , @RequestParam int leverage
            , @RequestParam int goalPricePercent
            , @RequestParam int stockSelectionCount
            , @RequestParam BigDecimal quoteAssetVolumeStandard) throws Exception {
        return commonUtils.okResponsePackaging(futureService.autoTradingOpen(symbol, interval, leverage, goalPricePercent, stockSelectionCount, quoteAssetVolumeStandard));
    }
    @GetMapping(value = "/future/stream/close")
    @Operation(summary="스트림을 클로즈합니다.", description="거래추적 스트림을 클로즈 합니다.")
    public void streamClose(@RequestParam int streamId) {
        futureService.streamClose(streamId);
    }

    @GetMapping(value = "/future/stock/selection")
    @Operation(summary="거래량과 변동폭 기준으로 종목을 가져옵니다.", description="거래량과 변동폭 기준으로 종목을 가져옵니다.")
    public ResponseEntity getStockSelection(@RequestParam int limit) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getStockSelection(limit));
    }

    @GetMapping(value = "/future/klines/")
    @Operation(summary="해당 심볼의 캔들 데이터를 가져옵니다(기본값 500개)", description="해당 심볼의 캔들 데이터를 가져옵니다.")
    public ResponseEntity getKlines(@RequestParam String symbol, @RequestParam String interval, @RequestParam int limit) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getKlines(symbol, interval, limit));
    }

    @GetMapping(value = "/future/auto/info")
    @Operation(summary="스트림 정보를 가져옵니다.", description="스트림 정보를 가져옵니다.")
    public ResponseEntity autoTradingInfo() throws Exception {
        return commonUtils.okResponsePackaging(futureService.autoTradingInfo());
    }

    @GetMapping(value = "/future/account/info")
    @Operation(summary="계좌 정보를 가져옵니다.", description="계좌 정보를 가져옵니다.")
    public ResponseEntity accountInfo() throws Exception {
        return commonUtils.okResponsePackaging(futureService.accountInfo());
    }
}