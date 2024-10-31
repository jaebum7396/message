package message.controller;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import message.common.model.Response;
import message.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    @PostMapping(value = "/message/prev")
    @Operation(summary="이전 채팅 메시지 가져오기", description="이전 채팅 메시지 가져오기")
    public ResponseEntity<Response> getPrevMessages(HttpServletRequest request, @RequestParam String topic, Pageable page) throws Exception {
        return okResponsePackaging(messageService.getPrevMessages(request, topic, page));
    }
}