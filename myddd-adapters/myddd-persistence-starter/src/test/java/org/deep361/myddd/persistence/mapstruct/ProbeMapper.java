package org.deep361.myddd.persistence.mapstruct;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 基座的最小使用形态：一行 config 拿到全部框架约定。 */
@Mapper(config = MydddMapperConfig.class)
interface ProbeMapper {

    @Mapping(target = "secret", ignore = true)
    ProbeDst toDst(ProbeSrc src);
}
