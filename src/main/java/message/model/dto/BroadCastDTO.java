package message.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.annotations.ApiModelProperty;
import lombok.*;
import message.model.entity.MessageEntity;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@ToString
public class BroadCastDTO {
    @ApiModelProperty(required = false)
    String symbol;

    @ApiModelProperty(value = "10", example = "10")
    int shortMovingPeriod; //
    @ApiModelProperty(value = "60", example = "60")
    int midMovingPeriod; //
    @ApiModelProperty(value = "120", example = "120")
    int longMovingPeriod; //

    public MessageEntity toEntity() {
        return MessageEntity.builder()
                .symbol(symbol)
                .shortMovingPeriod(shortMovingPeriod)
                .midMovingPeriod(midMovingPeriod)
                .longMovingPeriod(longMovingPeriod)
                .build();
    }
}
