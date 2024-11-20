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

        // 1. 전체 메시지 중 최신 순으로 페이지네이션
        JPQLQuery<MessageEntity> subQuery = queryFactory
                .selectFrom(qMessage)
                .where(qMessage.topic.eq(topic))
                .orderBy(qMessage.insertDt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize());

        // 2. 서브쿼리로 조회된 ID 목록을 기반으로 다시 조회하여 오래된 순으로 정렬
        List<String> messageIds = subQuery.select(qMessage.messageCd).fetch();

        JPQLQuery<MessageEntity> finalQuery = queryFactory
                .selectFrom(qMessage)
                .where(qMessage.messageCd.in(messageIds))
                .orderBy(qMessage.insertDt.asc());

        List<MessageEntity> messages = finalQuery.fetch();

        long count = queryFactory
                .select(qMessage.count()) // count() 프로젝션 사용
                .from(qMessage)
                .where(qMessage.topic.eq(topic))
                .fetchOne();  // count 쿼리는 단일 결과를 반환하므로 fetchOne() 사용

        return new PageImpl<>(messages, pageable, count);
    }
}