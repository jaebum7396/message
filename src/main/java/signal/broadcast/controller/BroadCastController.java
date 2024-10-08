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
@Api(tags = "BroadCastController")
@Tag(name = "BroadCastController", description = "BroadCastController")
@RestController
@RequiredArgsConstructor
public class BroadCastController {
    @Autowired
    BroadCastService broadCastService;

    @PostMapping(value = "/broadcast/scraping")
    @Operation(summary="캔들 데이타 스크래핑", description="캔들 데이타 스크래핑")
    public void klineScraping(HttpServletRequest request, @RequestBody BroadCastDTO broadCastDTO) throws Exception {
        broadCastService.klineScraping(request, broadCastDTO);
    }

    @PostMapping(value = "/broadcast/open")
    @Operation(summary="신호전파 시작", description="신호전파 시작")
    public ResponseEntity broadCastOpen(HttpServletRequest request, @RequestBody BroadCastDTO broadCastDTO) throws Exception {
        return okResponsePackaging(broadCastService.broadCastingOpen(request, broadCastDTO));
    }
}