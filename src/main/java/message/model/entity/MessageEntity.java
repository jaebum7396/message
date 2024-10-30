package message.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import message.common.model.BaseEntity;

import javax.persistence.*;
import java.io.Serializable;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper=false)
@Entity(name = "MESSAGE")
public class MessageEntity extends BaseEntity implements Serializable, Cloneable {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    @Column( name = "MESSAGE_CD")
    private String messageCd; // ID 필드 추가 (데이터베이스 식별자)

    @Column( name = "USER_CD")
    private String userCd; //

    @Column( name = "TOPIC")
    private String topic; //

    @Column( name = "PAYLOAD")
    private String payload; //
}