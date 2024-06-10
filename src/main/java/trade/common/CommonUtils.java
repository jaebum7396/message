package trade.common;

import org.json.JSONArray;
import org.json.JSONObject;
import trade.common.model.Response;
import trade.configuration.JacksonConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import trade.future.model.dto.KlineDTO;
import trade.future.model.dto.EventDTO;
import trade.future.model.entity.KlineEntity;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CommonUtils {
    @Value("${jwt.secret.key}")
    private String JWT_SECRET_KEY;

    /**
     * <h3>성공 응답을 포장하는 ResponseEntity를 생성합니다.</h3>
     */
    public ResponseEntity<Response> okResponsePackaging(Map<String, Object> result) {
        Response response = Response.builder()
                .message("요청 성공")
                .result(result).build();
        return ResponseEntity.ok().body(response);
    }

    /**
     * <h3>HttpServletRequest의 인증 헤더에서 JWT 클레임을 추출하고 파싱합니다.</h3>
     */
    public Claims getClaims(HttpServletRequest request){
        try{
            Key secretKey = Keys.hmacShaKeyFor(JWT_SECRET_KEY.getBytes(StandardCharsets.UTF_8));
            Claims claim = Jwts.parserBuilder().setSigningKey(secretKey).build()
                    .parseClaimsJws(request.getHeader("authorization")).getBody();
            return claim;
        } catch (ExpiredJwtException e) {
            throw new ExpiredJwtException(null, null, "로그인 시간이 만료되었습니다.");
        } catch (Exception e) {
            throw new BadCredentialsException("인증 정보에 문제가 있습니다.");
        }
    }

    /**
     * <h3>HttpServletRequest의 파라미터 맵을 변환합니다.</h3>
     */
    public static HashMap<String, Object> convertRequestParameterMap(HttpServletRequest request) {
        HashMap<String, Object> mapParam = new HashMap<String, Object>();
        try {
            // 파라미터 추가
            Enumeration<String> params = request.getParameterNames();
            while (params.hasMoreElements()) {
                String key = params.nextElement();
                System.out.println("key : " +request.getParameter(key));
                mapParam.put(key, request.getParameter(key));
            }

        } catch (Exception e) {
            log.debug("{}", e.toString());
        }
        return mapParam;
    }

    /**
     * <h3>객체를 JSON 형태의 문자열로 반환합니다.</h3>
     */
    public static String getJsonPretty(Object p_obj) {
        String strReturn = "";
        try {
            if (p_obj != null) {
                strReturn = CommonUtils.getString(JacksonConfig.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(p_obj));
            }
        } catch (Exception e) {
            log.debug("{}", e.toString());
        }
        return strReturn;
    }

    /**
     * <h3>객체를 문자열로 변환합니다.</h3>
     */
    public static String getString(Object p_Object) {
        String strReturn = "";

        try {
            if (p_Object != null) {
                strReturn = StringUtils.trimToEmpty(p_Object.toString());
            }

        } catch (Exception e) {
            log.debug("{}", e.toString());
        }
        return strReturn;
    }

    /**
     * <h3>요청 바디를 문자열로 읽어옵니다.</h3>
     */
    public static String readRequestBody(CachedBodyHttpServletWrapper requestWrapper) {
        try (BufferedReader reader = requestWrapper.getReader()) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    /**
     * <h3>타임스탬프를 LocalDateTime으로 변환합니다.</h3>
     */
    public static LocalDateTime convertTimestampToDateTime(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /**
     * <h3>BigDecimal dollar 포맷.</h3>
     */
    public static String getDollarFormat(String amount) {
        // 숫자 포맷 지정
        DecimalFormat df = new DecimalFormat("#,###.##");
        return "$" + df.format(Double.parseDouble(amount));
    }

    public static KlineEntity parseKlineEntity(JSONArray klineArray) {
        LocalDateTime startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(klineArray.getLong(0)), ZoneOffset.UTC);
        LocalDateTime endTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(klineArray.getLong(6)), ZoneOffset.UTC);

        return KlineEntity.builder()
                .kLineCd(null) // ID는 자동 생성
                .startTime(startTime)
                .endTime(endTime)
                .symbol("BTCUSDT") // 심볼은 주어진 데이터에 없으므로 임의로 지정
                .candleInterval("1m") // 인터벌도 임의로 지정
                .firstTradeId(null) // 데이터에 포함되지 않으므로 null로 설정
                .lastTradeId(null) // 데이터에 포함되지 않으므로 null로 설정
                .openPrice(new BigDecimal(klineArray.getString(1)))
                .closePrice(new BigDecimal(klineArray.getString(4)))
                .highPrice(new BigDecimal(klineArray.getString(2)))
                .lowPrice(new BigDecimal(klineArray.getString(3)))
                .volume(new BigDecimal(klineArray.getString(5)))
                .tradeCount(klineArray.getInt(8))
                .isClosed(true) // 데이터에 포함되지 않으므로 임의로 true로 설정
                .quoteAssetVolume(new BigDecimal(klineArray.getString(7)))
                .takerBuyBaseAssetVolume(new BigDecimal(klineArray.getString(9)))
                .takerBuyQuoteAssetVolume(new BigDecimal(klineArray.getString(10)))
                .ignoreField(klineArray.getInt(11))
                .build();
    }

    public static EventDTO convertKlineEventDTO(String event) {
        JSONObject eventObj = new JSONObject(event);
        JSONObject klineEventObj = new JSONObject(eventObj.get("data").toString());
        JSONObject klineObj = new JSONObject(klineEventObj.get("k").toString());
        EventDTO eventDTO = EventDTO.builder()
            .e(klineEventObj.get("e").toString())
            .E(Long.parseLong(klineEventObj.get("E").toString()))
            .s(klineEventObj.get("s").toString())
            .k(KlineDTO.builder()
                .t(Long.parseLong(klineObj.get("t").toString()))
                .T(Long.parseLong(klineObj.get("T").toString()))
                .s(klineObj.get("s").toString())
                .i(klineObj.get("i").toString())
                .f(new BigDecimal(klineObj.get("f").toString()))
                .L(new BigDecimal(klineObj.get("L").toString()))
                .o(new BigDecimal(klineObj.get("o").toString()))
                .c(new BigDecimal(klineObj.get("c").toString()))
                .h(new BigDecimal(klineObj.get("h").toString()))
                .l(new BigDecimal(klineObj.get("l").toString()))
                .v(new BigDecimal(klineObj.get("v").toString()))
                .n(Integer.parseInt(klineObj.get("n").toString()))
                .x(Boolean.parseBoolean(klineObj.get("x").toString()))
                .q(new BigDecimal(klineObj.get("q").toString()))
                .V(new BigDecimal(klineObj.get("V").toString()))
                .Q(new BigDecimal(klineObj.get("Q").toString()))
                .B(Integer.parseInt(klineObj.get("B").toString()))
                .build()
            ).build();
        return eventDTO;
    }

    // x배율 롱포지션에서 n% 이득을 보는 가격 계산
    public static BigDecimal calculateLongPositionGoalPrice(BigDecimal currentPrice, int leverage, int profitPercentage) {
        // n% 이득을 보려면 목표 가격(targetPrice)를 다음과 같이 계산할 수 있습니다.
        // 이익 퍼센트를 실수로 변환
        BigDecimal profitMultiplier = BigDecimal.ONE.add(new BigDecimal(profitPercentage).divide(new BigDecimal(100)));
        // 목표 이익 금액 계산
        BigDecimal profitAmount = currentPrice.multiply(profitMultiplier).subtract(currentPrice);
        // 목표 가격 계산
        BigDecimal targetPrice = currentPrice.add(profitAmount);
        // 레버리지로 나누어 목표 가격을 조정
        targetPrice = targetPrice.divide(new BigDecimal(leverage), 2, BigDecimal.ROUND_HALF_UP);

        return targetPrice;
    }

    // x배율 숏포지션에서 n% 이득을 보는 가격 계산
    public static BigDecimal calculateShortPositionGoalPrice(BigDecimal currentPrice, int leverage, int profitPercentage) {
        // n% 이득을 보려면 목표 가격(targetPrice)를 다음과 같이 계산할 수 있습니다.
        // 이익 퍼센트를 실수로 변환
        BigDecimal profitMultiplier = BigDecimal.ONE.subtract(new BigDecimal(profitPercentage).divide(new BigDecimal(100)));
        // 목표 이익 금액 계산
        BigDecimal profitAmount = currentPrice.subtract(currentPrice.multiply(profitMultiplier));
        // 목표 가격 계산
        BigDecimal targetPrice = currentPrice.subtract(profitAmount);
        // 레버리지로 나누어 목표 가격을 조정
        targetPrice = targetPrice.divide(new BigDecimal(leverage), 2, BigDecimal.ROUND_HALF_UP);

        return targetPrice;
    }

    public static BigDecimal calculateGoalPrice(BigDecimal currentPrice, String positionSide, int leverage, int goalProfitPercentage) {
        if (leverage < 1) {
            throw new IllegalArgumentException("레버리지는 1보다 커야 합니다.");
        }

        BigDecimal multiplier = new BigDecimal(goalProfitPercentage).divide(new BigDecimal(100));
        BigDecimal targetPrice;

        if ("LONG".equalsIgnoreCase(positionSide)) {
            // 롱 포지션인 경우
            targetPrice = currentPrice.add(currentPrice.multiply(multiplier).divide(new BigDecimal(leverage), 8, RoundingMode.HALF_UP));
        } else if ("SHORT".equalsIgnoreCase(positionSide)) {
            // 숏 포지션인 경우
            targetPrice = currentPrice.subtract(currentPrice.multiply(multiplier).divide(new BigDecimal(leverage), 8, RoundingMode.HALF_UP));
        } else {
            throw new IllegalArgumentException("올바른 포지션 사이드를 입력하세요 (LONG 또는 SHORT).");
        }

        return targetPrice;
    }
}
