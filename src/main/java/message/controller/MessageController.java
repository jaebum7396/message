package message.controller;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import message.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Api(tags = "MessageController")
@Tag(name = "MessageController", description = "MessageController")
@RestController
@RequiredArgsConstructor
public class MessageController {
    @Autowired
    MessageService messageService;
}