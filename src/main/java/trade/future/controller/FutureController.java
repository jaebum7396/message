package trade.future.controller;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import trade.future.model.dto.TradingDTO;
import trade.future.service.FutureService;

import javax.servlet.http.HttpServletRequest;

import static trade.common.공통유틸.okResponsePackaging;

@Slf4j
@Api(tags = "FutureMLController")
@Tag(name = "FutureMLController", description = "선물머신러닝컨트롤러")
@RestController
@RequiredArgsConstructor
public class FutureController {
    @Autowired
    FutureService futureService;

    @PostMapping(value = "/future/backtest")
    @Operation(summary="백테스팅", description="백테스팅")
    public ResponseEntity backTest(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        return okResponsePackaging(futureService.backTest(request, tradingDTO));
    }

    @PostMapping(value = "/future/scraping/test")
    @Operation(summary="스크래핑", description="스크래핑")
    public void scrapingTest(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        futureService.scrapingTest(request, tradingDTO);
    }

    @PostMapping(value = "/future/open")
    @Operation(summary="자동매매를 시작합니다.", description="자동매매를 시작합니다.")
    public ResponseEntity autoTradingOpen(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        return okResponsePackaging(futureService.autoTradingOpen(request, tradingDTO));
    }

    @GetMapping(value = "/future/close")
    @Operation(summary="자동매매를 종료합니다.", description="모든 포지션을 종료합니다.")
    public void closeAllPositions() {
        futureService.closeAllPositions();
    }
}