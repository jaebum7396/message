package signal.broadcast.controller;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import signal.broadcast.model.dto.BroadCastDTO;
import signal.broadcast.service.BroadCastService;

import javax.servlet.http.HttpServletRequest;

import static signal.common.공통유틸.okResponsePackaging;

@Slf4j
@Api(tags = "FutureMLController")
@Tag(name = "FutureMLController", description = "선물머신러닝컨트롤러")
@RestController
@RequiredArgsConstructor
public class BroadCastController {
    @Autowired
    BroadCastService broadCastService;

    @PostMapping(value = "/future/scraping/test")
    @Operation(summary="스크래핑", description="스크래핑")
    public void scrapingTest(HttpServletRequest request, @RequestBody BroadCastDTO broadCastDTO) throws Exception {
        broadCastService.scrapingTest(request, broadCastDTO);
    }

    @PostMapping(value = "/signal/broadcast/open")
    @Operation(summary="신호 전파를 시작합니다.", description="신호 전파를 시작합니다.")
    public ResponseEntity broadCastOpen(HttpServletRequest request, @RequestBody BroadCastDTO broadCastDTO) throws Exception {
        return okResponsePackaging(broadCastService.broadCastingOpen(request, broadCastDTO));
    }
}