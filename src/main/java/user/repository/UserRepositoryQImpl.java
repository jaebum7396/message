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
import user.model.*;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepositoryQImpl implements UserRepositoryQ {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<User> findUsersWithPageable(String queryString, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QUser user = QUser.user;
        QUserInfo userInfo = QUserInfo.userInfo;
        QUserProfileImage userProfileImage = QUserProfileImage.userProfileImage;

        JPQLQuery<User> query = queryFactory
                .selectFrom(user)
                .join(user.userInfo, userInfo).fetchJoin()
                .leftJoin(userInfo.userProfileImages, userProfileImage).fetchJoin()
                .where(user.userId.eq(queryString)
                        .or(user.userNm.eq(queryString))
                        .or(user.userPhoneNo.eq(queryString))
                        .or(userInfo.userNickNm.eq(queryString)))
                .orderBy(user.userCd.asc(), user.userInfo.userNickNm.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                ;

        List<User> users = query
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        long count = query
                .fetchCount();

        return new PageImpl<>(users, pageable, count);
    }

    @Override
    public Optional<User> getMyInfo(String userId) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);

        QUser user = QUser.user;
        QUserInfo userInfo = QUserInfo.userInfo;
        QUserProfileImage userProfileImage = QUserProfileImage.userProfileImage;

        User userEntity = queryFactory
                .selectFrom(user)
                .leftJoin(user.userInfo, userInfo).fetchJoin()
                .leftJoin(userInfo.userProfileImages, userProfileImage)
                .where(user.userId.eq(userId))
                .orderBy(userProfileImage.insertDt.desc())
                .fetchFirst();

        System.out.println(userEntity.toString());

        // userProfileImage가 없는 경우를 처리하기 위해 null 체크
        //if (userEntity != null && userEntity.getUserInfo() != null) {
        //    UserProfileImage latestImage = userEntity.getUserInfo().getUserProfileImages().get(0);
        //    userEntity.getUserInfo().setUserProfileImages(Collections.singletonList(latestImage));
        //}

        return userEntity == null ? Optional.empty() : Optional.of(userEntity);
    }
}