/**
 * 端口契约基类：把"两个厂商适配器必须给出同一组可观察结果"写成一等公民的测试类。
 *
 * <p>放在 main 而不是 test，是因为本仓禁止模块之间的 test 作用域相互依赖
 * （{@code myddd-mq-rocketmq} 不能 depend on {@code myddd-mq-kafka:test}）；
 * 消费方（各适配器模块）把自己的测试类标成 {@code extends} 这些基类即完成等价性绑定。
 *
 * <p>三条纪律：① 基类只引用 {@code myddd-kernel-*} 的类型，出现任何厂商类即为设计失败；
 * ② "怎么把身份/额度/任务装进 provider"是子类的缝（{@code protected abstract}），
 * "装完之后端口的行为"才是基类的断言；③ 每个基类要求 {@code xxx()} 端口缝**每个测试实例新建**
 * （JUnit 5 默认 per-method 实例），static 注册表会让用例之间互相污染并被变异测试检出。
 */
package org.deep361.myddd.testing.contract;
