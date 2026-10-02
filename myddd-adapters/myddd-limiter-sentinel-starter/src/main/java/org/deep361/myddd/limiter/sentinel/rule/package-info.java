/**
 * Sentinel 规则文档的读取、校验与下发。
 *
 * <p><b>三张互不重叠的网</b>：读取器（Step 4）只判形态——是不是对象、字段认不认识、类型对不对；
 * 校验器（Step 5a）只判语义——这个值在两个端口的形态下能不能真生效；下发后的回读核验（Step 5b）
 * 判"厂商到底收没收"。三张网各自独立存在，是因为厂商对非法规则的处理是<b>静默丢弃</b>
 * （实测 {@code count=0}：{@code isValidRule=false}、{@code getRules()} 少一条、不报错不日志），
 * 所以"厂商认识"与"能生效"是两件事，而"我们收下"与"厂商装了"又是另一件事。
 *
 * <p><b>字段面为什么这么窄</b>：规则文件能写的东西必须运行期真做点什么。一手实测把下面这些
 * 形态判了死刑，于是它们连字段都没有，写出来会当成未知字段拒收（消息里给出这一条能写的字段全集）：
 * {@code grade=0}（线程数，立即 exit 记账下永不拒绝）、{@code strategy=1/2}（关联/链路，端口调用
 * 里没有那个上下文）、{@code limitApp≠default}（无来源时静默失效）、{@code controlBehavior=1/2/3}
 * （预热或排队，前者按预热期放大放行、后者实测阻塞 1009ms）、{@code clusterMode}（要独立的
 * token server）、degrade 的 {@code exception-count} 形态（连续失败到阈值后仍然放行，开闸时刻
 * 不可预测）。
 *
 * <p><b>清空再发布只用于熔断段</b>：同一份 degrade 表重发是 no-op（实测读数为"仍然开闸"），
 * 所以 {@code SentinelRulePublisher} 对 degrade 先清空再整表替换；flow 段直接替换，不在两次
 * {@code loadRules} 之间开出一个"这一瞬不限流"的窗口。清空也清不掉 QPS 计数（实测重发之后额度
 * 并没有回来），所以窗口翻篇只能靠真等——Step 9 的两份契约子类就是这么实现的。
 */
package org.deep361.myddd.limiter.sentinel.rule;
