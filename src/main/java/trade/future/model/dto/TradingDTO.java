package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import org.springframework.web.bind.annotation.RequestParam;
import trade.common.CommonUtils;
import trade.future.model.entity.KlineEntity;

import java.math.BigDecimal;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@ToString
public class TradingDTO {
    String symbol;
    String interval;
    int leverage;
    int trendFollowFlag; // 1: trend follow, -1: trend reverse
    int stockSelectionCount; // 종목 몇개를 확인할 것인지
    int maxPositionCount; // 최대 포지션 수
    boolean adxChecker;
    boolean macdHistogramChecker;
    boolean rsiChecker;
    BigDecimal colleteralRate; //매매에 사용할 담보금 비율
    //int goalPricePercent;
    //BigDecimal quoteAssetVolumeStandard;
}
