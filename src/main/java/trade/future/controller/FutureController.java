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
import trade.future.model.dto.TradingDTO;
import trade.future.service.FutureService;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.UUID;

@Slf4j
@Api(tags = "FutureController")
@Tag(name = "FutureController", description = "선물컨트롤러")
@RestController
@RequiredArgsConstructor
public class FutureController {
    @Autowired FutureService futureService;
    @Autowired CommonUtils commonUtils;

    @PostMapping(value = "/future/auto/open")
    @Operation(summary="자동매매를 시작합니다.", description="자동매매를 시작합니다.")
    public ResponseEntity autoTradingOpen(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        return commonUtils.okResponsePackaging(futureService.autoTradingOpen(request, tradingDTO));
    }
    @GetMapping(value = "/future/positions/close")
    @Operation(summary="모든 포지션을 종료합니다.", description="모든 포지션을 종료합니다.")
    public void closeAllPositions() {
        futureService.closeAllPositions();
    }

    @GetMapping(value = "/future/stock/selection")
    @Operation(summary="거래량과 변동폭 기준으로 종목을 선정합니다.", description="거래량과 변동폭 기준으로 종목을 선정합니다.")
    public ResponseEntity getStockSelection(@RequestParam int limit) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getStockSelection(limit));
    }
    @PostMapping(value = "/future/stock/find")
    @Operation(summary="거래량 추세를 기준으로 종목을 선정합니다.", description="거래량과 추세를 기준으로 종목을 선정합니다.")
    public ResponseEntity getStockFind(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getStockFind(request, tradingDTO));
    }
    @PostMapping(value = "/future/klines")
    @Operation(summary="해당 심볼의 캔들 데이터를 가져옵니다(기본값 500개)", description="해당 심볼의 캔들 데이터를 가져옵니다.")
    public ResponseEntity getKlines(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getKlines(request, tradingDTO));
    }
    @GetMapping(value = "/future/trading/streams")
    @Operation(summary="스트림 정보를 가져옵니다.", description="스트림 정보를 가져옵니다.")
    public ResponseEntity autoTradingInfo(HttpServletRequest request) throws Exception {
        return commonUtils.okResponsePackaging(futureService.autoTradingInfo(request));
    }
    @PostMapping(value = "/future/stream/close/all")
    @Operation(summary="모든 스트림을 종료합니다.", description="모든 스트림을 종료합니다.")
    public void allStreamClose(HttpServletRequest request) {
        futureService.allStreamClose();
    }

    @PostMapping(value = "/future/stream/close")
    @Operation(summary="스트림을 클로즈합니다.", description="거래추적 스트림을 클로즈 합니다.")
    public void streamClose(HttpServletRequest request, @RequestParam int streamId) {
        futureService.streamClose(streamId);
    }
    @GetMapping(value = "/future/event")
    @Operation(summary="해당 심볼의 이벤트들을 가져옵니다", description="해당 심볼의 이벤트들을 가져옵니다")
    public ResponseEntity getEvent(@RequestParam String symbol) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getEvent(symbol));
    }
    @GetMapping(value = "/future/reports")
    @Operation(summary="해당 트레이딩의 리포트들을 가져옵니다", description="해당 트레이딩의 리포트들을 가져옵니다")
    public ResponseEntity getReports(@RequestParam String tradingCd) throws Exception {
        return commonUtils.okResponsePackaging(futureService.getReports(tradingCd));
    }
    @GetMapping(value = "/future/account/info")
    @Operation(summary="계좌 정보를 가져옵니다.", description="계좌 정보를 가져옵니다.")
    public ResponseEntity accountInfo() throws Exception {
        return commonUtils.okResponsePackaging(futureService.accountInfo());
    }
    @PostMapping(value = "/future/order/submit")
    @Operation(summary="주문제출", description="주문제출")
    public ResponseEntity orderSubmit(HttpServletRequest request, @RequestBody LinkedHashMap<String,Object> requestParam) throws Exception {
        return commonUtils.okResponsePackaging(futureService.orderSubmit(request, requestParam));
    }
    @PostMapping(value = "/future/order/submit/collateral")
    @Operation(summary="자산기준 주문제출", description="자산기준 주문제출")
    public ResponseEntity orderSubmitCollateral(HttpServletRequest request, @RequestBody LinkedHashMap<String,Object> requestParam) throws Exception {
        return commonUtils.okResponsePackaging(futureService.orderSubmitCollateral(request, requestParam));
    }
}