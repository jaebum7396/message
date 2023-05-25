package user.repository;

import com.querydsl.core.types.SubQueryExpression;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import user.model.QUser;
import user.model.QUserInfo;
import user.model.QUserProfileImage;
import user.model.User;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class UserRepositoryQImpl implements UserRepositoryQ {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<User> findUsersWithPageable(String userCd, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QUser user = QUser.user;
        QUserInfo userInfo = QUserInfo.userInfo;
        QUserProfileImage userProfileImage = QUserProfileImage.userProfileImage;

        JPQLQuery<User> query = queryFactory
                .selectFrom(user)
                .join(user.userInfo, userInfo).fetchJoin()
                .leftJoin(userInfo.userProfileImages, userProfileImage).fetchJoin()
                .where(user.userCd.eq(userCd))
                .orderBy(user.userCd.asc(), user.userInfo.userNickNm.asc())
                //.offset(pageable.getOffset())
                //.limit(pageable.getPageSize())
                ;

        SubQueryExpression<LocalDateTime> maxInsertDTQuery = JPAExpressions
                .select(userProfileImage.insertDt.max())
                .from(user.userInfo)
                .join(userInfo.userProfileImages, userProfileImage)
                .where(userInfo.eq(user.userInfo));

        BooleanExpression hasUserProfileImage = userProfileImage.insertDt.eq(maxInsertDTQuery);
        BooleanExpression userProfileImageIsNull = user.userInfo.isNull().or(userInfo.userProfileImages.isEmpty());
        query.where(hasUserProfileImage.or(userProfileImageIsNull));
        List<User> users = query.fetch();

        System.out.println("users : " + users);

        long count = queryFactory
                .select(user.userCd)
                .from(user)
                .where(user.userCd.eq(userCd))
                .fetchCount();

        return new PageImpl<>(users, pageable, count);
    }
}