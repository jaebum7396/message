package trade.future.controller;

import com.binance.connector.client.WebSocketApiClient;
import com.binance.connector.client.impl.WebSocketApiClientImpl;
import io.swagger.annotations.Api;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import trade.future.service.FutureService;

@Slf4j
@Api(tags = "FutureController")
@Tag(name = "FutureController", description = "선물컨트롤러")
@RestController
@RequiredArgsConstructor
public class FutureController {
    @Autowired FutureService futureService;

}