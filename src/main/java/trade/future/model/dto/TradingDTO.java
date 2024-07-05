package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import org.springframework.web.bind.annotation.RequestParam;
import trade.common.CommonUtils;
import trade.future.model.entity.KlineEntity;
import trade.future.model.entity.TradingEntity;

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
    int stockSelectionCount; // 종목 몇개를 확인할 것인지
    int maxPositionCount; // 최대 포지션 수
    int trendFollowFlag; // 1: trend follow, -1: trend reverse
    int adxChecker;
    int macdHistogramChecker;
    int rsiChecker;
    BigDecimal collateralRate; //매매에 사용할 담보금 비율
    //int goalPricePercent;
    //BigDecimal quoteAssetVolumeStandard;
    public TradingEntity toEntity() {
        return TradingEntity.builder()
                .targetSymbol(symbol)
                .leverage(leverage)
                .candleInterval(interval)
                .stockSelectionCount(stockSelectionCount)
                .maxPositionCount(maxPositionCount)
                .trendFollowFlag(trendFollowFlag)
                .adxChecker(adxChecker)
                .macdHistogramChecker(macdHistogramChecker)
                .rsiChecker(rsiChecker)
                .collateralRate(collateralRate)
                .build();
    }
}
