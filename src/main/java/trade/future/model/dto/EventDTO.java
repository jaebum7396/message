package trade.future.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.ToString;
import trade.future.model.entity.EventEntity;
import trade.future.model.entity.KlineEntity;

import static trade.common.공통유틸.convertTimestampToDateTime;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@ToString
public class EventDTO {
    private String e; // 이벤트 타입
    private long E; // 이벤트 시간
    private String s; // 심볼
    private KlineDTO k;

    public EventEntity toEntity() {
        EventEntity entity = new EventEntity();
        entity.setEventType(this.e);
        entity.setEventTime(convertTimestampToDateTime(this.E));
        KlineEntity kEntity = this.k.toEntity();
        kEntity.setEvent(entity);
        entity.setKlineEntity(kEntity);
        return entity;
    }
}


