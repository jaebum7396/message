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
    int goalPricePercent;
    int stockSelectionCount;
    BigDecimal quoteAssetVolumeStandard;
    int maxPositionCount;
}
