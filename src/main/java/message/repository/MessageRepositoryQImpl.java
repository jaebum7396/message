package message.repository;

import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.extern.slf4j.Slf4j;
import message.model.entity.MessageEntity;
import message.model.entity.QMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;

@Repository
@Slf4j
public class MessageRepositoryQImpl implements MessageRepositoryQ {
    @PersistenceContext
    private EntityManager entityManager;
    @Override
    public Page<MessageEntity> findMessagesWithPageable(String topic, Pageable pageable) {
        log.info("ChatRepositoryQImpl.findChatsWithPageable");
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        //QChat chat = QChat.chat;
        QMessageEntity qMessage = new QMessageEntity("qChat");

        JPQLQuery<MessageEntity> query = queryFactory
                .selectFrom(qMessage)
                .where(qMessage.topic.eq(topic))
                .orderBy(qMessage.messageDt.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize());

        List<MessageEntity> messages = query.fetch();

        long count = queryFactory
                .select(qMessage.messageCd)
                .from(qMessage)
                .where(qMessage.topic.eq(topic))
                .fetchCount();

        return new PageImpl<>(messages, pageable, count);
    }
}