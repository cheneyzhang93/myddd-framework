package org.deep361.myddd.kernel.extension;

/** 扩展点/扩展实现的共享夹具：涵盖默认实现、多场景、多扩展点、以及各类非法登记。 */
final class ExtensionFixtures {

    private ExtensionFixtures() {
    }

    @ExtensionPoint
    interface RiskCheck {
        String check(String userId);
    }

    @ExtensionPoint("notify")
    interface NotifyChannel {
        String channel();
    }

    /** 名称为空白字符的扩展点：视为"未命名"，诊断名回退为接口简单类名。 */
    @ExtensionPoint("  ")
    interface WhitespacePoint {
    }

    /** 未标注 @ExtensionPoint 的普通接口：不能作为扩展点。 */
    interface PlainService {
    }

    @Extension
    static final class DefaultRiskCheck implements RiskCheck {
        @Override
        public String check(String userId) {
            return "default:" + userId;
        }
    }

    @Extension(scenario = "vip", priority = 10)
    static final class VipRiskCheck implements RiskCheck {
        @Override
        public String check(String userId) {
            return "vip:" + userId;
        }
    }

    @Extension(scenario = "vip", priority = 200)
    static final class BackupRiskCheck implements RiskCheck {
        @Override
        public String check(String userId) {
            return "backup:" + userId;
        }
    }

    @Extension(point = NotifyChannel.class, scenario = "sms")
    static final class SmsNotifyChannel implements NotifyChannel {
        @Override
        public String channel() {
            return "sms";
        }
    }

    /** 空白命名扩展点的实现：登记后其诊断名应为接口简单类名。 */
    @Extension
    static final class WhitespacePointCheck implements WhitespacePoint {
    }

    /** 同时实现两个扩展点：未显式声明 point 时无法推断，必须构建失败。 */
    @Extension
    static final class MultiPointCheck implements RiskCheck, NotifyChannel {
        @Override
        public String check(String userId) {
            return "multi:" + userId;
        }

        @Override
        public String channel() {
            return "multi";
        }
    }

    /** 同时实现两个扩展点、显式声明 point：只登记到声明的扩展点下。 */
    @Extension(point = NotifyChannel.class, scenario = "multi")
    static final class ExplicitPointCheck implements RiskCheck, NotifyChannel {
        @Override
        public String check(String userId) {
            return "explicit:" + userId;
        }

        @Override
        public String channel() {
            return "multi";
        }
    }

    /** 未显式声明 point、且实现的接口都不是扩展点：无从推断，必须构建失败。 */
    @Extension
    static final class NoPointCheck implements PlainService {
    }

    /** 扩展点接口继承另一个扩展点接口：实现类按继承闭包推断。 */
    interface CompositeRiskCheck extends RiskCheck {
    }

    /** 实现子接口：推断出的扩展点是继承链上的 @ExtensionPoint（RiskCheck）。 */
    @Extension
    static final class CompositeRiskCheckImpl implements CompositeRiskCheck {
        @Override
        public String check(String userId) {
            return "composite:" + userId;
        }
    }

    /** 菱形继承左分支：接口闭包会经由此路再次到达 {@link RiskCheck}。 */
    interface LeftCheck extends RiskCheck {
    }

    /** 菱形继承右分支：接口闭包会经由此路再次到达 {@link RiskCheck}。 */
    interface RightCheck extends RiskCheck {
    }

    /** 实现菱形继承的两个分支：闭包必须去重，否则会误判为"实现 2 个 @ExtensionPoint 接口"。 */
    @Extension
    static final class DiamondRiskCheck implements LeftCheck, RightCheck {
        @Override
        public String check(String userId) {
            return "diamond:" + userId;
        }
    }

    /** 实现类未标注 @Extension。 */
    static final class UnannotatedRiskCheck implements RiskCheck {
        @Override
        public String check(String userId) {
            return "unannotated:" + userId;
        }
    }

    /** 被误标为扩展点的类（@ExtensionPoint 只能标注接口）。 */
    @ExtensionPoint
    static final class AnnotatedClass {
    }

    static final class PlainServiceImpl implements PlainService {
    }

    /** 标准登记集：4 个实现、3 个场景（"" / vip×2、notify 的点下 sms）。 */
    static DefaultExtensionRegistry standardRegistry() {
        return DefaultExtensionRegistry.builder()
                .register(new VipRiskCheck())
                .register(new BackupRiskCheck())
                .register(new DefaultRiskCheck())
                .register(new SmsNotifyChannel())
                .build();
    }
}
