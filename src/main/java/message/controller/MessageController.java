package message.controller;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import message.model.dto.BroadCastDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import message.service.MessageService;

import javax.servlet.http.HttpServletRequest;

import static message.common.공통유틸.okResponsePackaging;

@Slf4j
@Api(tags = "MessageController")
@Tag(name = "MessageController", description = "MessageController")
@RestController
@RequiredArgsConstructor
public class MessageController {
    @Autowired
    MessageService messageService;

    @PostMapping(value = "/broadcast/scraping")
    @Operation(summary="캔들 데이타 스크래핑", description="캔들 데이타 스크래핑")
    public void klineScraping(HttpServletRequest request, @RequestBody BroadCastDTO broadCastDTO) throws Exception {
        messageService.klineScraping(request, broadCastDTO);
    }

    @PostMapping(value = "/broadcast/open")
    @Operation(summary="신호전파 시작", description="신호전파 시작")
    public ResponseEntity broadCastOpen(HttpServletRequest request, @RequestBody BroadCastDTO broadCastDTO) throws Exception {
        return okResponsePackaging(messageService.broadCastingOpen(request, broadCastDTO));
    }
}