package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModelProperty;
import lombok.*;
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
    @ApiModelProperty(value = "3", example = "3")
    int leverage;
    @ApiModelProperty(value = "50", example = "50")
    int stockSelectionCount; // 종목 몇개를 확인할 것인지
    @ApiModelProperty(value = "10", example = "10")
    int maxPositionCount; // 최대 포지션 수

    @ApiModelProperty(value = "20", example = "20")
    int shortMovingPeriod; //
    @ApiModelProperty(value = "30", example = "30")
    int midMovingPeriod; //
    @ApiModelProperty(value = "50", example = "50")
    int longMovingPeriod; //

    @ApiModelProperty(value = "1" , example = "0.95")  //매매에 사용할 담보금 비율
    BigDecimal collateralRate;

    @ApiModelProperty(value = "-1" , example = "-1")
    int stopLossChecker;
    @ApiModelProperty(value = "2" , example = "2")
    int stopLossRate;
    @ApiModelProperty(value = "-1" , example = "-1")
    int takeProfitChecker;
    @ApiModelProperty(value = "3" , example = "3")
    int takeProfitRate;

    @ApiModelProperty(value = "0" , example = "0")
    double priceChangeThreshold;

    public TradingEntity toEntity() {
        return TradingEntity.builder()
                .targetSymbol(targetSymbol)
                .symbol(symbol)
                .leverage(leverage)
                .stockSelectionCount(stockSelectionCount)
                .maxPositionCount(maxPositionCount)
                .shortMovingPeriod(shortMovingPeriod)
                .midMovingPeriod(midMovingPeriod)
                .longMovingPeriod(longMovingPeriod)
                .collateralRate(collateralRate)
                .stopLossChecker(stopLossChecker)
                .stopLossRate(stopLossRate)
                .takeProfitChecker(takeProfitChecker)
                .takeProfitRate(takeProfitRate)
                .priceChangeThreshold(priceChangeThreshold)
                .build();
    }
}
