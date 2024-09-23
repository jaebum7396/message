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
import trade.future.service.FutureMLService;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;

import static trade.common.공통유틸.okResponsePackaging;

@Slf4j
@Api(tags = "FutureMLController")
@Tag(name = "FutureMLController", description = "선물머신러닝컨트롤러")
@RestController
@RequiredArgsConstructor
public class FutureMLController {
    @Autowired
    FutureMLService futureMLService;

    @PostMapping(value = "/future/ml/backtest")
    @Operation(summary="백테스팅", description="백테스팅")
    public ResponseEntity backTest(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        return okResponsePackaging(futureMLService.backTest(request, tradingDTO));
    }

    @PostMapping(value = "/future/ml/scraping/test")
    @Operation(summary="스크래핑", description="스크래핑")
    public void scrapingTest(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        futureMLService.scrapingTest(request, tradingDTO);
    }

    @PostMapping(value = "/future/ml/open")
    @Operation(summary="자동매매를 시작합니다.", description="자동매매를 시작합니다.")
    public ResponseEntity autoTradingOpen(HttpServletRequest request, @RequestBody TradingDTO tradingDTO) throws Exception {
        return okResponsePackaging(futureMLService.autoTradingOpen(request, tradingDTO));
    }

    @GetMapping(value = "/future/ml/close")
    @Operation(summary="자동매매를 종료합니다.", description="모든 포지션을 종료합니다.")
    public void closeAllPositions() {
        futureMLService.closeAllPositions();
    }
}