package message.repository;

import message.model.entity.MessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MessageRepositoryQ {
    Page<MessageEntity> findMessagesWithPageable(String topic, Pageable pageable);
}
