package trade.market.controller;

import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import trade.future.service.FutureService;

@Slf4j
@Api(tags = "MarketController")
@Tag(name = "MarketController", description = "시장컨트롤러")
@RestController
@RequiredArgsConstructor
public class MarketController {
    @Autowired FutureService futureService;
}