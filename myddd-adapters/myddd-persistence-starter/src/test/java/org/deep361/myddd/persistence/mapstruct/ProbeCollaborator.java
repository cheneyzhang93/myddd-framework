package org.deep361.myddd.persistence.mapstruct;

/** 夹具协作者：证明"有依赖的 mapper"按构造器注入（测试 3）。 */
class ProbeCollaborator {

    String decorate(String value) {
        return value == null ? null : "<" + value + ">";
    }
}
