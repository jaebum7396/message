package message.repository;

import message.model.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, String>, QuerydslPredicateExecutor<MessageEntity>, MessageRepositoryQ {
    List<MessageEntity> findBySymbolAndBroadCastStatus(String symbol, String broad);
    Optional<MessageEntity> findByStreamId(int streamId);
}