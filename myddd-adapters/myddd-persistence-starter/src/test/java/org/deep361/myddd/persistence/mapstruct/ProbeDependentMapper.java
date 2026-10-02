package org.deep361.myddd.persistence.mapstruct;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 带 {@code uses} 的同款 mapper：只用于测注入形态，不测装饰行为。 */
@Mapper(config = MydddMapperConfig.class, uses = ProbeCollaborator.class)
interface ProbeDependentMapper {

    @Mapping(target = "secret", ignore = true)
    ProbeDst toDst(ProbeSrc src);
}
