package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import trade.common.CommonUtils;
import trade.future.model.entity.KlineEntity;
import trade.future.model.entity.KlineEventEntity;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@ToString
public class KlineEventDTO {
    private String e; // 이벤트 타입
    private long E; // 이벤트 시간
    private String s; // 심볼
    private KlineDTO k;

    public KlineEventEntity toEntity() {
        KlineEventEntity entity = new KlineEventEntity();
        entity.setEventType(this.e);
        entity.setEventTime(CommonUtils.convertTimestampToDateTime(this.E));
        KlineEntity kEntity = this.k.toEntity();
        kEntity.setKlineEvent(entity);
        entity.setKlineEntity(kEntity);
        return entity;
    }
}


