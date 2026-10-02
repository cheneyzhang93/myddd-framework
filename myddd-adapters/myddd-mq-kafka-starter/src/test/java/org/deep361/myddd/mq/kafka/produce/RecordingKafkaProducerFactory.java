package org.deep361.myddd.mq.kafka.produce;

import org.apache.kafka.clients.producer.Producer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 录制型 {@code ProducerFactory}：把 {@link RecordingKafkaProducer} 接进<b>真</b> {@code KafkaTemplate}。
 *
 * <p><b>模板一律用 {@code new KafkaTemplate<>(factory, false)} 构造</b>（第二个参数 {@code autoFlush=false}），
 * 理由是这条实测差异：缺省的 {@code autoFlush=true} 会让<b>模板</b>在每次 send 之后自己调
 * {@code producer.flush()}（{@code k4out.txt} 末两行），那样 ST1"框架不自己 flush"的调用序就被模板的
 * 噪音污染，断言写不干净。
 *
 * <p><b>{@code false} 只挡得住 flush，挡不住 close</b>：一手实测（本模块第一轮全绿前的红名）即便
 * {@code autoFlush=false}，一次成功 send 在替身上留下的仍是 {@code [send, close(PT5S)]}——模板对
 * 非事务 producer 的寿命处理是"每次 send 新建、发完自己关"。这条差异决定了 ST1 的期望序把
 * {@code close(...)} 排除在外（{@code KafkaMessagePortTest#frameworkCalls}），不排除就只能把
 * spring-kafka 的内部生命周期当成框架的越界来报警。生产环境的模板由 Boot 装配（缺省
 * {@code autoFlush=true}），与本类的可观察结果无关：端口只认 future 的终态。
 */
public final class RecordingKafkaProducerFactory implements ProducerFactory<String, String> {

    private final List<RecordingKafkaProducer> produced = new CopyOnWriteArrayList<>();
    private java.util.function.Consumer<RecordingKafkaProducer> pendingArm;

    @Override
    public Producer<String, String> createProducer() {
        RecordingKafkaProducer producer = new RecordingKafkaProducer();
        java.util.function.Consumer<RecordingKafkaProducer> arm;
        synchronized (this) {
            arm = pendingArm;
            pendingArm = null;
        }
        if (arm != null) {
            arm.accept(producer);
        }
        produced.add(producer);
        return producer;
    }

    /**
     * 把失败注入挂到<b>下一个</b>被 {@code createProducer()} 产出的替身上（一次性）。
     *
     * <p>存在理由是一条实测形状：真模板每次 send 都新建 producer（{@code k3out.txt} 的
     * {@code [createProducer, send] ×3}），失败必须发生在"send 已经开始、producer 刚建好"的那个替身上，
     * 而 {@link #last()} 要到 send 之后才拿得到它——没有本方法，"首次 send 即失败"就注入不进去。
     */
    public synchronized void arm(java.util.function.Consumer<RecordingKafkaProducer> toArm) {
        this.pendingArm = toArm;
    }

    @Override
    public Map<String, Object> getConfigurationProperties() {
        return Map.of();
    }

    /** 最近一次 {@code createProducer()} 产出的替身（用例在它上面挂失败注入）。 */
    public RecordingKafkaProducer last() {
        return produced.get(produced.size() - 1);
    }

    /** 全部替身的观察合起来看（并发用例与"每次 send 新建 producer"的实测形状都要用）。 */
    public List<RecordingKafkaProducer> all() {
        return List.copyOf(produced);
    }

    public int createCount() {
        return produced.size();
    }

    /** 夹具入口：真模板、录制工厂、autoFlush=false。 */
    public static KafkaTemplate<String, String> newTemplate(RecordingKafkaProducerFactory factory) {
        return new KafkaTemplate<>(factory, false);
    }
}
