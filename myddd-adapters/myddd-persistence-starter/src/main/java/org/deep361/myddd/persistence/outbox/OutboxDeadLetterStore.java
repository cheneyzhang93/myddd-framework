package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.domain.outbox.OutboxRecord;
import org.deep361.myddd.kernel.common.page.PageQuery;
import org.deep361.myddd.kernel.common.page.PageResult;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 死信（{@link org.deep361.myddd.domain.outbox.OutboxStatus#DEAD}）的读侧 API：分页查询与重放。
 *
 * <p><b>为什么它是 persistence-starter 里的新类型，而不是 {@code OutboxStore} 上的两个新方法</b>：
 * <ol>
 *   <li><b>职责方向</b>：{@code OutboxStore} 是投递侧的 SPI（写、原子认领、状态覆盖），由投递器线程高频调用，
 *       并被设计成"换一家存储只需实现三个方法"。分页、时间窗、{@code in (...)} 是运维语义，塞进去会让每个
 *       实现者都被迫背上它。</li>
 *   <li><b>读者不同</b>：本接口由运维入口（Plan 4 的管理端点）低频调用，返回值要带总数、按时间倒序；
 *       {@code claimDispatchable} 要的恰好相反（先进先出、不要总数）。</li>
 *   <li><b>契约稳定</b>：往已交付的 SPI 上加方法是二进制不兼容变更，本接口是纯新增。</li>
 * </ol>
 *
 * <p><b>本接口只到"数据"为止</b>：谁在什么时候重放了哪条记录属审计范畴（由 Plan 4 的入口留痕），
 * 这一层不做，也不假装做。
 */
public interface OutboxDeadLetterStore {

    /**
     * 按创建时间倒序分页查询死信（最新一条在第一页）。
     *
     * @param from 时间窗下界，含（{@code created_at >= from}）；null 表示不设下界
     * @param to   时间窗上界，<b>不含</b>（{@code created_at < to}）；null 表示不设上界。左闭右开让
     *             "按小时切片逐段处理"的运维脚本不会把边界那一秒处理两次
     * @param page 分页参数；页大小再被 {@link #queryLimit()} 截一次（超出归一，不报错）
     * @return 永不为 null；无匹配时 {@code items} 为空、{@code total} 为 0
     * @throws org.deep361.myddd.kernel.common.error.FrameworkException {@code to} 不晚于 {@code from}
     */
    PageResult<OutboxRecord> findDead(Instant from, Instant to, PageQuery page);

    /**
     * 把指定记录从 {@code DEAD} 重置回 {@code PENDING}，交给投递器重投。
     *
     * <p><b>只碰死信</b>：谓词带 {@code status = 'DEAD'}，所以传一个正在投递中（{@code DISPATCHING}）或已
     * 投递成功的 id 不会把它拽回队列——那会造成同一事件被投两次。因此返回值可能小于入参条数，差值就是
     * "当前不是死信"的条数；调用方（运维界面）按差值提示，而不是由本方法替它猜原因。
     *
     * <p><b>重置了什么</b>：{@code status = PENDING}、{@code retry_count = 0}（重新给满整轮退避预算）、
     * {@code next_retry_at = now}（立刻可投递）、租约列清空。<b>保留了什么</b>：{@code payload} 与
     * {@code last_error}——后者正是"该不该重放"的判断依据，留到下一次投递失败时由投递器覆盖。
     *
     * @param eventIds 待重放的 {@code event_id}：非空集合、条数不超过 {@link #queryLimit()}、每条非空白
     * @param now      重放时刻，同时作为新的 {@code next_retry_at}；由调用方传入而不是本类读时钟，
     *                 这样运维界面点下去的时刻与它刚看到的那个列表是同一时刻口径
     * @return 实际被重置的行数
     * @throws org.deep361.myddd.kernel.common.error.FrameworkException 集合为空之外的非法入参：超过上限、含 null 或空白
     */
    int requeue(List<String> eventIds, Instant now);

    /**
     * 单页与单批的最大条数，来自 {@code myddd.persistence.outbox.dead-letter.query-limit}。
     *
     * <p>公开它是有意的：Plan 4 的运维入口要能把自己的分页控件与批量按钮限制在同一个值上，而不是自己抄一份数字。
     */
    int queryLimit();
}
