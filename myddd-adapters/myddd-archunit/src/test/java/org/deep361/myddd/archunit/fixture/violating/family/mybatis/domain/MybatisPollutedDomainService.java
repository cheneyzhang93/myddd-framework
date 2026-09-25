package org.deep361.myddd.archunit.fixture.violating.family.mybatis.domain;

import com.baomidou.mybatisplus.annotation.TableName;

/** 反例：领域服务直接使用 MyBatis-Plus 注解。 */
@TableName("order")
public class MybatisPollutedDomainService {

    public String describe() {
        return "领域服务被 MyBatis-Plus 污染";
    }
}
