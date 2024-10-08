package signal.broadcast.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import signal.common.model.BaseEntity;
import signal.broadcast.model.dto.BroadCastDTO;

import javax.persistence.*;
import java.io.Serializable;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "BROADCAST")
public class BroadCastEntity extends BaseEntity implements Serializable, Cloneable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "BROADCAST_CD")
    private String broadCastCd; // ID 필드 추가 (데이터베이스 식별자)

    @Column( name = "SYMBOL")
    private String symbol; // 거래 페어

    @Column( name = "BROADCAST_STATUS")
    private String broadCastStatus; // 신호 전파 상태

    @Column( name = "STREAM_ID")
    private int streamId; // 스트림 ID

    @Column( name = "SHORT_MOVING_PERIOD")
    int shortMovingPeriod;

    @Column( name = "MID_MOVING_PERIOD")
    int midMovingPeriod;

    @Column( name = "LONG_MOVING_PERIOD")
    int longMovingPeriod;

    public BroadCastDTO toDTO() {
        return BroadCastDTO.builder()
                .symbol(this.symbol)
                .build();
    }

    // 복사 생성자
    public BroadCastEntity(BroadCastEntity original) {
        this.symbol = original.symbol;
        this.streamId = original.streamId;
        this.shortMovingPeriod = original.shortMovingPeriod;
        this.midMovingPeriod = original.midMovingPeriod;
        this.longMovingPeriod = original.longMovingPeriod;
    }

    @Override
    public BroadCastEntity clone() {
        return new BroadCastEntity(this);
    }
}