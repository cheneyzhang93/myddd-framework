package org.deep361.myddd.web.error;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;

/**
 * 对外文案的两个来源与它们的优先序（R12）：注解自带文案 → {@link ErrorMessageRenderer} 的四级链。
 *
 * <p><b>为什么需要这一个类</b>：{@code @DistributedLock}/{@code @Idempotent}/{@code @RateLimit}/
 * {@code @RequiresPermission} 四个注解都有 {@code message()}，内核 javadoc 与
 * {@code IdempotentAspect} 明文承诺过"业务写了文案就以它为准"。但一手核对（Task 47 收口后复查，
 * 见计划正文 Task 48 Step 0 第 6 条）显示：{@code handleBizException} 把响应体文案完全交给
 * {@code errorResponse(errorCode, ex.getMessageArgs())}，而 {@code ErrorMessageRenderer} 的 public 面只有
 * {@code render(ErrorCode, Object[])} 与 {@code render(MessageSourceResolvable)} 两个入口，
 * <b>没有任何一条路径读异常自带文案</b>——所有 {@code message()} 写出来的话术都只进了日志
 * （{@code GlobalExceptionHandler.java:77,79}）。D-72 记的是同一个方向，D-172 记的是"欠这一格"这件事本身。
 *
 * <p><b>判据是结构而不是类型</b>：本类只问"这条异常携带了与错误码缺省不同的非空白文案吗"，
 * <b>不问</b>它是 {@code RateLimitRejection} 还是 {@code PermissionRejection}。因为 web-starter 不许
 * 依赖 resilience/auth 两个壳模块（R2 的依赖方向、§0.3-11 的同一条精神）：一旦写成 {@code instanceof}
 * 某一家拒绝类型，"web 层只负责出口"这句包说明就破产，而新增第三个带文案的注解还得回来改这里。
 * 两家拒绝类的做法是同一件事：{@code extends BizException} 并只覆写 {@code getMessage()}。
 *
 * <p><b>为什么不需要"排除 {@code BizException} 本身"那一格判断</b>：{@code BizException} 的
 * {@code message} 按构造契约就是 {@code errorCode.formatDefaultMessage(messageArgs)}（一手：
 * {@code BizException.java} 类注释与 {@code resolveMessage(...)}），所以"自带文案等于错误码缺省渲染"
 * 这一条就把它整个挡住了——不必再写 {@code ex.getClass() != BizException.class}。两个真源只会在一处改的时候漏掉另一处。
 *
 * <p><b>注解文案原样输出，不再渲染</b>：命中优先序时本类直接返回那个串，一次都不调渲染器。
 * 把它丢进四级链会让 {@code MessageFormat} 处理业务手写文案里的 {@code &#123;&#125;}（{@code TX4} 钉这条），
 * 而注解文案是业务写死的话术、不是模板。这也意味着注解文案<b>不参与多语言</b>：写了它就等于
 * 声明"这句话在所有语言环境下都说这一句"，这条取舍写在 {@code error} 包说明里。
 */
final class AnnotationCarriedMessage {

    private AnnotationCarriedMessage() {
    }

    /**
     * 解析业务异常的对外文案。
     *
     * @param exception 已经带错误码的业务异常
     * @param renderer  四级链渲染器（本类的唯一渲染入口，缺失时由装配保证不可为 null）
     * @return 注解自带文案（非空白且不等于错误码缺省渲染时），否则渲染器给出的文案
     */
    static String messageFor(BizException exception, ErrorMessageRenderer renderer) {
        ErrorCode errorCode = exception.getErrorCode();
        Object[] messageArgs = exception.getMessageArgs();
        String carried = exception.getMessage();
        if (carried == null || carried.isBlank()) {
            return renderer.render(errorCode, messageArgs);
        }
        if (carried.equals(errorCode.formatDefaultMessage(messageArgs))) {
            return renderer.render(errorCode, messageArgs);
        }
        return carried;
    }
}
