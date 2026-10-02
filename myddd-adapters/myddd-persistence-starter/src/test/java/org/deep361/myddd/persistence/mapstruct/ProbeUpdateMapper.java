package org.deep361.myddd.persistence.mapstruct;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** 部分更新：源端为 null 的属性不该把目标写空（测试 5）。 */
@Mapper(config = MydddMapperConfig.class)
interface ProbeUpdateMapper {

    @Mapping(target = "secret", ignore = true)
    void update(ProbeSrc src, @MappingTarget ProbeDst dst);
}
