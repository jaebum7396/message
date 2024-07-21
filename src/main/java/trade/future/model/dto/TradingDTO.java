package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModelProperty;
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
    @ApiModelProperty(required = false)
    String targetSymbol;
    @ApiModelProperty(required = false)
    String symbol;
    @ApiModelProperty(value = "5m", example = "5m")
    String interval;
    @ApiModelProperty(value = "20", example = "20")
    int leverage;
    @ApiModelProperty(value = "50", example = "50")
    int stockSelectionCount; // 종목 몇개를 확인할 것인지
    @ApiModelProperty(value = "10", example = "10")
    int maxPositionCount; // 최대 포지션 수
    @ApiModelProperty(value = "300", example = "300")
    int candleCount; //
    @ApiModelProperty(value = "1" , example = "0.95")
    BigDecimal collateralRate; //매매에 사용할 담보금 비율
    @ApiModelProperty(value = "-1" , example = "-1")
    int trendFollowFlag; // 1: trend follow, -1: trend reverse

    //strategyChecker
    @ApiModelProperty(value = "-1" , example = "-1")
    int bollingerBandChecker;
    @ApiModelProperty(value = "-1" , example = "-1")
    int adxChecker;
    @ApiModelProperty(value = "1" , example = "1")
    int macdHistogramChecker;
    @ApiModelProperty(value = "1" , example = "1")
    int macdCrossChecker;
    @ApiModelProperty(value = "-1" , example = "-1")
    int stochChecker;
    @ApiModelProperty(value = "1" , example = "1")
    int stochRsiChecker;
    @ApiModelProperty(value = "1" , example = "1")
    int rsiChecker;
    @ApiModelProperty(value = "1" , example = "1")
    int movingAverageChecker;

    public TradingEntity toEntity() {
        return TradingEntity.builder()
                .targetSymbol(targetSymbol)
                .symbol(symbol)
                .candleInterval(interval)
                .leverage(leverage)
                .stockSelectionCount(stockSelectionCount)
                .maxPositionCount(maxPositionCount)
                .candleCount(candleCount)
                .collateralRate(collateralRate)
                .trendFollowFlag(trendFollowFlag)
                //strategyChecker
                .bollingerBandChecker(bollingerBandChecker)
                .adxChecker(adxChecker)
                .macdHistogramChecker(macdHistogramChecker)
                .macdCrossChecker(macdCrossChecker)
                .stochChecker(stochChecker)
                .stochRsiChecker(stochRsiChecker)
                .rsiChecker(rsiChecker)
                .movingAverageChecker(movingAverageChecker)
                .build();
    }
}
